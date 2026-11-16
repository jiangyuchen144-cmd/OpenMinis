package com.openminis.minimal.data

// [T-minimal] Storage.
//
// Sessions live as one JSON file per conversation. Not a database, and that is a
// decision rather than a shortcut:
//
//  * A chat app's read pattern is "open one conversation, append to it". That is
//    a file. A relational schema earns its keep when you query ACROSS records,
//    which this app — which has no search, no tags, no folders, no filters —
//    never does.
//  * It removes Room, KSP and a schema migration story from the build, which for
//    a 3 000-line app is more machinery than the feature it supports.
//
// The cost is honest: opening a very long conversation parses the whole file. At
// a few hundred messages that is single-digit milliseconds; if it ever stops
// being, that is the moment a database becomes the right answer, with evidence.

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    /** Epoch millis. Sorting and display both come from this. */
    val at: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

@Serializable
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
) {
    /** The first user line, trimmed — a title the user would recognise. */
    val title: String
        get() = messages.firstOrNull { it.role == Message.ROLE_USER }
            ?.text
            ?.trim()
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.take(40)
            ?: "新对话"
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class SessionStore(context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, "$id.json")

    /** Newest first. */
    fun list(): List<Session> =
        dir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { f ->
                runCatching { json.decodeFromString<Session>(f.readText()) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Session? =
        fileFor(id).takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<Session>(it.readText()) }.getOrNull() }

    fun save(session: Session) {
        val stamped = session.copy(updatedAt = System.currentTimeMillis())
        val target = fileFor(stamped.id)
        // Write-then-rename. A message saved into a half-written file is a
        // conversation that silently loses its tail — the one failure a chat app
        // must never have.
        val tmp = File(dir, "${stamped.id}.tmp")
        tmp.writeText(json.encodeToString(stamped))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }
}
