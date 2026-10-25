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

    suspend fun reply(history: List<Pair<String, String>>): String {
        val instructions = javaClass.getResourceAsStream("/ALTER_INSTRUCTIONS.md")!!.bufferedReader().use { it.readText() }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", instructions +
            "\nDesktop tools and web research are unavailable in this build. Never claim to save or execute actions."))
        history.takeLast(20).forEach { (sender, text) ->
            messages.put(JSONObject().put("role", sender).put("content", text.take(16000)))
        }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
            .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("messages", messages)
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
