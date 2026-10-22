package com.snaptoprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PhotoTransferService : Service() {
    companion object {
        const val ACTION_START_EVENT = "com.snaptoprint.START_EVENT"
        const val ACTION_FINISH_EVENT = "com.snaptoprint.FINISH_EVENT"
        const val EXTRA_EVENT_NAME = "event_name"
        private const val TAG = "SnapToPrint"
        private const val CHANNEL_ID = "snap_to_print_active"
        private const val NOTIFICATION_ID = 8017
    }

    private lateinit var database: SnapDatabase
    private lateinit var server: PhoneHttpServer
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val stager = Executors.newFixedThreadPool(2)
    private val stagingIds = ConcurrentHashMap.newKeySet<String>()
    private var observer: ContentObserver? = null
    @Volatile private var serverReady = false
    @Volatile private var finishRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val scanQueued = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        database = SnapDatabase(applicationContext)
        createNotificationChannel()
        startInForeground("Ready to receive the Windows connection")
        server = PhoneHttpServer(applicationContext, database) { clientAt ->
            getSharedPreferences("snap_to_print", MODE_PRIVATE).edit().putLong("last_client_seen_ms", clientAt).apply()
        }
        server.start { error ->
            serverReady = error == null
            getSharedPreferences("snap_to_print", MODE_PRIVATE).edit().putString("server_error", error.orEmpty()).apply()
            Log.i(TAG, if (error == null) "Local receiver started on port 8765" else "Receiver error: $error")
            updateNotification()
        }
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { requestScan() }
            override fun onChange(selfChange: Boolean, uri: Uri?) { requestScan() }
        }.also {
            try { contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it) }
            catch (e: Exception) { Log.w(TAG, "Cannot register photo observer", e) }
        }
        finishRequested = database.activeSession() == null && database.pendingCount() > 0
        if (database.activeSession() != null || database.pendingCount() > 0) acquireWakeLock()
        scheduler.scheduleWithFixedDelay({ requestScan() }, 400, 1000, TimeUnit.MILLISECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_EVENT -> {
                val name = intent.getStringExtra(EXTRA_EVENT_NAME)?.trim().orEmpty().ifBlank { "Guest magnets" }
                stager.execute { startEvent(name) }
            }
            ACTION_FINISH_EVENT -> stager.execute { finishEvent() }
            else -> if (database.activeSession() == null && database.pendingCount() == 0) {
                // START_STICKY can restart an idle service after its previous event has completed.
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun startEvent(name: String) {
        if (database.activeSession() != null) return
        acquireWakeLock()
        try {
            val baselineIds = snapshotCameraIds()
            val startedAt = System.currentTimeMillis()
            val session = database.createSession(name.take(80), startedAt, startedAt / 1000L, baselineIds)
            getSharedPreferences("snap_to_print", MODE_PRIVATE).edit().remove("server_error").apply()
            Log.i(TAG, "Event started: ${session.id}; baseline images=${baselineIds.size}")
            updateNotification()
            requestScan()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start event safely", e)
            getSharedPreferences("snap_to_print", MODE_PRIVATE).edit()
                .putString("start_error", "Could not read the camera folder to create a safe event baseline: ${e.message}")
                .apply()
            updateNotification()
            if (database.activeSession() == null && database.pendingCount() == 0) stopSelf()
        }
    }

    private fun finishEvent() {
        val active = database.activeSession() ?: return
        finishRequested = true
        val finished = database.finishSession(active.id)
        Log.i(TAG, "Event finished: ${active.id}; pending=${database.pendingCount(active.id)}")
        updateNotification()
        if (database.pendingCount() == 0) { releaseWakeLock(); stopSelf() } // all confirmed items are already safe in the laptop folder
        else Log.i(TAG, "Keeping receiver alive to drain old queued photos")
        finished?.let { getSharedPreferences("snap_to_print", MODE_PRIVATE).edit().putString("last_finished_session", it.id).apply() }
    }

    private fun requestScan() {
        if (scheduler.isShutdown || !scanQueued.compareAndSet(false, true)) return
        try {
            stager.execute {
                try {
                    scanMediaStore()
                    stagePendingPhotos()
                    updateNotification()
                } catch (e: Exception) { Log.w(TAG, "Reconciliation pass failed; the next pass will retry", e) }
                finally {
                    scanQueued.set(false)
                    if (finishRequested && database.activeSession() == null && database.pendingCount() == 0) {
                        releaseWakeLock()
                        stopSelf()
                    }
                }
            }
        } catch (_: Exception) { scanQueued.set(false) /* service is shutting down */ }
    }

    private fun scanMediaStore() {
        val session = database.activeSession() ?: return
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING
        )
        val threshold = (session.startedAtMs / 1000L - 2L).coerceAtLeast(0L)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(threshold.toString(), "DCIM/Camera/%")
        val rows = ArrayList<MediaRow>()
        contentResolver.query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media.DATE_TAKEN} ASC, ${MediaStore.Images.Media._ID} ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val pendingCol = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                rows += MediaRow(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    displayName = cursor.getString(nameCol) ?: "IMG_$id.jpg",
                    mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "application/octet-stream" else "application/octet-stream",
                    dateTakenMs = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol) else 0L,
                    dateAddedSec = cursor.getLong(addedCol),
                    size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L,
                    isPending = pendingCol >= 0 && !cursor.isNull(pendingCol) && cursor.getInt(pendingCol) != 0
                )
            }
        }
        database.recordScanRows(session, rows)
    }

    private fun snapshotCameraIds(): List<Long> {
        val result = ArrayList<Long>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/Camera/%"),
            null
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) result += cursor.getLong(id)
        } ?: throw IllegalStateException("Android did not allow reading the camera media library")
        return result
    }

    private fun stagePendingPhotos() {
        for (photo in database.photosNeedingStage(12)) {
            if (!stagingIds.add(photo.photoId)) continue
            try {
                stager.execute {
                    try { stageOne(photo) }
                    catch (e: Exception) {
                        database.markStageRetry(photo.photoId, e.message ?: "File not ready")
                        Log.d(TAG, "Photo ${photo.photoId} will be retried: ${e.message}")
                    } finally { stagingIds.remove(photo.photoId) }
                }
            } catch (_: Exception) { stagingIds.remove(photo.photoId) }
        }
    }

    private fun stageOne(photo: QueuedPhoto) {
        val uri = Uri.parse(photo.uri)
        val session = database.sessionById(photo.sessionId) ?: throw IllegalStateException("Event session no longer exists")
        val infoBefore = readCurrentMediaInfo(uri) ?: throw IllegalStateException("Media item disappeared")
        if (infoBefore.pending || infoBefore.size <= 0) throw IllegalStateException("Camera is still saving the photo")
        // Re-check capture metadata: some camera providers fill it in after first publishing the image row.
        if ((infoBefore.dateTakenMs > 0 && infoBefore.dateTakenMs < session.startedAtMs) ||
            (infoBefore.dateTakenMs <= 0 && infoBefore.dateAddedSec < session.startedAtMs / 1000L)) {
            database.markSkipped(photo.photoId, "Capture metadata shows the image predates this event")
            return
        }
        // A brief stability check catches cameras that create the row before finishing the JPEG.
        Thread.sleep(220)
        val infoStable = readCurrentMediaInfo(uri) ?: throw IllegalStateException("Media item disappeared")
        if (infoStable.pending || infoBefore.size != infoStable.size) throw IllegalStateException("Photo size is still changing")
        if ((infoStable.dateTakenMs > 0 && infoStable.dateTakenMs < session.startedAtMs) ||
            (infoStable.dateTakenMs <= 0 && infoStable.dateAddedSec < session.startedAtMs / 1000L)) {
            database.markSkipped(photo.photoId, "Capture metadata shows the image predates this event")
            return
        }

        val ext = photo.displayName.substringAfterLast('.', "jpg").lowercase().take(8).let { if (it.matches(Regex("[a-z0-9]+"))) it else "jpg" }
        val dir = File(filesDir, "photo-queue/${photo.sessionId}")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Cannot create pending-photo storage")
        val finalFile = File(dir, "${photo.photoId}.$ext.pending")
        val partFile = File(dir, "${photo.photoId}.$ext.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(partFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    copied += n
                }
                output.fd.sync()
            }
        } ?: throw IllegalStateException("Cannot open camera image")
        val infoAfter = readCurrentMediaInfo(uri)
        if (infoAfter == null || infoAfter.pending || infoAfter.size != copied || copied != infoStable.size) {
            partFile.delete()
            throw IllegalStateException("Photo changed while it was being staged")
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.delete()
            throw IllegalStateException("Cannot commit staged photo")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        database.markStaged(photo.photoId, finalFile.absolutePath, copied, sha)
    }

    private data class CurrentMediaInfo(val size: Long, val pending: Boolean, val dateTakenMs: Long, val dateAddedSec: Long)

    private fun readCurrentMediaInfo(uri: Uri): CurrentMediaInfo? {
        val cursor = contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
            null, null, null
        ) ?: return null
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
            val sizeCol = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val pendingCol = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            CurrentMediaInfo(
                if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L,
                pendingCol >= 0 && !c.isNull(pendingCol) && c.getInt(pendingCol) != 0,
                if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L,
                if (addedCol >= 0 && !c.isNull(addedCol)) c.getLong(addedCol) else 0L
            )
        }
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val session = database.activeSession()
        val text = if (session == null) {
            val pending = database.pendingCount()
            if (pending > 0) "$pending photo(s) pending delivery" else "Waiting for the next event"
        } else {
            "${session.name}: ${database.pendingCount(session.id)} photo(s) pending"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = android.content.Intent(this, MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Snap-to-Print")
            .setContentText(text.take(180))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Snap-to-Print event transfer", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when Snap-to-Print is monitoring and transferring event photos"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock == null) wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SnapToPrint:EventMonitoring").apply { setReferenceCounted(false) }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (e: Exception) { Log.w(TAG, "Could not acquire a partial wake lock; the OS may defer scans while idle", e) }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        try { observer?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) {}
        try { server.stop() } catch (_: Exception) {}
        releaseWakeLock()
        scheduler.shutdownNow()
        stager.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
