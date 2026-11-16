package com.openminis.minimal.data

// [T-minimal] "Is this key real?" — asked of the vendor, not of the string.
//
// Shape-checking ("starts with sk- and is long enough") answers a different
// question from "does DeepSeek accept this". A well-formed string the vendor
// rejects would have walked the user into an app that cannot answer, and the
// failure would only surface on their first message — the worst possible moment.
//
// One GET, no model call, no tokens spent. The result is deliberately a plain
// boolean: this screen can only act on yes or no, and "we could not tell" is not
// something to show someone who is trying to get started.

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object KeyCheck {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * True only for a 200 with a non-empty `data` array.
     *
     * A 200 with an empty list would mean the account has no models, which is not
     * a working state even though the key is valid — so it is reported as a
     * failure rather than as a pass that leads nowhere.
     */
    fun verify(apiKey: String): Boolean {
        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body?.string().orEmpty()
            val count = runCatching {
                json.parseToJsonElement(body)
                    .jsonObject["data"]?.jsonArray?.size ?: 0
            }.getOrDefault(0)
            return count > 0
        }
    }
}
