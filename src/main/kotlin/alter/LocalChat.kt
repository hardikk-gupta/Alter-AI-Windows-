package alter

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Only a user-started loopback runtime is supported. No remote endpoint or cloud fallback.
class LocalChat(private val port: Int) {
    init { require(port in 1024..65535) { "Use a local server port from 1024 to 65535." } }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
        }).build()

    suspend fun installedModels(runtime: DesktopRuntime): List<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port${runtime.catalogPath}"))
            .timeout(Duration.ofSeconds(4)).GET().build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() == 200) { "${runtime.title} returned HTTP ${response.statusCode()}." }
        require(response.body().length <= 1_000_000) { "Model list is too large." }
        val ollama = runtime == DesktopRuntime.Ollama
        val entries = JSONObject(response.body()).getJSONArray(if (ollama) "models" else "data")
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val name = entry.optString(if (ollama) "name" else "id").trim()
                if (name.isNotEmpty() && name.length <= 256 && !name.endsWith(":cloud") &&
                    entry.optString("remote_host").isBlank() && entry.optString("remote_model").isBlank()) add(name)
            }
        }.distinct().sorted()
    }

    suspend fun reply(history: List<Pair<String, String>>, model: String? = null): String {
        val messages = chatMessages(history)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
            .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("model", model).put("messages", messages)
                .put("stream", false).put("max_tokens", 512).put("temperature", .3)
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false)).toString())).build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() == 200) { "Local model returned HTTP ${response.statusCode()}. Check the running model." }
        require(response.body().length <= 1_000_000) { "Local model response was too large." }
        val text = JSONObject(response.body()).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim()
        // Never display an unfinished reasoning channel or persist it as a final answer.
        val final = text.replace(Regex("(?is)<think>.*?</think>"), "")
            .substringBefore("<think>").trim()
        check(final.isNotBlank()) { "The local model returned no final answer. Try a compatible chat model." }
        return final
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    whenComplete { value, error ->
        if (error == null) continuation.resume(value) else continuation.resumeWithException(error)
    }
}


enum class DesktopRuntime(val title: String, val port: Int, val catalogPath: String) {
    Ollama("Ollama", 11434, "/api/tags"),
    LmStudio("LM Studio", 1234, "/v1/models")
}

data class RuntimeModel(val runtime: DesktopRuntime, val name: String) {
    val label get() = "$name · ${runtime.title}"
}

private fun chatMessages(history: List<Pair<String, String>>): JSONArray {
    val instructions = LocalChat::class.java.getResourceAsStream("/ALTER_INSTRUCTIONS.md")!!.bufferedReader().use { it.readText() }
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", instructions +
        "\nDesktop tools and web research are unavailable in this build. Never claim to save or execute actions."))
    history.takeLast(20).forEach { (sender, text) ->
        messages.put(JSONObject().put("role", sender).put("content", text.take(16000)))
    }
    return messages
}

class OpenAiSession(private val apiKey: String, val model: String) {
    suspend fun reply(history: List<Pair<String, String>>): String {
        val body = JSONObject().put("model", model).put("messages", chatMessages(history)).put("max_completion_tokens", 512)
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() == 200) { "OpenAI returned HTTP ${response.statusCode()}. Check your key, model and billing." }
        val content = JSONObject(response.body()).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim()
        check(content.isNotEmpty()) { "OpenAI returned an empty response." }
        return content
    }

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()
}
