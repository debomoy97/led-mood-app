package com.debomoy97.ledmood

import android.util.Log
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
    val customColors: List<Triple<Int, Int, Int>>? = null,
    val customMode: CustomPatternMode? = null,
    val customForward: Boolean? = null,
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
            - "color": [r, g, b] each 0-255 (the dominant/base color for this mood,
              used when not building a custom_colors sequence)
            - "brightness": integer 0-100
            - "pattern": integer index into this pattern list (0 = off/static
              color): $patternListJson
            - "mic_eq": integer 0-255, ONLY set this if the user explicitly wants
              the strip to react live to music/audio via its built-in microphone
              (0 disables it). Don't combine mic_eq with pattern or custom_colors.

            Instead of "pattern", you may design an ORIGINAL color sequence
            tailored to the mood, rather than picking from the preset list:
            - "custom_colors": a list of 2-6 [r, g, b] colors that together
              capture the requested mood/scene as a sequence (e.g. a sunset
              mood might be a list moving from deep orange to purple to navy)
            - "custom_mode": one of "GD" (gradual blend), "FD" (fade),
              "FW" (flowing wipe), "FS" (flash/strobe - high energy only),
              "PU" (pulse/jump - rhythmic), "FL" (flicker), "HO" (hold/static,
              effectively just cycles through the list without animating)
            - "custom_forward": true or false, direction of the sequence

            Prefer designing a custom_colors sequence over picking a generic
            preset "pattern" whenever the mood has a clear color story (e.g.
            sunset, ocean, forest, fire, a specific song's mood) - this gives a
            much more tailored result than the fixed preset list. Use "pattern"
            for generic requests (e.g. "just cycle colors", "rainbow") where a
            preset is just as good. Don't set both "pattern" and
            "custom_colors" in the same response.

            Pick values that genuinely fit the requested mood/scene. Always
            include at least "color" and "brightness", even when also setting
            custom_colors (color acts as a fallback/base). Output raw JSON only.
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

                        val customColors = if (data.has("custom_colors")) {
                            val arr = data.getJSONArray("custom_colors")
                            (0 until arr.length()).map { i ->
                                val c = arr.getJSONArray(i)
                                Triple(c.getInt(0), c.getInt(1), c.getInt(2))
                            }
                        } else null

                        val customMode = if (data.has("custom_mode")) {
                            try {
                                CustomPatternMode.valueOf(data.getString("custom_mode").uppercase())
                            } catch (e: IllegalArgumentException) {
                                Log.w("MoodInterpreter", "Unknown custom_mode value, ignoring: ${data.getString("custom_mode")}")
                                null
                            }
                        } else null

                        val command = LedCommand(
                            power = if (data.has("power")) data.getBoolean("power") else null,
                            color = color,
                            brightness = if (data.has("brightness")) data.getInt("brightness") else null,
                            pattern = if (data.has("pattern")) data.getInt("pattern") else null,
                            micEq = if (data.has("mic_eq")) data.getInt("mic_eq") else null,
                            customColors = customColors,
                            customMode = customMode,
                            customForward = if (data.has("custom_forward")) data.getBoolean("custom_forward") else null,
                        )
                        continuation.resume(command)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
}