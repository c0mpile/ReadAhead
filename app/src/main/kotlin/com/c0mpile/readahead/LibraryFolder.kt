package com.c0mpile.readahead

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.c0mpile.readahead.narration.ExportRunner
import java.io.File
import java.io.IOException

/**
 * The destination folder the user picked (a SAF tree). Finished audiobooks go to `<author>/<title>/` inside it, the
 * layout audiobook servers expect. Only this folder is ever written; the permission was granted by the user's pick.
 */
class LibraryFolder(
    private val context: Context,
    private val tree: Uri,
) {
    /** Copies [files] into `<author>/<title>/` (made if missing), replacing same-named files; returns that path. */
    fun save(
        files: List<File>,
        author: String?,
        title: String,
    ): String {
        val names = listOf(author?.let(ExportRunner::safeName) ?: UNKNOWN_AUTHOR, ExportRunner.safeName(title))
        try {
            var dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (name in names) dir = child(dir, name)?.first ?: makeDir(dir, name)
            for (file in files) copy(dir, file)
        } catch (e: SecurityException) {
            throw IOException("the folder is no longer accessible; choose it again", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("the folder is no longer accessible; choose it again", e)
        }
        return names.joinToString("/")
    }

    private fun makeDir(
        parent: Uri,
        name: String,
    ): Uri =
        DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("the folder refused to create $name")

    private fun copy(
        dir: Uri,
        file: File,
    ) {
        val resolver = context.contentResolver
        // A same-named file is replaced; otherwise the provider would save the new one as "name (1)".
        child(dir, file.name)?.first?.let { DocumentsContract.deleteDocument(resolver, it) }
        // A generic type, so the provider does not tack ".m4a" on to a ".m4b" name.
        val target =
            DocumentsContract.createDocument(resolver, dir, "application/octet-stream", file.name)
                ?: throw IOException("the folder refused ${file.name}")
        val out = resolver.openOutputStream(target) ?: throw IOException("cannot write ${file.name}")
        val written = out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
        if (written != file.length()) throw IOException("${file.name} was not written completely")
    }

    /** The child of [parent] with this display name: its document URI and whether it is a folder. */
    private fun child(
        parent: Uri,
        name: String,
    ): Pair<Uri, Boolean>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    val isDir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)) to isDir
                }
            }
        }
        return null
    }

    companion object {
        const val UNKNOWN_AUTHOR = "Unknown Author"

        /** A short name for the folder: the part of its document id after the volume, or the volume itself. */
        fun label(tree: Uri): String =
            DocumentsContract
                .getTreeDocumentId(tree)
                .substringAfter(':')
                .ifEmpty { "Storage root" }
    }
}
