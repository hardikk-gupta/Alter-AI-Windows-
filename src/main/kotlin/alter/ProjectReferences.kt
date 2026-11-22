package alter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.AlterCard
import java.nio.file.Files
import javax.swing.JFileChooser

internal fun readDocumentDraft(): AlterCard? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Import a project document"
    chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Text or Markdown", "txt", "md")
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile
    require(file.extension.lowercase() in setOf("txt", "md")) { "Choose a .txt or .md file." }
    val bytes = Files.newInputStream(file.toPath()).use { it.readNBytes(100001) }
    require(bytes.size <= 100000) { "Choose a document under 100 KB." }
    val body = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    require(body.isNotBlank() && !body.contains('\u0000')) { "Choose a UTF-8 text document." }
    return AlterCard(type = "note", rawInput = file.name, processedContent = body, source = "Imported from ${file.name}")
}

@Composable
internal fun ReferencePicker(cards: List<AlterCard>, use: (AlterCard) -> Unit, close: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val found = cards.filter { it.type in setOf("text", "note") &&
        (it.rawInput.contains(search, true) || it.processedContent.contains(search, true)) }.take(20)
    AlertDialog(onDismissRequest = close, title = { Text("Add project context") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("Find a memory or note") })
            if (found.isEmpty()) Text("No matching entries in this project.")
            found.forEach { card ->
                TextButton(onClick = { use(card) }) {
                    Text(card.rawInput.ifBlank { card.processedContent }.lineSequence().first().take(90))
                }
            }
        } }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
