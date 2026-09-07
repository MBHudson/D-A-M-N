package com.damn.app.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

interface DamnVfs {
    fun getRootName(): String
    fun getMetadata(path: String): VfsNode?
    fun listChildren(path: String): List<VfsNode>
    fun openStream(path: String): InputStream?
    
    /**
     * For engines that require a real file (like native PHP).
     * Implementations may copy a small file to cache on demand.
     */
    fun getAsFile(path: String, cacheDir: File): File?
}

data class VfsNode(
    val name: String,
    val path: String, // Relative to root, e.g. "/subdir/file.txt"
    val size: Long,
    val isDirectory: Boolean,
    val mimeType: String?,
    val lastModified: Long = 0
)

class FileVfs(private val root: File) : DamnVfs {
    override fun getRootName() = root.name
    
    override fun getMetadata(path: String): VfsNode? {
        val f = File(root, path.trimStart('/'))
        if (!f.exists()) return null
        return f.toVfsNode(path)
    }

    override fun listChildren(path: String): List<VfsNode> {
        val f = File(root, path.trimStart('/'))
        return f.listFiles()?.map { it.toVfsNode("${path.trimEnd('/')}/${it.name}") } ?: emptyList()
    }

    override fun openStream(path: String): InputStream? {
        val f = File(root, path.trimStart('/'))
        return if (f.exists() && f.isFile) f.inputStream() else null
    }

    override fun getAsFile(path: String, cacheDir: File): File? {
        val f = File(root, path.trimStart('/'))
        return if (f.exists()) f else null
    }

    private fun File.toVfsNode(relPath: String) = VfsNode(
        name = name,
        path = relPath,
        size = length(),
        isDirectory = isDirectory,
        mimeType = null, // Will be determined by extension in server
        lastModified = lastModified()
    )
}

class DocumentVfs(private val ctx: Context, private val treeUri: Uri) : DamnVfs {
    private val contentResolver = ctx.contentResolver
    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootName = FileUtils.getDisplayName(ctx, treeUri)

    override fun getRootName() = rootName

    override fun getMetadata(path: String): VfsNode? {
        if (path == "/" || path == "") {
            return VfsNode(rootName, "/", 0, true, DocumentsContract.Document.MIME_TYPE_DIR)
        }
        val docId = resolveDocId(path) ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return queryNode(docUri, path)
    }

    override fun listChildren(path: String): List<VfsNode> {
        val docId = resolveDocId(path) ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = mutableListOf<VfsNode>()
        try {
            contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                    val mod = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    result.add(VfsNode(name, "${path.trimEnd('/')}/$name", size, mime == DocumentsContract.Document.MIME_TYPE_DIR, mime, mod))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    override fun openStream(path: String): InputStream? {
        val docId = resolveDocId(path) ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            contentResolver.openInputStream(docUri)
        } catch (_: Exception) { null }
    }

    override fun getAsFile(path: String, cacheDir: File): File? {
        // Copy only this specific file to cache
        val node = getMetadata(path) ?: return null
        if (node.isDirectory) return null
        
        val tempFile = File(cacheDir, "vfs_cache_${node.name}")
        try {
            openStream(path)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            }
            return tempFile
        } catch (_: Exception) {
            return null
        }
    }

    private fun queryNode(uri: Uri, relPath: String): VfsNode? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                    val mod = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    VfsNode(name, relPath, size, mime == DocumentsContract.Document.MIME_TYPE_DIR, mime, mod)
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun resolveDocId(path: String): String? {
        if (path == "/" || path == "") return rootDocId
        val segments = path.trim('/').split('/')
        var currentDocId = rootDocId
        
        for (segment in segments) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            var foundId: String? = null
            try {
                contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                        if (name == segment) {
                            foundId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
            currentDocId = foundId ?: return null
        }
        return currentDocId
    }
}
