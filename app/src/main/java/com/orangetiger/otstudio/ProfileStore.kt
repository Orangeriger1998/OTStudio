package com.orangetiger.otstudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ProfileStore(context: Context) : SQLiteOpenHelper(context, "model-profiles.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profiles (mode TEXT NOT NULL, origin TEXT NOT NULL, model TEXT NOT NULL, has_schema INTEGER NOT NULL, PRIMARY KEY(mode,origin,model))")
        db.execSQL("CREATE TABLE inputs (mode TEXT NOT NULL, origin TEXT NOT NULL, model TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(mode,origin,model,name))")
        db.execSQL("CREATE TABLE definitions (mode TEXT NOT NULL, origin TEXT NOT NULL, model TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, default_value TEXT, minimum REAL, maximum REAL, required INTEGER NOT NULL, description TEXT NOT NULL, uri INTEGER NOT NULL, PRIMARY KEY(mode,origin,model,name))")
        db.execSQL("CREATE TABLE choices (mode TEXT NOT NULL, origin TEXT NOT NULL, model TEXT NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY(mode,origin,model,name,position))")
        db.execSQL("CREATE TABLE unsupported (mode TEXT NOT NULL, origin TEXT NOT NULL, model TEXT NOT NULL, name TEXT NOT NULL, required INTEGER NOT NULL, PRIMARY KEY(mode,origin,model,name))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported profile schema upgrade.") }
    private val where = "mode = ? AND origin = ? AND model = ?"
    private fun args(s: ModelSettings) = arrayOf(s.mode.name, Rules.origin(s.baseUrl), s.model)
    private fun values(s: ModelSettings) = ContentValues().apply { put("mode", s.mode.name); put("origin", Rules.origin(s.baseUrl)); put("model", s.model) }

    @Synchronized fun load(base: ModelSettings): ModelSettings? {
        val db = readableDatabase
        val exists = db.query("profiles", arrayOf("has_schema"), where, args(base), null, null, null).use { if (it.moveToFirst()) it.getInt(0) else return null }
        val options = db.query("inputs", arrayOf("name", "kind", "value"), where, args(base), null, null, "name").use { c -> buildList { while (c.moveToNext()) add(InputValue(c.getString(0), InputKind.valueOf(c.getString(1)), c.getString(2))) } }
        val choices = db.query("choices", arrayOf("name", "value"), where, args(base), null, null, "name,position").use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }.groupBy({ it.first }, { it.second })
        val unsupported = db.query("unsupported", arrayOf("name", "required"), where, args(base), null, null, "name").use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to (c.getInt(1) == 1)) } }
        val schema = if (exists == 0) null else db.query("definitions", arrayOf("name", "kind", "default_value", "minimum", "maximum", "required", "description", "uri"), where, args(base), null, null, "position").use { c ->
            InputSchema(buildList { while (c.moveToNext()) add(InputDefinition(c.getString(0), InputKind.valueOf(c.getString(1)), if (c.isNull(2)) null else c.getString(2), choices[c.getString(0)].orEmpty(), if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4), c.getInt(5) == 1, c.getString(6), c.getInt(7) == 1)) }, unsupported.map { it.first }, unsupported.filter { it.second }.map { it.first })
        }
        return base.copy(schema = schema, options = options).also(Rules::validate)
    }

    @Synchronized fun save(s: ModelSettings) {
        Rules.validate(s)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery("SELECT COUNT(*) FROM profiles WHERE mode = ? AND origin = ?", arrayOf(s.mode.name, Rules.origin(s.baseUrl))).use { it.moveToFirst(); it.getInt(0) }
            val known = db.query("profiles", arrayOf("model"), where, args(s), null, null, null).use { it.moveToFirst() }
            if (count >= 128 && !known) throw UserError("This provider already has 128 saved models.")
            db.insertWithOnConflict("profiles", null, values(s).apply { put("has_schema", if (s.schema != null) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE).also { if (it == -1L) throw UserError("Could not save the model.") }
            listOf("inputs", "definitions", "choices", "unsupported").forEach { db.delete(it, where, args(s)) }
            s.options.forEach { v -> db.insertOrThrow("inputs", null, values(s).apply { put("name", v.name); put("kind", v.kind.name); put("value", v.value) }) }
            s.schema?.fields?.forEachIndexed { index, d ->
                db.insertOrThrow("definitions", null, values(s).apply { put("position", index); put("name", d.name); put("kind", d.kind.name); put("default_value", d.defaultValue); put("minimum", d.minimum); put("maximum", d.maximum); put("required", if (d.required) 1 else 0); put("description", d.description); put("uri", if (d.uri) 1 else 0) })
                d.choices.forEachIndexed { position, value -> db.insertOrThrow("choices", null, values(s).apply { put("name", d.name); put("position", position); put("value", value) }) }
            }
            s.schema?.unsupported?.forEach { name -> db.insertOrThrow("unsupported", null, values(s).apply { put("name", name); put("required", if (name in s.schema.unsupportedRequired) 1 else 0) }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun modelIds(mode: Mode, baseUrl: String): List<String> = readableDatabase.query("profiles", arrayOf("model"), "mode = ? AND origin = ?", arrayOf(mode.name, Rules.origin(baseUrl)), null, null, "model").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
}
