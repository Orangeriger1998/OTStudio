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

    fun key(mode: Mode): String {
        val value = keys.getString(mode.name, null) ?: return ""
        val boundOrigin = keys.getString("${mode.name}.origin", null)
        if (boundOrigin != Rules.origin(get(mode).baseUrl)) {
            throw UserError("This saved key belongs to a different API origin. Re-enter the key for this provider.")
        }
        try {
            val parts = value.split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD("${mode.name}|$boundOrigin".toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { throw UserError("The saved API key cannot be decrypted. Re-enter it in Settings.") }
    }

    fun hasKey(mode: Mode): Boolean = keys.contains(mode.name)

    private fun writeKey(mode: Mode, value: String, origin: String) {
        if (value.isEmpty()) {
            if (!keys.edit().remove(mode.name).remove("${mode.name}.origin").commit()) throw UserError("Could not remove the API key.")
            return
        }
        if (value.length > 4096 || value.any { it.isWhitespace() || it.code !in 33..126 }) throw UserError("The API key contains invalid characters.")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            cipher.updateAAD("${mode.name}|$origin".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            if (!keys.edit().putString(mode.name, record).putString("${mode.name}.origin", origin).commit()) throw UserError("Could not save the API key.")
        } catch (e: UserError) { throw e }
        catch (_: Exception) { throw UserError("Android Keystore could not protect this key. Nothing was saved.") }
    }

    fun resolve(mode: Mode): ResolvedModel = Rules.resolve(mode, ::get, ::key)

    fun keyForEntry(entry: Entry): String {
        if (Rules.origin(entry.baseUrl) != Rules.origin(get(entry.credentialMode).baseUrl)) throw UserError("This job used a different provider. Restore that provider's API URL and key before refreshing it.")
        return key(entry.credentialMode).takeIf { it.isNotBlank() } ?: throw UserError("Restore the ${entry.credentialMode.title.lowercase()} API key to refresh this job.")
    }
}

class History(context: Context) : SQLiteOpenHelper(context, "studio.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT NOT NULL,
            prompt TEXT NOT NULL, response TEXT NOT NULL DEFAULT '', state TEXT NOT NULL,
            model TEXT NOT NULL, base_url TEXT NOT NULL, credential_mode TEXT NOT NULL,
            remote_id TEXT NOT NULL DEFAULT '', output_url TEXT NOT NULL DEFAULT '',
            local_file TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, request_summary TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX entries_mode_id ON entries(mode,id)")
        createDetails(db)
    }
    private fun createDetails(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE request_inputs (entry_id INTEGER NOT NULL REFERENCES entries(id), name TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(entry_id,name))")
        db.execSQL("CREATE TABLE media_outputs (entry_id INTEGER NOT NULL REFERENCES entries(id), position INTEGER NOT NULL, url TEXT NOT NULL, local_file TEXT NOT NULL DEFAULT '', PRIMARY KEY(entry_id,position))")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN request_summary TEXT NOT NULL DEFAULT ''")
            createDetails(db)
            db.execSQL("INSERT INTO media_outputs(entry_id,position,url,local_file) SELECT id,0,output_url,local_file FROM entries WHERE output_url != ''")
        } else error("Unsupported history schema upgrade.")
    }

    @Synchronized fun insert(mode: Mode, prompt: String, model: ResolvedModel): Long {
        val values = ContentValues().apply {
            put("mode", mode.name); put("prompt", prompt); put("state", if (mode == Mode.CHAT) "chatting" else "submitting")
            put("model", model.settings.model); put("base_url", model.settings.baseUrl)
            put("credential_mode", model.credentialMode.name); put("created", System.currentTimeMillis())
            put("request_summary", ModelOptions.summary(model.settings.model, model.settings.options))
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

    @Synchronized fun update(id: Long, state: String, response: String? = null, remoteId: String? = null, outputUrl: String? = null, localFile: String? = null) {
        val values = ContentValues().apply {
            put("state", state)
            response?.let { put("response", it) }; remoteId?.let { put("remote_id", it) }
            outputUrl?.let { put("output_url", it) }; localFile?.let { put("local_file", it) }
        }
        if (writableDatabase.update("entries", values, "id = ?", arrayOf(id.toString())) != 1) throw UserError("Could not persist the request result.")
    }

    @Synchronized fun list(mode: Mode? = null, pendingOnly: Boolean = false): List<Entry> {
        val selection = if (pendingOnly) "state IN ('submitting','chatting','processing','canceling','download')" else mode?.let { "mode = ?" }
        val args = mode?.let { arrayOf(it.name) }
        val columns = arrayOf("id", "mode", "prompt", "response", "state", "model", "base_url", "credential_mode", "remote_id", "output_url", "local_file", "created", "request_summary")
        return readableDatabase.query("entries", columns, selection, if (pendingOnly) null else args, null, null, "id DESC", if (pendingOnly) null else "100").use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), Mode.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), Mode.valueOf(c.getString(7)), c.getString(8), c.getString(9), c.getString(10), c.getLong(11), c.getString(12))) }.reversed()
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

    @Synchronized fun turns(): List<Turn> = list(Mode.CHAT).filter { it.state == "succeeded" }.flatMap { listOf(Turn("user", it.prompt), Turn("assistant", it.response)) }
}
