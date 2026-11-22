package alter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun PluginPanel(store: Store, grants: MutableMap<ServicePlugin, PluginGrant>, repoGrant: RepoGrant?,
    setRepoGrant: (RepoGrant?) -> Unit, previewConnection: Boolean, configureRepo: () -> Unit) {
    val scope = rememberCoroutineScope()
    var figma by remember { mutableStateOf(store.setting("figmaEnabled").toBoolean()) }
    var selected by remember { mutableStateOf<String?>(if (previewConnection) "Figma Desktop" else null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    val repo = store.setting("githubRepo")
    val github = validRepo(repo)
    val names = listOf("GitHub", "Figma Desktop") + ServicePlugin.entries.map { it.label }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(13.dp))) {
        names.forEachIndexed { index, name ->
            val plugin = ServicePlugin.entries.firstOrNull { it.label == name }
            val grant = grants[plugin]
            if (grant != null) LaunchedEffect(grant) {
                delay((grant.expiry - System.currentTimeMillis()).coerceAtLeast(0))
                grants.remove(plugin)
            }
            val connected = when (name) { "GitHub" -> github; "Figma Desktop" -> figma; else -> grant != null }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { selected = name; status = "" }) { Text(if (connected) "Manage" else "Connect") }
            }
            if (index < names.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
    selected?.let { name ->
        val plugin = ServicePlugin.entries.firstOrNull { it.label == name }
        val grant = grants[plugin]
        var token by remember(name) { mutableStateOf("") }
        var privateAccess by remember(name) { mutableStateOf(false) }
        fun close() { job?.cancel(); selected = null }
        AlertDialog(onDismissRequest = ::close, title = { Text(name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (name) {
                    "GitHub" -> {
                        Text(if (github) repo else "Choose a repository to read workflow runs and issues.")
                        if (github) {
                            Text("In chat, use /repo checks or /repo issues.")
                            if (repoGrant?.repository == repo) TextButton(onClick = {
                                setRepoGrant(null); status = "Private access disconnected."
                            }) { Text("Disconnect private access") }
                            else if (!privateAccess) TextButton(onClick = { privateAccess = true }) { Text("Add private access") }
                            if (privateAccess) {
                                Text("Use a token with read access to this repository’s Actions and Issues. It stays in this project session.")
                                OutlinedTextField(token, { token = it.trim().take(1024) }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(), label = { Text("GitHub token") })
                                TextButton(enabled = token.isNotBlank(), onClick = {
                                    setRepoGrant(RepoGrant(repo, token)); token = ""; privateAccess = false
                                }) { Text("Save token") }
                            }
                        }
                    }
                    "Figma Desktop" -> Text(if (figma) "Select a layer in Figma and ask Alter to read your selection."
                        else "Open a design file in Figma Desktop and enable its desktop MCP server. Connect to share selected layer metadata with this project.")
                    else -> {
                        Text(when (plugin) {
                            ServicePlugin.Calendar -> "Read upcoming events from your primary Google Calendar."
                            ServicePlugin.Drive -> "Read the names and links of recently modified files."
                            else -> "Play a requested track on your active Spotify Premium device."
                        })
                        if (plugin?.configured == false) Text("Provider registration is required before this build can connect.")
                        else if (grant != null) Text("Connected for this project session.")
                    }
                }
                if (status.isNotBlank()) SelectionContainer { Text(status) }
                if (job != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, dismissButton = { TextButton(onClick = ::close) { Text(if (job == null) "Close" else "Cancel") } },
            confirmButton = {
                when (name) {
                    "GitHub" -> TextButton(onClick = { close(); configureRepo() }) { Text(if (github) "Change repository" else "Choose repository") }
                    "Figma Desktop" -> TextButton(enabled = job == null, onClick = {
                        if (figma) { store.setting("figmaEnabled", "false", true); figma = false; close() }
                        else job = scope.launch {
                            try {
                                runInterruptible(Dispatchers.IO) { FigmaMcp().readSelection(true) }
                                store.setting("figmaEnabled", "true", true); figma = true; selected = null
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { status = e.message ?: "Could not reach Figma Desktop on this PC." }
                            finally { job = null }
                        }
                    }) { Text(if (figma) "Disconnect" else "Connect") }
                    else -> TextButton(enabled = plugin != null && plugin.configured && job == null, onClick = {
                        if (plugin != null && grant != null) { grants.remove(plugin); close() }
                        else if (plugin != null) job = scope.launch {
                            try { grants[plugin] = DesktopAuth.connect(plugin); selected = null }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { status = e.message ?: "Connection failed." }
                            finally { job = null }
                        }
                    }) { Text(if (grant == null) "Continue in browser" else "Disconnect") }
                }
            })
    }
}
