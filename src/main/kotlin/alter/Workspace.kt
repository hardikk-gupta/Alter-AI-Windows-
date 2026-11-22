package alter

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.example.ui.AlterIcons
import com.example.ui.theme.AlterYellow

internal enum class Destination(val title: String, val icon: ImageVector) {
    Home("Home", AlterIcons.Home), Chat("Alter AI", AlterIcons.Chat), Memories("Memories", AlterIcons.Memories),
    Upcoming("Upcoming", AlterIcons.Calendar), Notes("Notes", AlterIcons.FileText)
}
internal val settingsPages = listOf("Appearance", "AI models", "Voice & files", "Actions & schedules", "Backup & account", "About")

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceButton(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    TooltipArea(tooltip = { Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 12.sp)
    } }) {
        IconButton(onClick, Modifier.size(36.dp).background(if (selected) c.onSurface.copy(alpha = .10f) else Color.Transparent,
            RoundedCornerShape(9.dp)).semantics { this.selected = selected }) {
            Icon(icon, label, Modifier.size(18.dp), tint = c.onSurface.copy(alpha = if (selected) 1f else .68f))
        }
    }
}

@Composable
internal fun ActivityRail(current: Destination, settings: Boolean, select: (Destination) -> Unit, openSettings: () -> Unit, collapse: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.width(42.dp).fillMaxHeight().padding(vertical = 10.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(painterResource("alter-logo.xml"), "Alter", Modifier.padding(bottom = 9.dp).size(18.dp), AlterYellow)
        Destination.entries.forEach { tab ->
            val selected = !settings && current == tab
            WorkspaceButton(tab.icon, tab.title, selected) { select(tab) }
        }
        Spacer(Modifier.weight(1f))
        collapse?.let { WorkspaceButton(AlterIcons.ArrowLeft, "Collapse navigation", onClick = it) }
        WorkspaceButton(AlterIcons.Settings, "Settings", settings, openSettings)
    }
}

@Composable
internal fun WorkspaceRow(title: String, icon: ImageVector? = null, selected: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) c.onSurface.copy(alpha = .10f) else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = c.onSurface.copy(alpha = .70f))
        Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = c.onSurface.copy(alpha = if (enabled) .90f else .45f))
    }
}

@Composable
private fun SectionHeader(name: String, expanded: Boolean, toggle: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)
    Row(Modifier.fillMaxWidth().height(32.dp).clickable(role = Role.Button, onClick = toggle).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(if (expanded) AlterIcons.ChevronDown else AlterIcons.ChevronRight, if (expanded) "Collapse" else "Expand", Modifier.size(12.dp), tint)
        Text(name.uppercase(), fontSize = 10.sp, letterSpacing = .6.sp, color = tint)
    }
}

@Composable
internal fun WorkspaceSidebar(settings: String?, active: Long?, threads: List<ChatThread>,
    search: String, setSearch: (String) -> Unit, newChat: () -> Unit, openThread: (Long) -> Unit,
    canSwitch: Boolean, openSettings: (String) -> Unit, update: (ChatThread) -> Unit,
    categories: Map<Destination, Int>, openCategory: (Destination) -> Unit, modelCount: Int) {
    val c = MaterialTheme.colorScheme
    val expanded = remember { mutableStateMapOf("Favorites" to true, "Chats" to true, "Categories" to true, "Archive" to false) }
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)).background(
        c.surface).padding(vertical = 8.dp, horizontal = 7.dp)) {
        if (settings != null) {
            Text("Settings", Modifier.padding(10.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            settingsPages.forEach { WorkspaceRow(it, selected = settings == it) { openSettings(it) } }
        } else {
            BasicTextField(search, setSearch, Modifier.fillMaxWidth().padding(6.dp).semantics { contentDescription = "Search chats" },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary), decorationBox = { inner ->
                    Row(Modifier.height(29.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AlterIcons.Search, null, Modifier.size(15.dp), c.onSurface.copy(alpha = .65f))
                        Box(Modifier.weight(1f)) { if (search.isEmpty()) Text("Search chats…", fontSize = 13.sp, color = c.onSurface.copy(alpha = .66f)); inner() }
                    }
                })
            WorkspaceRow("New chat", AlterIcons.Edit, enabled = canSwitch, onClick = newChat)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (search.isNotBlank() && threads.isEmpty()) item { Text("No matching chats", Modifier.padding(8.dp), fontSize = 13.sp) }
                for (name in listOf("Favorites", "Chats", "Categories", "Archive")) {
                    val open = expanded[name] == true || search.isNotBlank()
                    if (name == "Categories" && search.isNotBlank()) continue
                    val group = when (name) {
                        "Favorites" -> threads.filter { it.favorite && !it.archived }
                        "Chats" -> threads.filter { !it.favorite && !it.archived }
                        "Archive" -> threads.filter { it.archived }
                        else -> emptyList()
                    }
                    if (search.isNotBlank() && group.isEmpty()) continue
                    item { SectionHeader(name, open) { expanded[name] = !open } }
                    if (open && name == "Categories") items(categories.keys.toList()) { dest ->
                        Row(Modifier.fillMaxWidth().height(30.dp).clickable { openCategory(dest) }.padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(dest.icon, null, Modifier.size(13.dp), c.primary.copy(alpha = .8f))
                            Text(dest.title, Modifier.weight(1f), fontSize = 13.sp)
                            Text(categories.getValue(dest).toString(), fontSize = 11.sp, color = c.onSurface.copy(alpha = .6f))
                        }
                    } else if (open) {
                        if (group.isEmpty()) item {
                            Text(when (name) { "Favorites" -> "No favorites yet"; "Archive" -> "No archived chats"; else -> "No chats yet" },
                                Modifier.padding(start = 25.dp, bottom = 5.dp), fontSize = 11.sp, color = c.onSurface.copy(alpha = .50f))
                        }
                        items(group, key = { it.id }) { thread ->
                            var menu by remember { mutableStateOf(false) }
                            val selected = active == thread.id
                            Row(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (selected) c.onSurface.copy(alpha = .12f) else Color.Transparent),
                                verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).fillMaxHeight().clickable(enabled = canSwitch) { openThread(thread.id) }.padding(start = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (thread.favorite) AlterIcons.Pin else AlterIcons.Chat, null, Modifier.size(13.dp),
                                        if (thread.favorite) c.primary else c.onSurface.copy(alpha = .6f))
                                    Text(thread.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box {
                                    Box(Modifier.size(26.dp).clickable(enabled = canSwitch) { menu = true }
                                        .semantics { contentDescription = "Chat options for ${thread.title}" }, contentAlignment = Alignment.Center) {
                                        Icon(AlterIcons.ChevronDown, null, Modifier.size(11.dp), c.onSurface.copy(alpha = .58f))
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(if (thread.favorite) "Remove from favorites" else "Add to favorites") },
                                            onClick = { menu = false; update(thread.copy(favorite = !thread.favorite)) })
                                        DropdownMenuItem(text = { Text(if (thread.archived) "Restore chat" else "Archive chat") },
                                            onClick = { menu = false; update(thread.copy(archived = !thread.archived)) })
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.onSurface.copy(alpha = .08f))
                .clickable { openSettings("AI models") }.padding(12.dp)) {
                Text("Local models", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(if (modelCount == 0) "Browse models" else "$modelCount installed · Manage", fontSize = 12.sp,
                    color = c.primary)
            }
        }
    }
}

@Composable
internal fun WorkspaceDetails(model: String?, voice: String?, runtime: String, manage: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp))
        .border(1.dp, c.outline, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Workspace", Modifier.weight(1f), fontSize = 14.sp, color = c.onSurface.copy(alpha = .70f))
            WorkspaceButton(AlterIcons.Settings, "Manage local models", onClick = manage)
        }
        listOf(AlterIcons.Grid to "This PC", AlterIcons.Chat to (model ?: "No text model loaded"),
            AlterIcons.Mic to (voice ?: "No voice model selected"), AlterIcons.Waves to runtime).forEach { (icon, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 31.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(15.dp), tint = c.onSurface.copy(alpha = .68f))
                Text(label, fontSize = 13.sp, lineHeight = 16.sp)
            }
        }
    }
}
