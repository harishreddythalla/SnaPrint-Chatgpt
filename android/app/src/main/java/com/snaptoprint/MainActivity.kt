package com.snaptoprint

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var database: SnapDatabase
    private lateinit var status: TextView
    private lateinit var eventName: EditText
    private lateinit var startButton: Button
    private lateinit var finishButton: Button
    private lateinit var pairingCode: TextView
    private lateinit var laptopStatus: TextView
    private lateinit var localAddressText: TextView
    private lateinit var copyCodeButton: Button
    private lateinit var resetPairingButton: Button
    private lateinit var deliveredText: TextView
    private lateinit var pendingText: TextView
    private lateinit var warningText: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var eventStateCard: LinearLayout

    private val refreshRunnable = object : Runnable {
        override fun run() { refreshStatus(); mainHandler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = SnapDatabase(applicationContext)
        buildUi()
        PairingStore.pairingCode(this)
        refreshStatus()
        if (!hasImagePermission() || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            requestRequiredPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildUi() {
        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(246, 248, 252)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        rootScroll.addView(root)
        setContentView(rootScroll)

        val title = TextView(this).apply {
            text = "SnapToPrint"
            textSize = 28f
            setTextColor(Color.rgb(20, 38, 66))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title, matchWrap())
        root.addView(TextView(this).apply {
            text = "Your event photos. On your laptop. Automatically."
            textSize = 14f
            setTextColor(Color.rgb(81, 96, 116))
        }, marginParams(top = 4, bottom = 18))

        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(Color.WHITE, 20)
            elevation = dp(2).toFloat()
        }
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.rgb(20, 38, 66)); setTypeface(null, android.graphics.Typeface.BOLD) }
        laptopStatus = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(81, 96, 116)) }
        deliveredText = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(40, 105, 65)) }
        pendingText = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(140, 87, 24)) }
        statusBox.addView(status, marginParams(bottom = 8))
        statusBox.addView(laptopStatus, marginParams(bottom = 4))
        localAddressText = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(100, 112, 130)) }
        statusBox.addView(localAddressText, marginParams(bottom = 6))
        statusBox.addView(deliveredText, marginParams(bottom = 3))
        statusBox.addView(pendingText)
        root.addView(statusBox, marginParams(bottom = 18))

        addLabel(root, "EVENT NAME")
        eventName = EditText(this).apply {
            setSingleLine(true)
            setText("Guest magnets")
            textSize = 16f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.WHITE, 12)
        }
        root.addView(eventName, marginParams(top = 5, bottom = 12))

        startButton = Button(this).apply {
            text = "▶  Start new event"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(37, 99, 235), 14)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onStartEvent() }
        }
        finishButton = Button(this).apply {
            text = "■  Finish current event"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.rgb(185, 28, 28))
            background = roundedBackground(Color.rgb(254, 242, 242), 14)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Finish this event?")
                    .setMessage("New photos will no longer be added to this event. Photos already queued will continue retrying until transferred.")
                    .setNegativeButton("Keep event active", null)
                    .setPositiveButton("Finish event") { _, _ -> onFinishEvent() }
                    .show()
            }
        }
        root.addView(startButton, marginParams(bottom = 8))
        root.addView(finishButton, marginParams(bottom = 20))

        addLabel(root, "EVENT HISTORY")
        historyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBackground(Color.WHITE, 16)
        }
        root.addView(historyBox, marginParams(top = 6, bottom = 18))

        addLabel(root, "LAPTOP PAIRING")
        pairingCode = TextView(this).apply {
            textSize = 30f
            letterSpacing = 0.14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(37, 82, 170))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(15), dp(10), dp(15))
            background = roundedBackground(Color.WHITE, 12)
            text = PairingStore.pairingCode(this@MainActivity)
        }
        root.addView(pairingCode, marginParams(top = 5, bottom = 4))
        copyCodeButton = Button(this).apply {
            text = "Copy pairing code"
            isAllCaps = false
            setOnClickListener {
                if (PairingStore.hasPaired(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "This phone is already paired. Use Reset pairing only when changing laptops.", Toast.LENGTH_LONG).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Snap-to-Print pairing code", PairingStore.pairingCode(this@MainActivity)))
                    Toast.makeText(this@MainActivity, "Pairing code copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(copyCodeButton, marginParams(bottom = 4))
        resetPairingButton = Button(this).apply {
            text = "Reset pairing (change laptop)"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset laptop pairing?")
                    .setMessage("The saved laptop token will stop working. On Windows, close the receiver and run SnapToPrint-Windows-x64.exe -reset-pairing. Then start an event and pair again with the new code.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ ->
                        PairingStore.resetPairing(this@MainActivity)
                        refreshStatus()
                        Toast.makeText(this@MainActivity, "Pairing reset", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        root.addView(resetPairingButton, marginParams(bottom = 14))

        addLabel(root, "QUICK SETUP & TROUBLESHOOTING")
        root.addView(TextView(this).apply {
            text = "1  Turn on your phone's hotspot and connect the laptop to it.\n\n2  Start a new event here. The app snapshots existing photos so old images are not included.\n\n3  Open SnapToPrint on Windows. On first pairing, enter the six-digit code above.\n\n4  Take photos with your normal Camera app. Keep this app's event notification active.\n\nIf the laptop says disconnected: confirm both devices use the same hotspot, keep the event active, reopen the Windows receiver, and wait a few seconds. Pending photos stay queued on the phone and retry automatically. If pairing is broken, use Reset pairing on both devices and pair again. Grant access to all photos, not selected-only access."
            textSize = 14f
            setTextColor(Color.rgb(64, 78, 98))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(Color.WHITE, 12)
        }, marginParams(top = 6, bottom = 12))

        warningText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(150, 70, 30))
            visibility = View.GONE
        }
        root.addView(warningText, marginParams(top = 4))
    }

    private fun onStartEvent() {
        if (!hasImagePermission()) {
            requestRequiredPermissions()
            Toast.makeText(this, "Allow access to all photos, then tap Start Event again", Toast.LENGTH_LONG).show()
            return
        }
        if (database.activeSession() != null) {
            Toast.makeText(this, "An event is already active", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("snap_to_print", MODE_PRIVATE).edit().remove("start_error").apply()
        val intent = Intent(this, PhotoTransferService::class.java).apply {
            action = PhotoTransferService.ACTION_START_EVENT
            putExtra(PhotoTransferService.EXTRA_EVENT_NAME, eventName.text.toString().trim().ifBlank { "Guest magnets" })
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Starting event baseline…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start transfer service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onFinishEvent() {
        val intent = Intent(this, PhotoTransferService::class.java).apply { action = PhotoTransferService.ACTION_FINISH_EVENT }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not finish event: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStatus() {
        if (!::database.isInitialized || !::status.isInitialized) return
        val current = database.activeSession()
        val latest = current ?: database.latestSession()
        historyBox.removeAllViews()
        val sessions = database.recentSessions(8)
        if (sessions.isEmpty()) {
            historyBox.addView(TextView(this).apply { text = "No events yet. Your completed events will appear here."; textSize = 13f; setTextColor(Color.rgb(100, 116, 139)); setPadding(0, dp(8), 0, dp(8)) })
        } else sessions.forEachIndexed { index, session ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(9), dp(4), dp(9)) }
            val heading = TextView(this).apply { text = (if (session.active) "●  " else "○  ") + session.name; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(if (session.active) Color.rgb(22, 163, 74) else Color.rgb(30, 41, 59)) }
            val stats = TextView(this).apply { text = "${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(session.startedAtMs))}  ·  ${database.deliveredCount(session.id)} received  ·  ${database.pendingCount(session.id)} pending"; textSize = 12f; setTextColor(Color.rgb(100, 116, 139)); setPadding(dp(22), dp(3), 0, 0) }
            row.addView(heading); row.addView(stats); historyBox.addView(row)
            if (index < sessions.lastIndex) historyBox.addView(View(this).apply { setBackgroundColor(Color.rgb(241, 245, 249)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        val prefs = getSharedPreferences("snap_to_print", MODE_PRIVATE)
        val lastClient = prefs.getLong("last_client_seen_ms", 0L)
        val now = System.currentTimeMillis()
        val isConnectedRecently = lastClient > 0 && now - lastClient < 12_000
        if (current != null) {
            status.text = "●  EVENT IN PROGRESS"
            startButton.isEnabled = false
            startButton.text = "Event already in progress"
            finishButton.isEnabled = true
            eventName.isEnabled = false
            deliveredText.text = "Photos received by laptop: ${database.deliveredCount(current.id)}"
            pendingText.text = "Photos waiting to transfer: ${database.pendingCount(current.id)}"
            laptopStatus.text = if (isConnectedRecently) "Laptop connected just now" else "Laptop not connected · photos will queue on this phone"
        } else {
            startButton.isEnabled = true
            startButton.text = "▶  Start new event"
            finishButton.isEnabled = false
            eventName.isEnabled = true
            if (latest == null) {
                status.text = "READY TO START"
                deliveredText.text = "Photos received by laptop: 0"
                pendingText.text = "Photos waiting to transfer: ${database.pendingCount()}"
            } else {
                status.text = when (latest.status) {
                    "FINISHED_WITH_PENDING" -> "EVENT FINISHED · TRANSFERS PENDING"
                    "FINISHED" -> "READY FOR NEXT EVENT"
                    else -> "READY TO START"
                }
                deliveredText.text = "Last event photos received: ${database.deliveredCount(latest.id)}"
                pendingText.text = "Photos still waiting: ${database.pendingCount()}"
            }
            laptopStatus.text = if (isConnectedRecently) "Laptop receiver connected" else "Start the event to make the local receiver available"
        }
        val paired = PairingStore.hasPaired(this)
        pairingCode.text = if (paired) "PAIRED" else PairingStore.pairingCode(this)
        val candidates = localIPv4Addresses()
        localAddressText.text = if (candidates.isEmpty()) "Local receiver port: 8765 · Windows will auto-detect the hotspot gateway" else "Local address candidates: " + candidates.joinToString(", ") { "$it:8765" }
        copyCodeButton.isEnabled = !paired
        copyCodeButton.text = if (paired) "Laptop paired" else "Copy pairing code"
        resetPairingButton.visibility = if (paired) View.VISIBLE else View.GONE
        val startError = prefs.getString("start_error", "").orEmpty()
        val serverError = prefs.getString("server_error", "").orEmpty()
        val warning = when {
            startError.isNotBlank() -> startError
            serverError.isNotBlank() -> serverError
            !hasImagePermission() -> "Photo access is required. Allow access to all images, not only selected images."
            else -> ""
        }
        warningText.text = warning
        warningText.visibility = if (warning.isBlank()) View.GONE else View.VISIBLE
        if (current != null && current.status == "ACTIVE") finishButton.text = "■  Finish current event"
    }

    private fun localIPv4Addresses(): List<String> {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .sortedBy { name -> if (name.name.contains("ap", true) || name.name.contains("wlan", true)) 0 else 1 }
            interfaces.flatMap { iface ->
                try {
                    if (!iface.isUp || iface.isLoopback) emptyList()
                    else iface.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>()
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
                        .map { it.hostAddress.orEmpty() }
                } catch (_: Exception) { emptyList() }
            }.filter { it.isNotBlank() }.distinct().take(5)
        } catch (_: Exception) { emptyList() }
    }

    private fun hasImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.READ_MEDIA_IMAGES
        else required += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 34) required += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) required += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(required.distinct().toTypedArray(), 401)
    }

    @Deprecated("Android callback API kept to avoid external dependencies in the first MVP")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 401) {
            refreshStatus()
            if (hasImagePermission()) Toast.makeText(this, "Photo access enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.rgb(81, 96, 116))
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
        }, marginParams(bottom = 2))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun roundedBackground(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), Color.rgb(226, 232, 240))
    }
}
