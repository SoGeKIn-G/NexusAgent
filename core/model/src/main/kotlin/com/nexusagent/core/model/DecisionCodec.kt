package com.nexusagent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire form of one model decision.
 *
 * Deliberately **flat**, not a polymorphic sealed hierarchy. Structured-output schemas
 * across providers handle a single object with optional fields far more reliably than
 * discriminated unions, and a flat shape means a model that fills in a field the chosen
 * action doesn't use produces a harmless extra rather than a parse failure.
 *
 * Validation happens in [toDomain], not in the deserializer: a syntactically valid
 * response can still be semantically wrong (`click` with no element id), and that needs to
 * come back as a correctable error the agent can feed to the next turn - not an exception.
 */
@Serializable
data class AgentDecisionDto(
    val thought: String = "",
    val action: String = "",
    @SerialName("element_id") val elementId: Int? = null,
    val text: String? = null,
    val submit: Boolean = false,
    val direction: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    val shortcut: String? = null,
    /**
     * Shortcut arguments as a JSON object *string*, e.g. `{"hour":"6","minutes":"30"}`.
     *
     * A string rather than a real map because provider schemas model open-ended
     * dictionaries poorly - several reject `additionalProperties` outright - and a nested
     * free-form object is where structured output most often breaks.
     */
    @SerialName("params_json") val paramsJson: String? = null,
    val millis: Long? = null,
    val summary: String? = null,
    val reason: String? = null,
    val question: String? = null,
    val confidence: Float = 1f,
)

sealed interface DecisionParse {
    data class Ok(val decision: AgentDecision) : DecisionParse

    /** Recoverable: the message is fed back so the model can correct itself next turn. */
    data class Invalid(val message: String) : DecisionParse
}

object DecisionCodec {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun decode(raw: String): DecisionParse {
        val cleaned = raw.stripCodeFence()
        val dto = runCatching { json.decodeFromString<AgentDecisionDto>(cleaned) }
            .getOrElse { return DecisionParse.Invalid("Not valid JSON: ${it.message}") }
        return dto.toDomain()
    }

    /**
     * Models wrap JSON in markdown fences even when told not to, and even when the
     * response mime type is set to application/json. Cheap to tolerate, expensive to
     * debug if you don't.
     */
    private fun String.stripCodeFence(): String {
        val trimmed = trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    fun AgentDecisionDto.toDomain(): DecisionParse {
        val parsed: AgentAction = when (action.lowercase().trim()) {
            "click" -> AgentAction.Click(elementId ?: return missing("element_id"))
            "long_click" -> AgentAction.LongClick(elementId ?: return missing("element_id"))

            "type" -> AgentAction.TypeText(
                elementId = elementId ?: return missing("element_id"),
                text = text ?: return missing("text"),
                submit = submit,
            )

            "scroll" -> AgentAction.Scroll(
                direction = when (direction?.lowercase()) {
                    "backward", "up" -> ScrollDirection.BACKWARD
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.FORWARD
                },
                elementId = elementId,
            )

            "back" -> AgentAction.Global(GlobalAction.BACK)
            "home" -> AgentAction.Global(GlobalAction.HOME)
            "recents" -> AgentAction.Global(GlobalAction.RECENTS)
            "notifications" -> AgentAction.Global(GlobalAction.NOTIFICATIONS)

            "launch_app" -> AgentAction.LaunchApp(packageName ?: return missing("package_name"))

            "intent" -> AgentAction.IntentShortcut(
                name = shortcut ?: return missing("shortcut"),
                params = paramsJson.toParamMap(),
            )

            "wait" -> AgentAction.Wait(millis ?: 1_000L)
            "screenshot" -> AgentAction.RequestScreenshot
            "ask_user" -> AgentAction.AskUser(question ?: return missing("question"))
            "done" -> AgentAction.Done(summary ?: "Task complete")
            "fail" -> AgentAction.Fail(reason ?: "No reason given")

            "" -> return DecisionParse.Invalid("no action given")
            else -> return DecisionParse.Invalid("unknown action '$action'")
        }

        return DecisionParse.Ok(
            AgentDecision(
                thought = thought.ifBlank { "(no thought given)" },
                action = parsed,
                confidence = confidence.coerceIn(0f, 1f),
            ),
        )
    }

    private fun AgentDecisionDto.missing(field: String) =
        DecisionParse.Invalid("action '$action' requires '$field'")

    /** Tolerates a null, a malformed blob, or non-string values inside the object. */
    private fun String?.toParamMap(): Map<String, String> {
        if (this.isNullOrBlank()) return emptyMap()
        return runCatching {
            (DecisionCodec.json.parseToJsonElement(this) as JsonObject)
                .mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.content ?: value.toString()
                }
        }.getOrDefault(emptyMap())
    }
}
