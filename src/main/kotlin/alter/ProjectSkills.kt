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

internal data class ProjectSkill(val title: String, val instructions: String, val enabled: Boolean = true)

internal fun Store.projectSkills(): List<ProjectSkill> {
    val list = JSONArray(setting("projectSkills", "[]"))
    return (0 until list.length()).map {
        val item = list.getJSONObject(it)
        ProjectSkill(item.getString("title"), item.getString("instructions"), item.getBoolean("enabled"))
    }
}

internal fun Store.saveSkills(skills: List<ProjectSkill>) {
    require(skills.size <= 50) { "A project can have up to 50 skills." }
    require(skills.distinctBy { it.title.lowercase() }.size == skills.size) { "Choose a unique skill name." }
    val list = JSONArray()
    skills.forEach {
        require(it.title.isNotBlank() && it.title.length <= 100 && it.instructions.isNotBlank() && it.instructions.length <= 8000)
        list.put(JSONObject().put("title", it.title).put("instructions", it.instructions).put("enabled", it.enabled))
    }
    setting("projectSkills", list.toString(), true)
}

@Composable
internal fun ProjectSkillsPanel(store: Store, skills: List<ProjectSkill>, refresh: () -> Unit) {
    var candidate by remember { mutableStateOf<ProjectSkill?>(null) }
    var error by remember { mutableStateOf("") }
    fun save(next: List<ProjectSkill>): Boolean = try {
        store.saveSkills(next); refresh(); error = ""; true
    } catch (e: Exception) { error = e.message ?: "Could not save skill."; false }
    Text("Project skills", style = MaterialTheme.typography.headlineSmall)
    Text("Import Markdown instructions, then choose a skill from the chat composer to review and send the draft.")
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
                error = ""; candidate = ProjectSkill(title.trim().take(100), body)
            }
        } catch (e: Exception) { error = e.message ?: "Could not open the file." }
    }) { Text("Import skill…") }
    skills.forEach { skill ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(skill.enabled, { checked -> save(skills.map { if (it == skill) it.copy(enabled = checked) else it }) })
            Text(skill.title, Modifier.weight(1f))
            TextButton(onClick = { save(skills - skill) }) { Text("Remove") }
        }
    }
    if (error.isNotBlank()) Text(error)
    candidate?.let { skill ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text("Import skill") },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(skill.title, { candidate = skill.copy(title = it.take(100)) }, label = { Text("Name") })
                Text(skill.instructions)
                if (error.isNotEmpty()) Text(error)
            } }, confirmButton = { TextButton(enabled = skill.title.isNotBlank(), onClick = {
                if (save(skills + skill)) candidate = null
            }) { Text("Import") } }, dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } })
    }
}
