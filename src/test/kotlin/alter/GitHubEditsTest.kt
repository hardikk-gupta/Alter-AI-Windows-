package alter

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.*

class GitHubEditsTest {
    @Test fun existingFileGetsOneBranchAndOneDraftAcrossRetries() {
        val base = "1".repeat(40)
        val baseTree = "2".repeat(40)
        val newTree = "3".repeat(40)
        val commit = "4".repeat(40)
        val blob = "ce013625030ba8dba906f756967f9e9ca394464a"
        var branch: String? = null
        var created = false
        var failPull = true
        var branchCreates = 0
        var pullCreates = 0
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repos/user/demo") { e -> e.use {
            val path = e.requestURI.path.removePrefix("/repos/user/demo")
            val method = e.requestMethod
            requests += "$method $path"
            val input = e.requestBody.bufferedReader().readText().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            var status = 200
            val body = when {
                e.requestHeaders.getFirst("Authorization") != "Bearer fixture" -> { status = 401; "{}" }
                method == "GET" && path == "" -> """{"default_branch":"release"}"""
                path == "/git/ref/heads/release" -> """{"ref":"refs/heads/release","object":{"sha":"$base"}}"""
                path == "/git/commits/$base" -> """{"tree":{"sha":"$baseTree"}}"""
                path == "/git/trees/$baseTree" -> """{"tree":[{"path":"read me.txt","mode":"100755","size":6,"sha":"$blob"}]}"""
                path == "/git/blobs/$blob" -> """{"encoding":"base64","size":6,"content":"aGVsbG8K"}"""
                path == "/pulls" && method == "GET" -> if (created) """[{"number":7}]""" else "[]"
                path == "/git/trees" && method == "POST" -> {
                    val entry = input.getJSONArray("tree").getJSONObject(0)
                    if (entry.getString("mode") != "100755" || entry.getString("content") != "bye\n" ||
                        input.getString("base_tree") != baseTree) status = 422
                    """{"sha":"$newTree"}"""
                }
                path == "/git/commits" && method == "POST" -> {
                    if (input.getString("tree") != newTree || input.getJSONArray("parents").getString(0) != base) status = 422
                    """{"sha":"$commit"}"""
                }
                path.startsWith("/git/ref/heads/alter/edit-") && method == "GET" -> {
                    if (branch == null) status = 404
                    """{"ref":"$branch","object":{"sha":"$commit"}}"""
                }
                path == "/git/refs" && method == "POST" -> {
                    branchCreates++
                    branch = input.getString("ref")
                    if (!branch!!.startsWith("refs/heads/alter/edit-") || input.getString("sha") != commit) status = 422
                    "{}"
                }
                path == "/git/commits/$commit" -> """{"tree":{"sha":"$newTree"},"parents":[{"sha":"$base"}]}"""
                path == "/pulls" && method == "POST" -> {
                    if (failPull) { status = 503; failPull = false }
                    else if (!input.getBoolean("draft") || input.getString("base") != "release" ||
                        "refs/heads/" + input.getString("head") != branch) status = 422
                    else { created = true; pullCreates++ }
                    """{"number":7}"""
                }
                else -> { status = 400; "{}" }
            }
            val bytes = body.toByteArray()
            e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.write(bytes)
        } }
        try {
            server.start()
            val edits = GitHubEdits(RepoGrant("user/demo","fixture"), URI("http://127.0.0.1:${server.address.port}"))
            assertFailsWith<IllegalArgumentException> { edits.load("../secrets") }
            assertTrue(requests.isEmpty())
            val file = edits.load("read me.txt")
            assertEquals("hello\n", file.original)
            assertTrue(requests.all { it.startsWith("GET ") })
            assertFailsWith<IllegalArgumentException> { edits.publish(file, file.original) }
            var recovery = ""
            assertFailsWith<IllegalStateException> { edits.publish(file, "bye\n") { recovery = it } }
            assertEquals(file.compareUrl, recovery)
            repeat(2) { assertEquals("https://github.com/user/demo/pull/7", edits.publish(file, "bye\n")) }
            assertEquals(1, branchCreates)
            assertEquals(1, pullCreates)
        } finally { server.stop(0) }
    }
}
