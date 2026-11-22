package alter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.awt.Desktop
import java.net.URI

@Composable
internal fun RepositoryEditDialog(repo: String, store: Store, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<RepoFile?>(null) }
    var replacement by remember { mutableStateOf("") }
    var review by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf("") }
    var published by remember { mutableStateOf("") }
    var previous by remember { mutableStateOf(store.setting("repoEditLink")) }
    fun request(block: suspend () -> Unit) {
        error = ""
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "GitHub could not complete the request." }
            finally { job = null }
        }
    }
    fun open(url: String) {
        try { Desktop.getDesktop().browse(URI(url)) } catch (e: Exception) { error = e.message ?: "Could not open the browser." }
    }
    AlertDialog(onDismissRequest = { if (job == null) close() },
        title = { Text(if (published.isNotEmpty()) "Draft pull request" else if (review) "Review file change" else "Edit repository file") },
        text = { Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val loaded = file
            if (loaded == null) {
                Text(repo)
                OutlinedTextField(path, { path = it.take(1024) }, enabled = job == null, singleLine = true, label = { Text("File path") })
                OutlinedTextField(token, { token = it.trim() }, enabled = job == null, singleLine = true,
                    label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation())
                Text("Use a fine-grained token for this repository with Contents and Pull requests write access. The key lasts until you close this editor.")
            } else if (published.isEmpty()) {
                Text("${loaded.repository} · ${loaded.base} · ${loaded.path}")
                if (review) {
                    Text("Original", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(loaded.original, {}, readOnly = true, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                    Text("Proposed", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedTextField(replacement, { if (it.length <= 98304) replacement = it }, readOnly = review,
                    modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
                if (review) Text("Create a branch and open a draft against ${loaded.base}.")
            } else Text(published)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (published.isEmpty() && (file == null || submitted) &&
                previous.startsWith("https://github.com/$repo/") && job == null)
                TextButton(onClick = { open(previous) }) { Text("Open last review on GitHub") }
            if (job != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, dismissButton = { Row {
            if (review && !submitted) TextButton(onClick = { review = false }) { Text("Edit") }
            TextButton(enabled = job == null, onClick = close) { Text("Close") }
        } }, confirmButton = {
            val loaded = file
            when {
                published.isNotEmpty() -> Button(onClick = { open(published) }) { Text("View pull request") }
                loaded == null -> Button(enabled = path.isNotBlank() && token.isNotBlank() && job == null, onClick = { request {
                    val source = runInterruptible(Dispatchers.IO) { GitHubEdits(RepoGrant(repo, token)).load(path.trim()) }
                    file = source; replacement = source.original
                } }) { Text("Load file") }
                !review -> Button(enabled = replacement != loaded.original, onClick = { review = true }) { Text("Review change") }
                else -> Button(enabled = job == null, onClick = { request {
                    submitted = true
                    val url = try {
                        runInterruptible(Dispatchers.IO) {
                            GitHubEdits(RepoGrant(repo, token)).publish(loaded, replacement) { store.setting("repoEditLink", it, true) }
                        }
                    } finally { previous = store.setting("repoEditLink") }
                    store.setting("repoEditLink", url, true); published = url; token = ""
                } }) { Text(if (submitted && error.isNotBlank()) "Retry draft PR" else "Create draft PR") }
            }
        })
}
