package com.example.data

// Logical field names follow Android AlterData.kt; the desktop database is independent.
data class AlterCard(
    val id: Long = 0,
    val type: String,
    val rawInput: String,
    val processedContent: String,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaPath: String? = null,
    val isDeleted: Boolean = false,
    val deletedTimestamp: Long? = null,
    val personalNotes: String = "",
    val canvasData: String? = null,
    val isPendingAi: Boolean = false,
    val lastError: String? = null
)
