package com.openminis.minimal.data

// [T-minimal] Reading memory from a folder the user picks.
//
// Storage Access Framework rather than a direct path, for a reason that is not
// bureaucracy: from Android 11 the app cannot open `/sdcard/Download/...` by path
// at all, and asking for MANAGE_EXTERNAL_STORAGE to copy a few markdown files
// would mean asking the user to grant this app sight of every file on the phone.
// SAF hands back exactly one folder, for as long as the grant lasts, which is
// both less code and less permission.
//
// The picked files are COPIED into app storage rather than referenced. A tree Uri
// can be revoked — by a reboot, an uninstall of the source app, the user tidying
// their downloads — and a conversation that quietly loses its context weeks later
// is worse than one that never had it.

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

object MemoryImporter {
    private const val TAG = "MinimalMemory"
    private const val MAX_FILE_BYTES = 512 * 1024

    /**
     * Copies every .md/.txt under [treeUri] into [MemoryStore].
     *
     * @return number of files imported, or null when the folder could not be read.
     */
    fun importTree(context: Context, treeUri: Uri): Int? = runCatching {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)

        val files = LinkedHashMap<String, String>()

        fun walk(dirDoc: Uri, depth: Int) {
            if (depth > 3 || files.size >= 40) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(dirDoc),
            )
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), depth + 1)
                        continue
                    }
                    if (!name.endsWith(".md", true) && !name.endsWith(".txt", true)) continue
                    if (size > MAX_FILE_BYTES) {
                        Log.w(TAG, "skipping $name ($size bytes)")
                        continue
                    }
                    val text = resolver.openInputStream(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    )?.use { it.readBytes().toString(Charsets.UTF_8) } ?: continue
                    files[name] = text
                }
            }
        }

        walk(rootDoc, 0)
        if (files.isEmpty()) return 0
        MemoryStore.replaceAll(context, files)
        files.size
    }.onFailure { Log.w(TAG, "import failed", it) }.getOrNull()
}
