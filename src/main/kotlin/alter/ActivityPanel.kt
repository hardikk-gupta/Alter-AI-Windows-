package alter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.*

@Composable
internal fun ActivityPanel(catalog: Store, workspace: Store, project: Project, run: (RunEntry) -> Unit, enabled: Boolean, openChat: (Long) -> Unit, skillsChanged: () -> Unit) {
    var repo by remember { mutableStateOf(workspace.setting("githubRepo")) }
    var schedule by remember { mutableStateOf(catalog.schedules().find { it.project == project.id }) }
    var time by remember { mutableStateOf(schedule?.time?.toString() ?: "09:00") }
    var status by remember { mutableStateOf("") }
    val threadIds = remember { workspace.threads().map { it.id }.toSet() }
    var activity by remember { mutableStateOf(catalog.activity(project.id)) }
    LaunchedEffect(project.id) { while (true) {
        activity = catalog.activity(project.id)
        schedule = catalog.schedules().find { it.project == project.id }
        delay(2000)
    } }
    Text("Actions & schedules", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(repo, { repo = it.trim().take(150) }, singleLine = true, label = { Text("GitHub repository · owner/name") })
    TextButton(enabled = validRepo(repo), onClick = { workspace.setting("githubRepo",repo,true); status = "Repository saved for this project." }) { Text("Save repository") }
    Text("Type or dictate “review my repo”, “open my repo”, or “open Spotify” in chat, then review the action.")
    Text("Weekday repository brief", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(time, { time = it.take(5) }, singleLine = true, label = { Text("Local time · HH:mm") })
    TextButton(enabled = validRepo(repo), onClick = {
        try {
            val clock = LocalTime.parse(time)
            val zone = ZoneId.systemDefault()
            val value = BriefSchedule(project.id,repo,clock,zone,nextWeekday(Instant.now(),clock,zone),true)
            catalog.saveSchedule(value); schedule = value; status = "Schedule saved."
        } catch (_: java.time.format.DateTimeParseException) { status = "Enter a 24-hour time such as 09:30." }
    }) { Text("Schedule weekday brief") }
    schedule?.let { current ->
        Text(if (current.enabled) "Next: ${current.next.atZone(current.zone)} · ${current.repo}" else "Paused · ${current.repo}")
        TextButton(onClick = {
            val changed = current.copy(enabled = !current.enabled, next = nextWeekday(Instant.now(),current.time,current.zone))
            catalog.saveSchedule(changed); schedule = changed
        }) { Text(if (current.enabled) "Pause" else "Resume") }
    }
    Text("Scheduled checks use public repository access. Alter must be running. A missed brief runs once when Alter resumes.")
    if (status.isNotEmpty()) Text(status)
    Text("Activity", style = MaterialTheme.typography.titleMedium)
    if (activity.isEmpty()) Text("No actions yet in this project.")
    activity.forEach { entry ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("${entry.action.title} · ${entry.state}")
            if (entry.result.isNotEmpty()) SelectionContainer { Text(entry.result, style = MaterialTheme.typography.bodySmall) }
            if (entry.started > 0) Text(Instant.ofEpochMilli(entry.started).atZone(ZoneId.systemDefault()).toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(enabled = enabled && entry.state != "running", onClick = { run(entry) }) {
                    Text(if (entry.state == "awaiting approval") "Review" else "Run again")
                }
                if (entry.state == "awaiting approval") TextButton(onClick = {
                    catalog.resolveApproval(project.id, entry.id, false); activity = catalog.activity(project.id)
                }) { Text("Dismiss") }
                if (entry.thread != null && entry.thread in threadIds)
                    TextButton(enabled = enabled, onClick = { openChat(entry.thread) }) { Text("Open chat") }
                if (entry.state == "completed") TextButton(onClick = {
                    try {
                        val connection = when (entry.action.name) {
                            "open_repo", "repo_brief", "repo_issues" -> "GitHub"
                            "figma_read" -> "Figma Desktop"
                            else -> entry.action.plugin?.label
                        }
                        val skill = ProjectSkill(entry.action.title.take(100), entry.action.command, connections = setOfNotNull(connection))
                        workspace.saveSkills(workspace.projectSkills() + skill)
                        skillsChanged(); status = "Saved to project skills."
                    } catch (e: IllegalArgumentException) { status = e.message ?: "Could not save skill." }
                }) { Text("Save as skill") }
            }
        }
    }
}
