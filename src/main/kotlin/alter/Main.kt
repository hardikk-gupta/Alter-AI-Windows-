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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

fun main(args: Array<String>) {
    val store = Store(defaultDataDirectory())
    val preview = args.firstOrNull { it.startsWith("--preview=") }?.substringAfter('=')
    System.getenv("ALTER_DATA_DIR")?.let {
        val cache = Path.of(it, "skiko")
        Files.createDirectories(cache)
        System.setProperty("skiko.data.path", cache.toString())
    }
    try {
        application(exitProcessOnExit = false) {
        var visible by remember { mutableStateOf(true) }
        var keepInTray by remember { mutableStateOf(store.setting("keepInTray").toBoolean()) }
        val trayAvailable = java.awt.SystemTray.isSupported()
        if (trayAvailable && keepInTray) Tray(painterResource("alter-window.png"), tooltip = "Alter",
            onAction = { visible = true }, menu = {
                Item("Open Alter", onClick = { visible = true })
                Item("Quit Alter", onClick = ::exitApplication)
            })
        LaunchedEffect(store) {
            while (isActive) {
                for (schedule in store.schedules()) {
                    val run = store.claimSchedule(schedule, Instant.now()) ?: continue
                    store.executeRun(run, DesktopAction("repo_brief", schedule.repo))
                }
                delay(30_000)
            }
        }
        Window(visible = visible, onCloseRequest = {
            if (keepInTray && trayAvailable) visible = false else exitApplication()
        }, title = "Alter",
            icon = painterResource("alter-window.png"),
            state = rememberWindowState(width = 1280.dp, height = 800.dp, position =
                if ("--screenshot" in args) WindowPosition(0.dp, 0.dp) else WindowPosition.PlatformDefault)) {
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
            var project by remember {
                mutableStateOf(store.projects().find { it.id.toString() == store.setting("activeProject") } ?: personalWorkspace)
            }
            var account by remember { mutableStateOf<AccountSession?>(null) }
            LaunchedEffect(account) { account?.let { delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); account = null } }
            key(project.id) {
                val workspace = remember { if (project.id == 0L) store else store.openProject(project) }
                DisposableEffect(workspace) { onDispose { if (workspace !== store) workspace.close() } }
                AlterApp(workspace, visible && isVisible, preview, store, project, account, { account = it },
                    trayAvailable, keepInTray, { keepInTray = it; store.setting("keepInTray", it.toString(), true) }) { next ->
                    store.setting("activeProject", next.id.toString(), true)
                    project = next
                }
            }
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
                exitApplication()
            }
        }
        }
    } finally { store.close() }
}

@Composable
private fun AlterApp(store: Store, active: Boolean, preview: String?, catalog: Store, project: Project, account: AccountSession?, changeAccount: (AccountSession?) -> Unit, trayAvailable: Boolean, keepInTray: Boolean, setKeepInTray: (Boolean) -> Unit, selectProject: (Project) -> Unit) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(Destination.entries.firstOrNull { it.name.equals(preview, true) } ?: if (preview == "capture") Destination.Home else Destination.Chat) }
    var settings by remember { mutableStateOf<String?>(when (preview) {
        "models" -> "AI models"; "appearance" -> "Appearance"; "about" -> "About"
        "voice" -> "Voice & files"; "backup" -> "Backup & account"; else -> null
    }) }
    var theme by remember { mutableStateOf(store.setting("theme", "Pitch Black")) }
    var reducedMotion by remember { mutableStateOf(store.setting("reducedMotion", "false").toBoolean()) }
    var cards by remember { mutableStateOf(store.cards()) }
    var threads by remember { mutableStateOf(store.threads()) }
    var threadId by remember { mutableStateOf(threads.firstOrNull { !it.archived }?.id) }
    var messages by remember { mutableStateOf(threadId?.let(store::messages).orEmpty()) }
    var historyQuery by remember { mutableStateOf("") }
    val filteredThreads = remember(threads, messages, historyQuery) {
        if (historyQuery.isBlank()) threads else store.threads(historyQuery)
    }
    val drafts = remember { mutableMapOf<Long?, String>() }
    var deleteChat by remember { mutableStateOf<ChatThread?>(null) }
    var search by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<AlterCard?>(null) }
    var delete by remember { mutableStateOf<AlterCard?>(null) }
    var captureOpen by remember(destination, settings) { mutableStateOf(preview == "capture" && destination == Destination.Home && settings == null) }
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
    var confirmDownload by remember { mutableStateOf<ModelSpec?>(null) }
    var activeModel by remember { mutableStateOf<String?>(null) }
    var runtimeModel by remember { mutableStateOf<RuntimeModel?>(null) }
    var detectedModels by remember { mutableStateOf(emptyList<RuntimeModel>()) }
    var cloud by remember { mutableStateOf<OpenAiSession?>(null) }
    var importedModel by remember { mutableStateOf<String?>(null) }
    val modelLabel = cloud?.let { "OpenAI · ${it.model}" } ?: runtimeModel?.label ?: importedModel ?: models.find { it.id == activeModel }?.title?.substringBefore(" ·")
    var voiceModel by remember { mutableStateOf(store.setting("voiceModel")) }
    var customRuntime by remember { mutableStateOf(store.setting("cudaServer")) }
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
    val colors = if (dark) DarkColorScheme else LightColorScheme
    val typography = Typography().let { t -> t.copy(
        headlineSmall = t.headlineSmall.copy(fontFamily = satoshi, fontSize = 26.sp, lineHeight = 34.sp),
        titleMedium = t.titleMedium.copy(fontFamily = satoshi),
        titleLarge = t.titleLarge.copy(fontFamily = satoshi, fontSize = 20.sp, lineHeight = 28.sp),
        bodyLarge = t.bodyLarge.copy(fontFamily = satoshi, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = satoshi, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontFamily = satoshi), bodySmall = t.bodySmall.copy(fontFamily = satoshi)) }
    fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { error = e.message ?: "Could not save. Your editor is still open." } }
    fun openThread(id: Long?) {
        if (generation != null || speaking || modelWork != null) return
        drafts[threadId] = prompt
        threadId = id
        messages = id?.let(store::messages).orEmpty()
        prompt = drafts[id].orEmpty()
        destination = Destination.Chat
        settings = null
    }
    fun activateModel(spec: ModelSpec?, file: Path? = null) {
        if (modelWork != null || generation != null || speaking) return
        cloud = null
        runtimeModel = null
        val title = spec?.title?.substringBefore(" ·") ?: requireNotNull(file).fileName.toString()
        modelWork = scope.launch {
            modelStatus = "Loading $title…"
            modelStartupFailed = false
            try {
                val custom = customRuntime.takeIf(String::isNotBlank)?.let(Path::of)
                val port = store.setting("modelPort", "8080").toInt()
                val layers = if (custom == null) 0 else 99
                if (spec != null) {
                    native.start(spec, port, custom, layers)
                    store.setting("textModel", spec.id, true)
                } else native.startFile(requireNotNull(file), port, custom, layers)
                importedModel = if (file != null) title else null
                cloud = null
                runtimeModel = null
                activeModel = spec?.id
                error = null
                modelStatus = "$title is ready."
            } catch (e: CancellationException) { modelStatus = "Startup cancelled."; throw e }
            catch (e: Exception) {
                modelStartupFailed = true
                modelStatus = "Could not start $title."
                error = e.message ?: modelStatus
            } finally {
                if (!native.running) { activeModel = null; importedModel = null }
                modelWork = null
            }
        }
    }
    fun download(spec: ModelSpec) {
        if (modelWork != null || speaking) return
        modelWork = scope.launch {
            modelStatus = "Downloading ${spec.title.substringBefore(" ·")}…"
            try {
                downloadModel(spec, { n, total -> modelStatus = "${spec.title.substringBefore(" ·")} · ${n * 100 / total}%" })
                installedVersion++
                modelStatus = "Download complete. Select the model to start it."
            } catch (e: CancellationException) { modelStatus = "Download cancelled."; throw e }
            catch (e: Exception) { error = e.message ?: "Model download failed."; modelStatus = "Download failed." }
            finally { modelWork = null }
        }
    }
    var pendingAction by remember { mutableStateOf<DesktopAction?>(null) }
    var commandDraft by remember { mutableStateOf<String?>(null) }
    fun reviewAction(action: DesktopAction, source: String?) {
        if (action.name != "open_spotify" && !validRepo(action.argument)) {
            settings = "Actions & schedules"
            error = "Save a public GitHub repository for this project, then try the command again."
            return
        }
        commandDraft = source
        pendingAction = action
    }
    fun send() {
        if (prompt.isBlank() || generation != null || speaking || modelWork != null) return
        DesktopAction.parse(prompt, store.setting("githubRepo"))?.let { reviewAction(it, prompt.trim()); return }
        val external = runtimeModel
        val selectedCloud = cloud
        if (selectedCloud == null && external == null && !native.running) { activeModel = null; settings = "AI models"; error = "Choose a text model and tap Use model before chatting."; return }
        val text = prompt.trim()
        attempt {
            val id = store.addMessage(threadId, "user", text)
            drafts.remove(threadId)
            threadId = id
            messages = store.messages(id)
            val history = messages
            threads = store.threads()
            prompt = ""
            generation = scope.launch {
                try {
                    val reply = selectedCloud?.reply(history, project.instructions) ?: LocalChat(external?.runtime?.port ?: store.setting("modelPort", "8080").toInt()).reply(history, external?.name, project.instructions)
                    store.addMessage(id, "assistant", reply); messages = store.messages(id); threads = store.threads()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Model unavailable. Check model settings." }
                finally { generation = null }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = typography) {
        pendingAction?.let { action ->
            AlertDialog(onDismissRequest = { pendingAction = null }, title = { Text(action.title) },
                text = { Text("Run in ${project.name}? Alter will record the result in Activity.") },
                dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancel") } },
                confirmButton = { Button(onClick = {
                    pendingAction = null
                    attempt {
                        val origin = commandDraft
                        val id = if (origin != null) store.addMessage(threadId, "user", origin) else null
                        if (id != null) {
                            drafts.remove(threadId); threadId = id; prompt = ""
                            messages = store.messages(id); threads = store.threads()
                        }
                        generation = scope.launch {
                            try {
                                val run = catalog.beginRun(project.id, action)
                                val result = catalog.executeRun(run, action)
                                if (id != null) {
                                    store.addMessage(id, "assistant", result)
                                    messages = store.messages(id); threads = store.threads()
                                }
                            } finally { generation = null }
                        }
                    }
                }) { Text("Run") } })
        }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            val content = cards.filter { card -> when (destination) {
                Destination.Memories -> card.type == "text"
                Destination.Notes -> card.type == "note"
                Destination.Upcoming -> card.type == "upcoming"
                else -> true
            } }
            val showRiver = settings == null && (destination == Destination.Chat || content.isEmpty())
            BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.N && settings == null) {
                    if (destination == Destination.Chat) openThread(null)
                    else draft = AlterCard(type = if (destination == Destination.Upcoming) "upcoming" else "note", rawInput = "", processedContent = "")
                    true
                } else false
            }) {
                val compact = maxWidth < 850.dp
                val wide = maxWidth >= 1160.dp
                var sidebar by remember(compact) { mutableStateOf(!compact) }
                var details by remember(wide) { mutableStateOf(wide) }
                val canSwitch = generation == null && modelWork == null && !speaking
                val selectedThread = threads.firstOrNull { it.id == threadId }
                val sidebarContent = @Composable {
                    WorkspaceSidebar(settings, threadId.takeIf { settings == null && destination == Destination.Chat },
                        filteredThreads, historyQuery, { historyQuery = it },
                        { openThread(null); if (compact) sidebar = false },
                        { openThread(it); if (compact) sidebar = false }, canSwitch,
                        { settings = it; if (compact) sidebar = false },
                        { thread -> attempt { store.updateThread(thread); threads = store.threads() } },
                        remember(cards) { mapOf(Destination.Memories to cards.count { it.type == "text" },
                            Destination.Upcoming to cards.count { it.type == "upcoming" }, Destination.Notes to cards.count { it.type == "note" }) },
                        { destination = it; settings = null; search = ""; if (compact) sidebar = false }, installed.count { it.value })
                }
                val detailsContent = @Composable {
                    WorkspaceDetails(modelLabel,
                        models.find { it.id == voiceModel }?.title?.substringBefore(" ·"),
                        cloud?.let { "OpenAI API" } ?: runtimeModel?.runtime?.title ?: if (customRuntime.isBlank()) "Included CPU runtime" else "NVIDIA runtime selected",
                        { settings = "AI models"; if (!wide) details = false })
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(43.dp).background(colors.surface).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            WorkspaceButton(AlterIcons.SidebarLeft, if (sidebar) "Collapse navigation" else "Expand navigation", sidebar) { sidebar = !sidebar }
                            if (compact) DropdownMenu(sidebar, { sidebar = false }) {
                                Box(Modifier.width(245.dp).height(365.dp)) { sidebarContent() }
                            }
                        }
                        ProjectPicker(project, catalog, canSwitch && prompt.isBlank(), selectProject)
                        Text(settings ?: if (destination == Destination.Chat) selectedThread?.title ?: "New chat"
                            else destination.title, Modifier.weight(1f).padding(horizontal = 14.dp), fontSize = 14.sp,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium)
                        if (settings == null && destination == Destination.Chat && selectedThread != null && canSwitch)
                            WorkspaceButton(AlterIcons.Trash, "Delete chat") { deleteChat = selectedThread }
                        Box {
                            WorkspaceButton(AlterIcons.Info, "Workspace details", details) { details = !details }
                            if (!wide) DropdownMenu(details, { details = false }) {
                                Box(Modifier.width(248.dp)) { detailsContent() }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.outline)
                    Row(Modifier.fillMaxSize()) {
                if (compact || sidebar) Row(Modifier.fillMaxHeight().padding(5.dp).clip(RoundedCornerShape(16.dp))
                    .background(colors.surface).border(1.dp, colors.outline, RoundedCornerShape(16.dp)).padding(2.dp)) {
                ActivityRail(destination, settings != null, { destination = it; settings = null; search = "" },
                    { settings = "Appearance"; if (!compact) sidebar = true },
                    if (compact) null else ({ sidebar = false }))
                if (sidebar && !compact) {
                    Box(Modifier.width(174.dp)) { sidebarContent() }
                }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                River(pond, showRiver, active && !reducedMotion && draft == null,
                    if (destination == Destination.Chat) if (messages.isEmpty()) .40f else .045f else .50f, dark)
                Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 26.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (settings != null || destination != Destination.Chat) Spacer(Modifier.height(7.dp))
                    if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            if (modelStartupFailed && isRegularFile(modelRuntimeLog())) TextButton(onClick = {
                                attempt { Desktop.getDesktop().open(modelRuntimeLog().toFile()) }
                            }) { Text("Open runtime log") }
                            TextButton(onClick = { error = null; modelStartupFailed = false }) { Text("Dismiss") }
                        }
                    }
                    if (settings != null) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (settings) {
                            "Appearance" -> {
                                Text("Appearance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                                Text("AI models", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Download a model once, then chat or dictate offline. Nothing starts until you choose it.",
                                    color = colors.onSurface.copy(alpha = .66f))
                                Text("This PC: about ${hardware.ramBytes / (1024 * 1024 * 1024)} GB RAM · ${hardware.processors} CPU threads. Suggested text model: ${models.first { it.id == hardware.suggestedTextId }.title}.",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = .65f))
                                if (modelStatus.isNotBlank()) Text(modelStatus, color = AlterYellow)

                                Text("Bundled model runtime: ${if (customRuntime.isBlank()) "Included CPU runtime" else "Selected NVIDIA CUDA runtime"}")
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
                                var keyInput by remember { mutableStateOf("") }
                                var cloudModel by remember { mutableStateOf("gpt-4.1-mini") }
                                Text("OpenAI API", style = MaterialTheme.typography.titleMedium)
                                Text("When selected, sending a message sends Alter’s instructions, project instructions and up to 20 messages from the open conversation to OpenAI. Your key stays in memory for this session.", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(keyInput, { keyInput = it.trim() }, singleLine = true, label = { Text("OpenAI API key") },
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                                OutlinedTextField(cloudModel, { cloudModel = it.trim() }, singleLine = true, label = { Text("Chat Completions model ID") })
                                TextButton(enabled = canSwitch && keyInput.isNotEmpty() && cloudModel.isNotBlank(), onClick = {
                                    importedModel = null
                                    native.stop()
                                    activeModel = null
                                    runtimeModel = null
                                    cloud = OpenAiSession(keyInput, cloudModel)
                                    keyInput = ""
                                    modelStatus = "OpenAI selected for chat."
                                }) { Text("Use OpenAI for chat") }
                                if (cloud != null) TextButton(enabled = canSwitch, onClick = {
                                    cloud = null
                                    keyInput = ""
                                    modelStatus = "OpenAI disconnected."
                                }) { Text("Disconnect OpenAI") }
                                TextButton(enabled = canSwitch, onClick = { attempt {
                                    val chooser = javax.swing.JFileChooser()
                                    chooser.dialogTitle = "Use a local GGUF text model"
                                    chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("GGUF models", "gguf")
                                    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION)
                                        activateModel(null, chooser.selectedFile.toPath())
                                } }) { Text("Use a GGUF file on this PC…") }
                                Text("Other runtimes on this PC", style = MaterialTheme.typography.titleMedium)
                                Text("Start Ollama or the LM Studio local server, then check for models.", style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = canSwitch, onClick = {
                                    modelWork = scope.launch {
                                        try {
                                            val found = mutableListOf<RuntimeModel>()
                                            val statuses = mutableListOf<String>()
                                            for (runtime in DesktopRuntime.entries) {
                                                try {
                                                    val names = LocalChat(runtime.port).installedModels(runtime)
                                                    found += names.map { RuntimeModel(runtime, it) }
                                                    statuses += "${runtime.title}: ${names.size} listed"
                                                } catch (e: CancellationException) { throw e }
                                                catch (e: Exception) { statuses += "${runtime.title}: unavailable on port ${runtime.port}" }
                                            }
                                            detectedModels = found
                                            modelStatus = statuses.joinToString(" · ")
                                        } finally { modelWork = null }
                                    }
                                }) { Text("Find installed models") }
                                detectedModels.forEach { model ->
                                    TextButton(enabled = canSwitch && runtimeModel != model, onClick = {
                                        importedModel = null
                                    native.stop()
                                        activeModel = null
                                        cloud = null
                                        runtimeModel = model
                                        modelStatus = "Selected ${model.label}."
                                    }) { Text(model.label) }
                                }
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
                                                if (spec.kind == "text") Button(enabled = canSwitch && activeModel != spec.id,
                                                    onClick = { activateModel(spec) }) { Text(if (activeModel == spec.id) "In use" else "Use model") }
                                                else Button(enabled = canSwitch, onClick = { attempt {
                                                    require(verified(spec)) { "Speech model checksum did not match. Download again." }
                                                    store.setting("voiceModel", spec.id, true); voiceModel = spec.id
                                                    modelStatus = "${spec.title} selected for dictation."
                                                } }) { Text(if (store.setting("voiceModel") == spec.id) "Selected" else "Use for voice") }
                                            } else Button(enabled = canSwitch, onClick = {
                                                download(spec)
                                            }) { Text("Download") }
                                            TextButton(onClick = { attempt { Desktop.getDesktop().browse(java.net.URI(spec.page)) } }) {
                                                Text("Source & licence", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                if (modelWork != null) TextButton(onClick = { modelWork?.cancel() }) { Text("Cancel download / startup") }
                                if (activeModel != null || importedModel != null) TextButton(enabled = canSwitch, onClick = { native.stop(); activeModel = null; importedModel = null; modelStatus = "Local model stopped." }) { Text("Release model") }
                            }
                            "Actions & schedules" -> {
                                if (trayAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(keepInTray, onCheckedChange = setKeepInTray)
                                    Text("Keep Alter running in the tray when closed")
                                }
                                ActivityPanel(catalog, store, project, { reviewAction(it, null) }, canSwitch)
                            }
                            "Voice & files" -> Text("Tap Mic in Alter AI to record, then Stop mic to transcribe with a downloaded Whisper model. Review the transcript before sending. File attachment, retrieval and system hotkeys need later Windows adapters.")
                            "Backup & account" -> AccountPanel(account, changeAccount)
                            "About" -> {
                                Text("About Alter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Version details and the path to a newer Windows build.", color = colors.onSurface.copy(alpha = .62f))
                                Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp))
                                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp)).padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("Alter for Windows · 0.2.0", fontWeight = FontWeight.Bold)
                                    Text("Install a newer MSI to upgrade. Your notes and downloaded models stay under your Windows user profile.")
                                    TextButton(onClick = { attempt { Desktop.getDesktop().browse(java.net.URI(
                                        "https://github.com/hardikk-gupta/Alter-AI-Windows-/actions/workflows/windows.yml")) } }) { Text("View latest build ↗") }
                                    HorizontalDivider(color = colors.outline)
                                    Text("Android dashboard reference · 5b05604",
                                        color = colors.onSurface.copy(alpha = .5f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        }
                    } else if (destination == Destination.Chat) {
                        key(threadId) {
                            ChatPane(messages, prompt, { prompt = it.take(16000) },
                                modelLabel, installed,
                                modelWork != null, speaking, generation != null,
                                if (speaking || modelWork != null) modelStatus else "",
                                { spec -> if (installed[spec] == true) activateModel(spec) else confirmDownload = spec }, { settings = "AI models" }, { type ->
                                    draft = AlterCard(type = type, rawInput = if (type == "note") "" else prompt, processedContent = prompt)
                                }, {
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
                            }, ::send, { generation?.cancel() })
                        }
                    } else if (destination == Destination.Home) {
                        HomeContent(cards, { destination = Destination.Chat }, { destination = Destination.Upcoming },
                            { draft = it }, { delete = it }, { card, text -> attempt {
                                store.save(completeCardTask(card, text)); cards = store.cards()
                            } }, Modifier.weight(1f))
                    } else {
                        Text(destination.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)), singleLine = true,
                            placeholder = { Text("Search ${destination.title.lowercase()}") }, leadingIcon = { Icon(AlterIcons.Search, null) })
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
                                listOf(Triple("Memory", "text", AlterIcons.Memories),
                                    Triple("Note", "note", AlterIcons.FileText),
                                    Triple("Task", "upcoming", AlterIcons.Calendar)).forEach { (label, type, icon) ->
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
                            Icon(AlterIcons.Plus, if (captureOpen) "Close capture actions" else "Add capture",
                                Modifier.size(24.dp).graphicsLayer { rotationZ = rotation })
                        }
                    }
                }
                }
                if (wide && details) Box(Modifier.width(240.dp).padding(start = 4.dp, end = 12.dp, top = 9.dp)) { detailsContent() }
                }
                }
            }
            confirmDownload?.let { spec -> AlertDialog(onDismissRequest = { confirmDownload = null },
                title = { Text("Download ${spec.title.substringBefore(" ·")}?") },
                text = { Text("${spec.description}. After downloading, select the model in the composer to run it on this PC.") },
                confirmButton = { TextButton(onClick = { confirmDownload = null; settings = "AI models"; download(spec) }) { Text("Download") } },
                dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("Cancel") } }) }
            deleteChat?.let { chat -> AlertDialog(onDismissRequest = { deleteChat = null },
                title = { Text("Delete this chat?") }, text = { Text(chat.title) },
                confirmButton = { TextButton(onClick = { attempt {
                    store.deleteThread(chat.id); drafts.remove(chat.id); threads = store.threads()
                    if (threadId == chat.id) { threadId = null; messages = emptyList(); prompt = drafts[null].orEmpty() }
                    deleteChat = null
                } }) { Text("Delete chat") } }, dismissButton = { TextButton(onClick = { deleteChat = null }) { Text("Cancel") } }) }
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
private fun HomeContent(cards: List<AlterCard>, onChat: () -> Unit, onUpcoming: () -> Unit,
    onEdit: (AlterCard) -> Unit, onDelete: (AlterCard) -> Unit,
    onComplete: (AlterCard, String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tasks = cards.filter { it.type == "upcoming" }.take(2)
    val recent = cards.filter { it.type != "upcoming" }.take(2)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        WorkspaceRow("Open Alter AI", AlterIcons.Chat, onClick = onChat)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

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
            IconButton(onClick = onDelete) { Icon(AlterIcons.Trash, "Delete entry", Modifier.size(18.dp)) }
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
    DisposableEffect(Unit) { onDispose { synchronized(pond) { renderer.close() } } }
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
