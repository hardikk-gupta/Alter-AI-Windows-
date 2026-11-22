package alter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Instant
import kotlin.time.TimeSource

internal suspend fun benchmarkModel(spec: ModelSpec, cudaServer: Path? = null): JSONObject = withContext(Dispatchers.IO) {
    val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    NativeModels().use { engine ->
        val start = TimeSource.Monotonic.markNow()
        engine.start(spec, port, cudaServer, if (cudaServer == null) 0 else 99)
        val loaded = TimeSource.Monotonic.markNow()
        val startupMs = start.elapsedNow().inWholeMilliseconds
        val answer = LocalChat(port).reply(listOf("user" to "Say hello in one short sentence."))
        val replyMs = loaded.elapsedNow().inWholeMilliseconds
        check(answer.isNotBlank()) { "No answer returned by the local engine." }
        val limits = hardwareBudget()
        JSONObject().put("model", spec.title).put("engine", if (cudaServer == null) "CPU" else "Selected CUDA runtime")
            .put("systemRamBytes", limits.ramBytes).put("logicalProcessors", limits.processors)
            .put("loadMs", startupMs).put("firstReplyMs", replyMs)
            .put("peakEngineBytes", if (WindowsIntegration.available) WindowsIntegration.peakMemory(engine.processId) else JSONObject.NULL)
            .put("recordedAt", Instant.now().toString())
    }
}

internal fun benchmarkLabel(report: JSONObject): String {
    val mib = report.optLong("peakEngineBytes", 0) / (1024 * 1024)
    return "${report.getString("model")} · ${report.getString("engine")}\n" +
        "Load ${report.getLong("loadMs")} ms · First reply ${report.getLong("firstReplyMs")} ms" +
        if (mib > 0) " · Peak engine memory $mib MiB" else ""
}
