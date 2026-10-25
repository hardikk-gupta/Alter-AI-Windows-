package alter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Reviewed official model commits and LFS digests. Weights live only in private app data.
data class ModelSpec(val id: String, val title: String, val kind: String, val repo: String,
    val revision: String, val file: String, val bytes: Long, val sha256: String, val description: String) {
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$file"
    val page get() = "https://huggingface.co/$repo"
}

val models = listOf(
    ModelSpec("qwen-small", "Qwen3 0.6B · low memory", "text", "Qwen/Qwen3-0.6B-GGUF",
        "23749fefcc72300e3a2ad315e1317431b06b590a", "Qwen3-0.6B-Q8_0.gguf", 639446688,
        "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", "CPU-friendly text model · 640 MB"),
    ModelSpec("qwen-4b", "Qwen3 4B · more capable", "text", "Qwen/Qwen3-4B-GGUF",
        "bc640142c66e1fdd12af0bd68f40445458f3869b", "Qwen3-4B-Q4_K_M.gguf", 2497280256,
        "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", "For 16 GB system RAM · 2.5 GB download"),
    ModelSpec("whisper-tiny", "Whisper Tiny · multilingual", "voice", "ggerganov/whisper.cpp",
        "5359861c739e955e79d9a303bcbc70fb988958b1", "ggml-tiny.bin", 77691713,
        "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", "Speech-to-text · 78 MB"),
    ModelSpec("whisper-base", "Whisper Base · multilingual", "voice", "ggerganov/whisper.cpp",
        "5359861c739e955e79d9a303bcbc70fb988958b1", "ggml-base.bin", 147951465,
        "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", "More accurate speech candidate · 148 MB")
)

fun modelPath(spec: ModelSpec, root: Path = defaultDataDirectory()): Path = root.resolve("models").resolve(spec.file)

data class HardwareBudget(val ramBytes: Long, val processors: Int) {
    val suggestedTextId get() = if (ramBytes < 12L * 1024 * 1024 * 1024) "qwen-small" else "qwen-4b"
    val renderFrameMillis get() = if (processors <= 4 || ramBytes < 8L * 1024 * 1024 * 1024) 100L else 50L
}

fun hardwareBudget(): HardwareBudget {
    val operatingSystem = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
    val memory = (operatingSystem as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
        ?: 8L * 1024 * 1024 * 1024
    return HardwareBudget(memory, Runtime.getRuntime().availableProcessors())
}

fun verified(spec: ModelSpec, root: Path = defaultDataDirectory()): Boolean {
    val path = modelPath(spec, root)
    if (!Files.isRegularFile(path) || Files.size(path) != spec.bytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val block = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(block)
            if (n < 0) break
            digest.update(block, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
}

suspend fun downloadModel(spec: ModelSpec, progress: (Long, Long) -> Unit,
    root: Path = defaultDataDirectory()): Path = withContext(Dispatchers.IO) {
    val target = modelPath(spec, root)
    Files.createDirectories(target.parent)
    if (verified(spec, root)) return@withContext target
    require(Files.getFileStore(target.parent).usableSpace >= spec.bytes + 300_000_000L) {
        "There is not enough free space for this model and its temporary download."
    }
    val part = target.resolveSibling(target.fileName.toString() + ".part")
    // Only immutable official Hugging Face links and their model-CDN redirects are accepted.
    var uri = URI(spec.url)
    try {
        var connection: HttpURLConnection? = null
        for (hop in 0..5) {
            require(uri.scheme == "https" && (uri.host == "huggingface.co" || uri.host.endsWith(".hf.co"))) {
                "Model download redirected outside the approved model host."
            }
            connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("User-Agent", "Alter-Windows/0.2")
            }
            val status = connection!!.responseCode
            if (status in 300..399) {
                require(hop < 5) { "Too many model redirects." }
                uri = uri.resolve(connection!!.getHeaderField("Location") ?: error("Missing redirect target"))
                connection!!.disconnect()
            } else {
                check(status == 200) { "Model download returned HTTP $status." }
                break
            }
        }
        val source = connection ?: error("No model response")
        check(source.responseCode == 200) { "Model source unavailable." }
        var count = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream.use { input -> Files.newOutputStream(part).use { output ->
            val block = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(block)
                if (n < 0) break
                count += n
                require(count <= spec.bytes) { "Model is larger than its reviewed size." }
                digest.update(block, 0, n); output.write(block, 0, n)
                progress(count, spec.bytes)
            }
        } }
        source.disconnect()
        require(count == spec.bytes) { "Model download was incomplete." }
        require(digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256) {
            "Model checksum did not match the reviewed official artifact."
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        target
    } finally { Files.deleteIfExists(part) }
}
