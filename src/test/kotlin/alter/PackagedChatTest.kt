package alter

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class PackagedChatTest {
    @Test fun packagedCpuEngineAnswersWithTheSmallQwenModel() {
        assumeTrue(System.getenv("ALTER_CHECK_PACKAGED_CHAT") == "1")
        val previous = System.getProperty("alter.dataDir")
        val directory = Files.createTempDirectory("alter-packaged-chat-")
        try {
            System.setProperty("alter.dataDir", directory.toString())
            val model = models.single { it.id == "qwen-small" }
            val cache = Path.of(System.getenv("ALTER_TEST_MODEL_CACHE"))
            runBlocking {
                val downloaded = downloadModel(model, { _, _ -> }, cache)
                Files.createDirectories(modelPath(model).parent)
                Files.copy(downloaded, modelPath(model))
                val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
                NativeModels().use { engine ->
                    engine.start(model, port)
                    val answer = LocalChat(port).reply(listOf("user" to "Say hello in one short sentence."))
                    assertTrue(answer.isNotBlank())
                }
            }
        } catch (e: Exception) {
            val log = directory.resolve("model-runtime.log")
            val detail = if (Files.exists(log)) Files.readString(log).takeLast(5000) else "No runtime log."
            throw AssertionError("Packaged inference failed: ${e.message}\n$detail", e)
        } finally {
            if (previous == null) System.clearProperty("alter.dataDir") else System.setProperty("alter.dataDir", previous)
            directory.toFile().deleteRecursively()
        }
    }
}
