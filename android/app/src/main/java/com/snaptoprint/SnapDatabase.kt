package com.snaptoprint

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SnapDatabase(context: Context) : SQLiteOpenHelper(context, "snap_to_print.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                baseline_cutoff_sec INTEGER NOT NULL,
                active INTEGER NOT NULL,
                status TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_sessions_active ON sessions(active, started_at_ms)")
        db.execSQL("CREATE TABLE baseline_ids (session_id TEXT NOT NULL, media_id INTEGER NOT NULL, PRIMARY KEY(session_id, media_id))")
        db.execSQL("""
            CREATE TABLE photos (
                photo_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                media_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                content_uri TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                capture_time_ms INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT NOT NULL DEFAULT '',
                staged_path TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                retry_after_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                UNIQUE(session_id, media_id),
                FOREIGN KEY(session_id) REFERENCES sessions(id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_photos_state ON photos(state, created_at_ms)")
        db.execSQL("CREATE INDEX idx_photos_session ON photos(session_id, sequence_no)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve user data. Explicit migrations should be added before incrementing DB_VERSION.
    }

    fun activeSession(): EventSession? = readableDatabase.rawQuery(
        "SELECT id,name,started_at_ms,active,status,baseline_cutoff_sec FROM sessions WHERE active=1 ORDER BY started_at_ms DESC LIMIT 1", null
    ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    fun latestSession(): EventSession? = readableDatabase.rawQuery(
        "SELECT id,name,started_at_ms,active,status,baseline_cutoff_sec FROM sessions ORDER BY started_at_ms DESC LIMIT 1", null
    ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    fun recentSessions(limit: Int = 8): List<EventSession> = readableDatabase.rawQuery(
        "SELECT id,name,started_at_ms,active,status,baseline_cutoff_sec FROM sessions ORDER BY started_at_ms DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 50).toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }

    fun sessionById(id: String): EventSession? = readableDatabase.rawQuery(
        "SELECT id,name,started_at_ms,active,status,baseline_cutoff_sec FROM sessions WHERE id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    fun createSession(name: String, startedAtMs: Long, baselineCutoffSec: Long, baselineMediaIds: List<Long>): EventSession {
        activeSession()?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", id); put("name", name); put("started_at_ms", startedAtMs)
                put("baseline_cutoff_sec", baselineCutoffSec); put("active", 1); put("status", "ACTIVE")
            }
            db.insertOrThrow("sessions", null, values)
            val insert = db.compileStatement("INSERT OR IGNORE INTO baseline_ids(session_id,media_id) VALUES(?,?)")
            baselineMediaIds.forEach { mediaId -> insert.clearBindings(); insert.bindString(1, id); insert.bindLong(2, mediaId); insert.executeInsert() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return EventSession(id, name, startedAtMs, true, "ACTIVE", baselineCutoffSec)
    }

    fun isBaseline(sessionId: String, mediaId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM baseline_ids WHERE session_id=? AND media_id=? LIMIT 1", arrayOf(sessionId, mediaId.toString())
    ).use { it.moveToFirst() }

    fun hasPhoto(sessionId: String, mediaId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM photos WHERE session_id=? AND media_id=? LIMIT 1", arrayOf(sessionId, mediaId.toString())
    ).use { it.moveToFirst() }

    /** Records all scan results in a transaction. Re-scanning after a crash is safe due to UNIQUE(session_id, media_id). */
    fun recordScanRows(session: EventSession, rows: List<MediaRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val baselineStmt = db.compileStatement("SELECT 1 FROM baseline_ids WHERE session_id=? AND media_id=? LIMIT 1")
            val existsStmt = db.compileStatement("SELECT 1 FROM photos WHERE session_id=? AND media_id=? LIMIT 1")
            val nextSequenceStmt = db.compileStatement("SELECT COALESCE(MAX(sequence_no),0)+1 FROM photos WHERE session_id=?")
            for (row in rows) {
                baselineStmt.clearBindings(); baselineStmt.bindString(1, session.id); baselineStmt.bindLong(2, row.mediaId)
                if (baselineStmt.simpleQueryForLongOrNull() != null) continue
                existsStmt.clearBindings(); existsStmt.bindString(1, session.id); existsStmt.bindLong(2, row.mediaId)
                if (existsStmt.simpleQueryForLongOrNull() != null) continue

                val captureKnown = row.dateTakenMs > 0
                val tooOldByCapture = captureKnown && row.dateTakenMs < session.startedAtMs
                val tooOldByAddedTime = !captureKnown && row.dateAddedSec < session.startedAtMs / 1000L
                val skipped = tooOldByCapture || tooOldByAddedTime
                val seq = if (skipped) 0 else {
                    nextSequenceStmt.clearBindings(); nextSequenceStmt.bindString(1, session.id); nextSequenceStmt.simpleQueryForLong().toInt()
                }
                val photoId = java.util.UUID.randomUUID().toString()
                val state = when {
                    skipped -> "SKIPPED"
                    row.isPending || row.size <= 0 -> "WAITING_FILE"
                    else -> "DISCOVERED"
                }
                val values = ContentValues().apply {
                    put("photo_id", photoId); put("session_id", session.id); put("media_id", row.mediaId)
                    put("sequence_no", seq); put("content_uri", row.uri); put("display_name", row.displayName)
                    put("mime_type", row.mimeType); put("capture_time_ms", row.dateTakenMs); put("state", state)
                    put("created_at_ms", System.currentTimeMillis())
                    put("size_bytes", row.size)
                }
                db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun photosNeedingStage(limit: Int = 8): List<QueuedPhoto> = readableDatabase.rawQuery(
        """SELECT p.photo_id,p.session_id,s.name,s.started_at_ms,p.sequence_no,p.media_id,p.content_uri,p.display_name,p.mime_type,
                  p.capture_time_ms,p.size_bytes,p.sha256,p.staged_path,p.state
           FROM photos p JOIN sessions s ON s.id=p.session_id
           WHERE p.state IN ('DISCOVERED','WAITING_FILE','RETRY_STAGE') AND p.retry_after_ms <= ?
           ORDER BY CASE p.state WHEN 'DISCOVERED' THEN 0 WHEN 'RETRY_STAGE' THEN 1 ELSE 2 END, p.created_at_ms ASC LIMIT ?""".trimIndent(), arrayOf(System.currentTimeMillis().toString(), limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toQueuedPhoto()) } }

    fun markStaged(photoId: String, path: String, size: Long, sha256: String) {
        val v = ContentValues().apply { put("staged_path", path); put("size_bytes", size); put("sha256", sha256); put("state", "QUEUED"); put("last_error", ""); put("attempts", 0); put("retry_after_ms", 0L) }
        writableDatabase.update("photos", v, "photo_id=? AND state!='ACKNOWLEDGED'", arrayOf(photoId))
    }

    fun markStageRetry(photoId: String, message: String) {
        val attempts = readableDatabase.rawQuery("SELECT attempts FROM photos WHERE photo_id=?", arrayOf(photoId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val delayMs = (1000L shl attempts.coerceIn(0, 6)).coerceAtMost(60_000L)
        val values = ContentValues().apply {
            put("state", "RETRY_STAGE")
            put("attempts", attempts + 1)
            put("retry_after_ms", System.currentTimeMillis() + delayMs)
            put("last_error", message.take(500))
        }
        writableDatabase.update("photos", values, "photo_id=? AND state NOT IN ('ACKNOWLEDGED','SKIPPED')", arrayOf(photoId))
    }

    fun markSkipped(photoId: String, message: String) {
        val values = ContentValues().apply { put("state", "SKIPPED"); put("last_error", message.take(500)) }
        writableDatabase.update("photos", values, "photo_id=? AND state NOT IN ('ACKNOWLEDGED','SKIPPED')", arrayOf(photoId))
    }

    fun photoById(photoId: String): QueuedPhoto? = readableDatabase.rawQuery(
        """SELECT p.photo_id,p.session_id,s.name,s.started_at_ms,p.sequence_no,p.media_id,p.content_uri,p.display_name,p.mime_type,
                  p.capture_time_ms,p.size_bytes,p.sha256,p.staged_path,p.state
           FROM photos p JOIN sessions s ON s.id=p.session_id WHERE p.photo_id=? LIMIT 1""".trimIndent(), arrayOf(photoId)
    ).use { c -> if (c.moveToFirst()) c.toQueuedPhoto() else null }

    fun nextQueuedPhoto(): QueuedPhoto? = readableDatabase.rawQuery(
        """SELECT p.photo_id,p.session_id,s.name,s.started_at_ms,p.sequence_no,p.media_id,p.content_uri,p.display_name,p.mime_type,
                  p.capture_time_ms,p.size_bytes,p.sha256,p.staged_path,p.state
           FROM photos p JOIN sessions s ON s.id=p.session_id
           WHERE p.state='QUEUED' ORDER BY p.created_at_ms ASC,p.sequence_no ASC LIMIT 1""".trimIndent(), null
    ).use { c -> if (c.moveToFirst()) c.toQueuedPhoto() else null }

    fun acknowledge(photoId: String, sha256: String, size: Long): Pair<Boolean, String> {
        val photo = photoById(photoId) ?: return false to ""
        if (photo.sha256.isBlank() || !photo.sha256.equals(sha256, true) || photo.size != size) return false to ""
        if (photo.state == "ACKNOWLEDGED") return true to photo.stagedPath
        if (photo.state != "QUEUED") return false to ""
        val values = ContentValues().apply { put("state", "ACKNOWLEDGED") }
        val updated = writableDatabase.update("photos", values, "photo_id=? AND state='QUEUED'", arrayOf(photoId))
        return (updated == 1) to photo.stagedPath
    }

    fun pendingCount(sessionId: String? = null): Int {
        val sql = if (sessionId == null) "SELECT COUNT(*) FROM photos WHERE state IN ('DISCOVERED','WAITING_FILE','RETRY_STAGE','QUEUED')" else "SELECT COUNT(*) FROM photos WHERE session_id=? AND state IN ('DISCOVERED','WAITING_FILE','RETRY_STAGE','QUEUED')"
        val args = if (sessionId == null) null else arrayOf(sessionId)
        return readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun deliveredCount(sessionId: String? = null): Int {
        val sql = if (sessionId == null) "SELECT COUNT(*) FROM photos WHERE state='ACKNOWLEDGED'" else "SELECT COUNT(*) FROM photos WHERE session_id=? AND state='ACKNOWLEDGED'"
        val args = if (sessionId == null) null else arrayOf(sessionId)
        return readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun finishSession(sessionId: String): EventSession? {
        val state = if (pendingCount(sessionId) > 0) "FINISHED_WITH_PENDING" else "FINISHED"
        val values = ContentValues().apply { put("active", 0); put("status", state) }
        writableDatabase.update("sessions", values, "id=?", arrayOf(sessionId))
        return sessionById(sessionId)
    }

    fun statusCounts(): Triple<Int, Int, Int> {
        val pending = pendingCount()
        val delivered = deliveredCount()
        val failed = readableDatabase.rawQuery("SELECT COUNT(*) FROM photos WHERE state='FAILED'", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Triple(delivered, pending, failed)
    }

    private fun android.database.Cursor.toSession() = EventSession(
        getString(0), getString(1), getLong(2), getInt(3) == 1, getString(4), getLong(5)
    )

    private fun android.database.Cursor.toQueuedPhoto() = QueuedPhoto(
        photoId = getString(0), sessionId = getString(1), eventName = getString(2), sessionStartedAtMs = getLong(3),
        sequence = getInt(4), mediaId = getLong(5), uri = getString(6), displayName = getString(7), mimeType = getString(8),
        captureTimeMs = getLong(9), size = getLong(10), sha256 = getString(11), stagedPath = getString(12), state = getString(13)
    )

    private fun android.database.sqlite.SQLiteStatement.simpleQueryForLongOrNull(): Long? = try { simpleQueryForLong() } catch (_: android.database.sqlite.SQLiteDoneException) { null }
}
