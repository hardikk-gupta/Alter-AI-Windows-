package alter

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.*
import kotlin.test.*

class PluginTest {
    @Test fun modelCanOnlySuggestAnEnabledActionWithoutChangingItsTarget() {
        val allowed = DesktopAction("open_repo", "owner/demo")
        fun response(name: String, args: String): String {
            val function = JSONObject().put("name", name).put("arguments", args)
            val message = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("function", function)))
            return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))).toString()
        }
        assertEquals(allowed, parseTurn(response("open_repo", "{}"), listOf(allowed)).proposal)
        assertFailsWith<IllegalArgumentException> { parseTurn(response("open_repo", """{"repo":"other/repo"}"""), listOf(allowed)) }
        assertFailsWith<IllegalArgumentException> { parseTurn(response("open_repo", "{}"), emptyList()) }
        assertEquals("Hey Jude", DesktopAction.parse("Play Hey Jude on Spotify", "")?.argument)
        assertFailsWith<IllegalArgumentException> { ServiceTools.execute(ServicePlugin.Drive, null, "") }
        assertFailsWith<IllegalArgumentException> { ServiceTools.execute(ServicePlugin.Calendar, PluginGrant("dummy", 0), "") }
    }

    @Test fun figmaReadsOnlySelectedMetadataAfterSessionInitialization() {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            exchange.use {
                if (it.requestMethod == "DELETE") { it.sendResponseHeaders(200, -1); return@use }
                val input = JSONObject(it.requestBody.bufferedReader().readText())
                val method = input.getString("method")
                methods += method
                if (method != "initialize" && it.requestHeaders.getFirst("Mcp-Session-Id") != "demo") {
                    it.sendResponseHeaders(403, -1); return@use
                }
                val result = when (method) {
                    "initialize" -> { it.responseHeaders.add("Mcp-Session-Id","demo"); JSONObject().put("protocolVersion", "2025-03-26") }
                    "notifications/initialized" -> { it.sendResponseHeaders(202, -1); return@use }
                    "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name","get_metadata")))
                    "tools/call" -> {
                        val params = input.getJSONObject("params")
                        if (params.getString("name") != "get_metadata" || !params.getJSONObject("arguments").isEmpty) {
                            it.sendResponseHeaders(400, -1); return@use
                        }
                        JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text","Layer A")))
                    }
                    else -> error(method)
                }
                it.responseHeaders.add("Content-Type", "text/event-stream")
                val data = "data: " + JSONObject().put("id", input.getInt("id")).put("result", result) + "\n\n"
                val bytes = data.toByteArray()
                it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.write(bytes)
            }
        }
        try {
            server.start()
            assertEquals("Layer A", FigmaMcp(server.address.port).readSelection(false))
            assertEquals(listOf("initialize","notifications/initialized","tools/list","tools/call"), methods)
        } finally { server.stop(0) }
    }
}
