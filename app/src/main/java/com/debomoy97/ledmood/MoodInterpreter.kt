package com.debomoy97.ledmood

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class LedCommand(
    val power: Boolean? = null,
    val color: Triple<Int, Int, Int>? = null,
    val brightness: Int? = null,
    val pattern: Int? = null,
    val micEq: Int? = null,
)

/**
 * Sends a mood/scene/music description to OpenAI and parses the structured
 * JSON response into an LedCommand. Mirrors the verified Python prompt logic.
 */
object MoodInterpreter {

    private val client = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    private fun systemPrompt(): String {
        val patternListJson = JSONArray(Patterns.PATTERNS).toString()
        return """
            You control an RGB LED strip. Given a description of a mood, scene, or
            music, respond with a JSON object (no markdown, no commentary) with
            these optional fields:

            - "power": true or false
            - "color": [r, g, b] each 0-255 (the dominant/base color for this mood)
            - "brightness": integer 0-100
            - "pattern": integer index into this pattern list (0 = off/static
              color): $patternListJson
            - "mic_eq": integer 0-255, ONLY set this if the user explicitly wants
              the strip to react live to music/audio via its built-in microphone
              (0 disables it). Don't combine mic_eq with pattern.

            Pick values that genuinely fit the requested mood/scene. Always
            include at least "color" and "brightness". Output raw JSON only.
        """.trimIndent()
    }

    suspend fun interpret(apiKey: String, prompt: String): LedCommand =
        suspendCoroutine { continuation ->
            val body = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("max_tokens", 300)
                put("response_format", JSONObject().put("type", "json_object"))
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt())
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val bodyStr = response.body?.string()
                            ?: throw IOException("Empty response body")
                        if (!response.isSuccessful) {
                            throw IOException("OpenAI API error ${response.code}: $bodyStr")
                        }
                        val root = JSONObject(bodyStr)
                        val content = root.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        val data = JSONObject(content)

                        val color = if (data.has("color")) {
                            val arr = data.getJSONArray("color")
                            Triple(arr.getInt(0), arr.getInt(1), arr.getInt(2))
                        } else null

                        val command = LedCommand(
                            power = if (data.has("power")) data.getBoolean("power") else null,
                            color = color,
                            brightness = if (data.has("brightness")) data.getInt("brightness") else null,
                            pattern = if (data.has("pattern")) data.getInt("pattern") else null,
                            micEq = if (data.has("mic_eq")) data.getInt("mic_eq") else null,
                        )
                        continuation.resume(command)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
}
