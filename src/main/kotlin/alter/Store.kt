package alter

import com.example.data.AlterCard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

fun defaultDataDirectory(): Path = System.getProperty("alter.dataDir")?.let(Path::of)
    ?: System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Alter") }
    ?: Path.of(System.getProperty("user.home"), ".local", "share", "Alter")

data class ChatThread(val id: Long, val title: String, val favorite: Boolean, val archived: Boolean)

class Store(directory: Path) : AutoCloseable {
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
            s.execute("CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            s.execute("""CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, text TEXT NOT NULL,
                timestamp INTEGER NOT NULL)""")
            s.execute("""CREATE TABLE IF NOT EXISTS thread_options (
                threadId INTEGER PRIMARY KEY, favorite INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)""")
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
    @Synchronized fun cards(): List<AlterCard> = db.createStatement().use { s ->
        s.executeQuery("SELECT * FROM cards WHERE isDeleted=0 ORDER BY timestamp DESC, id DESC").use { r ->
            buildList { while (r.next()) add(AlterCard(r.getLong("id"), r.getString("type"),
                r.getString("rawInput"), r.getString("processedContent"), r.getLong("timestamp"))) }
        }
    }
    @Synchronized fun save(card: AlterCard) {
        require(card.type in setOf("text", "note", "upcoming"))
        require(card.rawInput.isNotBlank() || card.processedContent.isNotBlank()) { "Write something first." }
        require(card.rawInput.length <= 100_000 && card.processedContent.length <= 100_000) { "This entry is too long." }
        if (card.id == 0L) db.prepareStatement(
            "INSERT INTO cards(type,rawInput,processedContent,timestamp) VALUES(?,?,?,?)"
        ).use { s ->
            s.setString(1, card.type); s.setString(2, card.rawInput); s.setString(3, card.processedContent)
            s.setLong(4, card.timestamp); s.executeUpdate()
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
        require(sender in setOf("user", "assistant"))
        return db.prepareStatement("""INSERT INTO messages(threadId,sender,text,timestamp)
            VALUES(coalesce(?,(SELECT coalesce(max(threadId),0)+1 FROM messages)),?,?,?) RETURNING threadId""").use { s ->
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
