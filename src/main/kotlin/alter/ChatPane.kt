package alter

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.example.ui.AlterIcons
import com.example.ui.theme.*

@Composable
internal fun ChatPane(messages: List<Pair<String,String>>, prompt: String, update: (String) -> Unit,
    activeModel: String?, installed: Map<ModelSpec, Boolean>, busy: Boolean, speaking: Boolean, generating: Boolean,
    projectSkills: List<ProjectSkill>, useSkill: (ProjectSkill) -> Unit,
    status: String, choose: (ModelSpec) -> Unit, manage: () -> Unit, capture: (String) -> Unit, mic: () -> Unit, send: () -> Unit, stop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scroll = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val skills = remember {
        val catalog = requireNotNull(com.example.agent.AgentSkills::class.java.getResourceAsStream("/agent_skills.json"))
        catalog.bufferedReader().use { com.example.agent.AgentSkills(it.readText()).skills }
    }
    var skillsMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, generating) {
        if (messages.isNotEmpty()) scroll.scrollToItem(messages.lastIndex + if (generating) 1 else 0)
    }
    Column(Modifier.fillMaxSize().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(painterResource("alter-logo.xml"), null, Modifier.size(23.dp), AlterYellow)
                Spacer(Modifier.height(12.dp))
                Text("What’s on your mind?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
        } else LazyColumn(Modifier.weight(1f).widthIn(max = 810.dp).fillMaxWidth(), state = scroll,
            contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(messages) { (role, text) ->
                val user = role == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(if (user) Modifier.fillMaxWidth(.78f).wrapContentWidth(Alignment.End)
                        .background(c.secondaryContainer, RoundedCornerShape(16.dp)).padding(horizontal = 15.dp, vertical = 10.dp)
                        else Modifier.fillMaxWidth()) {
                        if (role == "action") Text("Action result", style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp) }
                        if (!user) WorkspaceButton(AlterIcons.Copy, "Copy reply") { clipboard.setText(AnnotatedString(text)) }
                    }
                }
            }
            if (generating) item { Text("Generating…", color = c.onSurface.copy(alpha = .65f)) }
        }
        if (status.isNotBlank()) Text(status, Modifier.padding(6.dp), fontSize = 12.sp, color = c.onSurface.copy(alpha = .78f))
        Row(Modifier.widthIn(max = 810.dp).fillMaxWidth().background(c.secondaryContainer, RoundedCornerShape(24.dp))
            .border(1.dp, c.outline, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton({ skillsMenu = true }, Modifier.size(32.dp), enabled = !busy && !speaking && !generating) {
                    Icon(AlterIcons.Plus, "Capture with a skill", Modifier.size(16.dp))
                }
                DropdownMenu(skillsMenu, onDismissRequest = { skillsMenu = false }) {
                    projectSkills.filter { it.enabled }.forEach { skill ->
                        DropdownMenuItem(text = { Text(skill.title) }, onClick = { skillsMenu = false; useSkill(skill) })
                    }
                    skills.forEach { skill ->
                        val type = when (skill.id) {
                            "approved-memory" -> "text"
                            "stated-commitment" -> "upcoming"
                            "organize-note" -> "note"
                            else -> return@forEach
                        }
                        DropdownMenuItem(text = { Text(skill.title) }, onClick = { skillsMenu = false; capture(type) })
                    }
                }
            }
            BasicTextField(prompt, update, Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = "Message Alter" }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) { send(); true } else false
                }, maxLines = 5, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary), decorationBox = { inner ->
                    Box { if (prompt.isEmpty()) Text("Message Alter…", fontSize = 14.sp, color = c.onSurface.copy(alpha = .55f)); inner() }
                })
            Box {
                Row(Modifier.background(c.surface, CircleShape).clickable(enabled = !busy && !generating && !speaking) { modelMenu = true }
                    .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(activeModel ?: "Model", Modifier.widthIn(max = 150.dp), fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Icon(AlterIcons.ChevronDown, "Choose text model", Modifier.padding(start = 4.dp).size(12.dp))
                }
                DropdownMenu(modelMenu, onDismissRequest = { modelMenu = false }) {
                    models.filter { it.kind == "text" }.forEach { spec ->
                        val available = installed[spec] == true
                        DropdownMenuItem(enabled = activeModel != spec.title.substringBefore(" ·"), text = { Text(spec.title.substringBefore(" ·") + when { !available -> " · Download"; activeModel == spec.title.substringBefore(" ·") -> " · In use"; else -> "" }) },
                            onClick = { modelMenu = false; choose(spec) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Manage models…") }, onClick = { modelMenu = false; manage() })
                }
            }
            IconButton(mic, Modifier.size(34.dp), enabled = !busy && !generating) {
                Icon(if (speaking) AlterIcons.Stop else AlterIcons.Mic, if (speaking) "Stop dictation" else "Dictate", Modifier.size(16.dp))
            }
            FilledIconButton(if (generating) stop else send, Modifier.size(32.dp),
                enabled = generating || (prompt.isNotBlank() && !busy && !speaking),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = AlterYellow, contentColor = PitchBlack)) {
                Icon(if (generating) AlterIcons.Stop else AlterIcons.ArrowRight, if (generating) "Stop reply" else "Send message",
                    Modifier.size(16.dp).graphicsLayer { rotationZ = if (generating) 0f else -90f })
            }
        }
    }
}
