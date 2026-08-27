package com.damn.app.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

object FileUtils {

    fun getDisplayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            } catch (_: Exception) {}

            if (name == null) {
                // try DocumentsContract
                try {
                    val docId = if (DocumentsContract.isTreeUri(uri)) {
                        DocumentsContract.getTreeDocumentId(uri)
                    } else {
                        DocumentsContract.getDocumentId(uri)
                    }
                    name = docId.substringAfterLast(':').substringAfterLast('/')
                } catch (_: Exception) {}
            }
        }
        if (name == null || name.isEmpty()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
        }
        return name
    }

    fun copyUriToCache(ctx: Context, uri: Uri, displayName: String): File {
        val cacheDir = File(ctx.cacheDir, "damn_host")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        // clean old cache
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        val outFile: File
        val isSingleFile = isSingleFileUri(ctx, uri)
        if (isSingleFile) {
            outFile = File(cacheDir, displayName)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            // For single file hosting we serve its parent dir with file inside
            // But we want root = cacheDir
            return cacheDir
        } else {
            // It's a tree URI (folder) - copy tree
            val destRoot = File(cacheDir, "root")
            destRoot.mkdirs()
            copyTreeUri(ctx, uri, destRoot)
            return destRoot
        }
    }

    private fun isSingleFileUri(ctx: Context, uri: Uri): Boolean {
        // Heuristic: if uri authority is media or we can get mime without tree
        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        // Document tree uris contain /tree/
        if (uri.toString().contains("/tree/") && !uri.toString().contains("/document/")) {
            // Could be tree root without document - treat as folder
            return false
        }
        // Try to query mime type - if has children maybe folder?
        // Simple: if displayName contains . -> file
        val name = getDisplayName(ctx, uri)
        if (uri.toString().contains("/document/") && !uri.toString().contains("/tree/")) {
            // Could be single doc picked via ACTION_OPEN_DOCUMENT : file
            return true
        }
        // For tree uris that point to a document inside tree, they contain both /tree/ and /document/
        if (uri.toString().contains("/tree/") && uri.toString().contains("/document/")) {
            // Check if we can list children
            val children = tryListChildren(ctx, uri)
            return children.isEmpty()
        }
        return name.contains(".")
    }

    private fun tryListChildren(ctx: Context, treeUri: Uri): List<Uri> {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val list = mutableListOf<Uri>()
            ctx.contentResolver.query(childrenUri, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    list.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                }
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun copyTreeUri(ctx: Context, treeUri: Uri, dest: File) {
        // Robust recursive copy using DocumentsContract
        val treeDocId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { null }
            ?: return

        // Determine if treeUri itself points to a specific document inside tree
        val docIdToTraverse = try {
            if (treeUri.toString().contains("/document/")) DocumentsContract.getDocumentId(treeUri)
            else treeDocId
        } catch (_: Exception) { treeDocId }

        fun copyDoc(docId: String, outDir: File) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            var mime: String? = null
            var displayName: String? = null
            ctx.contentResolver.query(docUri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    mime = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                    displayName = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                }
            }
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val newDir = File(outDir, displayName ?: "folder")
                newDir.mkdirs()
                // list children
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                ctx.contentResolver.query(childrenUri, null, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                        copyDoc(childId, newDir)
                    }
                }
            } else {
                // file
                val outFile = File(outDir, displayName ?: "file")
                try {
                    ctx.contentResolver.openInputStream(docUri)?.use { input ->
                        outFile.outputStream().use { input.copyTo(it) }
                    }
                } catch (_: Exception) {}
            }
        }

        // If docIdToTraverse is the tree root, its children should be copied directly into dest
        // Not nesting extra folder level
        var mimeRoot: String? = null
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docIdToTraverse)
        ctx.contentResolver.query(rootUri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) mimeRoot = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
        }
        if (mimeRoot == DocumentsContract.Document.MIME_TYPE_DIR) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docIdToTraverse)
            ctx.contentResolver.query(childrenUri, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val childId = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    copyDoc(childId, dest)
                }
            }
        } else {
            copyDoc(docIdToTraverse, dest)
        }

        // Fallback: if dest empty but we have single file uri, copy it
        if ((dest.listFiles()?.isEmpty() != false)) {
            try {
                ctx.contentResolver.openInputStream(treeUri)?.use { input ->
                    val name = getDisplayName(ctx, treeUri)
                    File(dest, name).outputStream().use { input.copyTo(it) }
                }
            } catch (_: Exception) {}
        }
    }

    fun getLocalIp(ctx: Context): String {
        // Prioritize Wi-Fi interface if possible
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        // Fallback: search all interfaces, prioritizing non-mobile ones if Wi-Fi wasn't found above
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            // Priority 1: wlan0, eth0, etc (Local networks)
            // Priority 2: rmnet, ccmni, etc (Mobile data)
            val sorted = interfaces.sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            
            for (intf in sorted) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    /**
     * Fetch the true public external IP via a web service.
     */
    fun getExternalIpViaWeb(): String? {
        return try {
            val url = java.net.URL("https://api.ipify.org")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val res = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (res.split(".").size == 4) res else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch the global IPv6 address if available.
     */
    fun getGlobalIpv6(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet6Address) {
                        val ip = addr.hostAddress?.substringBefore('%') ?: continue
                        // Global Unicast start with 2 or 3
                        if (ip.startsWith("2") || ip.startsWith("3")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
