package com.nexusagent.agent.runtime.reasoning

import android.util.Log
import com.nexusagent.core.model.DecisionCodec
import com.nexusagent.core.model.DecisionParse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Claude backend.
 *
 * Exists to make the provider abstraction real rather than aspirational: perception,
 * execution, and the orchestrator are untouched by this file, and switching backends is
 * a Settings toggle plus a pasted key.
 *
 * ## Three ways this differs from the Gemini request, each of which is a hard error
 *
 * 1. **No sampling parameters.** `temperature`, `top_p`, and `top_k` were removed on
 *    Claude Opus 5 and return a 400. The Gemini path sets `temperature = 0.1` for
 *    determinism; copying that here would break every request.
 * 2. **Structured output is `output_config.format`,** not a `responseSchema` sibling of
 *    the generation settings, and the schema must declare `additionalProperties: false`.
 * 3. **Thinking is on by default** and is left on. Disabling it is available only at
 *    effort `high` or below and is the more expensive lever anyway - `effort: "low"`
 *    already recovers most of the latency, without the failure modes that come with
 *    thinking switched off.
 */
class AnthropicProvider(
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String?,
    private val model: String = DEFAULT_MODEL,
) : LlmProvider {

    override val id = ProviderId.ANTHROPIC
    override val supportsVision = true

    override suspend fun decide(turn: AgentTurn): LlmResult {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return LlmResult.Error("No API key. Add one in Settings.", retryable = false)
        }

        val startedAt = System.currentTimeMillis()

        val response: HttpResponse = try {
            client.post("$BASE_URL/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)
                setBody(buildRequest(turn))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed", e)
            return LlmResult.Error(e.message ?: "Network error", retryable = true)
        }

        val latencyMs = System.currentTimeMillis() - startedAt
        val bodyText = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return LlmResult.Error(
                message = describeHttpError(response.status, bodyText),
                retryable = response.status.value == 429 || response.status.value >= 500,
                // Anthropic states the wait in a header rather than the body.
                retryAfterMs = response.headers["retry-after"]
                    ?.toLongOrNull()
                    ?.let { it * 1000 },
            )
        }

        // A safety decline arrives as a successful 200 with stop_reason "refusal" and an
        // empty content array. Reading content[0] without this check crashes on exactly
        // the requests most worth handling gracefully.
        val stopReason = bodyText.field("stop_reason")
        if (stopReason == "refusal") {
            return LlmResult.Error(
                "The model declined this request. Rephrase the goal.",
                retryable = false,
            )
        }

        val text = extractText(bodyText)
            ?: return LlmResult.InvalidResponse("No text block in response", bodyText.take(400))

        return when (val parsed = DecisionCodec.decode(text)) {
            is DecisionParse.Ok -> LlmResult.Success(
                decision = parsed.decision,
                promptTokens = bodyText.usage("input_tokens"),
                responseTokens = bodyText.usage("output_tokens"),
                latencyMs = latencyMs,
            )

            is DecisionParse.Invalid -> {
                Log.w(TAG, "Unparseable response (${text.length} chars): $text")
                LlmResult.InvalidResponse(parsed.message, text.take(1500))
            }
        }
    }

    private fun buildRequest(turn: AgentTurn): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", 1024)

        // System prompt as a cacheable block. It is byte-identical on every step of every
        // run - by far the largest static prefix here - so a cache breakpoint on it turns
        // most of the per-step input cost into cache reads.
        putJsonArray("system") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", PromptBuilder.system(turn.shortcutCatalogue))
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                },
            )
        }

        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", PromptBuilder.user(turn))
                            },
                        )
                        turn.screenshotBase64Jpeg?.let { image ->
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", image)
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }

        putJsonObject("output_config") {
            // Low effort, thinking left on. The ReAct "thought" field already carries the
            // reasoning this loop needs, one action at a time, and latency matters more
            // than depth when every step is a round-trip.
            put("effort", "low")
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", RESPONSE_SCHEMA)
            }
        }
    }

    private fun extractText(body: String): String? = runCatching {
        DecisionCodec.json.parseToJsonElement(body)
            .jsonObject["content"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.content
    }.getOrNull()

    private fun String.field(name: String): String? = runCatching {
        DecisionCodec.json.parseToJsonElement(this).jsonObject[name]?.jsonPrimitive?.content
    }.getOrNull()

    private fun String.usage(name: String): Int? = runCatching {
        DecisionCodec.json.parseToJsonElement(this)
            .jsonObject["usage"]?.jsonObject?.get(name)?.jsonPrimitive?.content?.toIntOrNull()
    }.getOrNull()

    private fun describeHttpError(status: HttpStatusCode, body: String): String = when (status.value) {
        400 -> "Rejected by Anthropic (400). ${body.apiMessage()}"
        401 -> "API key rejected. Check it in Settings."
        403 -> "This key lacks permission for that model."
        404 -> "Unknown model '$model'."
        429 -> {
            Log.w(TAG, "429 body: $body")
            "Rate limited. ${body.apiMessage()}"
        }
        in 500..599 -> "Anthropic is unavailable (${status.value}). Retrying may help."
        else -> "HTTP ${status.value}: ${body.take(200)}"
    }

    private fun String.apiMessage(): String = runCatching {
        DecisionCodec.json.parseToJsonElement(this)
            .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: take(200)

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        private const val TAG = "NexusReasoning"
        private const val BASE_URL = "https://api.anthropic.com/v1"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        const val DEFAULT_MODEL = "claude-opus-5"

        /**
         * Same contract the Gemini provider enforces, in Anthropic's dialect.
         *
         * `additionalProperties: false` is mandatory for structured outputs here - the
         * request is rejected without it. The `action` enum is again the load-bearing
         * constraint: it makes inventing an action structurally impossible rather than
         * merely discouraged.
         */
        private val RESPONSE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("thought") {
                    put("type", "string")
                    put("description", "One short sentence: why this action, now.")
                }
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf(
                            "click", "long_click", "type", "scroll",
                            "back", "home", "recents", "notifications",
                            "launch_app", "intent", "wait", "screenshot",
                            "ask_user", "done", "fail",
                        ).forEach { add(JsonPrimitive(it)) }
                    }
                }
                putJsonObject("element_id") {
                    put("type", "integer")
                    put("description", "Id from the CURRENT screen. Required for click/long_click/type.")
                }
                putJsonObject("text") { put("type", "string") }
                putJsonObject("submit") { put("type", "boolean") }
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("forward", "backward").forEach { add(JsonPrimitive(it)) }
                    }
                }
                putJsonObject("package_name") { put("type", "string") }
                putJsonObject("shortcut") { put("type", "string") }
                putJsonObject("params_json") {
                    put("type", "string")
                    put("description", "JSON object as a string, e.g. {\"hour\":\"6\"}")
                }
                putJsonObject("millis") { put("type", "integer") }
                putJsonObject("summary") {
                    put("type", "string")
                    put("description", "Only for action=done. One short sentence, max 15 words.")
                }
                putJsonObject("reason") { put("type", "string") }
                putJsonObject("question") { put("type", "string") }
                putJsonObject("confidence") { put("type", "number") }
            }
            putJsonArray("required") {
                add(JsonPrimitive("thought"))
                add(JsonPrimitive("action"))
            }
        }
    }
}
