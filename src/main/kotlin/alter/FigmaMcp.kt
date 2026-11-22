package alter

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The desktop MCP server uses the account and selection in the local Figma app. */
internal class FigmaMcp private constructor(private val endpoint: URI, private val token: String?) {
    constructor(port: Int = 3845) : this(URI("http://127.0.0.1:$port/mcp"), null)
    companion object {
        fun remote(token: String) = FigmaMcp(URI(figmaResource), token)
        private val local = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(4)).build()
        private val cloud = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    }
    private val client = if (token == null) local else cloud
    private var session: String? = null
    private var version = "2025-03-26"
    private var nextId = 0

    private fun exchange(method: String, params: JSONObject?, notification: Boolean = false): JSONObject {
        val message = JSONObject().put("jsonrpc", "2.0").put("method", method)
        val id = ++nextId
        if (!notification) message.put("id", id)
        if (params != null) message.put("params", params)
        val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(message.toString()))
        token?.let { request.header("Authorization", "Bearer $it") }
        if (method != "initialize") request.header("MCP-Protocol-Version", version)
        session?.let { request.header("Mcp-Session-Id", it) }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Figma MCP returned HTTP ${response.statusCode()}." }
        if (method == "initialize") session = response.headers().firstValue("Mcp-Session-Id").orElse(null)
        if (notification) return JSONObject()
        val body = response.body()
        require(body.length <= 2_000_000) { "Select a smaller Figma node." }
        val reply = if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            body.replace("\r\n", "\n").split("\n\n").asSequence().mapNotNull { event ->
                val data = event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.substringAfter(':').trimStart() }
                if (data.isEmpty()) null else JSONObject(data)
            }.firstOrNull { it.optInt("id", -1) == id }
        } else JSONObject(body)
        check(reply != null && reply.optInt("id", -1) == id && !reply.has("error")) { "Figma could not complete $method." }
        return reply.getJSONObject("result")
    }

    fun readSelection(testOnly: Boolean, arguments: JSONObject = JSONObject()): String {
        val init = exchange("initialize", JSONObject().put("protocolVersion", version).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Alter").put("version", "0.2.4")))
        version = init.getString("protocolVersion")
        exchange("notifications/initialized", null, notification = true)
        try {
            val tools = exchange("tools/list", JSONObject()).getJSONArray("tools")
            check((0 until tools.length()).any { tools.getJSONObject(it).optString("name") == "get_metadata" }) {
                "This Figma connection does not offer get_metadata."
            }
            if (testOnly) return "Connected to Figma. Available read tool: get_metadata."
            val result = exchange("tools/call", JSONObject().put("name", "get_metadata").put("arguments", arguments))
            check(!result.optBoolean("isError")) { "Figma could not read the selection. Select a layer in a design file and try again." }
            val parts = result.getJSONArray("content")
            return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.optString("type") == "text" }
                .joinToString("\n") { it.getString("text") }.trim().take(16000).also { check(it.isNotEmpty()) { "Figma returned no metadata." } }
        } finally {
            session?.let { id -> runCatching {
                val end = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Mcp-Session-Id", id).header("MCP-Protocol-Version", version).DELETE()
                token?.let { end.header("Authorization","Bearer $it") }
                client.send(end.build(),
                    HttpResponse.BodyHandlers.discarding())
            } }
        }
    }
}
