package alter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.*

@Composable
internal fun PluginPanel(store: Store, grants: MutableMap<ServicePlugin, PluginGrant>, configureRepo: () -> Unit) {
    var enabled by remember { mutableStateOf(store.setting("figmaEnabled").toBoolean()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf<Job?>(null) }
    Text("Plugins", style = MaterialTheme.typography.headlineSmall)
    var modelTools by remember { mutableStateOf(store.setting("modelTools").toBoolean()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(modelTools, { modelTools = it; store.setting("modelTools", it.toString(), true) })
        Text("Let compatible chat models suggest enabled commands")
    }
    Text("Enabling tools sends command descriptions, including the selected repository name, to your selected model. Every suggestion opens a preview.")
    Text("GitHub", style = MaterialTheme.typography.titleMedium)
    Text("Public repository workflow runs and open issues. Use /repo checks or /repo issues in chat.")
    TextButton(onClick = configureRepo) { Text("Choose repository") }
    Text("Figma Desktop", style = MaterialTheme.typography.titleMedium)
    Text("Open a design file in Figma Desktop, enable its desktop MCP server, and select a layer. Alter uses the Figma session on this PC.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(enabled, onCheckedChange = {
            test?.cancel()
            store.setting("figmaEnabled", it.toString(), true)
            enabled = it
            status = ""
        })
        Text("Allow selected layer metadata in this project")
    }
    TextButton(enabled = enabled && test == null, onClick = {
        test = scope.launch {
            try { status = runInterruptible(Dispatchers.IO) { FigmaMcp().readSelection(true) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Could not reach the Figma desktop server on port 3845." }
            finally { test = null }
        }
    }) { Text("Test Figma connection") }
    for (plugin in ServicePlugin.entries) {
        val grant = grants[plugin]
        Text(plugin.label, style = MaterialTheme.typography.titleMedium)
        Text(plugin.command)
        if (grant != null) {
            LaunchedEffect(grant) {
                delay((grant.expiry - System.currentTimeMillis()).coerceAtLeast(0)); grants.remove(plugin)
            }
            TextButton(onClick = { grants.remove(plugin); status = "Disconnected from this project." }) { Text("Disconnect") }
        } else TextButton(enabled = test == null && plugin.configured, onClick = {
            test = scope.launch {
                try { grants[plugin] = DesktopAuth.connect(plugin); status = "${plugin.label} connected for this project session." }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "Connection failed." }
                finally { test = null }
            }
        }) { Text(if (plugin.configured) "Connect ${plugin.label}" else "Provider setup required") }
    }
    if (test != null) TextButton(onClick = { test?.cancel() }) { Text("Cancel connection") }
    if (status.isNotBlank()) Text(status)
    if (enabled) Text("Type or dictate “read my Figma selection” in chat. Review the action before it runs.")
}
