package alter

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID

private val gitHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
private const val maxFileBytes = 96 * 1024
private fun encodePart(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
private fun gitSha(value: String) = value.also { require(it.matches(Regex("[0-9a-f]{40}"))) { "Invalid Git object ID." } }
private fun blobSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
    update("blob ${bytes.size}\u0000".toByteArray())
    digest(bytes).joinToString("") { "%02x".format(it) }
}

internal data class RepoFile(val repository: String, val path: String, val base: String, val commit: String,
    val tree: String, val mode: String, val original: String, val branch: String = "alter/edit-${UUID.randomUUID()}") {
    val compareUrl get() = "https://github.com/$repository/compare/${encodePart(base)}...${encodePart(branch)}?expand=1"
}

internal class GitHubEdits(private val grant: RepoGrant, api: URI = URI("https://api.github.com")) {
    init {
        require(validRepo(grant.repository))
        require(grant.token.isNotBlank() && grant.token.none { it.isWhitespace() })
        require(api == URI("https://api.github.com") || (api.scheme == "http" && api.host == "127.0.0.1"))
    }
    private val root = "$api/repos/${grant.repository}"
    private fun request(path: String, body: JSONObject? = null, missingOk: Boolean = false): String? {
        val builder = HttpRequest.newBuilder(URI(root + path)).timeout(Duration.ofSeconds(25))
            .header("Accept", "application/vnd.github+json").header("Authorization", "Bearer ${grant.token}")
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        val response = gitHttp.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404 && missingOk) return null
        check(response.statusCode() in 200..299) { "GitHub returned HTTP ${response.statusCode()}. Check the repository, token permissions and branch rules." }
        return response.body()
    }

    private fun json(path: String, body: JSONObject? = null) = JSONObject(requireNotNull(request(path, body)))
    private fun branchHead(branch: String, missingOk: Boolean = false): String? {
        val path = branch.split('/').joinToString("/") { encodePart(it) }
        return request("/git/ref/heads/$path", missingOk = missingOk)?.let {
            val ref = JSONObject(it)
            check(ref.getString("ref") == "refs/heads/$branch")
            gitSha(ref.getJSONObject("object").getString("sha"))
        }
    }

    fun load(path: String): RepoFile {
        val parts = path.split('/')
        require(path.length in 1..1024 && parts.size <= 32 && parts.all {
            it.isNotBlank() && it !in setOf(".", "..") && it.none { c -> c.isISOControl() || c == '\\' }
        }) { "Enter a repository-relative file path." }
        val base = json("").getString("default_branch")
        val commit = requireNotNull(branchHead(base))
        val tree = gitSha(json("/git/commits/$commit").getJSONObject("tree").getString("sha"))
        var sha = tree
        var mode = ""
        for ((i, part) in parts.withIndex()) {
            val listing = json("/git/trees/$sha")
            val entries = listing.getJSONArray("tree")
            val entry = (0 until entries.length()).asSequence().map { entries.getJSONObject(it) }
                .firstOrNull { it.getString("path") == part } ?: error("File not found in the selected repository tree.")
            mode = entry.getString("mode")
            if (i == parts.lastIndex) {
                require(mode in setOf("100644", "100755") && entry.getLong("size") <= maxFileBytes) { "Choose a regular UTF-8 text file up to 96 KB." }
            } else require(mode == "040000") { "Choose a file inside a repository directory." }
            sha = gitSha(entry.getString("sha"))
        }
        val blob = json("/git/blobs/$sha")
        check(blob.getString("encoding") == "base64" && blob.getLong("size") <= maxFileBytes)
        val bytes = Base64.getMimeDecoder().decode(blob.getString("content"))
        check(bytes.size <= maxFileBytes && blobSha(bytes) == sha) { "GitHub returned different file content. Reload the file." }
        val content = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in content) { "Choose a UTF-8 text file." }
        return RepoFile(grant.repository, path, base, commit, tree, mode, content)
    }

    fun publish(file: RepoFile, replacement: String, branchReady: (String) -> Unit = {}): String {
        require(file.repository == grant.repository && file.branch != file.base && file.branch.matches(Regex("alter/edit-[0-9a-f-]{36}")))
        require(replacement != file.original && replacement.toByteArray().size <= maxFileBytes && '\u0000' !in replacement) {
            "Change the file and keep it within 96 KB."
        }
        val pulls = JSONArray(request("/pulls?state=all&head=${encodePart(grant.repository.substringBefore('/') + ":" + file.branch)}&base=${encodePart(file.base)}"))
        if (pulls.length() > 0) return "https://github.com/${grant.repository}/pull/${pulls.getJSONObject(0).getInt("number")}"
        val entry = JSONObject().put("path", file.path).put("mode", file.mode).put("type", "blob").put("content", replacement)
        val tree = gitSha(json("/git/trees", JSONObject().put("base_tree", file.tree).put("tree", JSONArray().put(entry))).getString("sha"))
        val head = branchHead(file.branch, missingOk = true)
        if (head == null) {
            val change = json("/git/commits", JSONObject().put("message", "Update ${file.path}").put("tree", tree)
                .put("parents", JSONArray().put(file.commit)))
            json("/git/refs", JSONObject().put("ref", "refs/heads/${file.branch}").put("sha", gitSha(change.getString("sha"))))
        } else {
            val change = json("/git/commits/$head")
            val parents = change.getJSONArray("parents")
            check(change.getJSONObject("tree").getString("sha") == tree && parents.length() == 1 &&
                parents.getJSONObject(0).getString("sha") == file.commit) { "The review branch changed on GitHub. Inspect the branch before continuing." }
        }
        branchReady(file.compareUrl)
        val pull = json("/pulls", JSONObject().put("title", "Update ${file.path}".take(240))
            .put("base", file.base).put("head", file.branch).put("draft", true)
            .put("body", "Proposed from Alter after reviewing the edit to ${file.path}."))
        return "https://github.com/${grant.repository}/pull/${pull.getInt("number")}"
    }
}
