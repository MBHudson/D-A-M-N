package com.damn.app.server

import android.util.Log
import com.damn.app.util.Favicon
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight HTTP file server mimicking `php -S host:port -t docroot`.
 */
class PhpFileServer(
    private val docRoot: File,
    private val port: Int,
    private val phpEngine: PhpEngine = SimplePhpEngine(),
    private val password: String? = null,
    private val onActivity: () -> Unit = {},
    private val onLog: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        // Bind to all interfaces (IPv4 and IPv6)
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(java.net.Inet6Address.getByName("::"), port), 50)
            }
        } catch (e: Exception) {
            // Fallback if IPv6 binding is not supported
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port), 50)
            }
        }
        running.set(true)
        log("DAMN server listening on [::]:$port docRoot=${docRoot.absolutePath}")
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val s = serverSocket?.accept() ?: break
                    pool.execute { handleClient(s) }
                } catch (e: Exception) {
                    if (running.get()) log("accept error: ${e.message}")
                }
            }
        }, "DAMN-Accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        try { acceptThread?.interrupt() } catch (_: Exception) {}
        log("server stopped")
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 8000
                val input = s.getInputStream()
                val out = s.getOutputStream()
                val requestLine = readHttpLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) { sendError(out, 400, "Bad Request"); return }
                val method = parts[0].uppercase()
                if (method !in setOf("GET", "HEAD", "POST", "OPTIONS")) { sendError(out, 405, "Method Not Allowed"); return }
                val query = parts[1].substringAfter("?", "")
                val rawPath = parts[1].substringBefore("?")
                val decodedPath = URLDecoder.decode(rawPath, "UTF-8")
                
                // read headers (raw bytes to avoid BufferedReader body buffering issues)
                var authHeader: String? = null
                var contentLength = 0
                var contentType: String? = null
                while (true) {
                    val line = readHttpLine(input) ?: break
                    if (line.isEmpty()) break
                    when {
                        line.startsWith("Authorization:", ignoreCase = true) -> authHeader = line.substringAfter(":").trim()
                        line.startsWith("Content-Length:", ignoreCase = true) -> contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        line.startsWith("Content-Type:", ignoreCase = true) -> contentType = line.substringAfter(":").trim()
                    }
                }
                // CORS preflight
                if (method == "OPTIONS") {
                    val w = out.bufferedWriter(Charsets.UTF_8)
                    w.write("HTTP/1.1 204 No Content\r\n")
                    w.write("Access-Control-Allow-Origin: *\r\n")
                    w.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    w.write("Access-Control-Allow-Headers: Content-Type\r\n")
                    w.write("Content-Length: 0\r\n")
                    w.write("Connection: close\r\n")
                    w.write("\r\n")
                    w.flush()
                    return
                }
                // read POST body as raw bytes (fixes UTF-8 / buffering / bad-json issues)
                var bodyBytes = ByteArray(0)
                if (method == "POST" && contentLength > 0) {
                    bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val r = input.read(bodyBytes, offset, contentLength - offset)
                        if (r == -1) break
                        offset += r
                    }
                    if (offset < contentLength) bodyBytes = bodyBytes.copyOf(offset)
                    // Log truncated bodies for debugging; still try to parse what we got
                    if (offset == 0) log("POST $rawPath body empty (expected $contentLength)")
                } else if (method == "POST" && contentLength == 0) {
                    // Some clients (sendBeacon) may use chunked or no length — try available
                    val avail = input.available()
                    if (avail > 0) {
                        val tmp = ByteArray(avail.coerceAtMost(8192))
                        val r = input.read(tmp)
                        if (r > 0) bodyBytes = tmp.copyOf(r)
                    }
                }
                
                // Notify activity
                onActivity()

                // Password Check (Basic Auth) - Username ignored
                if (!password.isNullOrBlank()) {
                    if (authHeader == null || !authHeader.startsWith("Basic ", ignoreCase = true)) {
                        sendUnauthorized(out)
                        return
                    }
                    val base64Credentials = authHeader.substringAfter("Basic ").trim()
                    val credentials = String(android.util.Base64.decode(base64Credentials, android.util.Base64.NO_WRAP))
                    // format is username:password. We take everything after the first ':' or the whole thing if no ':'
                    val providedPassword = if (credentials.contains(":")) {
                        credentials.substringAfter(":")
                    } else {
                        credentials
                    }

                    if (providedPassword != password) {
                        sendUnauthorized(out)
                        log("$method $rawPath -> 401 Unauthorized")
                        return
                    }
                }
                val sanitized = File(docRoot, decodedPath).canonicalFile
                if (!sanitized.canonicalPath.startsWith(docRoot.canonicalPath)) {
                    sendError(out, 403, "Forbidden"); log("$method $rawPath -> 403"); return
                }

                // Embedded favicon
                if (decodedPath == "/favicon.ico" || decodedPath == "/favicon.png") {
                    val bytes = android.util.Base64.decode(Favicon.PNG_BASE64, android.util.Base64.NO_WRAP)
                    sendResponse(out, 200, "OK", "image/png", bytes, method == "HEAD")
                    log("$method $rawPath -> 200 favicon")
                    return
                }

                // Native WebRTC signaling (bypasses PHP engine, works for POST)
                if (decodedPath.endsWith("/signal.php") || decodedPath == "/signal.php") {
                    handleSignal(out, method, decodedPath, bodyBytes, query)
                    return
                }

                // resolve file
                var target = sanitized
                if (target.isDirectory) {
                    val indexPhp = File(target, "index.php")
                    val indexHtml = File(target, "index.html")
                    when {
                        indexPhp.exists() -> target = indexPhp
                        indexHtml.exists() -> target = indexHtml
                        else -> {
                            val html = directoryListing(decodedPath, target)
                            sendResponse(out, 200, "OK", "text/html; charset=utf-8", html.toByteArray(), method == "HEAD")
                            log("$method $rawPath -> 200 dir-listing")
                            return
                        }
                    }
                }
                
                if (!target.exists() || !target.isFile) {
                    sendError(out, 404, "Not Found"); log("$method $rawPath -> 404"); return
                }

                // Forced download if ?download=1
                val forceDownload = query.contains("download=1")
                
                if (target.extension.lowercase() == "php" && !forceDownload) {
                    val rendered = phpEngine.render(target, docRoot)
                    val bytes = rendered.toByteArray(Charsets.UTF_8)
                    sendResponse(out, 200, "OK", "text/html; charset=utf-8", bytes, method == "HEAD")
                    log("$method $rawPath -> 200 php-rendered ${bytes.size}b")
                } else {
                    val mime = if (forceDownload) "application/octet-stream" else mimeFor(target.name)
                    val len = target.length()
                    sendFile(out, 200, "OK", mime, target, len, method == "HEAD", forceDownload)
                    log("$method $rawPath -> 200 $mime ${len}b")
                }
            } catch (e: Exception) {
                Log.w("DAMN-Server", "handle error", e)
                try { sendError(s.getOutputStream(), 500, "Internal Error") } catch (_: Exception) {}
            }
        }
    }

    private fun directoryListing(requestPath: String, dir: File): String {
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val parentLink = if (requestPath != "/" && requestPath.isNotEmpty()) {
            val parent = requestPath.trimEnd('/').substringBeforeLast('/', "")
            """<tr><td>📁</td><td><a href="$parent/">../ (parent)</a></td><td>-</td><td></td></tr>"""
        } else ""
        
        val rows = files.joinToString("\n") { f ->
            val href = (requestPath.trimEnd('/') + "/" + f.name).replace("//", "/")
            val icon = if (f.isDirectory) "📁" else iconFor(f.name)
            val size = if (f.isDirectory) "-" else humanSize(f.length())
            val dlBtn = if (!f.isDirectory) {
                """<a href="$href?download=1" title="Download" class="dl-icon">📥</a>"""
            } else ""
            """<tr><td>$icon</td><td><a href="$href${if (f.isDirectory) "/" else ""}">${f.name}${if (f.isDirectory) "/" else ""}</a></td><td>$size</td><td>$dlBtn</td></tr>"""
        }
        
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="icon" type="image/png" href="/favicon.png">
            <title>D·A·M·N – Index of $requestPath</title>
            <style>
                body{font-family:system-ui, sans-serif; background:#0B1220; color:#E2E8F0; margin:0; padding:24px}
                h1{font-size:24px; color:#38BDF8; display:flex; align-items:center} 
                a{color:#38BDF8; text-decoration:none} a:hover{text-decoration:underline}
                table{width:100%; border-collapse:collapse; margin-top:16px} 
                th{ text-align:left; color:#94A3B8; border-bottom:1px solid #1E293B; padding:12px 8px}
                td{padding:12px 8px; border-bottom:1px solid #151E33} 
                .badge{background:#1E293B; color:#94A3B8; padding:4px 8px; border-radius:6px; font-size:12px; margin-left:12px}
                .dl-icon{font-size:18px; opacity:0.6; transition:opacity 0.2s} .dl-icon:hover{opacity:1}
                .btn-all{display:inline-block; margin-top:32px; background:#38BDF8; color:#0B1220; padding:10px 20px; border-radius:8px; font-weight:bold; cursor:pointer}
                .btn-all:hover{background:#7DD3FC}
            </style>
            </head><body>
            <h1>📂 D·A·M·N <span class="badge">Drop Any Media Now</span></h1>
            <p>Index of <code>$requestPath</code> — ${files.size} items — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}</p>
            <table><tr><th></th><th>Name</th><th>Size</th><th>Action</th></tr>
            $parentLink
            $rows
            </table>
            <div id="footer">
                <a href="#" onclick="downloadAll()" class="btn-all">Download All (Sequential)</a>
                <p style="margin-top:24px; color:#475569; font-size:12px">By: MBHudson • Served by Android • ${docRoot.name}</p>
            </div>
            <script>
                function downloadAll() {
                    const links = Array.from(document.querySelectorAll('a.dl-icon'));
                    let delay = 0;
                    links.forEach(link => {
                        setTimeout(() => {
                            const a = document.createElement('a');
                            a.href = link.href;
                            a.download = '';
                            document.body.appendChild(a);
                            a.click();
                            document.body.removeChild(a);
                        }, delay);
                        delay += 500;
                    });
                }
            </script>
            </body></html>
        """.trimIndent()
    }

    private fun sendResponse(out: OutputStream, code: Int, msg: String, contentType: String, body: ByteArray, headOnly: Boolean) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("HTTP/1.1 $code $msg\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${body.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Server: DAMN-PHP/3.0\r\n")
        writer.write("Date: ${httpDate()}\r\n")
        writer.write("\r\n")
        writer.flush()
        if (!headOnly) {
            out.write(body)
        }
        out.flush()
    }

    private fun sendFile(out: OutputStream, code: Int, msg: String, mime: String, file: File, len: Long, headOnly: Boolean, forceDownload: Boolean) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("HTTP/1.1 $code $msg\r\n")
        writer.write("Content-Type: $mime\r\n")
        writer.write("Content-Length: $len\r\n")
        if (forceDownload) {
            writer.write("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
        }
        writer.write("Accept-Ranges: bytes\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Server: DAMN-PHP/3.0\r\n")
        writer.write("Date: ${httpDate()}\r\n")
        writer.write("\r\n")
        writer.flush()
        if (!headOnly) {
            FileInputStream(file).use { it.copyTo(out) }
        }
        out.flush()
    }

    private fun sendUnauthorized(out: OutputStream) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("HTTP/1.1 401 Unauthorized\r\n")
        writer.write("WWW-Authenticate: Basic realm=\"D·A·M·N Protected Area\"\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()
        out.flush()
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        val html = "<html><head><link rel=\"icon\" type=\"image/png\" href=\"/favicon.png\"></head><body><h1>$code $msg</h1><p>DAMN Server</p></body></html>"
        sendResponse(out, code, msg, "text/html", html.toByteArray(), false)
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "txt" -> "text/plain; charset=utf-8"
        "php" -> "text/html; charset=utf-8"
        "xml" -> "application/xml"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun iconFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "php" -> "🐘"
        "html", "htm" -> "🌐"
        "jpg", "jpeg", "png", "gif", "webp", "svg" -> "🖼️"
        "mp4", "mkv", "webm" -> "🎬"
        "mp3", "wav", "ogg" -> "🎵"
        "pdf" -> "📄"
        "zip", "rar", "7z" -> "📦"
        else -> "📄"
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun readHttpLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = try { input.read() } catch (_: Exception) { return if (sb.isEmpty()) null else sb.toString() }
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun httpDate(): String {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(Date())
    }

    private fun log(msg: String) {
        Log.i("DAMN-Server", msg)
        onLog.invoke("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $msg")
    }

    // ---- WebRTC signaling (native Kotlin, no PHP needed) ----
    private val signalLock = Any()
    private fun roomsDir(): File {
        val base = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "damn_rooms")
        if (!base.exists()) base.mkdirs()
        return base
    }
    private fun roomFile(room: String): File {
        val safe = room.replace(Regex("[^a-zA-Z0-9_-]"), "").take(32).ifEmpty { "default" }
        return File(roomsDir(), "$safe.json")
    }
    private fun handleSignal(out: OutputStream, method: String, path: String, body: ByteArray, query: String) {
        try {
            val corsHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "Content-Type"
            )
            if (method == "GET" && query.contains("action=poll")) {
                // Allow GET poll for fallback; treat query as body
                val params = query.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("=", ""), "UTF-8") }
                val room = params["room"] ?: ""
                val peerId = params["peerId"] ?: ""
                val res = pollRoom(room, peerId)
                sendJson(out, 200, res, corsHeaders)
                log("GET $path?${query} -> 200 signal poll")
                return
            }
            if (method != "POST") { sendJson(out, 405, JSONObject().put("error", "POST required"), corsHeaders); return }
            val bodyStr = body.toString(Charsets.UTF_8).trim()
            if (bodyStr.isEmpty()) { sendJson(out, 400, JSONObject().put("error", "empty body (got ${body.size} bytes)"), corsHeaders); log("signal empty body len=${body.size} path=$path"); return }
            val req = try { JSONObject(bodyStr) } catch (e: Exception) {
                val preview = bodyStr.take(300).replace("\n","\\n")
                log("signal invalid json: $preview")
                sendJson(out, 400, JSONObject().put("error", "invalid json: ${e.message}"), corsHeaders); return
            }
            val action = req.optString("action", "")
            val room = req.optString("room", "").trim()
            if (room.isEmpty() || room.length > 32) { sendJson(out, 400, JSONObject().put("error", "invalid room"), corsHeaders); return }
            val res = when (action) {
                "join" -> {
                    val peerId = req.optString("peerId", "")
                    val name = req.optString("name", "Guest").take(32)
                    if (peerId.isEmpty()) JSONObject().put("error", "peerId required")
                    else joinRoom(room, peerId, name)
                }
                "poll" -> {
                    val peerId = req.optString("peerId", "")
                    pollRoom(room, peerId)
                }
                "signal" -> {
                    val from = req.optString("from", "")
                    val to = req.optString("to", "")
                    val type = req.optString("type", "")
                    val data = req.opt("data")
                    if (from.isEmpty() || to.isEmpty() || type !in setOf("offer", "answer", "candidate")) JSONObject().put("error", "invalid signal")
                    else pushSignal(room, from, to, type, data)
                }
                "leave" -> {
                    val peerId = req.optString("peerId", "")
                    leaveRoom(room, peerId)
                }
                else -> JSONObject().put("error", "unknown action")
            }
            val code = if (res.has("error")) 400 else 200
            sendJson(out, code, res, corsHeaders)
            log("POST $path action=$action room=$room -> $code")
        } catch (e: Exception) {
            Log.w("DAMN-Signal", "signal error", e)
            try { sendJson(out, 500, JSONObject().put("error", e.message ?: "internal"), mapOf("Access-Control-Allow-Origin" to "*")) } catch (_: Exception) {}
        }
    }
    private fun joinRoom(room: String, peerId: String, name: String): JSONObject {
        synchronized(signalLock) {
            val f = roomFile(room)
            val now = System.currentTimeMillis()
            val obj = if (f.exists()) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
            val peers = obj.optJSONArray("peers") ?: JSONArray()
            val queues = obj.optJSONObject("queues") ?: JSONObject()
            // GC old peers (>90s without poll)
            val kept = JSONArray()
            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                if (now - p.optLong("seen", now) < 90_000 || p.optString("id") == peerId) kept.put(p)
                else queues.remove(p.optString("id"))
            }
            // upsert
            var found = false
            for (i in 0 until kept.length()) {
                val p = kept.getJSONObject(i)
                if (p.optString("id") == peerId) { p.put("name", name); p.put("seen", now); found = true; break }
            }
            if (!found) kept.put(JSONObject().put("id", peerId).put("name", name).put("seen", now))
            if (!queues.has(peerId)) queues.put(peerId, JSONArray())
            obj.put("peers", kept)
            obj.put("queues", queues)
            obj.put("updated", now)
            f.writeText(obj.toString())
            // return peers excluding self
            val others = JSONArray()
            for (i in 0 until kept.length()) {
                val p = kept.getJSONObject(i)
                if (p.optString("id") != peerId) others.put(JSONObject().put("id", p.optString("id")).put("name", p.optString("name")))
            }
            return JSONObject().put("peers", others).put("you", peerId)
        }
    }
    private fun pollRoom(room: String, peerId: String): JSONObject {
        synchronized(signalLock) {
            val f = roomFile(room)
            if (!f.exists()) return JSONObject().put("signals", JSONArray()).put("peers", JSONArray())
            val now = System.currentTimeMillis()
            val obj = try { JSONObject(f.readText()) } catch (_: Exception) { return JSONObject().put("signals", JSONArray()) }
            val peers = obj.optJSONArray("peers") ?: JSONArray()
            val queues = obj.optJSONObject("queues") ?: JSONObject()
            // update seen
            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                if (p.optString("id") == peerId) { p.put("seen", now); break }
            }
            // GC
            val kept = JSONArray()
            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                if (now - p.optLong("seen", now) < 90_000) kept.put(p) else queues.remove(p.optString("id"))
            }
            obj.put("peers", kept)
            val signals = queues.optJSONArray(peerId) ?: JSONArray()
            queues.put(peerId, JSONArray())
            obj.put("queues", queues)
            obj.put("updated", now)
            f.writeText(obj.toString())
            // peers list for UI
            val others = JSONArray()
            for (i in 0 until kept.length()) {
                val p = kept.getJSONObject(i)
                if (p.optString("id") != peerId) others.put(JSONObject().put("id", p.optString("id")).put("name", p.optString("name")))
            }
            // auto-delete empty room after 5 min idle
            if (kept.length() == 0 && now - obj.optLong("updated", now) > 300_000) try { f.delete() } catch (_: Exception) {}
            return JSONObject().put("signals", signals).put("peers", others)
        }
    }
    private fun pushSignal(room: String, from: String, to: String, type: String, data: Any?): JSONObject {
        synchronized(signalLock) {
            val f = roomFile(room)
            if (!f.exists()) return JSONObject().put("error", "room not found")
            val obj = try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
            val queues = obj.optJSONObject("queues") ?: JSONObject().also { obj.put("queues", it) }
            val q = queues.optJSONArray(to) ?: JSONArray().also { queues.put(to, it) }
            val sig = JSONObject().put("from", from).put("type", type).put("data", data ?: JSONObject.NULL).put("ts", System.currentTimeMillis())
            q.put(sig)
            obj.put("updated", System.currentTimeMillis())
            f.writeText(obj.toString())
            return JSONObject().put("ok", true)
        }
    }
    private fun leaveRoom(room: String, peerId: String): JSONObject {
        synchronized(signalLock) {
            val f = roomFile(room)
            if (!f.exists()) return JSONObject().put("ok", true)
            val obj = try { JSONObject(f.readText()) } catch (_: Exception) { return JSONObject().put("ok", true) }
            val peers = obj.optJSONArray("peers") ?: JSONArray()
            val queues = obj.optJSONObject("queues") ?: JSONObject()
            val kept = JSONArray()
            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                if (p.optString("id") != peerId) kept.put(p)
            }
            queues.remove(peerId)
            obj.put("peers", kept)
            obj.put("queues", queues)
            if (kept.length() == 0) try { f.delete() } catch (_: Exception) {} else f.writeText(obj.toString())
            return JSONObject().put("ok", true)
        }
    }
    private fun sendJson(out: OutputStream, code: Int, body: JSONObject, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val writer = out.bufferedWriter(Charsets.UTF_8)
        val msg = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "OK" }
        writer.write("HTTP/1.1 $code $msg\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        for ((k, v) in extraHeaders) writer.write("$k: $v\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Server: DAMN-PHP/3.0\r\n")
        writer.write("Date: ${httpDate()}\r\n")
        writer.write("\r\n")
        writer.flush()
        out.write(bytes)
        out.flush()
    }
}
