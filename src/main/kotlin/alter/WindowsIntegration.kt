package alter

import kotlinx.coroutines.*
import java.nio.file.Files

internal object WindowsIntegration {
    private val program get() = nativeDirectory().resolve("alter-windows.exe")
    val available get() = System.getProperty("os.name").startsWith("Windows") && Files.isRegularFile(program)

    fun credential(operation: String, project: Long, input: String = ""): String {
        require(operation in setOf("save", "load", "forget") && project >= 0)
        val process = ProcessBuilder(program.toString(), operation, project.toString()).start()
        return try {
            process.outputStream.use { it.write(input.toByteArray()) }
            val value = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Windows Credential Manager could not $operation the key for this project." }
            value
        } finally { process.destroy() }
    }

    suspend fun watchShortcut(event: suspend (String) -> Unit) = coroutineScope {
        val process = ProcessBuilder(program.toString(), "shortcut").start()
        try {
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { reader ->
                    // Closing the process releases the pipe even when the shortcut is idle.
                    val monitor = launch { awaitCancellation() }
                    monitor.invokeOnCompletion { process.destroy() }
                    try {
                        while (isActive) event(reader.readLine() ?: error("The shortcut is unavailable. Another app may be using it."))
                    } finally { monitor.cancel() }
                }
            }
        } finally { process.destroy() }
    }
}
