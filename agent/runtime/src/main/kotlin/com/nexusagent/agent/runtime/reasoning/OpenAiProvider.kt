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
 * Any OpenAI-compatible chat-completions endpoint.
 *
 * The `/v1/chat/completions` shape is the de-facto standard, so this one implementation
 * covers a wide range of backends with nothing but a base URL change:
 *
 *  - **Groq** - free tier, custom inference hardware, often sub-second per step
 *  - **OpenRouter** - one key across many models
 *  - **Together, Fireworks, DeepInfra** - hosted open models
 *  - **Ollama / LM Studio** - a model running on your own machine
 *  - **OpenAI itself**
 *
 * This is where the provider abstraction stops being a design claim and starts paying
 * rent: a base URL in Settings now selects between roughly a dozen backends, and
 * perception, execution, and the orchestrator are untouched by any of it.
 *
 * ## Structured output
 *
 * Prefers `response_format: {type: "json_schema", strict: true}`, which is enforced by
 * the decoder rather than merely requested. Not every compatible endpoint implements it -
 * [supportsJsonSchema] falls back to `{type: "json_object"}`, which guarantees valid JSON
 * but not a valid *action*; [DecisionCodec] catches the difference and the orchestrator
 * feeds the complaint back for a retry.
 */
class OpenAiProvider(
    private val client: HttpClient,
    private val apiKeyProvider: suspend () -> String?,
    private val model: String,
    private val baseUrl: String,
    private val supportsJsonSchema: Boolean = true,
) : LlmProvider {

    override val id = ProviderId.OPENAI

    // Vision support varies wildly across compatible endpoints, and sending an image to a
    // text-only model is a hard error rather than a graceful degradation. Off unless the
    // model id names a family known to accept images.
    override val supportsVision: Boolean
        get() = VISION_HINTS.any { model.contains(it, ignoreCase = true) }

    override suspend fun decide(turn: AgentTurn): LlmResult {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return LlmResult.Error("No API key. Add one in Settings.", retryable = false)
        }

        val startedAt = System.currentTimeMillis()

        val response: HttpResponse = try {
            client.post("${baseUrl.trimEnd('/')}/chat/completions") {
                header("Authorization", "Bearer $apiKey")
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
                retryAfterMs = response.headers["retry-after"]?.toLongOrNull()?.times(1000),
            )
        }

        val text = extractText(bodyText)
            ?: return LlmResult.InvalidResponse("No message content in response", bodyText.take(400))

        return when (val parsed = DecisionCodec.decode(text)) {
            is DecisionParse.Ok -> LlmResult.Success(
                decision = parsed.decision,
                promptTokens = bodyText.usage("prompt_tokens"),
                responseTokens = bodyText.usage("completion_tokens"),
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
        // Near-deterministic: this is UI navigation, not writing. Unlike the Anthropic
        // path, temperature is both accepted and useful here.
        put("temperature", 0.1)

        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", PromptBuilder.system(turn.shortcutCatalogue))
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    val image = turn.screenshotBase64Jpeg?.takeIf { supportsVision }
                    if (image == null) {
                        // Plain string content: some compatible servers reject the array
                        // form outright, and there is nothing to gain from it here.
                        put("content", PromptBuilder.user(turn))
                    } else {
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", PromptBuilder.user(turn))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64,$image")
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }

        putJsonObject("response_format") {
            if (supportsJsonSchema) {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "agent_decision")
                    put("strict", true)
                    put("schema", RESPONSE_SCHEMA)
                }
            } else {
                // Guarantees valid JSON but not a valid action. DecisionCodec rejects a
                // bad action and the orchestrator retries with the complaint attached.
                put("type", "json_object")
            }
        }
    }

    private fun extractText(body: String): String? = runCatching {
        DecisionCodec.json.parseToJsonElement(body)
            .jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    }.getOrNull()

    private fun String.usage(name: String): Int? = runCatching {
        DecisionCodec.json.parseToJsonElement(this)
            .jsonObject["usage"]?.jsonObject?.get(name)?.jsonPrimitive?.content?.toIntOrNull()
    }.getOrNull()

    private fun describeHttpError(status: HttpStatusCode, body: String): String = when (status.value) {
        400 -> "Rejected (400). ${body.apiMessage()}"
        401 -> "API key rejected. Check the key and base URL in Settings."
        403 -> "This key lacks access to '$model'."
        404 -> "Not found. Check the base URL ends at /v1, and that '$model' exists there."
        429 -> {
            Log.w(TAG, "429 body: $body")
            "Rate limited. ${body.apiMessage()}"
        }
        in 500..599 -> "Provider unavailable (${status.value}). Retrying may help."
        else -> "HTTP ${status.value}: ${body.take(200)}"
    }

    private fun String.apiMessage(): String = runCatching {
        DecisionCodec.json.parseToJsonElement(this)
            .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: take(200)

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        private const val TAG = "NexusReasoning"

        /** Groq: free, and materially faster per step than any other free option. */
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

        /**
         * Chosen for `json_schema` support, not size.
         *
         * Most of Groq's catalogue - including `llama-3.3-70b-versatile` and
         * `llama-3.1-8b-instant` - rejects `response_format: json_schema` outright. That
         * constraint decides the model here: without schema enforcement the agent would
         * fall back to `json_object`, which guarantees valid JSON but not a valid action,
         * and the loop would spend steps recovering from invented ones.
         *
         * Measured at ~730 ms per decision, against 2,000-4,500 ms on Gemini Flash.
         */
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"

        private val VISION_HINTS = listOf("gpt-4", "gpt-5", "vision", "llava", "gemini", "claude", "scout", "maverick")

        /**
         * Strict variant of the action contract.
         *
         * `strict: true` demands `additionalProperties: false` *and* that `required` list
         * every key in `properties` - optional-by-omission is not allowed. Nullable types
         * are how a field is made optional under strict mode, hence the `["integer",
         * "null"]` unions rather than a short `required` array.
         */
        private val RESPONSE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("thought") { put("type", "string") }
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
                putNullable("element_id", "integer")
                putNullable("text", "string")
                putNullable("submit", "boolean")
                putNullable("direction", "string")
                putNullable("package_name", "string")
                putNullable("shortcut", "string")
                putNullable("params_json", "string")
                putNullable("millis", "integer")
                putNullable("summary", "string")
                putNullable("reason", "string")
                putNullable("question", "string")
            }
            putJsonArray("required") {
                listOf(
                    "thought", "action", "element_id", "text", "submit", "direction",
                    "package_name", "shortcut", "params_json", "millis",
                    "summary", "reason", "question",
                ).forEach { add(JsonPrimitive(it)) }
            }
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
            name: String,
            type: String,
        ) = putJsonObject(name) {
            putJsonArray("type") {
                add(JsonPrimitive(type))
                add(JsonPrimitive("null"))
            }
        }
    }
}
