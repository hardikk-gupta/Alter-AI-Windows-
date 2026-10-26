package alter

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.example.data.*
import com.example.ui.*
import com.example.ui.theme.*
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.Desktop
import java.awt.Font
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Files.isRegularFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import alter.graphics.Bitmap
import alter.graphics.Canvas as RiverCanvas

private enum class Destination(val title: String) { Home("Home"), Chat("Alter AI"), Memories("Memories"), Upcoming("Upcoming"), Notes("Notes") }

fun main(args: Array<String>) {
    val store = Store(defaultDataDirectory())
    val preview = args.firstOrNull { it.startsWith("--preview=") }?.substringAfter('=')
    System.getenv("ALTER_DATA_DIR")?.let {
        val cache = Path.of(it, "skiko")
        Files.createDirectories(cache)
        System.setProperty("skiko.data.path", cache.toString())
    }
    application {
        Window(onCloseRequest = { store.close(); exitApplication() }, title = "Alter",
            icon = painterResource("alter-window.png"),
            state = rememberWindowState(width = 1280.dp, height = 800.dp)) {
            window.minimumSize = Dimension(650, 540)
            var isVisible by remember { mutableStateOf(true) }
            DisposableEffect(window) {
                val listener = object : WindowAdapter() {
                    override fun windowIconified(event: WindowEvent) { isVisible = false }
                    override fun windowDeiconified(event: WindowEvent) { isVisible = true }
                }
                window.addWindowListener(listener)
                onDispose { window.removeWindowListener(listener) }
            }
            AlterApp(store, isVisible, preview)
            if (args.contains("--screenshot")) LaunchedEffect(Unit) {
                delay(1000)
                val name = preview ?: "desktop"
                val before = Path.of("build", "screenshots", "$name-before.png")
                Files.createDirectories(before.parent)
                ImageIO.write(Robot().createScreenCapture(Rectangle(window.locationOnScreen, window.size)), "png", before.toFile())
                delay(3500)
                val output = Path.of("build", "screenshots", "$name.png")
                Files.createDirectories(output.parent)
                ImageIO.write(Robot().createScreenCapture(Rectangle(window.locationOnScreen, window.size)), "png", output.toFile())
                store.close(); exitApplication()
            }
        }
    }
}

@Composable
private fun AlterApp(store: Store, active: Boolean, preview: String?) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(Destination.entries.firstOrNull { it.name.equals(preview, true) } ?: Destination.Home) }
    var settings by remember { mutableStateOf<String?>(when (preview) {
        "models" -> "AI models"; "appearance" -> "Appearance"; "about" -> "About"; else -> null
    }) }
    var theme by remember { mutableStateOf(store.setting("theme", "Pitch Black")) }
    var reducedMotion by remember { mutableStateOf(store.setting("reducedMotion", "false").toBoolean()) }
    var cards by remember { mutableStateOf(store.cards()) }
    var messages by remember { mutableStateOf(store.messages()) }
    var search by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<AlterCard?>(null) }
    var delete by remember { mutableStateOf<AlterCard?>(null) }
    var captureOpen by remember { mutableStateOf(false) }
    LaunchedEffect(destination, settings) { captureOpen = false }
    var error by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf<Job?>(null) }
    val native = remember { NativeModels() }
    val recorder = remember { VoiceRecorder() }
    DisposableEffect(Unit) { onDispose { native.close(); recorder.close() } }
    var modelWork by remember { mutableStateOf<Job?>(null) }
    var modelStatus by remember { mutableStateOf("") }
    var modelStartupFailed by remember { mutableStateOf(false) }
    var installedVersion by remember { mutableIntStateOf(0) }
    var speaking by remember { mutableStateOf(false) }
    val installed = remember(installedVersion) { models.associateWith { isRegularFile(modelPath(it)) && Files.size(modelPath(it)) == it.bytes } }
    val pond = remember { ShoalSimulation() }
    val hardware = remember { hardwareBudget() }
    val dark = theme == "Pitch Black" || (theme == "Device" && isSystemInDarkTheme())
    val satoshi = remember {
        runCatching {
            val font = Thread.currentThread().contextClassLoader.getResourceAsStream("fonts/satoshi_regular.otf")
                ?: error("Satoshi unavailable")
            font.use { FontFamily(androidx.compose.ui.text.platform.Font("Satoshi", it.readBytes())) }
        }.getOrDefault(FontFamily.SansSerif)
    }
    val colors = if (dark) darkColorScheme(primary = AlterYellow, onPrimary = PitchBlack,
        background = PitchBlack, surface = SlateGrayCard, onSurface = SecondaryText,
        onBackground = SecondaryText, outline = ReviewBorder, secondaryContainer = HoverSurface)
    else lightColorScheme(primary = AlterYellow, onPrimary = PitchBlack,
        background = Color(0xFFF7F8FA), surface = Color.White, onSurface = Color(0xFF111111),
        onBackground = Color(0xFF111111), outline = Color(0xFFEAEAEA))
    val typography = Typography().let { t -> t.copy(
        headlineSmall = t.headlineSmall.copy(fontFamily = satoshi, fontSize = 26.sp, lineHeight = 34.sp),
        titleLarge = t.titleLarge.copy(fontFamily = satoshi, fontSize = 20.sp, lineHeight = 28.sp),
        bodyLarge = t.bodyLarge.copy(fontFamily = satoshi, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = satoshi, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontFamily = satoshi), bodySmall = t.bodySmall.copy(fontFamily = satoshi)) }
    fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { error = e.message ?: "Could not save. Your editor is still open." } }
    fun send() {
        if (prompt.isBlank() || generation != null) return
        if (!native.running) { settings = "AI models"; error = "Choose a text model and tap Use model before chatting."; return }
        val text = prompt.trim()
        attempt {
            store.addMessage("user", text); messages = store.messages(); prompt = ""
            generation = scope.launch {
                try {
                    val reply = LocalChat(store.setting("modelPort", "8080").toInt()).reply(messages)
                    store.addMessage("assistant", reply); messages = store.messages()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "Local model unavailable. ${e.message ?: "Check Model settings."}" }
                finally { generation = null }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = typography) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            val content = cards.filter { card -> when (destination) {
                Destination.Memories -> card.type == "text"
                Destination.Notes -> card.type == "note"
                Destination.Upcoming -> card.type == "upcoming"
                else -> true
            } }
            val showRiver = settings == null && (destination == Destination.Chat || content.isEmpty())
            BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.N && settings == null && destination != Destination.Chat) {
                    draft = AlterCard(type = if (destination == Destination.Upcoming) "upcoming" else "note", rawInput = "", processedContent = ""); true
                } else false
            }) {
                val compact = maxWidth < 900.dp
                Row(Modifier.fillMaxSize()) {
                Navigation(destination, settings != null, compact, { destination = it; settings = null; search = "" }, { settings = "Appearance" })
                Box(Modifier.weight(1f).fillMaxHeight()) {
                River(pond, showRiver, active && !reducedMotion && draft == null,
                    if (destination == Destination.Chat && messages.isNotEmpty()) .3f else .75f, dark)
                Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 20.dp else 44.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth().height(78.dp).background(colors.background.copy(alpha = .97f)),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("YOUR PRIVATE WORKSPACE", fontSize = 10.sp, letterSpacing = 1.5.sp,
                                color = colors.onSurface.copy(alpha = .45f), fontWeight = FontWeight.Bold)
                            Text(if (settings == null) destination.title else if (settings == "root") "Settings" else settings!!,
                                style = MaterialTheme.typography.headlineSmall)
                        }
                        Row(Modifier.background(colors.surface, RoundedCornerShape(50)).border(1.dp, colors.outline, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(Color(0xFF83D8AD), RoundedCornerShape(50)))
                            Spacer(Modifier.width(8.dp))
                            Text("Local-first", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            if (modelStartupFailed && isRegularFile(modelRuntimeLog())) TextButton(onClick = {
                                attempt { Desktop.getDesktop().open(modelRuntimeLog().toFile()) }
                            }) { Text("Open runtime log") }
                            TextButton(onClick = { error = null; modelStartupFailed = false }) { Text("Dismiss") }
                        }
                    }
                    if (settings != null) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.width(if (compact) 150.dp else 205.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Appearance", "AI models", "Voice & files", "Backup & account", "About").forEach { name ->
                                val selected = settings == name
                                Text(name, Modifier.fillMaxWidth().background(if (selected) colors.secondaryContainer else Color.Transparent,
                                    RoundedCornerShape(10.dp)).clickable { settings = name }.padding(13.dp),
                                    color = if (selected) AlterYellow else colors.onSurface.copy(alpha = .7f), fontSize = 14.sp)
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (settings) {
                            "root" -> {
                                Text("Make Alter yours", style = MaterialTheme.typography.titleLarge)
                                Text("Your workspace, models and privacy stay under your control.", color = colors.onSurface.copy(alpha = .65f))
                                Spacer(Modifier.height(12.dp))
                                listOf(
                                    Triple("Appearance", "Theme and motion", Lucide.Settings),
                                    Triple("AI models", "Download local text and speech models", Lucide.Sparkles),
                                    Triple("Voice & files", "Dictation and desktop capabilities", Lucide.FileText),
                                    Triple("Backup & account", "Local data and portability", Lucide.Memories),
                                    Triple("About", "Build and source reference", Lucide.Home)
                                ).forEach { (name, description, icon) ->
                                    Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp))
                                        .clickable { settings = name }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(38.dp).background(AlterYellow.copy(alpha = .08f), RoundedCornerShape(10.dp)),
                                            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), AlterYellow) }
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(name, style = MaterialTheme.typography.titleMedium)
                                            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = .6f))
                                        }
                                        Icon(Lucide.ChevronRight, null, Modifier.size(18.dp), colors.onSurface.copy(alpha = .45f))
                                    }
                                }
                            }
                            "Appearance" -> {
                                Text("Make Alter yours.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Appearance and motion across your private workspace.", color = colors.onSurface.copy(alpha = .62f))
                                Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp))
                                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp)).padding(18.dp)) {
                                    Text("Theme", fontWeight = FontWeight.Bold)
                                    listOf("Pitch Black", "Device", "Light").forEach { value ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(theme == value, onClick = { attempt { store.setting("theme", value, true); theme = value } })
                                            Text(value)
                                        }
                                    }
                                    HorizontalDivider(color = colors.outline)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(reducedMotion, onCheckedChange = { value -> attempt { store.setting("reducedMotion", value.toString(), true); reducedMotion = value } })
                                        Column {
                                            Text("Reduce motion")
                                            Text("Pause decorative movement.", color = colors.onSurface.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            "AI models" -> {
                                Text("AI that runs here.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Download a model once, then chat or dictate offline. Nothing starts until you choose it.",
                                    color = colors.onSurface.copy(alpha = .66f))
                                Text("This PC: about ${hardware.ramBytes / (1024 * 1024 * 1024)} GB RAM · ${hardware.processors} CPU threads. Suggested text model: ${models.first { it.id == hardware.suggestedTextId }.title}.",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = .65f))
                                if (modelStatus.isNotBlank()) Text(modelStatus, color = AlterYellow)
                                var customRuntime by remember { mutableStateOf(store.setting("cudaServer")) }
                                Text("Inference: ${if (customRuntime.isBlank()) "Included CPU runtime" else "Selected NVIDIA CUDA runtime"}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { attempt {
                                        val chooser = javax.swing.JFileChooser()
                                        chooser.dialogTitle = "Choose a compatible Windows CUDA llama-server.exe"
                                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                            val selected = chooser.selectedFile.toPath()
                                            require(selected.fileName.toString().equals("llama-server.exe", true)) { "Choose llama-server.exe." }
                                            store.setting("cudaServer", selected.toString(), true); customRuntime = selected.toString()
                                        }
                                    } }) { Text("Use NVIDIA GPU runtime…") }
                                    if (customRuntime.isNotBlank()) TextButton(onClick = {
                                        store.setting("cudaServer", "", true); customRuntime = ""
                                    }) { Text("Use included CPU") }
                                }
                                Text("The included CPU engine works on compatible PCs. For RTX 4050, select a compatible CUDA llama-server.exe with its DLLs beside it.",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = .55f))
                                models.forEach { spec ->
                                    if (spec.id == "qwen-small" || spec.id == "whisper-tiny") {
                                        Spacer(Modifier.height(12.dp))
                                        Text(if (spec.kind == "text") "Text models" else "Voice models",
                                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text(if (spec.kind == "text") "Start small. You can switch later."
                                            else "Dictation fills a draft you can review before sending.",
                                            color = colors.onSurface.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp))
                                        .border(1.dp, colors.outline, RoundedCornerShape(16.dp)).padding(18.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (spec.id == hardware.suggestedTextId) Text("SUGGESTED FOR THIS PC", color = AlterYellow,
                                                fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                                            Text(spec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text(spec.description, style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurface.copy(alpha = .55f))
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            if (installed[spec] == true) {
                                                if (spec.kind == "text") Button(enabled = modelWork == null, onClick = {
                                                    modelWork = scope.launch {
                                                        modelStatus = "Verifying and loading ${spec.title}…"
                                                        modelStartupFailed = false
                                                        try {
                                                            val custom = store.setting("cudaServer").takeIf(String::isNotBlank)?.let(Path::of)
                                                            native.start(spec, store.setting("modelPort", "8080").toInt(), custom,
                                                                if (custom == null) 0 else 99)
                                                            store.setting("textModel", spec.id, true)
                                                            modelStatus = "${spec.title} is ready for offline chat."
                                                        } catch (e: Exception) {
                                                            modelStatus = "${spec.title} could not start."
                                                            modelStartupFailed = true
                                                            error = e.message ?: "Model startup failed."
                                                        }
                                                        finally { modelWork = null }
                                                    }
                                                }) { Text(if (native.running && store.setting("textModel") == spec.id) "In use" else "Use model") }
                                                else Button(onClick = { attempt {
                                                    require(verified(spec)) { "Speech model checksum did not match. Download again." }
                                                    store.setting("voiceModel", spec.id, true)
                                                    modelStatus = "${spec.title} selected for dictation."
                                                } }) { Text(if (store.setting("voiceModel") == spec.id) "Selected" else "Use for voice") }
                                            } else Button(enabled = modelWork == null, onClick = {
                                                modelWork = scope.launch {
                                                    try {
                                                        modelStatus = "Downloading ${spec.title}…"
                                                        downloadModel(spec, { n, total -> modelStatus = "${spec.title}: ${n * 100 / total}%" })
                                                        installedVersion++
                                                        modelStatus = "${spec.title} downloaded and verified."
                                                    } catch (e: CancellationException) { modelStatus = "Download cancelled." }
                                                    catch (e: Exception) { error = e.message ?: "Model download failed." }
                                                    finally { modelWork = null }
                                                }
                                            }) { Text("Download") }
                                            TextButton(onClick = { attempt { Desktop.getDesktop().browse(java.net.URI(spec.page)) } }) {
                                                Text("Source & licence", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                if (modelWork != null) TextButton(onClick = { modelWork?.cancel() }) { Text("Cancel download / startup") }
                                if (native.running) TextButton(onClick = { native.stop(); modelStatus = "Local model stopped." }) { Text("Release model") }
                            }
                            "Voice & files" -> Text("Tap Mic in Alter AI to record, then Stop mic to transcribe with a downloaded Whisper model. Review the transcript before sending. File attachment, retrieval and system hotkeys need later Windows adapters.")
                            "Backup & account" -> Text("Records are stored privately on this PC. Android ZIP v2 import/export and desktop Google authorization are pending. Do not copy an Android database into this app.")
                            "About" -> {
                                Text("About Alter.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Version details and the path to a newer Windows build.", color = colors.onSurface.copy(alpha = .62f))
                                Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp))
                                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp)).padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("Alter for Windows · 0.1.3", fontWeight = FontWeight.Bold)
                                    Text("Install a newer MSI to upgrade. Your notes and downloaded models stay under your Windows user profile.")
                                    TextButton(onClick = { attempt { Desktop.getDesktop().browse(java.net.URI(
                                        "https://github.com/hardikk-gupta/Alter-AI-Windows-/actions/workflows/windows.yml")) } }) { Text("View latest build ↗") }
                                    HorizontalDivider(color = colors.outline)
                                    Text("Android design reference · 0bda3d8583d1f53b6f305e1eeb22628c17a08e66",
                                        color = colors.onSurface.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        }
                    } else if (destination == Destination.Chat) {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Alter AI", fontWeight = FontWeight.Bold)
                                Text("A private conversation on your device", color = colors.onSurface.copy(alpha = .55f),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { settings = "AI models" }) {
                                Text(if (native.running) "●  Local model ready" else "Set up local model ↗")
                            }
                        }
                        HorizontalDivider(color = colors.outline)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState,
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (messages.isEmpty()) item { Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.BottomStart) {
                                Text("What’s on your mind?", Modifier.background(colors.background.copy(alpha = .85f),
                                    RoundedCornerShape(12.dp)).padding(12.dp), color = colors.onBackground.copy(alpha = .7f))
                            } }
                            items(messages) { (sender, text) ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = if (sender == "user") Alignment.CenterEnd else Alignment.CenterStart) {
                                    Column(Modifier.widthIn(max = 650.dp).then(if (sender == "user") Modifier.background(colors.secondaryContainer,
                                        RoundedCornerShape(14.dp)).padding(14.dp) else Modifier.padding(8.dp))) {
                                        if (sender != "user") Text("✦  ALTER", color = AlterYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        androidx.compose.foundation.text.selection.SelectionContainer {
                                            Text(text, Modifier.padding(top = if (sender == "user") 0.dp else 8.dp), lineHeight = 21.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = generation == null, onClick = { attempt { store.clearMessages(); messages = store.messages() } }) { Text("New conversation") }
                            Spacer(Modifier.weight(1f))
                            Text("Stored on this PC", color = colors.onSurface.copy(alpha = .45f), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth().background(colors.surface.copy(alpha = .94f), RoundedCornerShape(17.dp))
                            .border(1.dp, colors.outline, RoundedCornerShape(17.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(prompt, { prompt = it.take(16000) }, Modifier.weight(1f).heightIn(max = 160.dp)
                                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && it.isCtrlPressed) { send(); true } else false },
                                placeholder = { Text("Ask Alter anything… · Ctrl+Enter to send") }, enabled = generation == null,
                                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = Color.Transparent))
                            TextButton(onClick = {
                                if (!recorder.recording) attempt {
                                    val chosen = models.firstOrNull { it.id == store.setting("voiceModel") }
                                        ?: error("Download and select a speech model in AI models first.")
                                    require(installed[chosen] == true) { "Selected speech model is missing." }
                                    recorder.start(); speaking = true; modelStatus = "Recording… Tap Stop when finished."
                                } else {
                                    speaking = false
                                    val chosen = models.first { it.id == store.setting("voiceModel") }
                                    modelWork = scope.launch {
                                        modelStatus = "Transcribing locally…"
                                        try {
                                            val transcript = recorder.stopAndTranscribe(chosen)
                                            prompt = listOf(prompt.trim(), transcript).filter(String::isNotBlank).joinToString(" ")
                                            modelStatus = "Transcript ready. Review it before sending."
                                        } catch (e: Exception) { error = e.message ?: "Transcription failed." }
                                        finally { modelWork = null }
                                    }
                                }
                            }) { Text(if (speaking) "Stop mic" else "Mic") }
                            if (generation != null) TextButton(onClick = { generation?.cancel() }) { Text("Stop") }
                            else IconButton(onClick = { send() }, enabled = prompt.isNotBlank()) { Text("→", fontSize = 26.sp) }
                        }
                    } else if (destination == Destination.Home) {
                        HomeContent(cards, { destination = Destination.Chat }, { destination = Destination.Upcoming },
                            { draft = it }, { delete = it }, { card, text -> attempt {
                                store.save(completeCardTask(card, text)); cards = store.cards()
                            } }, Modifier.weight(1f))
                    } else {
                        Column {
                            Text(when (destination) {
                                Destination.Memories -> "Keep what matters."
                                Destination.Upcoming -> "One thing at a time."
                                else -> "Notes for the thinking in between."
                            }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(when (destination) {
                                Destination.Memories -> "Small details, people, and moments you may want to find again."
                                Destination.Upcoming -> "Commitments you chose to keep. No dates are invented for you."
                                else -> "Drafts, plans, and ideas you want to work through."
                            }, color = colors.onSurface.copy(alpha = .62f), style = MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)), singleLine = true,
                            placeholder = { Text("Search ${destination.title.lowercase()}") }, leadingIcon = { Icon(Lucide.Search, null) })
                        val filtered = content.filter { (it.rawInput + "\n" + it.processedContent).contains(search, ignoreCase = true) }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (filtered.isEmpty()) item { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text(if (content.isNotEmpty()) "No matches" else when(destination) {
                                    Destination.Home, Destination.Memories -> "Make room for a memory"
                                    Destination.Upcoming -> "Leave a little space for what’s next"
                                    else -> "Start with your first note"
                                }, color = colors.onBackground.copy(alpha = .6f))
                            } }
                            items(filtered, key = { it.id }) { card -> EntryCard(card, onEdit = { draft = card }, onDelete = { delete = card }, onComplete = { text ->
                                attempt { store.save(completeCardTask(card, text)); cards = store.cards() }
                            }) }
                        }
                    }
                }
                if (settings == null && destination != Destination.Chat) {
                    Column(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnimatedVisibility(captureOpen,
                            enter = fadeIn(tween(180)) + scaleIn(transformOrigin = TransformOrigin(1f, 1f), animationSpec = tween(220)),
                            exit = fadeOut(tween(140)) + scaleOut(transformOrigin = TransformOrigin(1f, 1f), animationSpec = tween(180))) {
                            Column(Modifier.background(colors.surface, RoundedCornerShape(18.dp))
                                .border(1.dp, colors.outline, RoundedCornerShape(18.dp)).padding(8.dp)) {
                                listOf(Triple("Memory", "text", Lucide.Memories),
                                    Triple("Note", "note", Lucide.FileText),
                                    Triple("Task", "upcoming", Lucide.Calendar)).forEach { (label, type, icon) ->
                                    Row(Modifier.width(156.dp).height(46.dp).clickable {
                                        captureOpen = false
                                        draft = AlterCard(type = type, rawInput = "", processedContent = "")
                                    }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(icon, null, Modifier.size(20.dp), AlterYellow)
                                        Spacer(Modifier.width(12.dp)); Text(label)
                                    }
                                }
                            }
                        }
                        val rotation by animateFloatAsState(if (captureOpen) 45f else 0f, label = "Capture button")
                        FilledIconButton(onClick = { captureOpen = !captureOpen },
                            modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = AlterYellow, contentColor = PitchBlack)) {
                            Icon(Lucide.Plus, if (captureOpen) "Close capture actions" else "Add capture",
                                Modifier.size(24.dp).graphicsLayer { rotationZ = rotation })
                        }
                    }
                }
                }
                }
            }
            draft?.let { card ->
                Editor(card, onDismiss = { draft = null }, onSave = { edited -> attempt {
                    store.save(edited); cards = store.cards(); draft = null
                } })
            }
            delete?.let { card -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this entry?") },
                text = { Text("It will be removed from your lists.") }, confirmButton = { TextButton(onClick = { attempt {
                    store.delete(card.id); cards = store.cards(); delete = null
                } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
        }
    }
}

@Composable
private fun Navigation(current: Destination, settings: Boolean, compact: Boolean, select: (Destination) -> Unit, openSettings: () -> Unit) {
    val icons = listOf(Lucide.Home, Lucide.Sparkles, Lucide.Memories, Lucide.Calendar, Lucide.FileText)
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(if (compact) 64.dp else 234.dp).fillMaxHeight().background(if (colors.background == PitchBlack) SidebarSurface else colors.surface)
        .border(width = .5.dp, color = colors.outline).padding(horizontal = if (compact) 7.dp else 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(65.dp).padding(start = if (compact) 10.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource("alter-logo.xml"), "Alter", Modifier.size(28.dp), AlterYellow)
            if (!compact) { Spacer(Modifier.width(10.dp)); Text("alter", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        if (!compact) Text("WORKSPACE", Modifier.padding(start = 13.dp, top = 12.dp, bottom = 10.dp), fontSize = 10.sp,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = colors.onSurface.copy(alpha = .45f))
        Destination.entries.forEachIndexed { i, tab ->
            val selected = tab == current && !settings
            Row(Modifier.fillMaxWidth().height(44.dp).semantics { this.selected = selected }
                .background(if (selected) AlterYellow.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(11.dp))
                .clickable(role = Role.Tab) { select(tab) }.padding(horizontal = if (compact) 0.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (selected) Box(Modifier.width(3.dp).height(26.dp).background(AlterYellow, RoundedCornerShape(50)))
                if (compact) Spacer(Modifier.width(if (selected) 12.dp else 15.dp)) else Spacer(Modifier.width(if (selected) 9.dp else 12.dp))
                Icon(icons[i], tab.title, Modifier.size(18.dp), if (selected) AlterYellow else colors.onSurface.copy(alpha = .6f))
                if (!compact) {
                    Spacer(Modifier.width(13.dp))
                    Text(tab.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.onSurface else colors.onSurface.copy(alpha = .7f))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (!compact) Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(13.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(13.dp)).padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Color(0xFF83D8AD), RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp)); Text("On this PC", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Your notes and models stay local.", fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = .47f), lineHeight = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(44.dp).background(if (settings) AlterYellow.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable(onClick = openSettings).padding(horizontal = if (compact) 14.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Settings, "Settings", Modifier.size(18.dp), if (settings) AlterYellow else colors.onSurface.copy(alpha = .7f))
            if (!compact) { Spacer(Modifier.width(13.dp)); Text("Settings", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun HomeContent(cards: List<AlterCard>, onChat: () -> Unit, onUpcoming: () -> Unit,
    onEdit: (AlterCard) -> Unit, onDelete: (AlterCard) -> Unit,
    onComplete: (AlterCard, String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tasks = cards.filter { it.type == "upcoming" }.take(2)
    val recent = cards.filter { it.type != "upcoming" }.take(2)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Box(Modifier.fillMaxWidth().height(260.dp)
            .background(Brush.horizontalGradient(listOf(colors.surface, Color(0xFF10100E))), RoundedCornerShape(22.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(22.dp))) {
            Image(painterResource("alter-window.png"), null, Modifier.align(Alignment.CenterEnd).padding(end = 45.dp).size(220.dp), alpha = .55f)
            Column(Modifier.align(Alignment.CenterStart).padding(38.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("A QUIETER PLACE TO THINK", color = AlterYellow, fontSize = 10.sp,
                    letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                Text("Everything on your mind,\nin one calm place.", fontSize = 32.sp,
                    lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text("Capture the small things. Come back to what matters.",
                    color = colors.onSurface.copy(alpha = .68f), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onChat, shape = RoundedCornerShape(10.dp)) { Text("Talk to Alter ↗") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Your day", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("A simple view of what needs your attention.", color = colors.onSurface.copy(alpha = .58f))
            }
            TextButton(onClick = onUpcoming) { Text("See upcoming →") }
        }
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.BottomStart) {
                Column(Modifier.background(colors.background.copy(alpha = .85f), RoundedCornerShape(13.dp)).padding(16.dp)) {
                    Text("Your space starts here", fontWeight = FontWeight.Bold)
                    Text("Use the plus button to keep a thought, note or task.", color = colors.onSurface.copy(alpha = .64f))
                }
            }
        } else BoxWithConstraints {
            val wide = maxWidth >= 700.dp
            val sections = listOf("Today's commitments" to tasks, "Recently captured" to recent)
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                sections.forEach { (title, entries) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(title, fontWeight = FontWeight.Bold)
                        if (entries.isEmpty()) Text("Nothing here yet", color = colors.onSurface.copy(alpha = .5f))
                        entries.forEach { card -> EntryCard(card, { onEdit(card) }, { onDelete(card) }, { onComplete(card, it) }) }
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                sections.forEach { (title, entries) ->
                    Text(title, fontWeight = FontWeight.Bold)
                    entries.forEach { card -> EntryCard(card, { onEdit(card) }, { onDelete(card) }, { onComplete(card, it) }) }
                }
            }
        }
        Spacer(Modifier.height(75.dp))
    }
}

@Composable
private fun EntryCard(card: AlterCard, onEdit: () -> Unit, onDelete: () -> Unit, onComplete: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp))
        .border(1.dp, colors.outline, RoundedCornerShape(18.dp)).clickable(onClick = onEdit).padding(19.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(when(card.type) { "note" -> "NOTE"; "upcoming" -> "TASK"; else -> "MEMORY" },
                fontSize = 10.sp, letterSpacing = 1.2.sp, color = AlterYellow, fontWeight = FontWeight.Bold)
            Text(DateTimeFormatter.ofPattern("d MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(card.timestamp)),
                fontSize = 10.sp, color = colors.onSurface.copy(alpha = .44f))
        }
        if (card.type == "note" && card.rawInput.isNotBlank()) Text(card.rawInput, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        card.processedContent.lines().take(8).forEach { line ->
            val task = parseTodoLine(line)
            if (task != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(task.isCompleted, onCheckedChange = if (task.isCompleted) null else { _ -> onComplete(task.taskText) })
                Text(task.taskText)
            } else if (line.isNotBlank()) Text(line, fontSize = 14.sp, lineHeight = 21.sp,
                color = colors.onSurface.copy(alpha = .77f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit) { Text("Edit") }
            IconButton(onClick = onDelete) { Icon(Lucide.Trash, "Delete entry", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun Editor(card: AlterCard, onDismiss: () -> Unit, onSave: (AlterCard) -> Unit) {
    var title by remember(card) { mutableStateOf(if (card.type == "note") card.rawInput else "") }
    var body by remember(card) { mutableStateOf(card.processedContent) }
    var type by remember(card) { mutableStateOf(card.type) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (card.id == 0L) "Capture" else "Edit entry") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card.id == 0L) Row {
                listOf("text" to "Memory", "note" to "Note", "upcoming" to "Task").forEach { (value,label) ->
                    FilterChip(type == value, onClick = { type = value }, label = { Text(label) })
                }
            }
            if (type == "note") OutlinedTextField(title, { title = it.take(500) }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(body, { body = it.take(100_000) }, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                label = { Text(if (type == "upcoming") "One task per line · no reminder time is assumed" else "Write something") })
        } }, confirmButton = { Button(enabled = body.isNotBlank(), onClick = {
            val content = if (type == "upcoming" && card.id == 0L) body.lines().filter(String::isNotBlank)
                .joinToString("\n") { if (parseTodoLine(it) == null) "- [ ] ${it.trim()}" else it } else body
            onSave(card.copy(type = type, rawInput = if (type == "note") title.ifBlank { "Untitled Note" } else content, processedContent = content))
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun River(pond: ShoalSimulation, visible: Boolean, animate: Boolean, opacity: Float, dark: Boolean) {
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val frameMillis = remember { hardwareBudget().renderFrameMillis }
    val renderer = remember { KoiAsciiRenderer() }
    DisposableEffect(Unit) { onDispose { renderer.close() } }
    LaunchedEffect(visible, animate, dimensions, density, opacity, dark) {
        if (!visible || dimensions.width == 0 || dimensions.height == 0) return@LaunchedEffect
        synchronized(pond) { pond.resize(dimensions.width / density, dimensions.height / density) }
        val scale = minOf(1f, 1200f / dimensions.width, 900f / dimensions.height)
        val renderWidth = (dimensions.width * scale).toInt().coerceAtLeast(1)
        val renderHeight = (dimensions.height * scale).toInt().coerceAtLeast(1)
        do {
            image = withContext(Dispatchers.Default) {
                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                synchronized(pond) {
                    if (animate) pond.update(frameMillis / 1000f)
                    RiverCanvas(bitmap).use { renderer.draw(it, pond, renderWidth.toFloat(), renderHeight.toFloat(), density * scale, opacity, dark) }
                }
                bitmap.image.toComposeImageBitmap()
            }
            if (!animate) break
            delay(frameMillis)
        } while (isActive)
    }
    Box(Modifier.fillMaxSize().onSizeChanged { dimensions = it }
        .then(if (visible && animate) Modifier.pointerInput(Unit) { detectTapGestures { synchronized(pond) { pond.feed(it.x / density, it.y / density) } } } else Modifier)) {
        if (visible) image?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}
