package alter

import java.nio.file.Files
import java.time.*
import kotlin.test.*

class ProjectActionsTest {
    @Test fun projectHistoryAndSettingsStayIsolatedOnReopen() {
        val directory = Files.createTempDirectory("alter-project")
        try {
            Store(directory).use { catalog ->
                catalog.addMessage(null, "user", "Personal")
                val project = catalog.createProject("Work", "Use concise replies")
                assertFailsWith<IllegalArgumentException> { catalog.createProject("work", "") }
                assertFailsWith<IllegalArgumentException> { catalog.createProject("Personal", "") }
                catalog.openProject(project).use { workspace ->
                    assertTrue(workspace.threads().isEmpty())
                    workspace.setting("githubRepo", "owner/repo", true)
                    workspace.addMessage(null, "user", "Project chat")
                }
                assertEquals("", catalog.setting("githubRepo"))
            }
            Store(directory).use { catalog ->
                assertEquals("Personal", catalog.threads().single().title)
                catalog.openProject(catalog.projects().single()).use {
                    assertEquals("Project chat", it.threads().single().title)
                    assertEquals("owner/repo", it.setting("githubRepo"))
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun missedWeekdayRunsOnceAndRespectsPauseAcrossRestart() {
        val zone = ZoneId.of("America/New_York")
        val friday = Instant.parse("2026-03-06T14:00:00Z")
        val monday = Instant.parse("2026-03-09T13:15:00Z")
        val time = LocalTime.of(9, 0)
        val original = BriefSchedule(0, "owner/repo", time, zone, friday, true)
        assertEquals(Instant.parse("2026-03-09T13:00:00Z"), nextWeekday(friday, time, zone))
        val directory = Files.createTempDirectory("alter-schedule")
        try {
            Store(directory).use {
                it.saveSchedule(original)
                it.saveSchedule(original.copy(enabled = false))
                assertNull(it.claimSchedule(original, monday))
                it.saveSchedule(original)
                val id = assertNotNull(it.claimSchedule(original, monday))
                it.finishRun(id, "completed", "Synthetic results")
            }
            Store(directory).use {
                assertNull(it.claimSchedule(original, monday))
                assertEquals(Instant.parse("2026-03-10T13:00:00Z"), it.schedules().single().next)
                assertEquals("completed", it.activity(0).single().state)
                assertTrue(it.activity(1).isEmpty())
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun onlyExplicitCommandsAndRepositoryNamesMatch() {
        assertEquals(DesktopAction("open_spotify"), DesktopAction.parse("Open Spotify!", ""))
        assertNull(DesktopAction.parse("Explain how to open Spotify", ""))
        assertFalse(validRepo("owner/.."))
        assertFalse(validRepo("https://github.com/owner/repo"))
        assertTrue(validRepo("owner/repo"))
    }
}
