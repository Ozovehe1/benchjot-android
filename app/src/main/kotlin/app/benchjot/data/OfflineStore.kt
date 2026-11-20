package app.benchjot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.benchjot.core.Note
import app.benchjot.core.LocalData
import app.benchjot.core.Cell

/**
 * Where the phone keeps each account's copy (Offline.kt): a row per note and per cell, and one
 * record for the rest (projects, the queue, when it was synced). A change writes only the rows it
 * touched, in one transaction, so saving stays quick however big the account grows and a crash
 * never leaves half a change (the web's local-store.ts does the same in IndexedDB).
 */
class OfflineStore(context: Context) : SQLiteOpenHelper(context, "offline.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table rows (account text not null, kind text not null, id text not null, json text not null, primary key (account, kind, id))")
        db.execSQL("create table meta (account text primary key, json text not null)")
    }

    /**
     * Version 2 (2026-11-20): notes and cells were called entries and snippets before. Rows, their
     * JSON and the queued changes are renamed in place, so nothing kept or waiting to be sent is lost.
     */
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old >= 2) return
        db.execSQL("update rows set kind = case kind when 'entry' then 'note' when 'snippet' then 'cell' else kind end")
        db.execSQL("update rows set json = replace(json, '\"entry_id\":', '\"note_id\":')")
        for ((from, to) in listOf("\"entry_id\":" to "\"note_id\":", "\"entry.create\"" to "\"note.create\"", "\"entry.patch\"" to "\"note.patch\"",
            "\"entry.delete\"" to "\"note.delete\"", "\"snippets.create\"" to "\"cells.create\"", "\"snippet.patch\"" to "\"cell.patch\"",
            "\"snippet.delete\"" to "\"cell.delete\"", "\"entry\":{" to "\"note\":{", "\"entries\":" to "\"notes\":", "\"snippets\":" to "\"cells\":")) {
            db.execSQL("update meta set json = replace(json, ?, ?)", arrayOf(from, to))
        }
    }

    /** An account's copy, or null when the phone has none. */
    fun load(account: String): LocalData? {
        val db = readableDatabase
        val meta = db.rawQuery("select json from meta where account = ?", arrayOf(account)).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        val base = Backend.json.decodeFromString(LocalData.serializer(), meta)
        val notes = HashMap<String, Note>()
        val cells = HashMap<String, Cell>()
        db.rawQuery("select kind, json from rows where account = ?", arrayOf(account)).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    NOTE -> Backend.json.decodeFromString(Note.serializer(), c.getString(1)).let { notes[it.id] = it }
                    CELL -> Backend.json.decodeFromString(Cell.serializer(), c.getString(1)).let { cells[it.id] = it }
                }
            }
        }
        return base.copy(notes = notes, cells = cells)
    }

    /** Keeps what changed from [before] to [after]: rows new, replaced or gone, and the rest when it changed. */
    fun save(account: String, before: LocalData, after: LocalData) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows(db, account, NOTE, before.notes, after.notes) { Backend.json.encodeToString(Note.serializer(), it) }
            rows(db, account, CELL, before.cells, after.cells) { Backend.json.encodeToString(Cell.serializer(), it) }
            val metaChanged = before.projects != after.projects || before.outbox !== after.outbox || before.syncedAt != after.syncedAt ||
                before.username != after.username || (before.notes.isEmpty() && before.cells.isEmpty())
            if (metaChanged) {
                val rest = after.copy(notes = emptyMap(), cells = emptyMap())
                db.insertWithOnConflict("meta", null, ContentValues().apply {
                    put("account", account)
                    put("json", Backend.json.encodeToString(LocalData.serializer(), rest))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> rows(db: SQLiteDatabase, account: String, kind: String, a: Map<String, T>, b: Map<String, T>, encode: (T) -> String) {
        if (a === b) return
        val put = db.compileStatement("insert or replace into rows (account, kind, id, json) values (?, ?, ?, ?)")
        for ((id, row) in b) {
            if (a[id] === row) continue
            put.clearBindings()
            put.bindString(1, account); put.bindString(2, kind); put.bindString(3, id); put.bindString(4, encode(row))
            put.executeInsert()
        }
        val gone = a.keys.filter { it !in b }
        if (gone.isNotEmpty()) {
            val del = db.compileStatement("delete from rows where account = ? and kind = ? and id = ?")
            for (id in gone) { del.clearBindings(); del.bindString(1, account); del.bindString(2, kind); del.bindString(3, id); del.executeUpdateDelete() }
        }
    }

    /** Forgets an account's copy. */
    fun drop(account: String) {
        writableDatabase.apply {
            delete("rows", "account = ?", arrayOf(account))
            delete("meta", "account = ?", arrayOf(account))
        }
    }

    private companion object {
        const val NOTE = "note"
        const val CELL = "cell"
    }
}
