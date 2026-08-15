package com.nexusagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexusagent.agent.runtime.reasoning.AnthropicProvider
import com.nexusagent.agent.runtime.reasoning.GeminiProvider
import com.nexusagent.agent.runtime.reasoning.OpenAiProvider
import com.nexusagent.agent.runtime.reasoning.ProviderId
import com.nexusagent.agent.runtime.reasoning.ReasoningRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only: loads an API key onto the device without typing it on a phone keyboard.
 *
 * ```
 *   adb shell am broadcast -a com.nexusagent.SETKEY --es key AIza...
 * ```
 *
 * The key still ends up encrypted in the Keystore-backed store - this only bypasses the
 * text field, not the storage. It does mean the key passes through the shell, so it will
 * sit in shell history on the host machine; for anything but a throwaway development key,
 * use the Settings screen instead.
 *
 * Never logged, only ever confirmed by length.
 */
class DebugSetKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val key = intent.getStringExtra("key").orEmpty()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val reasoning = ReasoningRepository(context.applicationContext)

                // Provider first: setApiKey stores one credential, so switching provider
                // after writing the key would leave the new provider holding the old
                // provider's key and failing with a confusing 401.
                intent.getStringExtra("provider")?.let { name ->
                    val provider = runCatching { ProviderId.valueOf(name.uppercase()) }.getOrNull()
                    if (provider == null) {
                        Log.w(TAG, "Unknown provider '$name'; leaving it unchanged.")
                    } else {
                        reasoning.settingsStore.setProvider(provider)
                        reasoning.settingsStore.setModel(
                            intent.getStringExtra("model") ?: provider.defaultModel(),
                        )
                        intent.getStringExtra("baseurl")?.let { url ->
                            reasoning.settingsStore.setBaseUrl(url)
                            Log.i(TAG, "Base URL set to $url")
                        }
                        Log.i(TAG, "Provider set to ${provider.displayName}.")
                    }
                }

                reasoning.settingsStore.setApiKey(key)
                Log.i(
                    TAG,
                    if (key.isBlank()) {
                        "API key cleared."
                    } else {
                        "API key stored (${key.length} chars, encrypted)."
                    },
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun ProviderId.defaultModel(): String = when (this) {
        ProviderId.GEMINI -> GeminiProvider.DEFAULT_MODEL
        ProviderId.ANTHROPIC -> AnthropicProvider.DEFAULT_MODEL
        ProviderId.OPENAI -> OpenAiProvider.DEFAULT_MODEL
    }

    private companion object {
        const val TAG = "NexusReasoning"
    }
}
