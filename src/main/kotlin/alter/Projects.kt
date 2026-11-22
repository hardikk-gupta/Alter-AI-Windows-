package alter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class Project(val id: Long, val name: String, val instructions: String)
val personalWorkspace = Project(0, "Personal", "")

@Composable
internal fun ProjectPicker(current: Project, catalog: Store, enabled: Boolean, select: (Project) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Box {
        TextButton(enabled = enabled, onClick = { menu = true }) {
            Text("${current.name} ▾", Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(menu, { menu = false }) {
            if (menu) (listOf(personalWorkspace) + remember { catalog.projects() }).forEach { project ->
                DropdownMenuItem(text = { Text(project.name) }, onClick = { menu = false; select(project) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("New project…") }, onClick = {
                menu = false; name = ""; instructions = ""; error = ""; create = true
            })
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("New project") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Project name") })
            OutlinedTextField(instructions, { instructions = it.take(2000) }, label = { Text("Instructions for chat (optional)") })
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { create = false }) { Text("Cancel") } },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = {
            try {
                val project = catalog.createProject(name, instructions)
                select(project); create = false
            } catch (e: Exception) { error = e.message ?: "Could not create project." }
        }) { Text("Create") } })
}
