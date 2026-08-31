package com.damn.app.server

import android.util.Log
import com.damn.app.util.DamnVfs
import com.damn.app.util.Favicon
import com.damn.app.util.VfsNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
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
 * Now uses DamnVfs to avoid copying large directories.
 */
class PhpFileServer(
    private val vfs: DamnVfs,
    private val port: Int,
    private val phpEngine: PhpEngine = SimplePhpEngine(),
    private val password: String? = null,
    private val cacheDir: File? = null,
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
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(java.net.Inet6Address.getByName("::"), port), 50)
            }
        } catch (e: Exception) {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port), 50)
            }
        }
        running.set(true)
        log("DAMN server listening on [::]:$port root=${vfs.getRootName()}")
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
                if (parts.size < 2) { 
                    sendError(out, 400, "Bad Request")
                    log("400 BAD REQUEST: $requestLine")
                    return 
                }
                val method = parts[0].uppercase()
                if (method !in setOf("GET", "HEAD", "POST", "OPTIONS")) { 
                    sendError(out, 405, "Method Not Allowed")
                    log("405 $method ${parts[1]}")
                    return 
                }
                val query = parts[1].substringAfter("?", "")
                val rawPath = parts[1].substringBefore("?")
                val decodedPath = URLDecoder.decode(rawPath, "UTF-8")
                val logReq = { code: Int -> log("$code $method $decodedPath") }
                
                var authHeader: String? = null
                var contentLength = 0
                while (true) {
                    val line = readHttpLine(input) ?: break
                    if (line.isEmpty()) break
                    when {
                        line.startsWith("Authorization:", ignoreCase = true) -> authHeader = line.substringAfter(":").trim()
                        line.startsWith("Content-Length:", ignoreCase = true) -> contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                
                if (method == "OPTIONS") {
                    val w = out.bufferedWriter(Charsets.UTF_8)
                    w.write("HTTP/1.1 204 No Content\r\n")
                    w.write("Access-Control-Allow-Origin: *\r\n")
                    w.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    w.write("Access-Control-Allow-Headers: Content-Type\r\n")
                    w.write("Connection: close\r\n\r\n")
                    w.flush()
                    logReq(204)
                    return
                }

                var bodyBytes = ByteArray(0)
                if (method == "POST" && contentLength > 0) {
                    bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val r = input.read(bodyBytes, offset, contentLength - offset)
                        if (r == -1) break
                        offset += r
                    }
                }
                
                onActivity()

                if (!password.isNullOrBlank()) {
                    if (authHeader == null || !authHeader.startsWith("Basic ", ignoreCase = true)) {
                        sendUnauthorized(out)
                        logReq(401)
                        return
                    }
                    val credentials = String(android.util.Base64.decode(authHeader.substringAfter("Basic ").trim(), android.util.Base64.NO_WRAP))
                    if ((if (credentials.contains(":")) credentials.substringAfter(":") else credentials) != password) {
                        sendUnauthorized(out)
                        logReq(401)
                        return
                    }
                }

                if (decodedPath == "/favicon.ico" || decodedPath == "/favicon.png") {
                    val bytes = android.util.Base64.decode(Favicon.PNG_BASE64, android.util.Base64.NO_WRAP)
                    sendResponse(out, 200, "OK", "image/png", bytes, method == "HEAD")
                    logReq(200)
                    return
                }

                if (decodedPath.endsWith("/signal.php") || decodedPath == "/signal.php") {
                    handleSignal(out, method, decodedPath, bodyBytes, query)
                    logReq(200)
                    return
                }

                var node = vfs.getMetadata(decodedPath)
                if (node == null) {
                    sendError(out, 404, "Not Found")
                    logReq(404)
                    return
                }

                if (node.isDirectory) {
                    val indexPhp = vfs.getMetadata("${decodedPath.trimEnd('/')}/index.php")
                    val indexHtml = vfs.getMetadata("${decodedPath.trimEnd('/')}/index.html")
                    when {
                        indexPhp != null && !indexPhp.isDirectory -> node = indexPhp
                        indexHtml != null && !indexHtml.isDirectory -> node = indexHtml
                        else -> {
                            val html = directoryListing(decodedPath, vfs.listChildren(decodedPath))
                            sendResponse(out, 200, "OK", "text/html; charset=utf-8", html.toByteArray(), method == "HEAD")
                            logReq(200)
                            return
                        }
                    }
                }
                
                val forceDownload = query.contains("download=1")
                if (node.name.endsWith(".php", ignoreCase = true) && !forceDownload) {
                    val rendered = phpEngine.render(node.path, vfs, cacheDir ?: File("/tmp"))
                    sendResponse(out, 200, "OK", "text/html; charset=utf-8", rendered.toByteArray(), method == "HEAD")
                    logReq(200)
                } else {
                    val mime = if (forceDownload) "application/octet-stream" else mimeFor(node.name)
                    sendVfsFile(out, 200, "OK", mime, node, method == "HEAD", forceDownload)
                    logReq(200)
                }
            } catch (e: Exception) {
                Log.w("DAMN-Server", "handle error", e)
                try { 
                    sendError(s.getOutputStream(), 500, "Internal Error")
                    log("500 ERROR")
                } catch (_: Exception) {}
            }
        }
    }

    private fun directoryListing(requestPath: String, children: List<VfsNode>): String {
        val parentLink = if (requestPath != "/" && requestPath.isNotEmpty()) {
            val parent = requestPath.trimEnd('/').substringBeforeLast('/', "")
            """<tr><td>📁</td><td><a href="$parent/">../ (parent)</a></td><td>-</td><td></td></tr>"""
        } else ""
        val rows = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).joinToString("\n") { f ->
            val href = (requestPath.trimEnd('/') + "/" + f.name).replace("//", "/")
            val icon = if (f.isDirectory) "📁" else iconFor(f.name)
            val size = if (f.isDirectory) "-" else humanSize(f.size)
            val dlBtn = if (!f.isDirectory) """<a href="$href?download=1" title="Download" class="dl-icon">📥</a>""" else ""
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
            <p>Index of <code>$requestPath</code> — ${children.size} items — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}</p>
            <table><tr><th></th><th>Name</th><th>Size</th><th>Action</th></tr>
            $parentLink $rows
            </table>
            <div id="footer">
                <a href="#" onclick="downloadAll()" class="btn-all">Download All (Sequential)</a>
                <p style="margin-top:24px; color:#475569; font-size:12px">By: MBHudson • Served by Android • ${vfs.getRootName()}</p>
            </div>
            <script>
                function downloadAll() {
                    const links = Array.from(document.querySelectorAll('a.dl-icon'));
                    let delay = 0;
                    links.forEach(link => {
                        setTimeout(() => {
                            const a = document.createElement('a'); a.href = link.href; a.download = '';
                            document.body.appendChild(a); a.click(); document.body.removeChild(a);
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
        writer.write("HTTP/1.1 $code $msg\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\nServer: DAMN-PHP/3.0\r\nDate: ${httpDate()}\r\n\r\n")
        writer.flush()
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun sendVfsFile(out: OutputStream, code: Int, msg: String, mime: String, node: VfsNode, headOnly: Boolean, forceDownload: Boolean) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("HTTP/1.1 $code $msg\r\nContent-Type: $mime\r\nContent-Length: ${node.size}\r\n")
        if (forceDownload) writer.write("Content-Disposition: attachment; filename=\"${node.name}\"\r\n")
        writer.write("Accept-Ranges: bytes\r\nConnection: close\r\nServer: DAMN-PHP/3.0\r\nDate: ${httpDate()}\r\n\r\n")
        writer.flush()
        if (!headOnly) vfs.openStream(node.path)?.use { it.copyTo(out) }
        out.flush()
    }

    private fun sendUnauthorized(out: OutputStream) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"D·A·M·N Protected Area\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        writer.flush()
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        val html = "<html><body><h1>$code $msg</h1><p>DAMN Server</p></body></html>"
        sendResponse(out, code, msg, "text/html", html.toByteArray(), false)
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun iconFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "php" -> "🐘"
        "html", "htm" -> "🌐"
        "jpg", "jpeg", "png", "gif" -> "🖼️"
        "mp4", "mkv" -> "🎬"
        "mp3", "wav" -> "🎵"
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
        while (true) {
            val b = try { 
                input.read() 
            } catch (e: java.net.SocketException) {
                return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
            } catch (_: Exception) { 
                return if (sb.isEmpty()) null else sb.toString().trimEnd('\r') 
            }
            if (b == -1 || b == '\n'.code) return if (sb.isEmpty() && b == -1) null else sb.toString().trimEnd('\r')
            sb.append(b.toChar())
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

    private val signalLock = Any()
    private fun roomsDir(): File = File(cacheDir ?: File("/tmp"), "damn_rooms").apply { if (!exists()) mkdirs() }
    private fun roomFile(room: String): File = File(roomsDir(), "${room.replace(Regex("[^a-zA-Z0-9_-]"), "").take(32).ifEmpty { "default" }}.json")
    
    private fun handleSignal(out: OutputStream, method: String, path: String, body: ByteArray, query: String) {
        try {
            val cors = mapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Methods" to "GET, POST, OPTIONS", "Access-Control-Allow-Headers" to "Content-Type")
            if (method == "GET" && query.contains("action=poll")) {
                val params = query.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("=", ""), "UTF-8") }
                sendJson(out, 200, pollRoom(params["room"] ?: "", params["peerId"] ?: ""), cors)
                return
            }
            if (method != "POST") { sendJson(out, 405, JSONObject().put("error", "POST required"), cors); return }
            val req = JSONObject(body.toString(Charsets.UTF_8))
            val action = req.optString("action", "")
            val room = req.optString("room", "")
            val res = when (action) {
                "join" -> joinRoom(room, req.optString("peerId", ""), req.optString("name", "Guest"))
                "poll" -> pollRoom(room, req.optString("peerId", ""))
                "signal" -> pushSignal(room, req.optString("from", ""), req.optString("to", ""), req.optString("type", ""), req.opt("data"))
                "leave" -> leaveRoom(room, req.optString("peerId", ""))
                else -> JSONObject().put("error", "unknown action")
            }
            sendJson(out, if (res.has("error")) 400 else 200, res, cors)
        } catch (e: Exception) {
            sendJson(out, 500, JSONObject().put("error", e.message), mapOf("Access-Control-Allow-Origin" to "*"))
        }
    }

    private fun joinRoom(room: String, peerId: String, name: String): JSONObject = synchronized(signalLock) {
        val f = roomFile(room)
        val now = System.currentTimeMillis()
        val obj = if (f.exists()) JSONObject(f.readText()) else JSONObject()
        val peers = obj.optJSONArray("peers") ?: JSONArray()
        val queues = obj.optJSONObject("queues") ?: JSONObject()
        val kept = JSONArray()
        for (i in 0 until peers.length()) {
            val p = peers.getJSONObject(i)
            if (now - p.optLong("seen", now) < 90_000 || p.optString("id") == peerId) kept.put(p)
        }
        var found = false
        for (i in 0 until kept.length()) {
            val p = kept.getJSONObject(i)
            if (p.optString("id") == peerId) { p.put("name", name); p.put("seen", now); found = true; break }
        }
        if (!found) kept.put(JSONObject().put("id", peerId).put("name", name).put("seen", now))
        if (!queues.has(peerId)) queues.put(peerId, JSONArray())
        obj.put("peers", kept).put("queues", queues).put("updated", now)
        f.writeText(obj.toString())
        val others = JSONArray()
        for (i in 0 until kept.length()) {
            val p = kept.getJSONObject(i)
            if (p.optString("id") != peerId) others.put(JSONObject().put("id", p.optString("id")).put("name", p.optString("name")))
        }
        return JSONObject().put("peers", others).put("you", peerId)
    }

    private fun pollRoom(room: String, peerId: String): JSONObject = synchronized(signalLock) {
        val f = roomFile(room)
        if (!f.exists()) return JSONObject().put("signals", JSONArray()).put("peers", JSONArray())
        val now = System.currentTimeMillis()
        val obj = JSONObject(f.readText())
        val peers = obj.optJSONArray("peers") ?: JSONArray()
        val queues = obj.optJSONObject("queues") ?: JSONObject()
        for (i in 0 until peers.length()) {
            val p = peers.getJSONObject(i)
            if (p.optString("id") == peerId) { p.put("seen", now); break }
        }
        val kept = JSONArray()
        for (i in 0 until peers.length()) {
            val p = peers.getJSONObject(i)
            if (now - p.optLong("seen", now) < 90_000) kept.put(p) else queues.remove(p.optString("id"))
        }
        val signals = queues.optJSONArray(peerId) ?: JSONArray()
        queues.put(peerId, JSONArray())
        obj.put("peers", kept).put("queues", queues).put("updated", now)
        f.writeText(obj.toString())
        val others = JSONArray()
        for (i in 0 until kept.length()) {
            val p = kept.getJSONObject(i)
            if (p.optString("id") != peerId) others.put(JSONObject().put("id", p.optString("id")).put("name", p.optString("name")))
        }
        return JSONObject().put("signals", signals).put("peers", others)
    }

    private fun pushSignal(room: String, from: String, to: String, type: String, data: Any?): JSONObject = synchronized(signalLock) {
        val f = roomFile(room)
        if (!f.exists()) return JSONObject().put("error", "room not found")
        val obj = JSONObject(f.readText())
        val queues = obj.optJSONObject("queues") ?: JSONObject().also { obj.put("queues", it) }
        val q = queues.optJSONArray(to) ?: JSONArray().also { queues.put(to, it) }
        q.put(JSONObject().put("from", from).put("type", type).put("data", data ?: JSONObject.NULL).put("ts", System.currentTimeMillis()))
        obj.put("updated", System.currentTimeMillis())
        f.writeText(obj.toString())
        return JSONObject().put("ok", true)
    }

    private fun leaveRoom(room: String, peerId: String): JSONObject = synchronized(signalLock) {
        val f = roomFile(room)
        if (!f.exists()) return JSONObject().put("ok", true)
        val obj = JSONObject(f.readText())
        val peers = obj.optJSONArray("peers") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until peers.length()) {
            val p = peers.getJSONObject(i)
            if (p.optString("id") != peerId) kept.put(p)
        }
        obj.optJSONObject("queues")?.remove(peerId)
        obj.put("peers", kept)
        if (kept.length() == 0) f.delete() else f.writeText(obj.toString())
        return JSONObject().put("ok", true)
    }

    private fun sendJson(out: OutputStream, code: Int, body: JSONObject, extra: Map<String, String>) {
        val bytes = body.toString().toByteArray()
        val writer = out.bufferedWriter()
        writer.write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
        extra.forEach { (k, v) -> writer.write("$k: $v\r\n") }
        writer.write("\r\n")
        writer.flush()
        out.write(bytes)
    }
}
