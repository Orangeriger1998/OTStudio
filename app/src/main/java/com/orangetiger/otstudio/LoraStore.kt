package com.orangetiger.otstudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The LoRA library and LoRA trainings. Version 2 (1.9.0) adds Qwen Image LoRAs and the trainings table; the upgrade
 * copies every saved FLUX dev LoRA across unchanged.
 */
class LoraStore(context: Context) : SQLiteOpenHelper(context, "lora-library.db", null, 2) {
    private fun createLoras(db: SQLiteDatabase) = db.execSQL("CREATE TABLE loras (reference TEXT PRIMARY KEY, name TEXT NOT NULL, trigger_word TEXT NOT NULL, scale REAL NOT NULL CHECK(scale BETWEEN -1 AND 3), base_model TEXT NOT NULL CHECK(base_model IN ('flux-dev', 'qwen-image')))")
    private fun createTrainings(db: SQLiteDatabase) = db.execSQL("CREATE TABLE trainings (id TEXT PRIMARY KEY, data TEXT NOT NULL, created INTEGER NOT NULL)")
    override fun onCreate(db: SQLiteDatabase) { createLoras(db); createTrainings(db) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != 1 || newVersion != 2) error("Unsupported LoRA library upgrade.")
        // Android runs this inside one transaction, so a failure leaves version 1 as it was.
        db.execSQL("ALTER TABLE loras RENAME TO loras_v1")
        createLoras(db)
        db.execSQL("INSERT INTO loras (reference, name, trigger_word, scale, base_model) SELECT reference, name, trigger_word, scale, base_model FROM loras_v1")
        db.execSQL("DROP TABLE loras_v1")
        createTrainings(db)
    }

    @Synchronized fun list(): List<SavedLora> = readableDatabase.query("loras", arrayOf("name", "reference", "trigger_word", "scale", "base_model"), null, null, null, null, "name,reference").use { c ->
        buildList { while (c.moveToNext()) add(SavedLora(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getString(4))) }
    }
    @Synchronized fun save(item: SavedLora) {
        LoraCodec.validate(item)
        val existing = list()
        if (existing.size >= 100 && existing.none { it.reference == item.reference }) throw UserError("The library is limited to 100 LoRAs.")
        writableDatabase.insertWithOnConflict("loras", null, ContentValues().apply {
            put("name", item.name); put("reference", item.reference); put("trigger_word", item.trigger); put("scale", item.scale); put("base_model", item.baseModel)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { if (it < 0) throw UserError("Could not save the LoRA library entry.") }
    }
    /** Removes a LoRA from this library only. Its model and weights at the provider stay. */
    @Synchronized fun remove(reference: String) { writableDatabase.delete("loras", "reference = ?", arrayOf(reference)) }

    @Synchronized fun trainings(): List<LoraTrainingRecord> = readableDatabase.query("trainings", arrayOf("data"), null, null, null, null, "created DESC").use { c ->
        buildList { while (c.moveToNext()) LoraTraining.fromJson(c.getString(0))?.let(::add) }
    }
    @Synchronized fun saveTraining(r: LoraTrainingRecord) {
        writableDatabase.insertWithOnConflict("trainings", null, ContentValues().apply { put("id", r.id); put("data", LoraTraining.toJson(r)); put("created", r.created) }, SQLiteDatabase.CONFLICT_REPLACE)
            .also { if (it < 0) throw UserError("Could not save the training.") }
    }
    @Synchronized fun removeTraining(id: String) { writableDatabase.delete("trainings", "id = ?", arrayOf(id)) }
    /** On start: see LoraTraining.afterRestart. */
    @Synchronized fun recoverTrainings() { trainings().forEach { t -> LoraTraining.afterRestart(t).let { if (it != t) saveTraining(it) } } }
}
