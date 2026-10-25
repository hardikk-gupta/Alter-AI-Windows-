package com.example.data

data class ParsedTodoItem(
    val isTodo: Boolean,
    val isCompleted: Boolean,
    val taskText: String,
    val rawLine: String
)

fun parseTodoLine(line: String): ParsedTodoItem? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    // Match all checkbox patterns: - [ ], * [ ], + [ ], 1. [ ], • [ ], [ ], - [x], * [x], + [x], [x], [X], - [v], - [✓], - []
    val bracketRegex = Regex("^(?:[-*+•]|\\d+[.)])?\\s*\\[([ xXvV✓\\-]?)\\]\\s*(.*)\$")
    val match = bracketRegex.find(trimmed)
    if (match != null) {
        val checkChar = match.groupValues[1].trim().lowercase()
        val isCompleted = checkChar == "x" || checkChar == "v" || checkChar == "✓"
        val rawTaskText = match.groupValues[2].trim()
        val cleaned = cleanLinePrefix(rawTaskText)
        if (cleaned.equals("[NO_SPEECH_DETECTED]", true)) return null
        return ParsedTodoItem(
            isTodo = true,
            isCompleted = isCompleted,
            taskText = cleaned,
            rawLine = line
        )
    }

    // Match Unicode checkboxes: ☐ (unchecked), ☑ / ☒ (checked)
    if (trimmed.startsWith("☐") || trimmed.startsWith("☑") || trimmed.startsWith("☒")) {
        val isCompleted = trimmed.startsWith("☑") || trimmed.startsWith("☒")
        val rawTaskText = trimmed.drop(1).trim()
        val cleaned = cleanLinePrefix(rawTaskText)
        if (cleaned.equals("[NO_SPEECH_DETECTED]", true)) return null
        return ParsedTodoItem(
            isTodo = true,
            isCompleted = isCompleted,
            taskText = cleaned,
            rawLine = line
        )
    }

    return null
}

fun cleanLinePrefix(line: String): String {
    var trimmed = line.trim()

    // Remove brackets with any content or without: - [ ], * [x], 1. [ ], • [ ], [ ]
    val checkboxPrefixRegex = Regex("^(?:[-*+•]|\\d+[.)])?\\s*\\[[ xXvV✓\\-]?\\]\\s*")
    trimmed = trimmed.replace(checkboxPrefixRegex, "")

    // Remove bullets: -, *, +, •, or numbered list prefixes like 1. or 2)
    val bulletPrefixRegex = Regex("^(?:[-*+•]|\\d+[.)])\\s*")
    trimmed = trimmed.replace(bulletPrefixRegex, "")

    // Remove unicode boxes: ☐, ☑, ☒
    if (trimmed.startsWith("☐") || trimmed.startsWith("☑") || trimmed.startsWith("☒")) {
        trimmed = trimmed.drop(1).trim()
    }

    // Strip bold/italic asterisks from scheduling tasks
    trimmed = trimmed.replace("**", "").replace("*", "")

    return trimmed.trim()
}

/** Use the app's checkbox parser for both widget display and completion. */
internal fun pendingCardTasks(card: AlterCard): List<String> {
    if (card.isDeleted) return emptyList()
    return listOf(card.processedContent, card.personalNotes, if (card.type == "upcoming") card.rawInput else "")
        .flatMap { it.lines() }
        .mapNotNull { parseTodoLine(it) }
        .distinctBy { it.taskText }
        .filter { !it.isCompleted && it.taskText.isNotBlank() }
        .map { it.taskText }
}

internal fun completeCardTask(card: AlterCard, taskText: String): AlterCard {
    fun complete(text: String): String = text.lines().joinToString("\n") { line ->
        val task = parseTodoLine(line)
        if (task == null || task.isCompleted || task.taskText != taskText) line
        else if (line.trimStart().startsWith("☐")) line.replaceFirst("☐", "☑")
        else line.replaceFirst(Regex("\\[[ \\-]?\\]"), "[x]")
    }
    return card.copy(processedContent = complete(card.processedContent), personalNotes = complete(card.personalNotes),
        rawInput = if (card.type == "upcoming") complete(card.rawInput) else card.rawInput)
}
