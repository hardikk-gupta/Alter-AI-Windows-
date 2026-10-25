package alter

import com.example.data.AlterCard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

fun defaultDataDirectory(): Path = System.getProperty("alter.dataDir")?.let(Path::of)
    ?: System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Alter") }
    ?: Path.of(System.getProperty("user.home"), ".local", "share", "Alter")

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
            require(version in 0..1) { "This data was created by a newer Alter version. No data was changed." }
            s.execute("""CREATE TABLE IF NOT EXISTS cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, rawInput TEXT NOT NULL,
                processedContent TEXT NOT NULL, timestamp INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL DEFAULT 0, deletedTimestamp INTEGER)""")
            s.execute("CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            s.execute("""CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, text TEXT NOT NULL,
                timestamp INTEGER NOT NULL)""")
            s.execute("PRAGMA user_version=1")
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
    @Synchronized fun messages(): List<Pair<String, String>> = db.createStatement().use { s ->
        s.executeQuery("SELECT sender,text FROM messages ORDER BY id").use { r ->
            buildList { while (r.next()) add(r.getString(1) to r.getString(2)) }
        }
    }
    @Synchronized fun addMessage(sender: String, text: String) {
        require(sender in setOf("user", "assistant"))
        db.prepareStatement("INSERT INTO messages(sender,text,timestamp) VALUES(?,?,?)").use { s ->
            s.setString(1, sender); s.setString(2, text); s.setLong(3, System.currentTimeMillis()); s.executeUpdate()
        }
    }
    @Synchronized fun clearMessages() { db.createStatement().use { it.executeUpdate("DELETE FROM messages") } }
    override fun close() = db.close()
}
