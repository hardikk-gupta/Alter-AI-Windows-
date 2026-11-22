package alter

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.time.Duration
import java.time.Instant

internal enum class ServicePlugin(val label: String, val scope: String, val command: String) {
    Figma("Figma Cloud", "", "/figma <layer link>"),
    Calendar("Google Calendar", "https://www.googleapis.com/auth/calendar.events.readonly", "calendar events"),
    Drive("Google Drive", "https://www.googleapis.com/auth/drive.metadata.readonly", "list drive files"),
    Spotify("Spotify", "user-read-playback-state user-modify-playback-state", "play <track> on Spotify");

    val configured get() = this == Figma || (if (this == Spotify) listOf("ALTER_SPOTIFY_CLIENT_ID") else
        listOf("ALTER_GOOGLE_DESKTOP_CLIENT_ID", "ALTER_GOOGLE_DESKTOP_CLIENT_SECRET")).all { !System.getenv(it).isNullOrBlank() }
}

internal class PluginGrant(val token: String, val expiry: Long)

internal object ServiceTools {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(7)).build()
    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun request(url: String, token: String, putBody: JSONObject? = null): String {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $token")
        if (putBody != null) request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(putBody.toString()))
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            when (response.statusCode()) {
                401 -> "Access expired. Reconnect the plugin."
                403 -> "Access refused. Check provider permissions, enabled APIs and account eligibility."
                404 -> "The requested resource or playback device is unavailable."
                429 -> "The provider rate limit was reached. Try again later."
                else -> "The provider returned HTTP ${response.statusCode()}."
            }
        }
        return response.body()
    }

    fun execute(plugin: ServicePlugin, grant: PluginGrant?, argument: String): String {
        require(grant != null && grant.expiry > System.currentTimeMillis()) { "Connect ${plugin.label} in this project’s Plugins first." }
        return when (plugin) {
            ServicePlugin.Figma -> FigmaMcp.remote(grant.token).readSelection(false, figmaNode(argument))
            ServicePlugin.Calendar -> {
                val start = encode(Instant.now().toString())
                val entries = JSONObject(request("https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin=$start&singleEvents=true&orderBy=startTime&maxResults=10", grant.token)).getJSONArray("items")
                (0 until entries.length()).joinToString("\n") {
                    val entry = entries.getJSONObject(it)
                    val whenAt = entry.getJSONObject("start")
                    "${whenAt.optString("dateTime").ifBlank { whenAt.optString("date") }} · ${entry.optString("summary", "Busy")}"
                }.ifBlank { "No upcoming events found." }
            }
            ServicePlugin.Drive -> {
                val entries = JSONObject(request("https://www.googleapis.com/drive/v3/files?q=trashed%3Dfalse&orderBy=modifiedTime%20desc&pageSize=10&fields=files(id,name,webViewLink)", grant.token)).getJSONArray("files")
                (0 until entries.length()).joinToString("\n") {
                    val entry = entries.getJSONObject(it)
                    "${entry.getString("name")} ${entry.optString("webViewLink")}".trim()
                }.ifBlank { "No files found." }
            }
            ServicePlugin.Spotify -> {
                require(argument.isNotBlank() && argument.length <= 200)
                val search = JSONObject(request("https://api.spotify.com/v1/search?type=track&limit=1&q=${encode(argument)}", grant.token))
                val tracks = search.getJSONObject("tracks").getJSONArray("items")
                require(tracks.length() > 0) { "Spotify found no matching track." }
                val track = tracks.getJSONObject(0)
                val devices = JSONObject(request("https://api.spotify.com/v1/me/player/devices", grant.token)).getJSONArray("devices")
                val device = (0 until devices.length()).map { devices.getJSONObject(it) }
                    .firstOrNull { it.optBoolean("is_active") && !it.optBoolean("is_restricted") && !it.isNull("id") }
                    ?: error("Select an active device in Spotify, then try again.")
                request("https://api.spotify.com/v1/me/player/play?device_id=${encode(device.getString("id"))}",
                    grant.token, JSONObject().put("uris", JSONArray().put(track.getString("uri"))))
                "Spotify accepted playback of ${track.getString("name")} on ${device.getString("name")}."
            }
        }
    }
}
