package alter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.awt.Desktop
import java.net.URI
import java.net.http.*
import java.time.*

internal val repoPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9-]{0,38}/[a-zA-Z0-9_.-]{1,100}")

internal fun validRepo(repo: String) = repoPattern.matches(repo) && repo.substringAfter('/') !in setOf(".", "..")

internal data class DesktopAction(val name: String, val argument: String = "") {
    val plugin get() = when (name) {
        "calendar_events" -> ServicePlugin.Calendar
        "drive_files" -> ServicePlugin.Drive
        "play_spotify" -> ServicePlugin.Spotify
        else -> null
    }
    val title get() = when (name) {
        "calendar_events" -> "Read upcoming Google Calendar events"
        "drive_files" -> "List recently modified Drive files"
        "play_spotify" -> "Play “$argument” on your active Spotify device"
        "figma_read" -> "Read selected Figma layer"
        "repo_issues" -> "List open issues for $argument"
        "repo_brief" -> "Review checks for $argument"
        "open_repo" -> "Open $argument on GitHub"
        "open_spotify" -> "Open Spotify"
        else -> error("Unknown action.")
    }

    suspend fun execute(figmaEnabled: Boolean = false, grant: PluginGrant? = null): String = runInterruptible(Dispatchers.IO) {
        plugin?.let { return@runInterruptible ServiceTools.execute(it, grant, argument) }
        when (name) {
            "open_spotify" -> {
                Desktop.getDesktop().browse(URI("spotify:home"))
                "Sent the Spotify launch request to Windows."
            }
            "figma_read" -> {
                check(figmaEnabled) { "Enable Figma Desktop in this project’s Plugins first." }
                FigmaMcp().readSelection(false)
            }
            "open_repo", "repo_issues", "repo_brief" -> {
                require(validRepo(argument)) { "Use owner/repository." }
                val url = "https://github.com/$argument"
                if (name == "open_repo") {
                    Desktop.getDesktop().browse(URI(url))
                    "Opened $url in the browser."
                } else {
                    val endpoint = if (name == "repo_issues") "issues?state=open&per_page=30" else "actions/runs?per_page=5"
                    val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/$argument/$endpoint"))
                        .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json").build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    check(response.statusCode() == 200) { "GitHub returned HTTP ${response.statusCode()}. Check public repository access and rate limits." }
                    if (name == "repo_issues") {
                        val entries = org.json.JSONArray(response.body())
                        val issues = (0 until entries.length()).map { entries.getJSONObject(it) }.filterNot { it.has("pull_request") }.take(10)
                        return@runInterruptible issues.joinToString("\n") {
                            "#${it.getInt("number")} ${it.getString("title")} · $url/issues/${it.getInt("number")}"
                        }.ifEmpty { "No issues among the latest 30 open entries." }
                    }
                    val runs = JSONObject(response.body()).getJSONArray("workflow_runs")
                    buildString {
                        append("Recent workflow runs for $argument\n$url/actions")
                        if (runs.length() == 0) append("\nNo workflow runs found.")
                        for (i in 0 until runs.length()) {
                            val run = runs.getJSONObject(i)
                            append("\n\n").append(run.optString("name")).append(" · ")
                            append(if (run.isNull("conclusion")) run.optString("status") else run.optString("conclusion"))
                            append("\n$url/actions/runs/").append(run.getLong("id"))
                        }
                    }
                }
            }
            else -> error("Unsupported action.")
        }
    }

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
        fun parse(text: String, repo: String): DesktopAction? = when (text.trim().lowercase().trimEnd('.', '!')) {
            "calendar events", "/calendar" -> DesktopAction("calendar_events")
            "list drive files", "/drive" -> DesktopAction("drive_files")
            "read my figma selection", "/figma selection" -> DesktopAction("figma_read")
            "list repo issues", "/repo issues" -> DesktopAction("repo_issues", repo)
            "open spotify", "/spotify" -> DesktopAction("open_spotify")
            "open my repo", "open my repository", "/repo open" -> DesktopAction("open_repo", repo)
            "review my repo", "review my repository", "/repo checks" -> DesktopAction("repo_brief", repo)
            else -> Regex("play (.{1,200}) on spotify[.!]?", RegexOption.IGNORE_CASE).matchEntire(text.trim())
                ?.let { DesktopAction("play_spotify", it.groupValues[1]) }
        }
    }
}

internal data class RunEntry(val id: Long, val project: Long, val action: DesktopAction, val state: String, val result: String)
internal data class BriefSchedule(val project: Long, val repo: String, val time: LocalTime, val zone: ZoneId, val next: Instant, val enabled: Boolean)

internal fun nextWeekday(after: Instant, time: LocalTime, zone: ZoneId): Instant {
    var day = after.atZone(zone).toLocalDate()
    while (true) {
        val instant = day.atTime(time).atZone(zone).toInstant()
        if (day.dayOfWeek.value <= 5 && instant > after) return instant
        day = day.plusDays(1)
    }
}

internal suspend fun Store.executeRun(id: Long, action: DesktopAction, figmaEnabled: Boolean = false, grant: PluginGrant? = null): String {
    return try {
        val result = action.execute(figmaEnabled, grant)
        finishRun(id, "completed", result)
        result
    } catch (cancelled: CancellationException) {
        finishRun(id, "cancelled", "Stopped waiting for the action.")
        throw cancelled
    } catch (error: Exception) {
        val result = error.message ?: "The action failed."
        finishRun(id, "failed", result)
        result
    }
}
