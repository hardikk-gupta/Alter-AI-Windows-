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
internal data class ModelTurn(val text: String = "", val proposal: DesktopAction? = null)

internal fun JSONObject.offerTools(actions: List<DesktopAction>): JSONObject {
    if (actions.isEmpty()) return this
    val tools = JSONArray()
    for (action in actions) {
        val schema = JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)
        val function = JSONObject().put("name", action.name).put("description", action.title).put("parameters", schema)
        tools.put(JSONObject().put("type", "function").put("function", function))
    }
    return put("tools", tools)
}

internal fun parseTurn(body: String, actions: List<DesktopAction>): ModelTurn {
    val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    message.optJSONArray("tool_calls")?.takeIf { it.length() > 0 }?.let { calls ->
        require(calls.length() == 1) { "Ask for one action at a time." }
        val function = calls.getJSONObject(0).getJSONObject("function")
        val proposal = actions.singleOrNull { it.name == function.getString("name") }
        require(proposal != null && JSONObject(function.getString("arguments")).isEmpty) { "The model proposed an unavailable action or invalid arguments." }
        return ModelTurn(proposal = proposal)
    }
    val text = if (message.isNull("content")) "" else message.getString("content").trim()
    val final = text.replace(Regex("(?is)<think>.*?</think>"), "").substringBefore("<think>").trim()
    check(final.isNotBlank()) { "The model returned no final answer. Try a compatible chat model." }
    return ModelTurn(text = final)
}

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
                if (name.isNotEmpty() && name.length <= 256 && !name.endsWith(":cloud", true) && !name.endsWith("-cloud", true) &&
                    entry.optString("remote_host").isBlank() && entry.optString("remote_model").isBlank()) add(name)
            }
        }.distinct().sorted()
    }

    suspend fun checkChatModel(model: RuntimeModel): RuntimeModel {
        val root = "http://127.0.0.1:$port"
        val ollama = model.runtime == DesktopRuntime.Ollama
        val request = if (ollama) HttpRequest.newBuilder(URI("$root/api/show"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("model", model.name).toString()))
        else HttpRequest.newBuilder(URI("$root/api/v1/models")).GET()
        val response = client.sendAsync(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()).await()
        if (ollama || response.statusCode() == 200) {
            check(response.statusCode() == 200) { "Could not inspect ${model.name} in ${model.runtime.title}." }
            require(response.body().length <= 4_000_000) { "Model metadata is too large." }
            val info = JSONObject(response.body())
            if (ollama) {
                require(info.optString("remote_model").isBlank() && info.optString("remote_host").isBlank() &&
                    !model.name.endsWith("-cloud", true) && !model.name.endsWith(":cloud", true)) { "Choose a model installed on this PC." }
                info.optJSONArray("capabilities")?.let { list ->
                    val capabilities = (0 until list.length()).map { list.getString(it) }
                    require("completion" in capabilities) { "This model does not support text chat." }
                    return model.copy(supportsTools = "tools" in capabilities)
                }
            } else {
                val list = info.getJSONArray("models")
                val item = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull {
                    val instances = it.optJSONArray("loaded_instances") ?: JSONArray()
                    it.optString("key") == model.name || (0 until instances.length()).any { index ->
                        instances.getJSONObject(index).optString("id") == model.name
                    }
                }
                if (item != null) {
                    require(item.getString("type") == "llm") { "This model does not support text chat." }
                    return model.copy(supportsTools = item.optJSONObject("capabilities")?.optBoolean("trained_for_tool_use"))
                }
            }
        } else check(response.statusCode() in setOf(404,405)) { "LM Studio model metadata returned HTTP ${response.statusCode()}." }
        // Older local servers do not report capabilities. Probe the exact selected model.
        val probe = JSONObject().put("model", model.name).put("max_tokens", 1).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role","user").put("content","Hi")))
        val chat = HttpRequest.newBuilder(URI("$root/v1/chat/completions")).timeout(Duration.ofMinutes(2))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(probe.toString())).build()
        val result = client.sendAsync(chat, HttpResponse.BodyHandlers.ofString()).await()
        check(result.statusCode() == 200 && JSONObject(result.body()).getJSONArray("choices").getJSONObject(0).has("message")) {
            "The selected local model could not return a chat completion."
        }
        return model
    }

    suspend fun reply(history: List<Pair<String, String>>, model: String? = null, projectInstructions: String = ""): String {
        return turn(history, model, projectInstructions, emptyList()).text
    }

    internal suspend fun turn(history: List<Pair<String, String>>, model: String?, projectInstructions: String, actions: List<DesktopAction>): ModelTurn {
        val messages = chatMessages(history, projectInstructions, actions.isNotEmpty())
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
            .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("model", model).put("messages", messages)
                .offerTools(actions).put("stream", false).put("max_tokens", 512).put("temperature", .3)
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false)).toString())).build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() == 200) { "Local model returned HTTP ${response.statusCode()}. Check the running model." }
        require(response.body().length <= 1_000_000) { "Local model response was too large." }
        return parseTurn(response.body(), actions)
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

data class RuntimeModel(val runtime: DesktopRuntime, val name: String, val supportsTools: Boolean? = null) {
    val label get() = "$name · ${runtime.title}"
}

private fun chatMessages(history: List<Pair<String, String>>, projectInstructions: String, toolsEnabled: Boolean): JSONArray {
    val instructions = LocalChat::class.java.getResourceAsStream("/ALTER_INSTRUCTIONS.md")!!.bufferedReader().use { it.readText() }
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", instructions +
        if (toolsEnabled) "\nPropose a listed function only when requested. Alter previews each action for user confirmation. Never claim it ran." else "\nNo model tools are enabled. Never claim to save or execute actions."))
    if (projectInstructions.isNotBlank()) messages.put(JSONObject().put("role", "user")
        .put("content", "My instructions for this project: $projectInstructions"))
    history.filter { it.first in setOf("user", "assistant") }.takeLast(20).forEach { (sender, text) ->
        messages.put(JSONObject().put("role", sender).put("content", text.take(16000)))
    }
    return messages
}

class OpenAiSession(private val apiKey: String, val model: String) {
    suspend fun reply(history: List<Pair<String, String>>, projectInstructions: String = ""): String {
        return turn(history, projectInstructions, emptyList()).text
    }

    internal suspend fun turn(history: List<Pair<String, String>>, projectInstructions: String, actions: List<DesktopAction>): ModelTurn {
        val body = JSONObject().put("model", model).put("messages", chatMessages(history, projectInstructions, actions.isNotEmpty()))
            .offerTools(actions).put("max_completion_tokens", 512)
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() == 200) { "OpenAI returned HTTP ${response.statusCode()}. Check your key, model and billing." }
        return parseTurn(response.body(), actions)
    }

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()
}
