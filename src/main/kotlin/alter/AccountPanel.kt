package alter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
internal fun AccountPanel(account: AccountSession?, change: (AccountSession?) -> Unit) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Account", style = MaterialTheme.typography.headlineSmall)
        Text(account?.label ?: "Using Alter as a local guest")
        for ((provider, title) in listOf("google.com" to "Google", "github.com" to "GitHub")) {
            val configured = DesktopAuth.configured(provider)
            Button(enabled = configured && job == null, onClick = {
                job = scope.launch {
                    status = "Opening $title sign-in…"
                    try {
                        change(DesktopAuth.signIn(provider, account) { message -> withContext(Dispatchers.Main) { status = message } })
                        status = if (account == null) "Signed in." else "Provider linked."
                    } catch (e: CancellationException) { status = "Sign-in cancelled."; throw e }
                    catch (e: Exception) { status = e.message ?: "Sign-in failed." }
                    finally { job = null }
                }
            }) { Text(if (!configured) "$title · setup required" else if (account == null) "Continue with $title" else "Link $title") }
        }
        if (status.isNotBlank()) Text(status)
        if (job != null) TextButton(onClick = { job?.cancel() }) { Text("Cancel sign-in") }
        if (account != null) TextButton(enabled = job == null, onClick = { change(null); status = "" }) { Text("Sign out") }
        Text("Workspace records stay on this PC. This sign-in lasts for the current session.")
    }
}
