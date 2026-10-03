package com.orangetiger.otstudio

// Portable memory logic: no Android types, so a desktop build can reuse it.

/** One standing instruction the user wrote. Only enabled lines reach a model. */
data class MemoryLine(val id: Long, val text: String, val enabled: Boolean)

/** Where memory lines are kept. Android stores them in SQLite; a desktop build supplies its own. */
interface MemoryStore {
    fun lines(): List<MemoryLine>
    fun add(text: String): Long
    fun edit(id: Long, text: String)
    fun setEnabled(id: Long, enabled: Boolean)
    fun delete(id: Long)
}

object MemoryPrompt {
    const val MAX_LINES = 50
    const val MAX_LENGTH = 500

    fun clean(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) throw UserError("Write the instruction first.")
        if (t.length > MAX_LENGTH) throw UserError("Keep each instruction under $MAX_LENGTH characters.")
        return t
    }

    /** The opening of the system prompt for every chat. Empty when no line is enabled. */
    fun build(lines: List<MemoryLine>): String {
        val on = lines.filter { it.enabled }.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (on.isEmpty()) return ""
        return "The user set these standing instructions. Follow them in every reply unless the user says otherwise in this chat.\n" +
            on.joinToString("\n") { "- $it" }
    }
}
