package com.snaptoprint

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PhoneHttpServer(
    private val context: Context,
    private val database: SnapDatabase,
    private val onClientSeen: (Long) -> Unit
) {
    companion object {
        private const val TAG = "SnapToPrintHTTP"
        private const val PORT = 8765
        private const val MAX_HEADER_LINE = 8192
        private const val MAX_REQUEST_BODY = 32768
    }

    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val badPairCount = AtomicInteger(0)
    @Volatile private var pairWindowStart = System.currentTimeMillis()
    @Volatile private var serverSocket: ServerSocket? = null
    private val workers = ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32), ThreadPoolExecutor.CallerRunsPolicy())

    fun start(onState: (String?) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", PORT), 32)
                socket.soTimeout = 900
                serverSocket = socket
                onState(null)
                while (!stopping.get()) {
                    try {
                        val client = socket.accept()
                        client.soTimeout = 8000
                        workers.execute { handleClient(client) }
                    } catch (_: SocketTimeoutException) {
                        // Wake periodically so stop() can be observed promptly.
                    } catch (e: SocketException) {
                        if (!stopping.get()) Log.w(TAG, "Socket accept failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot listen on port $PORT", e)
                onState("Cannot start local receiver on port $PORT: ${e.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }, "SnapToPrint-HTTP-Server").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        workers.shutdownNow()
    }

    fun isStopping() = stopping.get()

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val request = readRequest(input) ?: return
                if (request.method !in setOf("GET", "POST")) {
                    respond(output, 405, "application/json", jsonError("method_not_allowed")); return
                }
                if (request.path != "/api/v1/health" && request.path != "/api/v1/pair") {
                    val expected = context.getSharedPreferences("snap_to_print", Context.MODE_PRIVATE).getString("auth_token", "").orEmpty()
                    val provided = request.headers["x-snap-token"].orEmpty()
                    if (expected.isBlank() || !constantTimeEquals(expected, provided)) {
                        respond(output, 401, "application/json", jsonError("unauthorized")); return
                    }
                }
                onClientSeen(System.currentTimeMillis())
                route(request, output)
            } catch (e: Exception) {
                Log.d(TAG, "Request ended with an error: ${e.message}")
                try { s.getOutputStream().write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) } catch (_: Exception) {}
            }
        }
    }

    private fun route(req: Request, out: BufferedOutputStream) {
        when {
            req.method == "GET" && req.path == "/api/v1/health" -> {
                respond(out, 200, "application/json", JSONObject().put("ok", true).put("service", "snap-to-print").put("version", 1).toString().toByteArray())
            }
            req.method == "POST" && req.path == "/api/v1/pair" -> pair(req, out)
            req.method == "GET" && req.path == "/api/v1/session/current" -> {
                val session = database.latestSession()
                val result = session?.toJson() ?: JSONObject().put("sessionId", "").put("name", "").put("startedAtMs", 0).put("active", false).put("status", "IDLE")
                respond(out, 200, "application/json", result.toString().toByteArray())
            }
            req.method == "GET" && req.path == "/api/v1/queue/next" -> {
                val photo = database.nextQueuedPhoto()
                val result = if (photo == null) JSONObject().put("empty", true) else JSONObject().put("empty", false).put("photo", photo.metadataJson())
                respond(out, 200, "application/json", result.toString().toByteArray())
            }
            req.method == "GET" && req.path.startsWith("/api/v1/photos/") && req.path.endsWith("/content") -> serveContent(req.path, out)
            req.method == "POST" && req.path.startsWith("/api/v1/photos/") && req.path.endsWith("/ack") -> acknowledge(req, out)
            req.method == "POST" && req.path.startsWith("/api/v1/photos/") && req.path.endsWith("/nack") -> {
                val id = req.path.removePrefix("/api/v1/photos/").removeSuffix("/nack").trim('/')
                database.markStageRetry(id, "Receiver requested retry")
                respond(out, 200, "application/json", JSONObject().put("accepted", true).toString().toByteArray())
            }
            else -> respond(out, 404, "application/json", jsonError("not_found"))
        }
    }

    private fun pair(req: Request, out: BufferedOutputStream) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - pairWindowStart > 60_000L) { pairWindowStart = now; badPairCount.set(0) }
            if (badPairCount.get() >= 10) { respond(out, 429, "application/json", jsonError("too_many_pair_attempts")); return }
        }
        val body = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (_: Exception) {
            respond(out, 400, "application/json", jsonError("invalid_json")); return
        }
        val supplied = body.optString("code", "")
        val token = PairingStore.pair(context, supplied)
        if (token == null) {
            badPairCount.incrementAndGet()
            respond(out, 403, "application/json", jsonError("invalid_pairing_code")); return
        }
        context.getSharedPreferences("snap_to_print", Context.MODE_PRIVATE).edit().putString("auth_token", token).apply()
        respond(out, 200, "application/json", JSONObject().put("token", token).put("protocolVersion", 1).toString().toByteArray())
    }

    private fun serveContent(path: String, out: BufferedOutputStream) {
        val id = path.removePrefix("/api/v1/photos/").removeSuffix("/content").trim('/')
        val photo = database.photoById(id)
        if (photo == null || photo.state != "QUEUED") {
            respond(out, 404, "application/json", jsonError("photo_not_queued")); return
        }
        val file = File(photo.stagedPath)
        if (!file.isFile || file.length() != photo.size || photo.sha256.isBlank()) {
            respond(out, 409, "application/json", jsonError("staged_file_not_ready")); return
        }
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nX-Photo-Sha256: ${photo.sha256}\r\nX-Photo-Size: ${file.length()}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        FileInputStream(file).use { input -> input.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun acknowledge(req: Request, out: BufferedOutputStream) {
        val id = req.path.removePrefix("/api/v1/photos/").removeSuffix("/ack").trim('/')
        val payload = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (_: Exception) {
            respond(out, 400, "application/json", jsonError("invalid_json")); return
        }
        val sha = payload.optString("sha256", "")
        val size = payload.optLong("size", -1)
        val (ok, stagedPath) = database.acknowledge(id, sha, size)
        if (!ok) { respond(out, 409, "application/json", jsonError("verification_mismatch_or_not_queued")); return }
        if (stagedPath.isNotBlank()) {
            try { File(stagedPath).delete() } catch (_: Exception) {}
        }
        respond(out, 200, "application/json", JSONObject().put("accepted", true).put("photoId", id).toString().toByteArray())
    }

    private fun readRequest(input: InputStream): Request? {
        val first = readAsciiLine(input) ?: return null
        if (first.isBlank()) return null
        val parts = first.split(' ')
        if (parts.size < 2) throw IllegalArgumentException("Invalid request line")
        val headers = linkedMapOf<String, String>()
        var totalHeaderChars = first.length
        while (true) {
            val line = readAsciiLine(input) ?: throw IllegalArgumentException("Unexpected EOF in headers")
            totalHeaderChars += line.length
            if (totalHeaderChars > 32 * 1024) throw IllegalArgumentException("Headers too large")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_REQUEST_BODY) throw IllegalArgumentException("Request body too large")
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(body, offset, length - offset)
            if (n < 0) throw IllegalArgumentException("Unexpected EOF in request body")
            offset += n
        }
        val target = parts[1].substringBefore('?')
        return Request(parts[0].uppercase(), target, headers, body)
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var seenAny = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (!seenAny && bytes.size() == 0) null else String(bytes.toByteArray(), Charsets.US_ASCII)
            seenAny = true
            if (b == '\n'.code) break
            if (bytes.size() >= MAX_HEADER_LINE) throw IllegalArgumentException("HTTP line too long")
            if (b != '\r'.code) bytes.write(b)
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    private fun respond(out: BufferedOutputStream, status: Int, contentType: String, body: ByteArray) {
        val phrase = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Internal Server Error" }
        val head = "HTTP/1.1 $status $phrase\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun jsonError(code: String) = JSONObject().put("error", code).toString().toByteArray(Charsets.UTF_8)

    private fun constantTimeEquals(expected: String, supplied: String): Boolean {
        if (expected.length != supplied.length) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
    }
}
