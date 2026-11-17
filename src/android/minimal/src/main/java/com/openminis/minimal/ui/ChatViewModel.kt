package com.openminis.minimal.ui

// [T-minimal] The one thing that has state.
//
// A session is loaded, appended to, and streamed into. That is the whole model —
// there is no tool loop, no queue, no background turn, because this app has no
// tools and therefore nothing that could outlive the screen.

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.minimal.MinimalApp
import com.openminis.minimal.data.ChatError
import com.openminis.minimal.data.Message
import com.openminis.minimal.data.Session
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as MinimalApp).sessions
    private val settings = (app as MinimalApp).settings
    private val client = (app as MinimalApp).client

    /**
     * Read once per ViewModel, not once per message: the corpus is a handful of
     * files and re-reading them for every turn would be disk work whose result
     * cannot have changed mid-conversation. A memory import during a live
     * session takes effect on the next one, which is the honest behaviour — the
     * model should not silently change what it thinks it knows mid-answer.
     */
    private val systemPrompt: String? = com.openminis.minimal.data.MemoryStore
        .systemPrompt(app)

    val messages = mutableStateListOf<Message>()

    var sessionId by mutableStateOf<String?>(null)
        private set

    var title by mutableStateOf("新对话")
        private set

    var streaming by mutableStateOf(false)
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    private var turn: Job? = null
    private var lastUserText: String? = null

    fun openSession(id: String) {
        val s = store.load(id) ?: return
        sessionId = s.id
        title = s.title
        messages.clear()
        messages.addAll(s.messages)
        errorText = null
    }

    fun startNew() {
        sessionId = null
        title = "新对话"
        messages.clear()
        errorText = null
    }

    fun send(text: String) {
        if (text.isBlank() || streaming) return
        errorText = null
        lastUserText = text

        val user = Message(role = Message.ROLE_USER, text = text)
        messages.add(user)

        // The assistant message is appended EMPTY and then grown in place, so the
        // transcript has one row that fills rather than a new row per token.
        val assistant = Message(role = Message.ROLE_ASSISTANT, text = "")
        messages.add(assistant)

        streaming = true
        turn = viewModelScope.launch {
            try {
                val reply = client.stream(
                    apiKey = settings.apiKey,
                    model = settings.model,
                    systemPrompt = systemPrompt,
                    messages = messages.filter { it.text.isNotBlank() && it.id != assistant.id },
                    onDelta = { delta ->
                        val i = messages.indexOfFirst { it.id == assistant.id }
                        if (i >= 0) {
                            messages[i] = messages[i].copy(text = messages[i].text + delta)
                        }
                    },
                )
                finishTurn(assistant.id, reply, failed = false)
            } catch (e: ChatError) {
                finishTurn(assistant.id, null, failed = true, error = explain(e))
            } catch (e: Exception) {
                finishTurn(assistant.id, null, failed = true, error = e.message ?: "未知错误")
            } finally {
                streaming = false
            }
        }
    }

    fun stop() {
        client.cancel()
        turn?.cancel()
        turn = null
        streaming = false
        // Whatever arrived is kept: a half answer the user stopped is still a
        // half answer they asked for.
        persist()
    }

    fun retry() {
        val text = lastUserText ?: return
        val lastUserIndex = messages.indexOfLast { it.role == Message.ROLE_USER }
        if (lastUserIndex >= 0) {
            while (messages.size > lastUserIndex) messages.removeAt(messages.size - 1)
        }
        errorText = null
        send(text)
    }

    private fun finishTurn(assistantId: String, reply: String?, failed: Boolean, error: String? = null) {
        if (failed) {
            messages.removeAll { it.id == assistantId }
            errorText = error
        } else if (reply.isNullOrEmpty()) {
            // Cancelled, or the vendor sent nothing. An empty assistant bubble
            // is visual noise; drop it.
            messages.removeAll { it.id == assistantId }
        }
        persist()
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val id = sessionId ?: Session().id
        sessionId = id
        store.save(
            Session(
                id = id,
                createdAt = store.load(id)?.createdAt ?: System.currentTimeMillis(),
                messages = messages.toList(),
            ),
        )
        title = store.load(id)?.title ?: "新对话"
    }

    private fun explain(e: ChatError): String = when (e) {
        is ChatError.Auth -> getApplication<Application>().getString(
            com.openminis.minimal.R.string.chat_error_auth,
        )
        is ChatError.RateLimited -> getApplication<Application>().getString(
            com.openminis.minimal.R.string.chat_error_busy,
        )
        is ChatError.Network -> getApplication<Application>().getString(
            com.openminis.minimal.R.string.chat_error_network,
            e.message ?: "connection failed",
        )
        is ChatError.Server -> getApplication<Application>().getString(
            com.openminis.minimal.R.string.chat_error_network,
            e.message ?: "server error",
        )
    }
}
