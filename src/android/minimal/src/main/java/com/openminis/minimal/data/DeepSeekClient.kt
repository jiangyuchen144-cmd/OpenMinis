package com.openminis.minimal.data

// [T-minimal] The DeepSeek client.
//
// One endpoint, one request shape, one stream parser. Written by hand instead of
// adopting a provider SDK because the SDK's job — normalising six vendors'
// differences — is the part this app does not need, and its abstraction would
// still have to be read to know what a 401 means.
//
// The streaming protocol is SSE: `data: {json}` lines, a literal `data: [DONE]`
// terminator, and blank lines that mean nothing. Comments (`: keep-alive`) arrive
// on idle connections and must be skipped rather than parsed.

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A failure the UI can say something useful about. */
sealed class ChatError(message: String) : Exception(message) {
    class Auth : ChatError("API key rejected")
    class RateLimited : ChatError("rate limited")
    class Network(detail: String) : ChatError(detail)
    class Server(detail: String) : ChatError(detail)
}

object Models {
    /**
     * The two ids this key can actually reach — confirmed against
     * `GET /v1/models` rather than assumed from documentation, because a model
     * name that does not exist fails at request time with a message about the
     * body, not about the model.
     */
    const val FLASH = "deepseek-flash"
    const val PRO = "deepseek-v4-pro"

    val all = listOf(FLASH to "Flash（快）", PRO to "Pro（强）")
}

class DeepSeekClient {

    private val http = OkHttpClient.Builder()
        // Connect fast, then be patient: a long answer can take minutes between
        // tokens while the model thinks, and a read timeout shorter than that
        // would cut perfectly healthy streams.
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var inFlight: okhttp3.Call? = null

    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        inFlight?.cancel()
    }

    /**
     * Streams one completion.
     *
     * @param onDelta called as text arrives.
     * @return the complete reply; empty when the caller cancelled.
     */
    suspend fun stream(
        apiKey: String,
        model: String,
        messages: List<Message>,
        systemPrompt: String? = null,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        cancelled.set(false)

        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", buildJsonArray {
                // The system message goes FIRST and is rebuilt per request rather
                // than being a stored message: it is context, not conversation,
                // and storing it would put it in every session file and show it
                // in the transcript.
                systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", prompt)
                    })
                }
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.text)
                    })
                }
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = http.newCall(request)
        inFlight = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw when (response.code) {
                        401, 403 -> ChatError.Auth()
                        429 -> ChatError.RateLimited()
                        else -> ChatError.Server("HTTP ${response.code}")
                    }
                }
                val source = response.body?.source()
                    ?: throw ChatError.Server("empty body")

                val sb = StringBuilder()
                while (true) {
                    // EOF or a cancelled call both end here; the caller
                    // distinguishes them by [cancelled].
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break

                    val delta = runCatching {
                        val obj = Json.parseToJsonElement(payload).jsonObject
                        obj["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")
                            ?.jsonObject?.get("content")
                            ?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: continue

                    if (delta.isNotEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
                sb.toString()
            }
        } catch (e: IOException) {
            // A cancel surfaces as an IOException from OkHttp; reporting it as a
            // network failure would tell the user their wifi broke.
            if (cancelled.get()) return@withContext ""
            throw ChatError.Network(e.message ?: "connection failed")
        } finally {
            inFlight = null
        }
    }

    private companion object {
        const val BASE_URL = "https://api.deepseek.com/v1"
    }
}
