package alter

import com.example.data.*
import com.example.ui.*
import alter.graphics.Bitmap
import alter.graphics.Canvas
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class DesktopTest {
    @Test fun hardwareRecommendationKeepsSmallPcsUsable() {
        assertEquals("qwen-small", HardwareBudget(8L shl 30, 4).suggestedTextId)
        assertEquals(100L, HardwareBudget(8L shl 30, 4).renderFrameMillis)
        assertEquals("qwen-4b", HardwareBudget(16L shl 30, 8).suggestedTextId)
        assertEquals(50L, HardwareBudget(16L shl 30, 8).renderFrameMillis)
    }
    @Test fun reviewedModelsRequireCorrectBytesAndDigest() {
        assertEquals(models.size, models.map { it.id }.distinct().size)
        models.forEach { spec ->
            assertTrue(spec.url.startsWith("https://huggingface.co/${spec.repo}/resolve/${spec.revision}/"))
            assertTrue(spec.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(spec.bytes > 1_000_000)
        }
        val dir = Files.createTempDirectory("alter-model-check-")
        try {
            val body = "real artifact bytes".toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val spec = ModelSpec("test", "Test", "text", "Qwen/example", "a".repeat(40), "test.gguf", body.size.toLong(), digest, "")
            Files.createDirectories(modelPath(spec, dir).parent)
            Files.write(modelPath(spec, dir), body)
            assertTrue(verified(spec, dir))
            Files.write(modelPath(spec, dir), "fake artifact bytes".toByteArray())
            assertFalse(verified(spec, dir))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun notesTasksAndSettingsSurviveReopening() {
        val dir = Files.createTempDirectory("alter-test-")
        try {
            Store(dir).use { s ->
                s.save(AlterCard(type = "note", rawInput = "नोट · café", processedContent = "Line 1\nLine 2 😀"))
                s.save(AlterCard(type = "upcoming", rawInput = "- [ ] Meet tomorrow", processedContent = "- [ ] Meet tomorrow"))
                s.setting("theme", "Light", true)
                val task = s.cards().first { it.type == "upcoming" }
                assertEquals(listOf("Meet tomorrow"), pendingCardTasks(task))
                s.save(completeCardTask(task, "Meet tomorrow"))
                s.addMessage("user", "Hi")
            }
            Store(dir).use { s ->
                assertEquals(2, s.cards().size)
                assertEquals("Light", s.setting("theme"))
                assertEquals("Line 1\nLine 2 😀", s.cards().first { it.type == "note" }.processedContent)
                assertTrue(pendingCardTasks(s.cards().first { it.type == "upcoming" }).isEmpty())
                assertEquals(listOf("user" to "Hi"), s.messages())
                val note = s.cards().first { it.type == "note" }
                s.save(note.copy(processedContent = "Updated"))
                s.delete(note.id)
                assertEquals(1, s.cards().size)
                assertFailsWith<IllegalStateException> { s.save(note) }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun invalidWritesLeaveStoredRecordsIntact() {
        val dir = Files.createTempDirectory("alter-validation-")
        try { Store(dir).use { s ->
            s.save(AlterCard(type = "text", rawInput = "Original", processedContent = "Original"))
            assertFailsWith<IllegalArgumentException> { s.save(AlterCard(type = "note", rawInput = "", processedContent = "")) }
            assertFailsWith<IllegalArgumentException> { s.save(AlterCard(type = "audio", rawInput = "path", processedContent = "")) }
            assertEquals("Original", s.cards().single().processedContent)
        } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun upstreamTaskSyntaxCompletesOnlyTheStatedTask() {
        listOf("1. [ ] Buy milk", "• [ ] Buy milk", "☐ Buy milk", "- [] Buy milk").forEach { text ->
            val c = AlterCard(type = "upcoming", rawInput = "$text\n- [ ] Call Mum", processedContent = text)
            val result = completeCardTask(c, "Buy milk")
            assertTrue(parseTodoLine(result.processedContent)!!.isCompleted)
            assertEquals(listOf("Call Mum"), pendingCardTasks(result))
        }
        assertNull(parseTodoLine("- [ ] [NO_SPEECH_DETECTED]"))
    }

    @Test fun localChatUsesActualReplyAndRuntimePersonality() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var request: JSONObject? = null
        server.createContext("/v1/chat/completions") { exchange ->
            request = JSONObject(exchange.requestBody.bufferedReader().readText())
            val bytes = """{"choices":[{"message":{"content":"<think>private</think>Hello Hardikk"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val answer = LocalChat(server.address.port).reply(listOf("user" to "Hello"))
            assertEquals("Hello Hardikk", answer)
            val messages = request!!.getJSONArray("messages")
            assertTrue(messages.getJSONObject(0).getString("content").contains("You are Alter"))
            assertEquals("Hello", messages.getJSONObject(1).getString("content"))
            assertFalse(request!!.getBoolean("stream"))
        } finally { server.stop(0) }
    }

    @Test fun modelHttpFailureIsReported() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.sendResponseHeaders(503, -1); exchange.close() }
        server.start()
        try { assertFailsWith<IllegalStateException> { LocalChat(server.address.port).reply(listOf("user" to "Hello")) } }
        finally { server.stop(0) }
        Unit
    }

    @Test fun riverRetainsSharedStateAndRendersOnDesktop() {
        val river = ShoalSimulation(seed = 42)
        river.resize(800f, 600f)
        repeat(120) { river.update(.033f) }
        river.feed(300f, 300f)
        val snapshot = river.snapshot()
        val restored = ShoalSimulation(seed = 7)
        restored.restore(snapshot)
        assertContentEquals(snapshot, restored.snapshot())
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        KoiAsciiRenderer().use { renderer -> Canvas(bitmap).use { renderer.draw(it, restored, 800f, 600f, 1f, 1f, true) } }
        val pixels = IntArray(800 * 600)
        bitmap.getPixels(pixels, 0, 800, 0, 0, 800, 600)
        assertTrue(pixels.count { it ushr 24 > 0 } > 1000)
        bitmap.recycle()
        assertEquals(setOf(RiverSpecies.KOI, RiverSpecies.HAMMERHEAD, RiverSpecies.DOLPHIN), river.fish.map { it.species }.toSet())
    }
}
