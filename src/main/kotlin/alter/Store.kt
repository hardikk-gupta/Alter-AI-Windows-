package alter

import com.example.data.AlterCard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.*

fun defaultDataDirectory(): Path = System.getProperty("alter.dataDir")?.let(Path::of)
    ?: System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Alter") }
    ?: Path.of(System.getProperty("user.home"), ".local", "share", "Alter")

data class ChatThread(val id: Long, val title: String, val favorite: Boolean, val archived: Boolean)

class Store(private val directory: Path) : AutoCloseable {
    private val db = run {
        Files.createDirectories(directory)
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("alter-desktop.db")}")
    }
    init {
        db.createStatement().use { s ->
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA journal_mode=WAL")
            val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            require(version in 0..2) { "This data was created by a newer Alter version. No data was changed." }
            s.execute("""CREATE TABLE IF NOT EXISTS cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, rawInput TEXT NOT NULL,
                processedContent TEXT NOT NULL, timestamp INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL DEFAULT 0, deletedTimestamp INTEGER)""")
            val hasSource = s.executeQuery("PRAGMA table_info(cards)").use { rows ->
                var found = false
                while (rows.next()) if (rows.getString("name") == "source") found = true
                found
            }
            if (!hasSource) s.execute("ALTER TABLE cards ADD COLUMN source TEXT NOT NULL DEFAULT 'Local entry'")
            s.execute("CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            s.execute("""CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, text TEXT NOT NULL,
                timestamp INTEGER NOT NULL)""")
            s.execute("""CREATE TABLE IF NOT EXISTS thread_options (
                threadId INTEGER PRIMARY KEY, favorite INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)""")
            s.execute("""CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, instructions TEXT NOT NULL)""")
            s.execute("""CREATE TABLE IF NOT EXISTS activity (id INTEGER PRIMARY KEY, project INTEGER NOT NULL,
                action TEXT NOT NULL, argument TEXT NOT NULL, state TEXT NOT NULL, result TEXT NOT NULL DEFAULT '')""")
            val activityColumns = s.executeQuery("PRAGMA table_info(activity)").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
            if ("thread" !in activityColumns) s.execute("ALTER TABLE activity ADD COLUMN thread INTEGER")
            if ("started" !in activityColumns) s.execute("ALTER TABLE activity ADD COLUMN started INTEGER NOT NULL DEFAULT 0")
            if ("ownerPid" !in activityColumns) s.execute("ALTER TABLE activity ADD COLUMN ownerPid INTEGER NOT NULL DEFAULT 0")
            s.execute("""CREATE TABLE IF NOT EXISTS schedules (project INTEGER PRIMARY KEY, repo TEXT NOT NULL, time TEXT NOT NULL,
                zone TEXT NOT NULL, next INTEGER NOT NULL, enabled INTEGER NOT NULL)""")
            if (version < 2) {
                db.autoCommit = false
                try {
                    s.execute("ALTER TABLE messages ADD COLUMN threadId INTEGER NOT NULL DEFAULT 0")
                    s.execute("CREATE INDEX messages_by_thread ON messages(threadId,id)")
                    s.execute("PRAGMA user_version=2")
                    db.commit()
                } catch (e: Exception) {
                    db.rollback()
                    throw e
                } finally { db.autoCommit = true }
            }
        }
    }
    @Synchronized fun projects(): List<Project> = db.createStatement().use { statement ->
        statement.executeQuery("SELECT id,name,instructions FROM projects ORDER BY name COLLATE NOCASE").use { rows ->
            buildList { while (rows.next()) add(Project(rows.getLong(1), rows.getString(2), rows.getString(3))) }
        }
    }

    @Synchronized fun createProject(name: String, instructions: String): Project {
        val title = name.trim()
        require(title.length in 1..80 && instructions.length <= 2000) { "Check the project name and instructions." }
        require(!title.equals(personalWorkspace.name, true)) { "Choose another name for this project." }
        return db.prepareStatement("INSERT INTO projects(name,instructions) VALUES(?,?) ON CONFLICT DO NOTHING RETURNING id").use { statement ->
            statement.setString(1, title); statement.setString(2, instructions)
            statement.executeQuery().use { rows ->
                require(rows.next()) { "A project with this name already exists." }
                Project(rows.getLong(1), title, instructions)
            }
        }
    }

    @Synchronized fun updateProject(project: Project): Project {
        val name = project.name.trim()
        require(project.id > 0 && name.length in 1..80 && !name.equals(personalWorkspace.name, true) && project.instructions.length <= 2000)
        db.prepareStatement("UPDATE OR IGNORE projects SET name=?, instructions=? WHERE id=?").use { statement ->
            statement.setString(1, name); statement.setString(2, project.instructions); statement.setLong(3, project.id)
            require(statement.executeUpdate() == 1) { "Choose a unique name for this project." }
        }
        return project.copy(name = name)
    }

    fun openProject(project: Project): Store {
        require(project.id > 0 && projects().any { it.id == project.id })
        return Store(directory.resolve("projects").resolve(project.id.toString()))
    }

    @Synchronized fun recoverInterruptedRuns() {
        val running = db.createStatement().use { sql ->
            sql.executeQuery("SELECT id,ownerPid,started FROM activity WHERE state='running'").use { rows ->
                buildList { while (rows.next()) add(Triple(rows.getLong(1), rows.getLong(2), rows.getLong(3))) }
            }
        }
        for ((id, pid, started) in running) {
            val owner = if (pid > 0) ProcessHandle.of(pid).orElse(null) else null
            if (owner == null || !owner.isAlive || owner.info().startInstant().map { it.toEpochMilli() > started }.orElse(false))
                finishRun(id, "interrupted", "Alter closed before it received a result.")
        }
    }

    @Synchronized internal fun beginRun(project: Long, action: DesktopAction, thread: Long? = null): Long = db.prepareStatement(
        "INSERT INTO activity(project,action,argument,thread,started,ownerPid,state) VALUES(?,?,?,?,?,?,'running') RETURNING id"
    ).use { s ->
        s.setLong(1, project); s.setString(2, action.name); s.setString(3, action.argument)
        s.setObject(4, thread); s.setLong(5, System.currentTimeMillis()); s.setLong(6, ProcessHandle.current().pid())
        s.executeQuery().use { it.next(); it.getLong(1) }
    }

    @Synchronized internal fun finishRun(id: Long, state: String, result: String) {
        db.prepareStatement("UPDATE activity SET state=?,result=? WHERE id=? AND state='running'").use { s ->
            s.setString(1, state); s.setString(2, result); s.setLong(3, id); s.executeUpdate()
        }
    }

    @Synchronized internal fun activity(project: Long): List<RunEntry> = db.prepareStatement(
        "SELECT * FROM activity WHERE project=? ORDER BY id DESC LIMIT 50"
    ).use { s ->
        s.setLong(1, project)
        s.executeQuery().use { r -> buildList { while (r.next()) add(RunEntry(r.getLong("id"), project,
            DesktopAction(r.getString("action"), r.getString("argument")), r.getString("state"), r.getString("result"), r.getObject("thread")?.let { r.getLong("thread") }, r.getLong("started"))) } }
    }

    @Synchronized internal fun saveSchedule(schedule: BriefSchedule) {
        require(validRepo(schedule.repo))
        db.prepareStatement("INSERT OR REPLACE INTO schedules VALUES(?,?,?,?,?,?)").use { s ->
            s.setLong(1,schedule.project); s.setString(2,schedule.repo); s.setString(3,schedule.time.toString())
            s.setString(4,schedule.zone.id); s.setLong(5,schedule.next.toEpochMilli()); s.setBoolean(6,schedule.enabled); s.executeUpdate()
        }
    }

    @Synchronized internal fun schedules(): List<BriefSchedule> = db.createStatement().use { s ->
        s.executeQuery("SELECT * FROM schedules").use { r -> buildList {
            while(r.next()) add(BriefSchedule(r.getLong("project"),r.getString("repo"),LocalTime.parse(r.getString("time")),
                ZoneId.of(r.getString("zone")),Instant.ofEpochMilli(r.getLong("next")),r.getBoolean("enabled")))
        } }
    }

    @Synchronized internal fun claimSchedule(schedule: BriefSchedule, now: Instant): Long? {
        if (!schedule.enabled || schedule.next > now) return null
        db.autoCommit = false
        try {
            val count = db.prepareStatement("UPDATE schedules SET next=? WHERE project=? AND next=? AND enabled=1 AND repo=? AND zone=? AND time=?").use { s ->
                s.setLong(1,nextWeekday(now,schedule.time,schedule.zone).toEpochMilli()); s.setLong(2,schedule.project)
                s.setLong(3,schedule.next.toEpochMilli()); s.setString(4,schedule.repo); s.setString(5,schedule.zone.id)
                s.setString(6,schedule.time.toString()); s.executeUpdate()
            }
            val run = if (count == 1) beginRun(schedule.project, DesktopAction("repo_brief",schedule.repo)) else null
            db.commit()
            return run
        } catch (e: Exception) { db.rollback(); throw e }
        finally { db.autoCommit = true }
    }

    @Synchronized fun cards(): List<AlterCard> = db.createStatement().use { s ->
        s.executeQuery("SELECT * FROM cards WHERE isDeleted=0 ORDER BY timestamp DESC, id DESC").use { r ->
            buildList { while (r.next()) add(AlterCard(r.getLong("id"), r.getString("type"),
                r.getString("rawInput"), r.getString("processedContent"), r.getLong("timestamp"), source = r.getString("source"))) }
        }
    }
    @Synchronized fun save(card: AlterCard) {
        require(card.type in setOf("text", "note", "upcoming"))
        require(card.rawInput.isNotBlank() || card.processedContent.isNotBlank()) { "Write something first." }
        require(card.rawInput.length <= 100_000 && card.processedContent.length <= 100_000) { "This entry is too long." }
        if (card.id == 0L) db.prepareStatement(
            "INSERT INTO cards(type,rawInput,processedContent,timestamp,source) VALUES(?,?,?,?,?)"
        ).use { s ->
            s.setString(1, card.type); s.setString(2, card.rawInput); s.setString(3, card.processedContent)
            s.setLong(4, card.timestamp); s.setString(5, card.source); s.executeUpdate()
        } else db.prepareStatement("UPDATE cards SET rawInput=?,processedContent=? WHERE id=? AND isDeleted=0").use { s ->
            s.setString(1, card.rawInput); s.setString(2, card.processedContent); s.setLong(3, card.id)
            check(s.executeUpdate() == 1) { "This entry has changed. Reopen it before saving." }
        }
    }
    @Synchronized fun delete(id: Long) {
        db.prepareStatement("UPDATE cards SET isDeleted=1,deletedTimestamp=? WHERE id=?").use { s ->
            s.setLong(1, System.currentTimeMillis()); s.setLong(2, id); s.executeUpdate()
        }
    }
    @Synchronized fun setting(name: String, fallback: String = ""): String = db.prepareStatement(
        "SELECT value FROM settings WHERE name=?"
    ).use { s -> s.setString(1, name); s.executeQuery().use { if (it.next()) it.getString(1) else fallback } }
    @Synchronized fun setting(name: String, value: String, write: Boolean) {
        require(write)
        db.prepareStatement("INSERT INTO settings VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value").use { s ->
            s.setString(1, name); s.setString(2, value); s.executeUpdate()
        }
    }
    @Synchronized fun threads(query: String = ""): List<ChatThread> = db.prepareStatement("""
        SELECT m.threadId, substr(first.text,1,90), coalesce(o.favorite,0), coalesce(o.archived,0) FROM messages m
        LEFT JOIN thread_options o ON o.threadId=m.threadId
        JOIN messages first ON first.id = (SELECT min(id) FROM messages WHERE threadId=m.threadId)
        GROUP BY m.threadId HAVING max(instr(lower(m.text),lower(?))) > 0
        ORDER BY max(m.id) DESC
    """).use { s ->
        s.setString(1, query.trim())
        s.executeQuery().use { r -> buildList {
            while (r.next()) add(ChatThread(r.getLong(1), r.getString(2).lineSequence().first(), r.getBoolean(3), r.getBoolean(4)))
        } }
    }

    @Synchronized fun messages(threadId: Long): List<Pair<String, String>> = db.prepareStatement(
        "SELECT sender,text FROM messages WHERE threadId=? ORDER BY id"
    ).use { s ->
        s.setLong(1, threadId)
        s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1) to r.getString(2)) } }
    }

    @Synchronized fun addMessage(threadId: Long?, sender: String, text: String): Long {
        require(sender in setOf("user", "assistant", "action"))
        return db.prepareStatement("""INSERT INTO messages(threadId,sender,text,timestamp)
            VALUES(coalesce(?,1+max(coalesce((SELECT seq FROM sqlite_sequence WHERE name='messages'),0),
                coalesce((SELECT max(threadId) FROM messages),0))),?,?,?) RETURNING threadId""").use { s ->
            s.setObject(1, threadId); s.setString(2, sender); s.setString(3, text); s.setLong(4, System.currentTimeMillis())
            s.executeQuery().use { it.next(); it.getLong(1) }
        }
    }

    @Synchronized fun updateThread(thread: ChatThread) {
        db.prepareStatement("""INSERT INTO thread_options(threadId,favorite,archived) VALUES(?,?,?)
            ON CONFLICT(threadId) DO UPDATE SET favorite=excluded.favorite, archived=excluded.archived""").use { s ->
            s.setLong(1, thread.id); s.setBoolean(2, thread.favorite); s.setBoolean(3, thread.archived); s.executeUpdate()
        }
    }

    @Synchronized fun deleteThread(id: Long) {
        db.autoCommit = false
        try {
            for (table in listOf("messages", "thread_options")) {
                db.prepareStatement("DELETE FROM $table WHERE threadId=?").use { s -> s.setLong(1, id); s.executeUpdate() }
            }
            db.commit()
        } catch (e: Exception) { db.rollback(); throw e }
        finally { db.autoCommit = true }
    }
    override fun close() = db.close()
}
