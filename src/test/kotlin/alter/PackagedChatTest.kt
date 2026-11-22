package alter

import java.awt.Robot
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedChatTest {
    @Test(timeout = 45_000L) fun holdToDictateReceivesPressAndReleaseAndCanRegisterAgain() {
        assumeTrue(System.getenv("ALTER_CHECK_PACKAGED_CHAT") == "1")
        val keyboard = Robot()
        runBlocking {
            withTimeout(25_000) {
                repeat(2) {
                    val events = Channel<String>(Channel.UNLIMITED)
                    val watcher = launch {
                        try { WindowsIntegration.watchShortcut { events.send(it) } }
                        catch (e: IllegalStateException) {
                            if (e is java.util.concurrent.CancellationException) throw e
                            throw AssertionError("Shortcut registration ${it + 1}: ${e.message}", e)
                        }
                    }
                    try {
                        assertEquals("ready", events.receive())
                        keyboard.keyPress(KeyEvent.VK_CONTROL)
                        keyboard.keyPress(KeyEvent.VK_ALT)
                        keyboard.keyPress(KeyEvent.VK_SPACE)
                        assertEquals("down", events.receive())
                        keyboard.keyRelease(KeyEvent.VK_SPACE)
                        assertEquals("up", events.receive())
                    } finally {
                        keyboard.keyRelease(KeyEvent.VK_SPACE)
                        keyboard.keyRelease(KeyEvent.VK_ALT)
                        keyboard.keyRelease(KeyEvent.VK_CONTROL)
                        watcher.cancelAndJoin()
                        events.close()
                    }
                }
            }
        }
    }

    @Test fun packagedEnginesAnswerAndTranscribe() {
        assumeTrue(System.getenv("ALTER_CHECK_PACKAGED_CHAT") == "1")
        val previous = System.getProperty("alter.dataDir")
        val directory = Path.of(System.getenv("ALTER_TEST_MODEL_CACHE")).toAbsolutePath()
        val reports = Path.of("build", "reports", "packaged-model-benchmarks")
        try {
            System.setProperty("alter.dataDir", directory.toString())
            Files.createDirectories(reports)
            runBlocking {
                for (model in models) {
                    downloadModel(model, { _, _ -> }, directory)
                    if (model.kind == "text") {
                        val report = benchmarkModel(model)
                        assertTrue(report.getLong("peakEngineBytes") > 0)
                        assertTrue(report.getLong("firstReplyMs") > 0)
                        Files.writeString(reports.resolve("${model.id}.json"), report.toString(2))
                    } else {
                        val sample = Path.of("src/test/resources/calendar-command.wav")
                        val transcript = transcribeWave(sample.toAbsolutePath(), model).lowercase()
                        assertContains(transcript, "calendar")
                        assertContains(transcript, "meeting")
                    }
                }
            }
        } catch (e: Exception) {
            val logs = listOf("model-runtime.log", "speech-runtime.log").map(directory::resolve)
                .filter(Files::isRegularFile).joinToString("\n") { Files.readString(it).takeLast(3000) }
            throw AssertionError("Packaged inference failed: ${e.message}\n$logs", e)
        } finally {
            if (previous == null) System.clearProperty("alter.dataDir") else System.setProperty("alter.dataDir", previous)
        }
    }
}
