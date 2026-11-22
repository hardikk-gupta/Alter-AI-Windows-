package alter

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

internal class AccountSession(val uid: String, val label: String, val idToken: String, val expiresAt: Long)

internal object DesktopAuth {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    fun configured(provider: String): Boolean {
        val keys = when (provider) {
            "google.com" -> listOf("ALTER_GOOGLE_DESKTOP_CLIENT_ID", "ALTER_GOOGLE_DESKTOP_CLIENT_SECRET")
            "github.com" -> listOf("ALTER_GITHUB_CLIENT_ID")
            else -> return false
        }
        return (keys + "ALTER_FIREBASE_WEB_API_KEY").all { !System.getenv(it).isNullOrBlank() }
    }

    private fun config(key: String) = System.getenv(key)?.takeIf(String::isNotBlank)
        ?: error("The desktop release needs $key configured.")
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    private fun form(vararg values: Pair<String, String>) = values.joinToString("&") { (k,v) -> "${encode(k)}=${encode(v)}" }
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun post(url: String, body: String, json: Boolean = false): JSONObject {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val result = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(result.statusCode() in 200..299) {
            val code = runCatching { JSONObject(result.body()).getJSONObject("error").optString("message").substringBefore(':') }.getOrNull()
            "Identity provider returned HTTP ${result.statusCode()}" + (code?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) }?.let { " ($it)" } ?: "")
        }
        return JSONObject(result.body())
    }

    private suspend fun browserGrant(scopes: String, spotify: Boolean = false): JSONObject {
        val client = config(if (spotify) "ALTER_SPOTIFY_CLIENT_ID" else "ALTER_GOOGLE_DESKTOP_CLIENT_ID")
        val secret = if (spotify) null else config("ALTER_GOOGLE_DESKTOP_CLIENT_SECRET")
        val state = random()
        val verifier = random()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val callback = CompletableDeferred<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", if (spotify) 43897 else 0), 0)
        val redirect = "http://127.0.0.1:${server.address.port}/callback"
        server.createContext("/callback") { exchange ->
            exchange.use {
                val params = runCatching { exchange.requestURI.rawQuery.orEmpty().split('&').associate { param ->
                    param.substringBefore('=') to URLDecoder.decode(param.substringAfter('=', ""), Charsets.UTF_8)
                } }.getOrDefault(emptyMap())
                val valid = exchange.requestMethod == "GET" && exchange.requestURI.path == "/callback" && params["state"] == state
                val body = (if (valid) "Return to Alter." else "Invalid sign-in callback.").toByteArray()
                exchange.sendResponseHeaders(if (valid) 200 else 400, body.size.toLong())
                exchange.responseBody.write(body)
                if (valid) params["code"]?.let(callback::complete) ?: callback.completeExceptionally(IllegalStateException("Authorization was declined."))
            }
        }
        try {
            server.start()
            val query = form("client_id" to client, "redirect_uri" to redirect, "response_type" to "code",
                "scope" to scopes, "state" to state, "code_challenge" to challenge, "code_challenge_method" to "S256")
            val authUrl = if (spotify) "https://accounts.spotify.com/authorize" else "https://accounts.google.com/o/oauth2/v2/auth"
            Desktop.getDesktop().browse(URI("$authUrl?$query"))
            val code = withTimeoutOrNull(180_000) { callback.await() } ?: error("Authorization expired.")
            val tokenUrl = if (spotify) "https://accounts.spotify.com/api/token" else "https://oauth2.googleapis.com/token"
            val fields = mutableListOf("code" to code, "client_id" to client, "code_verifier" to verifier,
                "grant_type" to "authorization_code", "redirect_uri" to redirect)
            if (secret != null) fields.add("client_secret" to secret)
            return post(tokenUrl, form(*fields.toTypedArray()))
        } finally { server.stop(0) }
    }

    suspend fun connect(plugin: ServicePlugin): PluginGrant = withContext(Dispatchers.IO) {
        val result = browserGrant(plugin.scope, plugin == ServicePlugin.Spotify)
        val granted = result.optString("scope").split(" ").toSet()
        check(plugin.scope.split(" ").all { it in granted }) { "The requested permission was not granted." }
        PluginGrant(result.getString("access_token"), System.currentTimeMillis() + 1000L * result.getLong("expires_in"))
    }

    private suspend fun github(status: suspend (String) -> Unit): String {
        val client = config("ALTER_GITHUB_CLIENT_ID")
        val response = post("https://github.com/login/device/code", form("client_id" to client, "scope" to "user:email"))
        status("Enter ${response.getString("user_code")} at github.com/login/device")
        Desktop.getDesktop().browse(URI("https://github.com/login/device"))
        val expires = System.currentTimeMillis() + response.getLong("expires_in") * 1000
        var interval = response.optLong("interval", 5).coerceAtLeast(5)
        while (System.currentTimeMillis() < expires) {
            delay(interval * 1000)
            val token = post("https://github.com/login/oauth/access_token", form("client_id" to client,
                "device_code" to response.getString("device_code"), "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            when (token.optString("error")) {
                "" -> return token.getString("access_token")
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                else -> error("GitHub sign-in was declined or expired.")
            }
        }
        error("GitHub sign-in expired.")
    }

    suspend fun signIn(provider: String, link: AccountSession?, status: suspend (String) -> Unit): AccountSession = withContext(Dispatchers.IO) {
        val key = config("ALTER_FIREBASE_WEB_API_KEY")
        val credential = when (provider) {
            "google.com" -> "id_token" to browserGrant("openid email profile").getString("id_token")
            "github.com" -> "access_token" to github(status)
            else -> error("Unknown identity provider")
        }
        val body = JSONObject().put("postBody", form("providerId" to provider, credential))
            .put("requestUri", "http://localhost").put("returnSecureToken", true)
        if (link != null) body.put("idToken", link.idToken)
        val result = post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=${encode(key)}", body.toString(), true)
        check(!result.optBoolean("needConfirmation")) { "Sign in with the existing account and link this provider." }
        val uid = result.getString("localId")
        check(link == null || uid == link.uid) { "Account linking could not be verified." }
        AccountSession(uid, result.optString("email").ifBlank { result.optString("displayName").ifBlank { uid } },
            result.getString("idToken"), System.currentTimeMillis() + result.getLong("expiresIn") * 1000)
    }
}
