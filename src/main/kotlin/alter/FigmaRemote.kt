package alter

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.http.*
import java.time.Duration

internal const val figmaResource = "https://mcp.figma.com/mcp"
internal const val figmaRedirect = "http://127.0.0.1:43898/callback"
private val figmaHosts = setOf("figma.com", "www.figma.com", "api.figma.com", "mcp.figma.com", "oauth.figma.com")

internal fun figmaEndpoint(address: String): URI = URI(address).also {
    require(it.scheme == "https" && it.host in figmaHosts && it.port in setOf(-1, 443) &&
        it.userInfo == null && it.fragment == null && it.query == null) { "Unexpected Figma authorization endpoint." }
}

internal fun figmaNode(link: String): JSONObject {
    val uri = URI(link)
    val path = uri.path.orEmpty().split('/')
    require(link.length <= 2000 && uri.scheme == "https" && uri.host in setOf("www.figma.com", "figma.com") &&
        uri.userInfo == null && uri.port in setOf(-1, 443) && path.size >= 3 && path[1] in setOf("design","file") &&
        path[2].matches(Regex("[A-Za-z0-9]{1,100}"))) { "Paste a Figma design layer link." }
    val node = uri.rawQuery.orEmpty().split('&').singleOrNull { it.startsWith("node-id=") }?.let {
        URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8).replace('-', ':')
    }
    require(node != null && node.matches(Regex("[0-9]{1,16}:[0-9]{1,16}"))) { "Copy a layer link containing node-id from Figma." }
    return JSONObject().put("fileKey", path[2]).put("nodeId", node)
}

internal data class FigmaAuthorization(val authorize: URI, val exchange: URI, val clientId: String, val scope: String)

internal object FigmaDiscovery {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()

    private fun metadata(url: URI, payload: JSONObject? = null, optional: Boolean = false): JSONObject? {
        val builder = HttpRequest.newBuilder(figmaEndpoint(url.toString())).timeout(Duration.ofSeconds(15)).header("Accept","application/json")
        if (payload != null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (optional && response.statusCode() == 404) return null
        check(response.statusCode() in 200..299 && response.body().length <= 131072) { "Figma setup returned HTTP ${response.statusCode()}." }
        return JSONObject(response.body())
    }

    fun discover(store: Store): FigmaAuthorization {
        val resource = metadata(URI("https://mcp.figma.com/.well-known/oauth-protected-resource/mcp"), optional = true)
            ?: requireNotNull(metadata(URI("https://mcp.figma.com/.well-known/oauth-protected-resource")))
        require(resource.getString("resource").trimEnd('/') in setOf(figmaResource, "https://mcp.figma.com")) { "Figma resource identifier changed." }
        val issuer = figmaEndpoint(resource.getJSONArray("authorization_servers").getString(0))
        val doc = requireNotNull(metadata(URI("https://${issuer.host}/.well-known/oauth-authorization-server${issuer.path.trimEnd('/')}")))
        require(doc.getString("issuer").trimEnd('/') == issuer.toString().trimEnd('/')) { "Figma authorization issuer changed." }
        val authorize = figmaEndpoint(doc.getString("authorization_endpoint"))
        val exchange = figmaEndpoint(doc.getString("token_endpoint"))
        val key = "figmaPublicClient:$issuer"
        val id = System.getenv("ALTER_FIGMA_MCP_CLIENT_ID")?.takeIf(String::isNotBlank) ?: store.setting(key).ifBlank {
            val endpoint = doc.optString("registration_endpoint").takeIf(String::isNotBlank)
                ?: error("Register Alter as a Figma MCP client and configure ALTER_FIGMA_MCP_CLIENT_ID.")
            val registration = JSONObject().put("client_name","Alter for Windows")
                .put("redirect_uris", JSONArray().put(figmaRedirect)).put("token_endpoint_auth_method","none")
                .put("grant_types",JSONArray().put("authorization_code")).put("response_types",JSONArray().put("code"))
            val registered = requireNotNull(metadata(figmaEndpoint(endpoint), registration))
            check(!registered.has("client_secret")) { "Figma requires a public desktop client registration." }
            registered.getString("client_id").also { store.setting(key,it,true) }
        }
        val scopes = resource.optJSONArray("scopes_supported") ?: doc.optJSONArray("scopes_supported")
        val scope = if (scopes != null && (0 until scopes.length()).any { scopes.optString(it) == "mcp:connect" }) "mcp:connect" else ""
        return FigmaAuthorization(authorize, exchange, id, scope)
    }
}
