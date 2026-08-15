package com.nexusagent.agent.runtime.reasoning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("agent_settings")

data class AgentSettings(
    val provider: ProviderId = ProviderId.GEMINI,
    val model: String = GeminiProvider.DEFAULT_MODEL,
    /** Only meaningful for [ProviderId.OPENAI] - the other two have fixed endpoints. */
    val baseUrl: String = OpenAiProvider.DEFAULT_BASE_URL,
    val maxSteps: Int = 25,
    val confirmDestructive: Boolean = true,
    val hasApiKey: Boolean = false,
)

/**
 * Settings and credential storage.
 *
 * ## On provider selection
 *
 * There is no subscription detection here, and there cannot be: no client app can inspect
 * another vendor's billing status, and a consumer chat subscription never includes API
 * access anyway - that is billed separately. So the model is simple and honest: the user
 * picks a provider and pastes a key, and whatever key exists is what gets used. Gemini is
 * the default purely because it has a usable free tier.
 *
 * ## On key storage
 *
 * The key is encrypted with AES-GCM using a key held in the Android Keystore, which is
 * backed by hardware on most modern devices. The plaintext key never touches
 * `local.properties`, `strings.xml`, or the APK - all three of which end up in version
 * control sooner or later.
 */
class AgentSettingsStore(private val context: Context) {

    val settings: Flow<AgentSettings> = context.dataStore.data.map { prefs ->
        AgentSettings(
            provider = prefs[KEY_PROVIDER]?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
                ?: ProviderId.GEMINI,
            model = prefs[KEY_MODEL] ?: GeminiProvider.DEFAULT_MODEL,
            baseUrl = prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() }
                ?: OpenAiProvider.DEFAULT_BASE_URL,
            maxSteps = prefs[KEY_MAX_STEPS] ?: 25,
            confirmDestructive = prefs[KEY_CONFIRM_DESTRUCTIVE] != "false",
            hasApiKey = !prefs[KEY_API_KEY].isNullOrBlank(),
        )
    }

    suspend fun apiKey(): String? {
        val stored = context.dataStore.data.first()[KEY_API_KEY] ?: return null
        return decrypt(stored)
    }

    suspend fun setApiKey(plaintext: String) {
        context.dataStore.edit { prefs ->
            if (plaintext.isBlank()) {
                prefs.remove(KEY_API_KEY)
            } else {
                prefs[KEY_API_KEY] = encrypt(plaintext.trim())
            }
        }
    }

    suspend fun setProvider(provider: ProviderId) {
        context.dataStore.edit { it[KEY_PROVIDER] = provider.name }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { it[KEY_MODEL] = model }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        context.dataStore.edit { prefs ->
            // Normalised on write so a trailing slash or a pasted "/chat/completions"
            // suffix doesn't produce a 404 the user has no way to diagnose.
            val cleaned = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
            if (cleaned.isBlank()) prefs.remove(KEY_BASE_URL) else prefs[KEY_BASE_URL] = cleaned
        }
    }

    suspend fun setMaxSteps(steps: Int) {
        context.dataStore.edit { it[KEY_MAX_STEPS] = steps.coerceIn(1, 60) }
    }

    suspend fun setConfirmDestructive(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_DESTRUCTIVE] = enabled.toString() }
    }

    // -- crypto -------------------------------------------------------------------

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not setUserAuthenticationRequired: the agent must be able
                // to run while the screen is off, and requiring a biometric per request
                // would make unattended operation impossible.
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        // GCM needs its IV to decrypt, and it is not secret - prepend it, length-tagged.
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext))
    }.getOrNull() // Keystore keys are wiped by a factory reset or backup restore.

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nexus_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128

        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_PROVIDER = stringPreferencesKey("provider")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_MAX_STEPS = intPreferencesKey("max_steps")
        val KEY_CONFIRM_DESTRUCTIVE = stringPreferencesKey("confirm_destructive")
    }
}
