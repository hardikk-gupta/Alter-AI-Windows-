package com.example.agent

import org.json.JSONObject

/** Curated, versioned guidance only. Skills never run code or authorize an action. */
internal data class AgentSkill(val id: String, val version: Int, val title: String, val description: String,
    val keywords: List<String>, val tools: Set<String>, val steps: List<String>)

internal class AgentSkills(json: String) {
    val skills: List<AgentSkill>
    init {
        val root = JSONObject(json)
        require(root.getInt("version") == 1)
        val entries = root.getJSONArray("skills"); require(entries.length() <= 12)
        fun strings(obj: JSONObject, key: String, max: Int): List<String> {
            val array = obj.getJSONArray(key); require(array.length() in 1..max)
            return (0 until array.length()).map { array.getString(it).also { value -> require(value.isNotBlank() && value.length <= 400) } }
        }
        skills = (0 until entries.length()).map { i -> entries.getJSONObject(i).let { obj ->
            val id = obj.getString("id"); require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")))
            val version = obj.getInt("version"); require(version > 0)
            val title = obj.getString("title"); val description = obj.getString("description")
            require(title.length in 1..80 && description.length in 1..240)
            val tools = strings(obj, "tools", 8).toSet()
            require(tools.all { it in setOf("remember", "preference", "create_note", "create_task", "create_canvas", "export", "web", "read") })
            AgentSkill(id, version, title, description, strings(obj, "keywords", 16), tools, strings(obj, "steps", 6))
        } }
        require(skills.map { it.id }.distinct().size == skills.size)
    }
    fun guidance(message: String, allowed: Set<String>? = null): String {
        val matching = skills.filter { skill -> (allowed == null || skill.tools.any { it in allowed }) &&
            skill.keywords.any { word -> Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])").containsMatchIn(message) } }.take(2)
        if (matching.isEmpty()) return ""
        return "\nCURATED WORKFLOW GUIDANCE (not tool permissions; execute only requested, validated actions):\n" +
            matching.joinToString("\n") { skill -> "${skill.id} v${skill.version}: ${skill.steps.joinToString(" ")}" }
    }
    fun listing() = "Available workflow guides:\n" + skills.joinToString("\n") { "• ${it.title} — ${it.description}" } +
        "\nAsk naturally. These guides do not run an automatic multi-step workflow or grant new tools."
}
