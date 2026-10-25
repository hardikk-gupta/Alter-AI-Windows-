package alter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Explicit user-controlled PCM capture; no background microphone or network service. */
class VoiceRecorder : AutoCloseable {
    private val format = AudioFormat(16000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private var wav: java.nio.file.Path? = null
    private val samples = ByteArrayOutputStream()
    val recording get() = line != null

    fun start() {
        check(!recording) { "Recording is already active." }
        val target = AudioSystem.getTargetDataLine(format)
        target.open(format, 32000)
        target.start()
        line = target
        samples.reset()
        val file = Files.createTempFile("alter-voice-", ".wav")
        wav = file
        thread = Thread({
            val buffer = ByteArray(3200)
            try { while (target.isOpen && samples.size() < 28_800_000) {
                val count = target.read(buffer, 0, buffer.size)
                if (count > 0) samples.write(buffer, 0, count)
            } } catch (_: Exception) { /* stop() checks captured bytes. */ }
        }, "Alter microphone").apply { isDaemon = true; start() }
    }

    suspend fun stopAndTranscribe(model: ModelSpec): String = withContext(Dispatchers.IO) {
        val file = wav ?: error("Start recording first.")
        val input = line ?: error("Microphone is unavailable.")
        try {
            input.stop(); input.close(); line = null
            thread?.join(3000)
            val pcm = samples.toByteArray()
            require(pcm.size > 4096) { "No speech was recorded. Check your microphone and try again." }
            AudioSystem.write(javax.sound.sampled.AudioInputStream(ByteArrayInputStream(pcm), format,
                (pcm.size / format.frameSize).toLong()), AudioFileFormat.Type.WAVE, file.toFile())
            require(Files.size(file) > 4096) { "No speech was recorded. Check your microphone and try again." }
            require(verified(model)) { "Download and verify a speech model first." }
            val exe = nativeProgram("whisper-cli")
            val output = Files.createTempFile("alter-transcript-", "")
            Files.deleteIfExists(output)
            try {
                val process = ProcessBuilder(exe.toString(), "-m", modelPath(model).toString(),
                    "-f", file.toString(), "-otxt", "-of", output.toString(), "-np")
                    .directory(exe.parent.toFile()).redirectErrorStream(true)
                    .redirectOutput(defaultDataDirectory().resolve("speech-runtime.log").toFile()).start()
                if (!process.waitFor(3, TimeUnit.MINUTES)) { process.destroyForcibly(); error("Speech transcription timed out.") }
                check(process.exitValue() == 0) { "Speech model failed. Check speech-runtime.log in Alter's data folder." }
                val transcript = Files.readString(output.resolveSibling(output.fileName.toString() + ".txt")).trim()
                check(transcript.isNotBlank()) { "No speech detected. Tap the microphone to try again." }
                transcript
            } finally {
                Files.deleteIfExists(output)
                Files.deleteIfExists(output.resolveSibling(output.fileName.toString() + ".txt"))
            }
        } finally {
            Files.deleteIfExists(file); wav = null; thread = null
        }
    }

    override fun close() { line?.stop(); line?.close(); line = null; wav?.let(Files::deleteIfExists); wav = null }
}
