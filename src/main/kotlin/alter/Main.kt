package alter

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
    System.getenv("ALTER_DATA_DIR")?.let {
        val cache = Path.of(it, "skiko")
        Files.createDirectories(cache)
        System.setProperty("skiko.data.path", cache.toString())
    }
    application {
        Window(onCloseRequest = { store.close(); exitApplication() }, title = "Alter",
            state = rememberWindowState(width = 1024.dp, height = 760.dp)) {
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
            AlterApp(store, isVisible)
            if (args.contains("--screenshot")) LaunchedEffect(Unit) {
                delay(1000)
                val before = Path.of("build", "screenshots", "desktop-before.png")
                Files.createDirectories(before.parent)
                ImageIO.write(Robot().createScreenCapture(Rectangle(window.locationOnScreen, window.size)), "png", before.toFile())
                delay(3500)
                val output = Path.of("build", "screenshots", "desktop.png")
                Files.createDirectories(output.parent)
                ImageIO.write(Robot().createScreenCapture(Rectangle(window.locationOnScreen, window.size)), "png", output.toFile())
                store.close(); exitApplication()
            }
        }
    }
}

@Composable
private fun AlterApp(store: Store, active: Boolean) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(Destination.Home) }
    var settings by remember { mutableStateOf<String?>(null) }
    var theme by remember { mutableStateOf(store.setting("theme", "Pitch Black")) }
    var reducedMotion by remember { mutableStateOf(store.setting("reducedMotion", "false").toBoolean()) }
    var cards by remember { mutableStateOf(store.cards()) }
    var messages by remember { mutableStateOf(store.messages()) }
    var search by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<AlterCard?>(null) }
    var delete by remember { mutableStateOf<AlterCard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf<Job?>(null) }
    val native = remember { NativeModels() }
    val recorder = remember { VoiceRecorder() }
    DisposableEffect(Unit) { onDispose { native.close(); recorder.close() } }
    var modelWork by remember { mutableStateOf<Job?>(null) }
    var modelStatus by remember { mutableStateOf("") }
    var installedVersion by remember { mutableIntStateOf(0) }
    var speaking by remember { mutableStateOf(false) }
    val installed = remember(installedVersion) { models.associateWith { isRegularFile(modelPath(it)) && Files.size(modelPath(it)) == it.bytes } }
    val pond = remember { ShoalSimulation() }
    val dark = theme == "Pitch Black" || (theme == "Device" && isSystemInDarkTheme())
    val satoshi = remember {
        runCatching {
            val font = Thread.currentThread().contextClassLoader.getResourceAsStream("fonts/satoshi_regular.otf")
                ?: error("Satoshi unavailable")
            font.use { FontFamily(androidx.compose.ui.text.platform.Font("Satoshi", it.readBytes())) }
        }.getOrDefault(FontFamily.SansSerif)
    }
    val colors = if (dark) darkColorScheme(primary = AlterYellow, onPrimary = PitchBlack,
        background = PitchBlack, surface = SlateGrayCard, onSurface = LightText,
        onBackground = LightText, outline = BorderGray, secondaryContainer = RaisedSurface)
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
        Surface(Modifier.fillMaxSize()) {
            val content = cards.filter { card -> when (destination) {
                Destination.Memories -> card.type == "text"
                Destination.Notes -> card.type == "note"
                Destination.Upcoming -> card.type == "upcoming"
                else -> true
            } }
            val showRiver = settings == null && (destination == Destination.Chat || content.isEmpty())
            Row(Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.N && settings == null && destination != Destination.Chat) {
                    draft = AlterCard(type = if (destination == Destination.Upcoming) "upcoming" else "note", rawInput = "", processedContent = ""); true
                } else false
            }) {
                Navigation(destination, settings != null, { destination = it; settings = null; search = "" }, { settings = "root" })
                Box(Modifier.weight(1f).fillMaxHeight()) {
                River(pond, showRiver, active && !reducedMotion && draft == null,
                    if (destination == Destination.Chat && messages.isNotEmpty()) .3f else .75f, dark)
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth().background(colors.background.copy(alpha = .94f), RoundedCornerShape(12.dp)),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (settings == null) destination.title else if (settings == "root") "Settings" else settings!!,
                            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        if (settings != null) TextButton(onClick = { settings = if (settings == "root") null else "root" }) { Text("Back") }
                        else IconButton(onClick = { settings = "root" }) { Icon(Lucide.Settings, "Settings") }
                    }
                    if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { error = null }) { Text("Dismiss") }
                        }
                    }
                    if (settings != null) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (settings) {
                            "root" -> listOf("Appearance", "AI models", "Voice & files", "Backup & account", "About").forEach { name ->
                                OutlinedButton(onClick = { settings = name }, Modifier.fillMaxWidth()) { Text(name) }
                            }
                            "Appearance" -> {
                                Text("Theme", style = MaterialTheme.typography.titleLarge)
                                listOf("Device", "Light", "Pitch Black").forEach { value ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(theme == value, onClick = { attempt { store.setting("theme", value, true); theme = value } })
                                        Text(value)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(reducedMotion, onCheckedChange = { value -> attempt { store.setting("reducedMotion", value.toString(), true); reducedMotion = value } })
                                    Text("Reduce motion")
                                }
                            }
                            "AI models" -> {
                                Text("On this PC", style = MaterialTheme.typography.titleLarge)
                                Text("Download one text model, then tap Use model. Speech models are separate. Models stay in Alter's private data folder and work offline after installation.")
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
                                Text("For RTX 4050, install a compatible CUDA llama.cpp release and select its llama-server.exe. Keep its DLLs beside the executable. This build includes the CPU runtime for all PCs.", style = MaterialTheme.typography.bodySmall)
                                models.forEach { spec ->
                                    Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(spec.title, style = MaterialTheme.typography.titleMedium)
                                        Text(spec.description, style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (installed[spec] == true) {
                                                if (spec.kind == "text") Button(enabled = modelWork == null, onClick = {
                                                    modelWork = scope.launch {
                                                        modelStatus = "Verifying and loading ${spec.title}…"
                                                        try {
                                                            val custom = store.setting("cudaServer").takeIf(String::isNotBlank)?.let(Path::of)
                                                            native.start(spec, store.setting("modelPort", "8080").toInt(), custom,
                                                                if (custom == null) 0 else 99)
                                                            store.setting("textModel", spec.id, true)
                                                            modelStatus = "${spec.title} is ready for offline chat."
                                                        } catch (e: Exception) { error = e.message ?: "Model startup failed." }
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
                                            TextButton(onClick = { java.awt.Desktop.getDesktop().browse(java.net.URI(spec.page)) }) { Text("Source & licence") }
                                        }
                                    }
                                }
                                if (modelWork != null) TextButton(onClick = { modelWork?.cancel() }) { Text("Cancel download / startup") }
                                if (native.running) TextButton(onClick = { native.stop(); modelStatus = "Local model stopped." }) { Text("Release model") }
                            }
                            "Voice & files" -> Text("Tap Mic in Alter AI to record, then Stop mic to transcribe with a downloaded Whisper model. Review the transcript before sending. File attachment, retrieval and system hotkeys need later Windows adapters.")
                            "Backup & account" -> Text("Records are stored privately on this PC. Android ZIP v2 import/export and desktop Google authorization are pending. Do not copy an Android database into this app.")
                            "About" -> Text("Alter for Windows · first desktop increment\nAndroid reference: 0bda3d8583d1f53b6f305e1eeb22628c17a08e66\nLocal notes, memories, tasks and chat history. See the parity ledger for pending features.")
                        }
                    } else if (destination == Destination.Chat) {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (messages.isEmpty()) item { Text("What’s on your mind?", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f)) }
                            items(messages) { (sender, text) ->
                                Column(Modifier.fillMaxWidth().padding(start = if (sender == "user") 40.dp else 0.dp, end = if (sender == "assistant") 40.dp else 0.dp)) {
                                    Text(if (sender == "user") "You" else "Alter", style = MaterialTheme.typography.bodySmall)
                                    androidx.compose.foundation.text.selection.SelectionContainer { Text(text, Modifier.padding(vertical = 8.dp)) }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = generation == null, onClick = { attempt { store.clearMessages(); messages = store.messages() } }) { Text("New conversation") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { settings = "AI models" }) { Text(if (native.running) "Local · ready ⌄" else "Set up local model ⌄") }
                        }
                        Row(Modifier.fillMaxWidth().background(colors.surface.copy(alpha = .88f), RoundedCornerShape(12.dp))
                            .border(.5.dp, colors.outline, RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(prompt, { prompt = it.take(16000) }, Modifier.weight(1f).heightIn(max = 160.dp)
                                .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && it.isCtrlPressed) { send(); true } else false },
                                placeholder = { Text("Ask Alter · Ctrl+Enter to send") }, enabled = generation == null)
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
                    } else {
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FilledTonalButton(onClick = { draft = AlterCard(type = if (destination == Destination.Upcoming) "upcoming" else if (destination == Destination.Notes) "note" else "text", rawInput = "", processedContent = "") },
                                shape = RoundedCornerShape(20.dp)) { Icon(Lucide.Plus, "Add"); Spacer(Modifier.width(8.dp)); Text("Capture") }
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
private fun Navigation(current: Destination, settings: Boolean, select: (Destination) -> Unit, openSettings: () -> Unit) {
    val icons = listOf(Lucide.Home, Lucide.Sparkles, Lucide.Memories, Lucide.Calendar, Lucide.FileText)
    Column(Modifier.width(206.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
        .border(width = .5.dp, color = MaterialTheme.colorScheme.outline).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().height(74.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource("alter-logo.xml"), null, Modifier.size(30.dp), AlterYellow)
            Spacer(Modifier.width(10.dp)); Text("alter", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Workspace", Modifier.padding(start = 13.dp, top = 14.dp, bottom = 8.dp), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        Destination.entries.forEachIndexed { i, tab ->
            val selected = tab == current && !settings
            Row(Modifier.fillMaxWidth().height(48.dp).semantics { this.selected = selected }
                .background(if (selected) AlterYellow.copy(alpha = .13f) else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(role = Role.Tab) { select(tab) }.padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icons[i], null, Modifier.size(20.dp), if (selected) AlterYellow else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                Spacer(Modifier.width(14.dp))
                Text(tab.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(48.dp).background(if (settings) AlterYellow.copy(alpha = .13f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = openSettings).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Settings, null, Modifier.size(20.dp), AlterYellow)
            Spacer(Modifier.width(14.dp)); Text("Settings", fontSize = 14.sp)
        }
    }
}

@Composable
private fun EntryCard(card: AlterCard, onEdit: () -> Unit, onDelete: () -> Unit, onComplete: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp)).clickable(onClick = onEdit)) {
        Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(when(card.type) { "note" -> "Note"; "upcoming" -> "Task"; else -> "Text" }, fontSize = 11.sp, color = colors.onSurface.copy(alpha = .6f))
            Text(DateTimeFormatter.ofPattern("d MMM · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(card.timestamp)), fontSize = 10.sp)
        }
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
            .background(colors.background, RoundedCornerShape(14.dp)).padding(12.dp)) {
            if (card.type == "note") Text(card.rawInput, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            card.processedContent.lines().take(12).forEach { line ->
                val task = parseTodoLine(line)
                if (task != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(task.isCompleted, onCheckedChange = if (task.isCompleted) null else { _ -> onComplete(task.taskText) })
                    Text(task.taskText)
                } else Text(line, fontSize = 14.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                IconButton(onClick = onDelete) { Icon(Lucide.Trash, "Delete entry", Modifier.size(18.dp)) }
            }
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
    val renderer = remember { KoiAsciiRenderer() }
    DisposableEffect(Unit) { onDispose { renderer.close() } }
    LaunchedEffect(visible, animate, dimensions, density, opacity, dark) {
        if (!visible || dimensions.width == 0 || dimensions.height == 0) return@LaunchedEffect
        synchronized(pond) { pond.resize(dimensions.width / density, dimensions.height / density) }
        do {
            image = withContext(Dispatchers.Default) {
                val bitmap = Bitmap.createBitmap(dimensions.width, dimensions.height, Bitmap.Config.ARGB_8888)
                synchronized(pond) {
                    if (animate) pond.update(.05f)
                    RiverCanvas(bitmap).use { renderer.draw(it, pond, dimensions.width.toFloat(), dimensions.height.toFloat(), density, opacity, dark) }
                }
                bitmap.image.toComposeImageBitmap()
            }
            if (!animate) break
            delay(50)
        } while (isActive)
    }
    Box(Modifier.fillMaxSize().onSizeChanged { dimensions = it }
        .then(if (visible && animate) Modifier.pointerInput(Unit) { detectTapGestures { synchronized(pond) { pond.feed(it.x / density, it.y / density) } } } else Modifier)) {
        if (visible) image?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}
