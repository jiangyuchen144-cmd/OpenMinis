package com.openminis.minimal.data

// [T-minimal] What the model should know before the first message.
//
// This replaces the idea of "sharing a database" with the thing that actually
// works here. This app has no tools, so the model cannot query SQL — a database
// wired into it would be storage nobody reads. What it CAN do is start every
// conversation already knowing who it is talking to.
//
// So memory is text, in one directory, concatenated once per launch and sent as
// the system message. The prose came from Hark's notes; the structured parts
// come from Hark's database, rendered down to sentences by whatever produced the
// file. The app does not care which — it reads markdown.
//
// Budget: the whole point is context the model has on every turn, so it has to
// stay small enough to be free. 12 000 characters is roughly 6k tokens of
// Chinese; past that it starts crowding out the conversation it is meant to
// support, and is skipped rather than truncated mid-sentence.

import android.content.Context
import android.util.Log
import java.io.File

object MemoryStore {
    private const val TAG = "MinimalMemory"

    /** The corpus is capped at this many characters; whole files only. */
    private const val BUDGET = 12_000

    fun dir(context: Context): File = File(context.filesDir, "memory").apply { mkdirs() }

    /**
     * The system prompt, or null when there is nothing to say.
     *
     * Files are concatenated newest-first: when the budget forces a cut, the
     * recent notes are the ones worth keeping.
     */
    fun systemPrompt(context: Context): String? {
        val files = dir(context).listFiles { f ->
            f.isFile && (f.extension == "md" || f.extension == "txt")
        }?.sortedByDescending { it.name } ?: return null
        if (files.isEmpty()) return null

        val sb = StringBuilder()
        var used = 0
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (sb.length + text.length > BUDGET) {
                Log.i(TAG, "budget reached at ${f.name}")
                break
            }
            sb.append(text.trim()).append("\n\n")
            used++
        }
        if (sb.isBlank()) return null

        Log.i(TAG, "system prompt: ${sb.length} chars from $used file(s)")
        return sb.toString().trim()
    }

    /** Replaces the stored corpus with [files] (name to content). */
    fun replaceAll(context: Context, files: Map<String, String>) {
        val d = dir(context)
        d.listFiles()?.forEach { it.delete() }
        files.forEach { (name, content) ->
            // Path traversal guard: the names come from a folder the user picked,
            // and a name is not a path.
            val safe = name.substringAfterLast('/').substringAfterLast('\\')
            if (safe.isNotBlank()) File(d, safe).writeText(content)
        }
        Log.i(TAG, "imported ${files.size} memory file(s)")
    }

    /** (file count, character count) for the settings screen. */
    fun stats(context: Context): Pair<Int, Int> {
        val files = dir(context).listFiles { f -> f.isFile } ?: return 0 to 0
        val chars = files.sumOf { runCatching { it.readText().length }.getOrDefault(0) }
        return files.size to chars
    }
}
