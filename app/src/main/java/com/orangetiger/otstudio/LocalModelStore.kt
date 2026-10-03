package com.orangetiger.otstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ChatEngine { LOCAL, OPENROUTER }

/** On-device model choices and files. Model files live in private storage (filesDir/models) and never in backups. */
class LocalModelStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_models", Context.MODE_PRIVATE)
    val folder = File(context.filesDir, "models")

    var engine: ChatEngine
        get() = ChatEngine.entries.firstOrNull { it.name == prefs.getString("engine", null) } ?: ChatEngine.OPENROUTER
        set(v) { prefs.edit().putString("engine", v.name).apply() }
    var contextTokens: Int
        get() = prefs.getInt("context", 8192).takeIf { it in CONTEXT_CHOICES } ?: 8192
        set(v) { require(v in CONTEXT_CHOICES); prefs.edit().putInt("context", v).apply() }
    /** Lets reasoning models think before answering, and shows that thinking apart from the answer. */
    var showThinking: Boolean
        get() = prefs.getBoolean("thinking", false)
        set(v) { prefs.edit().putBoolean("thinking", v).apply() }
    /** False until the user picks where Chat runs, so a new install can ask. */
    val engineChosen get() = prefs.contains("engine")
    /** 0 means automatic. */
    var threads: Int
        get() = prefs.getInt("threads", 0).takeIf { it in THREAD_CHOICES } ?: 0
        set(v) { require(v in THREAD_CHOICES); prefs.edit().putInt("threads", v).apply() }

    fun all(): List<LocalModelInfo> = LocalModelCatalog.entries + custom()

    fun custom(): List<LocalModelInfo> = try {
        val array = JSONArray(prefs.getString("custom", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            LocalModelInfo(o.getString("repo"), o.getString("file"), o.optString("revision", "main"), title = o.getString("file").substringAfterLast('/').removeSuffix(".gguf"), note = "Added from a Hugging Face link.")
        } }
    } catch (_: Exception) { emptyList() }

    fun addCustom(link: String): LocalModelInfo {
        val m = HfLink.parse(link)
        if (all().any { it.localName == m.localName }) throw UserError("This model is already in the list.")
        val array = JSONArray(prefs.getString("custom", "[]"))
        if (array.length() >= 20) throw UserError("The list holds up to 20 added models. Delete one first.")
        array.put(JSONObject().put("repo", m.repo).put("file", m.file).put("revision", m.revision))
        if (!prefs.edit().putString("custom", array.toString()).commit()) throw UserError("Could not save the model list.")
        return m
    }

    fun removeCustom(m: LocalModelInfo) {
        val array = JSONArray(prefs.getString("custom", "[]"))
        val kept = JSONArray()
        for (i in 0 until array.length()) array.getJSONObject(i).let { o ->
            if (LocalModelInfo(o.getString("repo"), o.getString("file"), o.optString("revision", "main")).localName != m.localName) kept.put(o)
        }
        prefs.edit().putString("custom", kept.toString()).apply()
    }

    fun file(m: LocalModelInfo) = File(folder, m.localName)
    fun part(m: LocalModelInfo) = File(folder, m.localName + ".part")
    fun installed(m: LocalModelInfo) = file(m).isFile

    /** The chosen model, if its file is present. */
    fun selected(): LocalModelInfo? = prefs.getString("selected", null)?.let { name -> all().firstOrNull { it.localName == name } }?.takeIf(::installed)
    fun select(m: LocalModelInfo?) { prefs.edit().apply { if (m == null) remove("selected") else putString("selected", m.localName) }.apply() }

    fun delete(m: LocalModelInfo) {
        file(m).delete(); part(m).delete()
        if (prefs.getString("selected", null) == m.localName) select(null)
        if (LocalModelCatalog.entries.none { it.localName == m.localName }) removeCustom(m)
    }

    fun freeBytes(): Long { folder.mkdirs(); return folder.usableSpace }

    companion object {
        val CONTEXT_CHOICES = listOf(2048, 4096, 8192, 16384)
        val THREAD_CHOICES = listOf(0, 2, 4, 6, 8)
    }
}
