package com.orangetiger.otstudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val keys = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val profiles = ProfileStore(context)

    fun get(mode: Mode): ModelSettings {
        val d = Rules.defaults(mode)
        val p = mode.name
        val base = d.copy(
            baseUrl = prefs.getString("$p.url", d.baseUrl) ?: d.baseUrl,
            model = prefs.getString("$p.model", d.model) ?: d.model,
            keySource = KeySource.entries.firstOrNull { it.name == prefs.getString("$p.keySource", d.keySource.name) } ?: d.keySource
        )
        profiles.load(base)?.let { return it }
        val selected = forModel(base, base.model)
        val legacy = listOf("aspect_ratio" to prefs.getString("$p.aspect", null), "duration" to if (prefs.contains("$p.duration")) prefs.getInt("$p.duration", 5).toString() else null, "resolution" to prefs.getString("$p.resolution", null))
        val migrated = selected.options.toMutableList()
        legacy.forEach { (name, value) ->
            val def = selected.schema?.fields?.firstOrNull { it.name == name }
            if (value != null && def != null) {
                try { val v = ModelOptions.value(def, value); migrated.removeAll { it.name == name }; migrated.add(v) } catch (_: UserError) { }
            }
        }
        return selected.copy(options = migrated)
    }

    fun forModel(base: ModelSettings, id: String): ModelSettings {
        val modelId = id.trim()
        val schema = if (base.mode == Mode.CHAT) ModelCatalog.chatSchema else ModelCatalog.find(base.mode, modelId)?.schema
        val selected = base.copy(model = modelId, schema = schema, options = ModelOptions.initial(base.mode, modelId, schema))
        Rules.validate(selected)
        return profiles.load(selected) ?: selected
    }

    fun models(mode: Mode, baseUrl: String): List<ModelPreset> {
        val ids = (ModelCatalog.presets.filter { it.mode == mode }.map { it.model } + profiles.modelIds(mode, baseUrl)).distinct()
        val base = Rules.defaults(mode).copy(baseUrl = baseUrl)
        return ids.map { id -> val s = forModel(base, id); ModelPreset(mode, id, s.schema ?: InputSchema(emptyList(), listOf("Load model inputs first"), listOf("Load model inputs first")), ModelCatalog.find(mode, id)?.fixedResolution) }
    }

    fun remember(s: ModelSettings) { profiles.save(s) }

    fun save(s: ModelSettings, newKey: String?) {
        Rules.validate(s)
        if (hasKey(s.mode) && Rules.origin(s.baseUrl) != Rules.origin(get(s.mode).baseUrl) && newKey == null) {
            throw UserError("Enter a new key or remove the saved key before changing providers. A saved key is never carried to a different API origin.")
        }
        if (newKey != null) writeKey(s.mode, newKey.trim(), Rules.origin(s.baseUrl))
        profiles.save(s)
        val p = s.mode.name
        if (!prefs.edit().putString("$p.url", s.baseUrl.trimEnd('/')).putString("$p.model", s.model)
                .putString("$p.keySource", s.keySource.name).commit()) {
            throw UserError("Could not save settings. Check available device storage.")
        }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("otstudio-keys-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("otstudio-keys-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun key(mode: Mode): String = readKey(mode.name, Rules.origin(get(mode).baseUrl))

    /** Reads the key in [slot], which must be bound to [origin]. Empty when none is saved. */
    private fun readKey(slot: String, origin: String): String {
        val value = keys.getString(slot, null) ?: return ""
        val boundOrigin = keys.getString("$slot.origin", null)
        if (boundOrigin != origin) {
            throw UserError("This saved key belongs to a different API origin. Re-enter the key for this provider.")
        }
        try {
            val parts = value.split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD("$slot|$boundOrigin".toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { throw UserError("The saved API key cannot be decrypted. Re-enter it in Settings.") }
    }

    fun hasKey(mode: Mode): Boolean = keys.contains(mode.name)

    // The Tavily key has its own slot, bound to Tavily's origin, so it can never reach another host.
    private val searchSlot = "SEARCH"
    private val searchOrigin get() = Rules.origin(WebSearch.TAVILY_ORIGIN)
    fun searchKey(): String = readKey(searchSlot, searchOrigin)
    fun hasSearchKey(): Boolean = keys.contains(searchSlot)
    /** Saves the Tavily key; an empty value removes it. */
    fun saveSearchKey(value: String) {
        val v = value.trim()
        if (v.isNotEmpty() && !v.startsWith("tvly-")) throw UserError("Tavily keys start with tvly-.")
        writeKey(searchSlot, v, searchOrigin)
    }

    /** Allow web search (default on): the chat may search Wikipedia, or Tavily with a key. */
    var allowSearch: Boolean
        get() = prefs.getBoolean("search.allow", true)
        set(v) { prefs.edit().putBoolean("search.allow", v).apply() }

    private fun writeKey(mode: Mode, value: String, origin: String) = writeKey(mode.name, value, origin)

    private fun writeKey(slot: String, value: String, origin: String) {
        if (value.isEmpty()) {
            if (!keys.edit().remove(slot).remove("$slot.origin").commit()) throw UserError("Could not remove the API key.")
            return
        }
        if (value.length > 4096 || value.any { it.isWhitespace() || it.code !in 33..126 }) throw UserError("The API key contains invalid characters.")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            cipher.updateAAD("$slot|$origin".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            if (!keys.edit().putString(slot, record).putString("$slot.origin", origin).commit()) throw UserError("Could not save the API key.")
        } catch (e: UserError) { throw e }
        catch (_: Exception) { throw UserError("Android Keystore could not protect this key. Nothing was saved.") }
    }

    fun resolve(mode: Mode): ResolvedModel = Rules.resolve(mode, ::get, ::key)

    /** The editing model chosen last in the Edit dialog. */
    var editModel: String
        get() = prefs.getString("edit.model", null)?.takeIf { ModelCatalog.edit(it) != null } ?: ModelCatalog.defaultEdit.model
        set(v) { require(ModelCatalog.edit(v) != null); prefs.edit().putString("edit.model", v).apply() }

    fun keyForEntry(entry: Entry): String {
        if (Rules.origin(entry.baseUrl) != Rules.origin(get(entry.credentialMode).baseUrl)) throw UserError("This job used a different provider. Restore that provider's API URL and key before refreshing it.")
        return key(entry.credentialMode).takeIf { it.isNotBlank() } ?: throw UserError("Restore the ${entry.credentialMode.title.lowercase()} API key to refresh this job.")
    }
}

class History(context: Context) : SQLiteOpenHelper(context, "studio.db", null, 6) {
    companion object { const val LOCAL_BASE = "on-device" }
    /**
     * When false, new chat turns live only in this list and vanish when the app process ends.
     * Image and video entries always go to the database, so their files can be kept.
     */
    @Volatile var saveChat = true
    private val unsaved = ArrayList<Entry>()
    private var nextUnsavedId = -1L
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT NOT NULL,
            prompt TEXT NOT NULL, response TEXT NOT NULL DEFAULT '', state TEXT NOT NULL,
            model TEXT NOT NULL, base_url TEXT NOT NULL, credential_mode TEXT NOT NULL,
            remote_id TEXT NOT NULL DEFAULT '', output_url TEXT NOT NULL DEFAULT '',
            local_file TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, request_summary TEXT NOT NULL DEFAULT '',
            parent_id INTEGER NOT NULL DEFAULT 0, plan TEXT NOT NULL DEFAULT '', job_entry INTEGER NOT NULL DEFAULT 0,
            sources TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX entries_mode_id ON entries(mode,id)")
        createDetails(db)
        createMemory(db)
    }
    private fun createMemory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memory (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, position INTEGER NOT NULL)")
    }
    private fun createDetails(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE request_inputs (entry_id INTEGER NOT NULL REFERENCES entries(id), name TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(entry_id,name))")
        db.execSQL("CREATE TABLE media_outputs (entry_id INTEGER NOT NULL REFERENCES entries(id), position INTEGER NOT NULL, url TEXT NOT NULL, local_file TEXT NOT NULL DEFAULT '', PRIMARY KEY(entry_id,position))")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1 || newVersion != 6) error("Unsupported history schema upgrade.")
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN request_summary TEXT NOT NULL DEFAULT ''")
            createDetails(db)
            db.execSQL("INSERT INTO media_outputs(entry_id,position,url,local_file) SELECT id,0,output_url,local_file FROM entries WHERE output_url != ''")
        }
        if (oldVersion < 3) createMemory(db)
        if (oldVersion < 4) db.execSQL("ALTER TABLE entries ADD COLUMN parent_id INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE entries ADD COLUMN plan TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN job_entry INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE entries ADD COLUMN sources TEXT NOT NULL DEFAULT ''")
    }

    private fun unsavedChat(prompt: String, model: String, baseUrl: String): Long {
        val id = nextUnsavedId--
        unsaved.add(Entry(id, Mode.CHAT, prompt, "", "chatting", model, baseUrl, Mode.CHAT, "", "", "", System.currentTimeMillis()))
        return id
    }

    @Synchronized fun insert(mode: Mode, prompt: String, model: ResolvedModel, parentId: Long = 0): Long {
        if (mode == Mode.CHAT && !saveChat) return unsavedChat(prompt, model.settings.model, model.settings.baseUrl)
        val values = ContentValues().apply {
            put("mode", mode.name); put("prompt", prompt); put("state", if (mode == Mode.CHAT) "chatting" else "submitting")
            put("model", model.settings.model); put("base_url", model.settings.baseUrl)
            put("credential_mode", model.credentialMode.name); put("created", System.currentTimeMillis())
            put("request_summary", ModelOptions.summary(model.settings.model, model.settings.options)); put("parent_id", parentId)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("entries", null, values)
            model.settings.options.forEach { input -> db.insertOrThrow("request_inputs", null, ContentValues().apply { put("entry_id", id); put("name", input.name); put("kind", input.kind.name); put("value", input.value) }) }
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    /** A chat turn answered by the on-device model. It has no provider address or key. */
    @Synchronized fun insertLocal(prompt: String, model: String): Long = if (!saveChat) unsavedChat(prompt, model, LOCAL_BASE) else writableDatabase.insertOrThrow("entries", null, ContentValues().apply {
        put("mode", Mode.CHAT.name); put("prompt", prompt); put("state", "chatting"); put("model", model); put("base_url", LOCAL_BASE)
        put("credential_mode", Mode.CHAT.name); put("created", System.currentTimeMillis())
    })

    @Synchronized fun update(id: Long, state: String, response: String? = null, remoteId: String? = null, outputUrl: String? = null, localFile: String? = null, summary: String? = null, plan: String? = null, sources: String? = null) {
        val values = ContentValues().apply {
            put("state", state); summary?.let { put("request_summary", it) }; plan?.let { put("plan", it) }; sources?.let { put("sources", it) }
            response?.let { put("response", it) }; remoteId?.let { put("remote_id", it) }
            outputUrl?.let { put("output_url", it) }; localFile?.let { put("local_file", it) }
        }
        if (id < 0) {
            val i = unsaved.indexOfFirst { it.id == id }
            if (i >= 0) unsaved[i] = unsaved[i].copy(state = state, response = response ?: unsaved[i].response, requestSummary = summary ?: unsaved[i].requestSummary, plan = plan ?: unsaved[i].plan, sources = sources ?: unsaved[i].sources)
            return
        }
        if (writableDatabase.update("entries", values, "id = ?", arrayOf(id.toString())) != 1) throw UserError("Could not persist the request result.")
    }

    @Synchronized fun list(mode: Mode? = null, pendingOnly: Boolean = false): List<Entry> {
        // Like the stored list, at most the newest 100 entries, so callers can rely on that bound.
        if (mode == Mode.CHAT && !pendingOnly && !saveChat) return unsaved.takeLast(100)
        val stored = listStored(mode, pendingOnly)
        return if (pendingOnly) stored + unsaved.filter { it.state == "chatting" } else stored
    }

    private fun listStored(mode: Mode?, pendingOnly: Boolean, where: String? = null, whereArgs: Array<String>? = null): List<Entry> {
        val selection = where ?: if (pendingOnly) "state IN ('submitting','chatting','processing','canceling','download')" else mode?.let { "mode = ?" }
        val args = whereArgs ?: mode?.let { arrayOf(it.name) }
        val columns = arrayOf("id", "mode", "prompt", "response", "state", "model", "base_url", "credential_mode", "remote_id", "output_url", "local_file", "created", "request_summary", "parent_id", "plan", "job_entry", "sources")
        return readableDatabase.query("entries", columns, selection, if (pendingOnly && where == null) null else args, null, null, "id DESC", if (pendingOnly) null else "100").use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), Mode.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), Mode.valueOf(c.getString(7)), c.getString(8), c.getString(9), c.getString(10), c.getLong(11), c.getString(12), c.getLong(13), c.getString(14), c.getLong(15), c.getString(16))) }.reversed()
        }
    }

    @Synchronized fun completePrediction(id: Long, prediction: Prediction) {
        val urls = prediction.outputUrls
        if (urls.isEmpty() || urls.size > 4) throw UserError("Unsupported output count.")
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("media_outputs", "entry_id = ?", arrayOf(id.toString()))
            urls.forEachIndexed { index, url -> db.insertOrThrow("media_outputs", null, ContentValues().apply { put("entry_id", id); put("position", index); put("url", Rules.outputUrl(url)) }) }
            update(id, "download", "Generated. Saving local copies...", Rules.remoteId(prediction.id), urls.first())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun outputs(ids: List<Long>): Map<Long, List<MediaOutput>> {
        if (ids.isEmpty()) return emptyMap()
        require(ids.size <= 100)
        return readableDatabase.query("media_outputs", arrayOf("entry_id", "position", "url", "local_file"), "entry_id IN (${ids.joinToString(",") { "?" }})", ids.map { it.toString() }.toTypedArray(), null, null, "entry_id,position").use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to MediaOutput(c.getInt(1), c.getString(2), c.getString(3))) }.groupBy({ it.first }, { it.second })
        }
    }

    @Synchronized fun savedOutput(id: Long, position: Int, file: String) {
        if (writableDatabase.update("media_outputs", ContentValues().apply { put("local_file", file) }, "entry_id = ? AND position = ?", arrayOf(id.toString(), position.toString())) != 1) throw UserError("Could not save media metadata.")
    }

    @Synchronized fun credentialEntry(id: Long): Entry = readableDatabase.query("entries", arrayOf("mode", "base_url", "credential_mode"), "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
        if (!c.moveToFirst()) throw UserError("This saved request is missing.")
        Entry(id, Mode.valueOf(c.getString(0)), "", "", "", "", c.getString(1), Mode.valueOf(c.getString(2)), "", "", "", 0)
    }

    @Synchronized fun recover() {
        list(pendingOnly = true).forEach { e ->
            val next = Rules.afterProcessRestart(e.state, e.remoteId)
            if (next != e.state) update(e.id, next, if (next == "uncertain") "The app stopped before a job ID was saved. Check your provider dashboard before submitting again." else null)
        }
    }

    /** Links a chat turn's plan to the media entry it started, or -1 when the user canceled the plan. */
    @Synchronized fun setJob(chatId: Long, jobEntry: Long) {
        if (chatId < 0) {
            val i = unsaved.indexOfFirst { it.id == chatId }
            if (i >= 0) unsaved[i] = unsaved[i].copy(jobEntry = jobEntry)
            return
        }
        if (writableDatabase.update("entries", ContentValues().apply { put("job_entry", jobEntry) }, "id = ?", arrayOf(chatId.toString())) != 1) throw UserError("Could not link the job to the chat.")
    }

    /** One saved entry by ID, or null. */
    @Synchronized fun entry(id: Long): Entry? = if (id < 0) unsaved.firstOrNull { it.id == id } else listStored(null, false, "id = ?", arrayOf(id.toString())).firstOrNull()

    /** Forgets the unsaved chat, as if the app had closed. */
    @Synchronized fun dropUnsaved() { unsaved.clear() }

    /** Deletes every saved and unsaved chat turn. Image and video entries stay. */
    @Synchronized fun clearChat() {
        unsaved.clear()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM request_inputs WHERE entry_id IN (SELECT id FROM entries WHERE mode = 'CHAT')")
            db.delete("entries", "mode = ?", arrayOf(Mode.CHAT.name))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Standing instructions, kept in this database as plain text. They are not secrets. */
    val memory: MemoryStore = object : MemoryStore {
        override fun lines(): List<MemoryLine> = synchronized(this@History) {
            readableDatabase.query("memory", arrayOf("id", "text", "enabled"), null, null, null, null, "position, id").use { c ->
                buildList { while (c.moveToNext()) add(MemoryLine(c.getLong(0), c.getString(1), c.getInt(2) != 0)) }
            }
        }
        override fun add(text: String): Long = synchronized(this@History) {
            if (lines().size >= MemoryPrompt.MAX_LINES) throw UserError("Memory holds up to ${MemoryPrompt.MAX_LINES} instructions. Delete one first.")
            val position = readableDatabase.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM memory", null).use { c -> c.moveToFirst(); c.getInt(0) }
            writableDatabase.insertOrThrow("memory", null, ContentValues().apply { put("text", MemoryPrompt.clean(text)); put("enabled", 1); put("position", position) })
        }
        override fun edit(id: Long, text: String) { change(id, ContentValues().apply { put("text", MemoryPrompt.clean(text)) }) }
        override fun setEnabled(id: Long, enabled: Boolean) { change(id, ContentValues().apply { put("enabled", if (enabled) 1 else 0) }) }
        override fun delete(id: Long) { synchronized(this@History) { writableDatabase.delete("memory", "id = ?", arrayOf(id.toString())) } }
        private fun change(id: Long, values: ContentValues) = synchronized(this@History) {
            if (writableDatabase.update("memory", values, "id = ?", arrayOf(id.toString())) != 1) throw UserError("Could not save the memory change.")
        }
    }

    @Synchronized fun turns(): List<Turn> = list(Mode.CHAT).filter { it.state == "succeeded" || (it.state == "stopped" && it.response.isNotBlank()) }
        // A model's earlier thinking never goes back into the conversation. A plan that started a job is followed
        // by a short note on what the app made, so the model knows which pictures exist.
        .flatMap { e ->
            val reply = ReplyText.visible(e.response)
            val job = if (e.jobEntry > 0) entry(e.jobEntry) else null
            val note = job?.let { Planner.note(if (it.mode == Mode.VIDEO) "video" else "picture", it.id, it.state, it.prompt) }
            listOf(Turn("user", e.prompt), Turn("assistant", if (note == null) reply else "$reply\n$note"))
        }
}
