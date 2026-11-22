package alter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import javax.swing.JFileChooser

internal val connectionNames = listOf("GitHub", "Figma Desktop") + ServicePlugin.entries.map { it.label }

internal data class ProjectSkill(val title: String, val instructions: String, val enabled: Boolean = true,
    val connections: Set<String> = emptySet()) {
    fun missingConnection(connected: Set<String>) = connections.firstOrNull { it !in connected }
}

internal fun Store.projectSkills(): List<ProjectSkill> {
    val list = JSONArray(setting("projectSkills", "[]"))
    return (0 until list.length()).map {
        val item = list.getJSONObject(it)
        val required = item.optJSONArray("connections") ?: JSONArray()
        ProjectSkill(item.getString("title"), item.getString("instructions"), item.getBoolean("enabled"),
            (0 until required.length()).map { index -> required.getString(index) }.toSet())
    }
}

internal fun Store.saveSkills(skills: List<ProjectSkill>) {
    require(skills.size <= 50) { "A project can have up to 50 skills." }
    require(skills.distinctBy { it.title.lowercase() }.size == skills.size) { "Choose a unique skill name." }
    val list = JSONArray()
    skills.forEach {
        require(it.title.isNotBlank() && it.title.length <= 100 && it.instructions.isNotBlank() && it.instructions.length <= 8000)
        require(it.connections.all(connectionNames::contains)) { "Choose a supported connection." }
        list.put(JSONObject().put("title", it.title).put("instructions", it.instructions).put("enabled", it.enabled).put("connections", JSONArray(it.connections)))
    }
    setting("projectSkills", list.toString(), true)
}

@Composable
internal fun ProjectSkillsPanel(store: Store, skills: List<ProjectSkill>, refresh: () -> Unit) {
    var candidate by remember { mutableStateOf<ProjectSkill?>(null) }
    var editing by remember { mutableStateOf<ProjectSkill?>(null) }
    var error by remember { mutableStateOf("") }
    fun save(next: List<ProjectSkill>): Boolean = try {
        store.saveSkills(next); refresh(); error = ""; true
    } catch (e: Exception) { error = e.message ?: "Could not save skill."; false }
    Text("Project skills", style = MaterialTheme.typography.headlineSmall)
    Button(onClick = {
        try {
            val chooser = JFileChooser()
            chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Markdown", "md")
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile.toPath()
                val bytes = Files.newInputStream(file).use { it.readNBytes(32_001) }
                require(bytes.size <= 32000) { "Choose a shorter skill file." }
                val body = bytes.toString(Charsets.UTF_8).trim()
                require(body.length in 1..8000) { "Use 1–8000 characters of instructions." }
                val title = body.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")
                    ?: chooser.selectedFile.nameWithoutExtension
                error = ""; editing = null; candidate = ProjectSkill(title.trim().take(100), body)
            }
        } catch (e: Exception) { error = e.message ?: "Could not open the file." }
    }) { Text("Import skill…") }
    skills.forEach { skill ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(skill.enabled, { checked -> save(skills.map { if (it == skill) it.copy(enabled = checked) else it }) })
            Text(skill.title, Modifier.weight(1f))
            TextButton(onClick = { editing = skill; candidate = skill; error = "" }) { Text("Manage") }
        }
    }
    if (error.isNotBlank()) Text(error)
    candidate?.let { skill ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text(if (editing == null) "Import skill" else "Manage skill") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(skill.title, { candidate = skill.copy(title = it.take(100)) }, label = { Text("Name") })
                Text("Required connections", style = MaterialTheme.typography.labelLarge)
                for (connection in connectionNames) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(connection in skill.connections, { checked ->
                        candidate = skill.copy(connections = if (checked) skill.connections + connection else skill.connections - connection)
                    })
                    Text(connection)
                }
                Text(skill.instructions)
                editing?.let { old -> TextButton(onClick = { if (save(skills - old)) candidate = null }) { Text("Remove skill") } }
                if (error.isNotEmpty()) Text(error)
            } }, confirmButton = { TextButton(enabled = skill.title.isNotBlank(), onClick = {
                val old = editing
                if (save(if (old == null) skills + skill else skills.map { if (it == old) skill else it })) candidate = null
            }) { Text(if (editing == null) "Import" else "Save") } }, dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } })
    }
}
