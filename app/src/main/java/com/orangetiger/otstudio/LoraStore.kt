package com.orangetiger.otstudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LoraStore(context: Context) : SQLiteOpenHelper(context, "lora-library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE loras (reference TEXT PRIMARY KEY, name TEXT NOT NULL, trigger_word TEXT NOT NULL, scale REAL NOT NULL CHECK(scale BETWEEN -1 AND 3), base_model TEXT NOT NULL CHECK(base_model = 'flux-dev'))") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported LoRA library upgrade.") }
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
}
