package alter

import kotlinx.coroutines.*
import java.nio.file.Files

internal data class WindowTarget(val handle: Long, val pid: Long, val title: String)

internal object WindowsIntegration {
    private val program get() = nativeDirectory().resolve("alter-windows.exe")
    val available get() = System.getProperty("os.name").startsWith("Windows") && Files.isRegularFile(program)

    private fun windowCommand(arguments: List<String>, input: String = ""): String {
        val process = ProcessBuilder(listOf(program.toString()) + arguments).start()
        try {
            val output = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            check(process.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) { "The window took too long to respond." }
            check(process.exitValue() == 0) { "The window changed or does not support this action. Choose the window again." }
            return output.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } finally { process.destroyForcibly() }
    }

    fun peakMemory(pid: Long): Long = windowCommand(listOf("peak-memory", pid.toString())).trim().toLong()

    fun windows(): List<WindowTarget> = windowCommand(listOf("windows", ProcessHandle.current().pid().toString()))
        .lineSequence().filter(String::isNotBlank).map {
            val fields = it.split('\t', limit = 3)
            WindowTarget(fields[0].toLong(), fields[1].toLong(), fields[2])
        }.sortedBy { it.title.lowercase() }.toList()

    fun resize(target: WindowTarget, maximize: Boolean): String {
        windowCommand(listOf(if (maximize) "maximize" else "restore", target.handle.toString(), target.pid.toString()), target.title)
        return if (maximize) "Maximized ${target.title}." else "Restored ${target.title}."
    }

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
        val process = ProcessBuilder(program.toString(), "shortcut", ProcessHandle.current().pid().toString()).start()
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
