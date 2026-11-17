package com.openminis.minimal.data

// [T-minimal] Memory, shared with the other agent on this phone.
//
// The first version COPIED a folder at import time, which produces two corpora
// that drift the moment either side changes anything. The ask was to route reads
// and writes at the same place, so this reads the picked folder LIVE through the
// Storage Access Framework and writes back into it.
//
// The design constraint that shapes everything here: two writers, no locks. So
// ownership is split by FILE, not by region of a file —
//
//   Hark  owns  00-global.md, 0X-*.md, NN-log-*.md   (writes, this app reads)
//   this app owns 90-from-minis.md                   (appends only)
//
// Appending to a file nobody else touches needs no coordination, and a
// last-write-wins clobber becomes impossible rather than unlikely.
//
// Why SAF and not a path: from Android 11 an app cannot open /storage/emulated/0
// by path at all. The alternative — MANAGE_EXTERNAL_STORAGE, "all files access" —
// would mean asking the user to hand this app sight of every file on the phone in
// order to read five markdown files.
//
// Why not share the SQLite database instead: SAF gives streams, not a path, and
// SQLite needs a real file with working POSIX locks. Copies through a stream also
// cannot be made atomic against a concurrent writer, and a half-copied database
// fails closed in the worst way — silently, by being valid but stale.

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object MemoryStore {
    private const val TAG = "MinimalMemory"

    /**
     * Context that has to fit on EVERY turn, so it cannot grow without cost.
     * 12 000 characters is roughly 6k tokens of Chinese — past that it starts
     * crowding out the conversation it exists to support.
     */
    private const val BUDGET = 12_000

    /** The one file this app writes. Named so it sorts last. */
    const val OWNED_FILE = "90-from-minis.md"

    private const val PREFS = "minimal"
    private const val KEY_TREE = "memory_tree_uri"

    private val json = Json { ignoreUnknownKeys = true }

    // ── Where memory lives ─────────────────────────────────────────────────

    /**
     * The shared folder, if the user has granted one.
     *
     * Persisted as a Uri plus a persistable permission, so it survives a reboot
     * without asking again. A revoked grant (the folder was deleted, or the user
     * cleared it) simply stops resolving — the app falls back to its private
     * folder rather than crashing.
     */
    fun sharedTree(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!held) {
            Log.w(TAG, "shared tree grant is gone; using the private folder")
            return null
        }
        return uri
    }

    fun setSharedTree(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE, uri.toString()).apply()
        Log.i(TAG, "memory folder set to $uri")
    }

    fun clearSharedTree(context: Context) {
        sharedTree(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TREE).apply()
    }

    /** Fallback: files copied into app storage, used only when no folder is granted. */
    private fun privateDir(context: Context): File =
        File(context.filesDir, "memory").apply { mkdirs() }

    // ── Reading ────────────────────────────────────────────────────────────

    /**
     * The system prompt, or null when there is nothing to say.
     *
     * Sorted by name so the numbered files come in a deliberate order and the
     * daily logs, which sort later, are the first to be dropped when the budget
     * runs out — a summary is worth more per character than one day's notes.
     */
    fun systemPrompt(context: Context): String? {
        val docs = if (sharedTree(context) != null) readShared(context) else readPrivate(context)
        if (docs.isEmpty()) return null

        val sb = StringBuilder()
        var used = 0
        for ((name, text) in docs) {
            if (sb.length + text.length > BUDGET) {
                Log.i(TAG, "budget reached at $name")
                break
            }
            sb.append(text.trim()).append("\n\n")
            used++
        }
        if (sb.isBlank()) return null
        Log.i(TAG, "system prompt: ${sb.length} chars from $used of ${docs.size} file(s)")
        return sb.toString().trim()
    }

    private fun readShared(context: Context): List<Pair<String, String>> {
        val tree = sharedTree(context) ?: return emptyList()
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val out = ArrayList<Pair<String, String>>()

        fun walk(dir: Uri, depth: Int) {
            if (depth > 2) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(dir),
            )
            val rows = runCatching {
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { c ->
                    buildList {
                        while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2).orEmpty()))
                    }
                }
            }.getOrNull() ?: return

            for ((id, name, mime) in rows) {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(DocumentsContract.buildDocumentUriUsingTree(tree, id), depth + 1)
                    continue
                }
                if (!name.endsWith(".md", true) && !name.endsWith(".txt", true)) continue
                val text = runCatching {
                    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: continue
                out.add(name to text)
            }
        }

        walk(rootDoc, 0)
        return out.sortedBy { it.first }
    }

    private fun readPrivate(context: Context): List<Pair<String, String>> =
        privateDir(context).listFiles { f -> f.isFile && (f.extension == "md" || f.extension == "txt") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f -> runCatching { f.name to f.readText() }.getOrNull() }
            .orEmpty()

    // ── Writing ────────────────────────────────────────────────────────────

    /**
     * Appends one entry to this app's own file.
     *
     * Append rather than rewrite: the file is the app's alone, so there is no
     * race to lose — but a rewrite would still mean holding the whole file in
     * memory to change one line, and appends are what a log actually is.
     */
    fun appendNote(context: Context, text: String): Boolean {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        val entry = "\n## $stamp\n${text.trim()}\n"

        val tree = sharedTree(context)
        if (tree == null) {
            val f = File(privateDir(context), OWNED_FILE)
            runCatching { f.appendText(entry) }.onFailure { Log.w(TAG, "append failed", it) }
            return f.exists()
        }

        return runCatching {
            val resolver = context.contentResolver
            // Find our file; create it if this is the first note.
            val existing = findChild(context, tree, OWNED_FILE)
            val uri = existing ?: DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                ),
                "text/markdown",
                OWNED_FILE,
            ) ?: return@runCatching false

            // "wa" opens for append without truncating — the point of an append.
            resolver.openOutputStream(uri, "wa")?.use { it.write(entry.toByteArray()) } != null
        }.onFailure { Log.w(TAG, "shared append failed", it) }.getOrDefault(false)
    }

    private fun findChild(context: Context, tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return runCatching {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
                null
            }
        }.getOrNull()
    }

    // ── Status, for the settings screen ────────────────────────────────────

    data class Status(
        val shared: Boolean,
        val files: Int,
        val chars: Int,
        val lastError: String? = null,
    )

    fun status(context: Context): Status {
        val shared = sharedTree(context) != null
        val docs = if (shared) readShared(context) else readPrivate(context)
        return Status(
            shared = shared,
            files = docs.size,
            chars = docs.sumOf { it.second.length },
        )
    }
}
