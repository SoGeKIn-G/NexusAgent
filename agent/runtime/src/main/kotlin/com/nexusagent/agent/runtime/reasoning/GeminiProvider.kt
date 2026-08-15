package com.nexusagent.agent.runtime.reasoning

import android.util.Log
import com.nexusagent.core.model.DecisionCodec
import com.nexusagent.core.model.DecisionParse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Google Gemini backend.
 *
 * Written against the REST API with Ktor rather than the vendor SDK. That is a deliberate
 * cost: it means owning the request shape, the schema, the error taxonomy and the token
 * accounting - all of which are exactly the parts worth understanding, and all of which
 * an SDK hides behind one method call.
 *
 * Correctness rests on **structured output**: `responseMimeType` plus a `responseSchema`
 * constrain decoding so the model cannot emit prose, markdown, or an action outside the
 * enumerated set. Prompting alone does not achieve that - it fails a few percent of the
 * time, which over a 12-step run is a coin flip.
 */
class GeminiProvider(
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String?,
    private val model: String = DEFAULT_MODEL,
) : LlmProvider {

    override val id = ProviderId.GEMINI
    override val supportsVision = true

    override suspend fun decide(turn: AgentTurn): LlmResult {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return LlmResult.Error("No API key. Add one in Settings.", retryable = false)
        }

        val startedAt = System.currentTimeMillis()

        val response: HttpResponse = try {
            client.post("$BASE_URL/models/$model:generateContent") {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(buildRequest(turn))
            }
        } catch (e: Exception) {
            // Network-level: no connectivity, DNS, TLS, timeout. Always worth one retry.
            Log.w(TAG, "Request failed", e)
            return LlmResult.Error(e.message ?: "Network error", retryable = true)
        }

        val latencyMs = System.currentTimeMillis() - startedAt
        val bodyText = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return LlmResult.Error(
                message = describeHttpError(response.status, bodyText),
                // 429 and 5xx are transient; 400/401/403 mean the request or key is wrong
                // and retrying just burns quota.
                retryable = response.status.value == 429 || response.status.value >= 500,
                retryAfterMs = if (response.status.value == 429) parseRetryAfter(bodyText) else null,
            )
        }

        val text = extractText(bodyText)
            ?: return LlmResult.InvalidResponse("No text part in response", bodyText.take(400))

        return when (val parsed = DecisionCodec.decode(text)) {
            is DecisionParse.Ok -> {
                val usage = extractUsage(bodyText)
                LlmResult.Success(
                    decision = parsed.decision,
                    promptTokens = usage?.first,
                    responseTokens = usage?.second,
                    latencyMs = latencyMs,
                )
            }
            is DecisionParse.Invalid -> {
                // Log the whole thing. A truncated sample of malformed JSON is close to
                // useless - the defect is almost never in the visible window.
                Log.w(TAG, "Unparseable response (${text.length} chars): $text")
                LlmResult.InvalidResponse(parsed.message, text.take(1500))
            }
        }
    }

    private fun buildRequest(turn: AgentTurn): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") {
                add(buildJsonObject { put("text", PromptBuilder.system(turn.shortcutCatalogue)) })
            }
        }

        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", PromptBuilder.user(turn)) })
                        turn.screenshotBase64Jpeg?.let { image ->
                            add(
                                buildJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", "image/jpeg")
                                        put("data", image)
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }

        putJsonObject("generationConfig") {
            // Near-deterministic on purpose. This is UI navigation, not writing - given
            // the same screen and goal, the same tap should follow, and creative variation
            // shows up as the agent wandering.
            put("temperature", 0.1)

            // Gemini 2.5 models think by default, and thinking tokens are charged against
            // maxOutputTokens. With a small budget the model spends it reasoning and the
            // JSON is cut off mid-string - which surfaces as "Unexpected EOF", not as an
            // obvious truncation. Disabled here: the ReAct "thought" field already carries
            // the reasoning we want, one action at a time, and latency matters more than
            // depth when every step is a round-trip.
            putJsonObject("thinkingConfig") {
                put("thinkingBudget", 0)
            }

            // Still generous relative to a single action, so a verbose summary cannot
            // truncate the response.
            put("maxOutputTokens", 1024)
            put("responseMimeType", "application/json")
            put("responseSchema", RESPONSE_SCHEMA)
        }
    }

    private fun extractText(body: String): String? = runCatching {
        DecisionCodec.json.parseToJsonElement(body)
            .jsonObject["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
    }.getOrNull()

    private fun extractUsage(body: String): Pair<Int?, Int?>? = runCatching {
        val usage = DecisionCodec.json.parseToJsonElement(body)
            .jsonObject["usageMetadata"]?.jsonObject ?: return null
        usage["promptTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() to
            usage["candidatesTokenCount"]?.jsonPrimitive?.content?.toIntOrNull()
    }.getOrNull()

    /** Turns provider error blobs into something a user can act on. */
    private fun describeHttpError(status: HttpStatusCode, body: String): String = when (status.value) {
        400 -> "Rejected by Gemini (400). ${body.extractApiMessage()}"
        401, 403 -> "API key rejected. Check it in Settings."
        429 -> {
            // The body names which quota was hit (per-minute vs per-day) and how long to
            // wait. Without it, "rate limited" is indistinguishable from "out of quota
            // until tomorrow" - and those call for very different responses.
            Log.w(TAG, "429 body: $body")
            "Rate limited. ${body.extractApiMessage()}"
        }
        in 500..599 -> "Gemini is unavailable (${status.value}). Retrying may help."
        else -> "HTTP ${status.value}: ${body.take(200)}"
    }

    /**
     * Reads the wait Gemini asks for out of a 429 body.
     *
     * Two forms appear: a structured `RetryInfo` detail carrying `retryDelay: "24s"`, and
     * a plain-English "Please retry in 24.4s" inside the message. Both are parsed because
     * which one you get varies, and neither is guaranteed - hence the nullable return and
     * the caller's fallback to exponential backoff.
     */
    private fun parseRetryAfter(body: String): Long? {
        Regex("""["']retryDelay["']\s*:\s*["'](\d+(?:\.\d+)?)s["']""")
            .find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { return (it * 1000).toLong() }

        return Regex("""retry in (\d+(?:\.\d+)?)s""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() }
    }

    private fun String.extractApiMessage(): String = runCatching {
        DecisionCodec.json.parseToJsonElement(this)
            .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: take(200)

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        private const val TAG = "NexusReasoning"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        /** Flash tier: the loop is latency-sensitive and every step is a request. */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /**
         * The contract the model is decoded against.
         *
         * `action` is an enum, which is the single most valuable constraint here - it makes
         * "invent a plausible-sounding action" structurally impossible rather than merely
         * discouraged. Every other field is optional because which ones apply depends on
         * the action chosen.
         */
        private val RESPONSE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("thought") {
                    put("type", "STRING")
                    put("description", "One short sentence: why this action, now.")
                }
                putJsonObject("action") {
                    put("type", "STRING")
                    putJsonArray("enum") {
                        listOf(
                            "click", "long_click", "type", "scroll",
                            "back", "home", "recents", "notifications",
                            "launch_app", "intent", "wait", "screenshot",
                            "ask_user", "done", "fail",
                        ).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }
                }
                putJsonObject("element_id") {
                    put("type", "INTEGER")
                    put("description", "Id from the CURRENT screen. Required for click/long_click/type.")
                }
                putJsonObject("text") { put("type", "STRING") }
                putJsonObject("submit") { put("type", "BOOLEAN") }
                putJsonObject("direction") {
                    put("type", "STRING")
                    putJsonArray("enum") {
                        listOf("forward", "backward").forEach {
                            add(kotlinx.serialization.json.JsonPrimitive(it))
                        }
                    }
                }
                putJsonObject("package_name") { put("type", "STRING") }
                putJsonObject("shortcut") { put("type", "STRING") }
                putJsonObject("params_json") {
                    put("type", "STRING")
                    put("description", "JSON object as a string, e.g. {\"hour\":\"6\"}")
                }
                putJsonObject("millis") { put("type", "INTEGER") }
                putJsonObject("summary") {
                    put("type", "STRING")
                    // Constrained explicitly: left open, the model narrates the whole run
                    // here and blows the output budget.
                    put("description", "Only for action=done. One short sentence, max 15 words.")
                }
                putJsonObject("reason") { put("type", "STRING") }
                putJsonObject("question") { put("type", "STRING") }
                putJsonObject("confidence") { put("type", "NUMBER") }
            }
            putJsonArray("required") {
                add(kotlinx.serialization.json.JsonPrimitive("thought"))
                add(kotlinx.serialization.json.JsonPrimitive("action"))
            }
            // Puts "thought" before "action" in the generated output, so the model
            // commits to a rationale before naming a move rather than justifying one
            // it has already picked.
            putJsonArray("propertyOrdering") {
                listOf("thought", "action", "element_id", "text", "submit", "direction").forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it))
                }
            }
        }
    }
}
