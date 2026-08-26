package com.damn.app.server

import android.util.Log
import com.damn.app.util.Favicon
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
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) { sendError(out, 400, "Bad Request"); return }
                val method = parts[0].uppercase()
                if (method !in setOf("GET", "HEAD")) { sendError(out, 405, "Method Not Allowed"); return }
                val query = parts[1].substringAfter("?", "")
                val rawPath = parts[1].substringBefore("?")
                val decodedPath = URLDecoder.decode(rawPath, "UTF-8")
                
                // read headers
                var line: String?
                var authHeader: String? = null
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.startsWith("Authorization:", ignoreCase = true)) {
                        authHeader = line!!.substringAfter(":").trim()
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

    private fun httpDate(): String {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(Date())
    }

    private fun log(msg: String) {
        Log.i("DAMN-Server", msg)
        onLog.invoke("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $msg")
    }
}
