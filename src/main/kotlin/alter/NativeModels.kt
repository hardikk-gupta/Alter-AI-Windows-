package alter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private fun nativeDirectory(): Path = System.getenv("ALTER_NATIVE_DIR")?.let(Path::of)
    ?: Path.of(System.getProperty("java.home")).parent.resolve("app").resolve("resources").resolve("native")

fun nativeProgram(name: String): Path {
    val family = if (name == "whisper-cli") "whisper" else "llama"
    val executable = nativeDirectory().resolve(family).resolve(name + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    require(Files.isRegularFile(executable)) {
        "The $name runtime is missing from this app build. Download the complete Alter-Windows ZIP again."
    }
    return executable
}

class NativeModels : AutoCloseable {
    private var server: Process? = null
    val running get() = server?.isAlive == true

    suspend fun start(spec: ModelSpec, port: Int, externalServer: Path? = null, gpuLayers: Int = 0): Unit = withContext(Dispatchers.IO) {
        require(spec.kind == "text" && verified(spec)) { "Download and verify the text model first." }
        if (running) stop()
        require(gpuLayers in 0..99)
        val exe = externalServer ?: nativeProgram("llama-server")
        require(Files.isRegularFile(exe)) { "Selected llama-server.exe is missing." }
        server = ProcessBuilder(exe.toString(), "-m", modelPath(spec).toString(),
            "--host", "127.0.0.1", "--port", port.toString(), "-c", "2048", "-ngl", gpuLayers.toString())
            .directory(exe.parent.toFile()).redirectErrorStream(true)
            .redirectOutput(defaultDataDirectory().resolve("model-runtime.log").toFile()).start()
        try { repeat(90) {
            if (!running) error("The local model exited during startup. Check model-runtime.log in Alter's data folder.")
            try {
                val request = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
                request.connectTimeout = 500; request.readTimeout = 500
                val status = request.responseCode; request.disconnect()
                if (status == 200) return@withContext
            } catch (_: Exception) { /* Still loading. */ }
            delay(1000)
        }
        stop(); error("The local model did not become ready within 90 seconds.")
        } catch (e: Exception) { stop(); throw e }
    }

    fun stop() {
        server?.destroy()
        server?.waitFor(2, TimeUnit.SECONDS)
        if (server?.isAlive == true) server?.destroyForcibly()
        server = null
    }

    override fun close() = stop()
}
