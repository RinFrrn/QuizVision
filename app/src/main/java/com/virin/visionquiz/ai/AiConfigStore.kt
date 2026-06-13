package com.virin.visionquiz.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AiConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        ensureProfilesMigrated()
    }

    fun read(): AiConfig {
        val profile = getDefaultProfile() ?: createFallbackProfile()
        return AiConfig(
            enabled = isEnabled(),
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            model = profile.model,
            quickReviewPrompt = quickReviewPrompt(),
            analysisPrompt = analysisPrompt(),
            techniquePrompt = techniquePrompt(),
            mnemonicPrompt = mnemonicPrompt(),
            profileId = profile.id,
            profileName = profile.name
        )
    }

    fun listProfiles(): List<AiProfile> {
        ensureProfilesMigrated()
        val profiles = readStoredProfiles()
            .map(::toProfile)
            .sortedBy { it.createdAt }
        if (profiles.isNotEmpty()) return profiles
        val fallback = AiProfile(
            name = "默认配置",
            baseUrl = DEFAULT_BASE_URL,
            apiKey = "",
            model = DEFAULT_MODEL
        )
        writeProfiles(listOf(fallback))
        prefs.edit().putString(KEY_DEFAULT_PROFILE_ID, fallback.id).apply()
        return listOf(fallback)
    }

    fun getProfile(id: String): AiProfile? = listProfiles().firstOrNull { it.id == id }

    fun getDefaultProfileId(): String {
        val profiles = listProfiles()
        val saved = prefs.getString(KEY_DEFAULT_PROFILE_ID, null)
        return profiles.firstOrNull { it.id == saved }?.id ?: profiles.first().id.also {
            prefs.edit().putString(KEY_DEFAULT_PROFILE_ID, it).apply()
        }
    }

    fun getDefaultProfile(): AiProfile? = getProfile(getDefaultProfileId())

    fun saveProfile(profile: AiProfile): AiProfile {
        val normalizedName = profile.name.trim()
        require(normalizedName.isNotBlank()) { "配置名称不能为空" }
        val profiles = listProfiles().toMutableList()
        require(profiles.none {
            it.id != profile.id && it.name.equals(normalizedName, ignoreCase = true)
        }) { "配置名称不能重复" }
        val index = profiles.indexOfFirst { it.id == profile.id }
        val now = System.currentTimeMillis()
        val saved = profile.copy(
            name = normalizedName,
            baseUrl = profile.baseUrl.trim(),
            apiKey = profile.apiKey.trim(),
            model = profile.model.trim(),
            createdAt = if (index >= 0) profiles[index].createdAt else profile.createdAt,
            updatedAt = now
        )
        if (index >= 0) profiles[index] = saved else profiles += saved
        writeProfiles(profiles)
        if (prefs.getString(KEY_DEFAULT_PROFILE_ID, null) == null) {
            setDefaultProfile(saved.id)
        }
        return saved
    }

    fun createProfile(name: String): AiProfile {
        return saveProfile(
            AiProfile(
                name = uniqueName(name),
                baseUrl = DEFAULT_BASE_URL,
                apiKey = "",
                model = DEFAULT_MODEL
            )
        )
    }

    fun duplicateProfile(id: String): AiProfile {
        val source = requireNotNull(getProfile(id)) { "配置不存在" }
        return saveProfile(
            source.copy(
                id = UUID.randomUUID().toString(),
                name = uniqueName("${source.name} 副本"),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                testResult = AiTestResult()
            )
        )
    }

    fun deleteProfile(id: String) {
        val profiles = listProfiles()
        require(profiles.size > 1) { "至少保留一个 AI 配置" }
        require(id != getDefaultProfileId()) { "请先将其他配置设为默认" }
        require(profiles.any { it.id == id }) { "配置不存在" }
        writeProfiles(profiles.filterNot { it.id == id })
    }

    fun setDefaultProfile(id: String) {
        require(listProfiles().any { it.id == id }) { "配置不存在" }
        prefs.edit().putString(KEY_DEFAULT_PROFILE_ID, id).apply()
    }

    fun saveTestResult(id: String, result: AiTestResult): AiProfile {
        val profile = requireNotNull(getProfile(id)) { "配置不存在" }
        return saveProfile(profile.copy(testResult = result))
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun savePrompts(
        quickReviewPrompt: String,
        analysisPrompt: String,
        techniquePrompt: String,
        mnemonicPrompt: String
    ) {
        prefs.edit()
            .putString(KEY_QUICK_REVIEW_PROMPT, quickReviewPrompt.trim())
            .putString(KEY_ANALYSIS_PROMPT, analysisPrompt.trim())
            .putString(KEY_TECHNIQUE_PROMPT, techniquePrompt.trim())
            .putString(KEY_MNEMONIC_PROMPT, mnemonicPrompt.trim())
            .apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun quickReviewPrompt(): String = prefs.getString(
        KEY_QUICK_REVIEW_PROMPT,
        AiPromptBuilder.DEFAULT_QUICK_REVIEW_PROMPT
    ).orEmpty()

    fun analysisPrompt(): String = prefs.getString(
        KEY_ANALYSIS_PROMPT,
        AiPromptBuilder.DEFAULT_ANALYSIS_PROMPT
    ).orEmpty()

    fun techniquePrompt(): String = prefs.getString(
        KEY_TECHNIQUE_PROMPT,
        AiPromptBuilder.DEFAULT_TECHNIQUE_PROMPT
    ).orEmpty()

    fun mnemonicPrompt(): String = prefs.getString(
        KEY_MNEMONIC_PROMPT,
        AiPromptBuilder.DEFAULT_MNEMONIC_PROMPT
    ).orEmpty()

    private fun ensureProfilesMigrated() {
        if (!prefs.getString(KEY_PROFILES_JSON, null).isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val profile = AiProfile(
            name = "默认配置",
            baseUrl = prefs.getString(KEY_LEGACY_BASE_URL, DEFAULT_BASE_URL).orEmpty(),
            apiKey = readLegacyApiKey(),
            model = prefs.getString(KEY_LEGACY_MODEL, DEFAULT_MODEL).orEmpty(),
            createdAt = now,
            updatedAt = now
        )
        writeProfiles(listOf(profile))
        prefs.edit().putString(KEY_DEFAULT_PROFILE_ID, profile.id).apply()
    }

    private fun createFallbackProfile(): AiProfile {
        val profile = AiProfile(
            name = "默认配置",
            baseUrl = DEFAULT_BASE_URL,
            apiKey = "",
            model = DEFAULT_MODEL
        )
        writeProfiles(listOf(profile))
        setDefaultProfile(profile.id)
        return profile
    }

    private fun uniqueName(baseName: String): String {
        val existing = listProfiles().map { it.name.lowercase() }.toSet()
        if (baseName.lowercase() !in existing) return baseName
        var suffix = 2
        while ("$baseName $suffix".lowercase() in existing) suffix++
        return "$baseName $suffix"
    }

    private fun readStoredProfiles(): List<StoredProfile> {
        val json = prefs.getString(KEY_PROFILES_JSON, null).orEmpty()
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<StoredProfile>>() {}.type
        return runCatching { gson.fromJson<List<StoredProfile>>(json, type) }
            .getOrDefault(emptyList())
    }

    private fun writeProfiles(profiles: List<AiProfile>) {
        val stored = profiles.map {
            StoredProfile(
                id = it.id,
                name = it.name,
                baseUrl = it.baseUrl,
                encryptedApiKey = if (it.apiKey.isBlank()) "" else encrypt(it.apiKey),
                model = it.model,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                testResult = it.testResult
            )
        }
        prefs.edit().putString(KEY_PROFILES_JSON, gson.toJson(stored)).apply()
    }

    private fun toProfile(stored: StoredProfile): AiProfile {
        return AiProfile(
            id = stored.id,
            name = stored.name,
            baseUrl = stored.baseUrl,
            apiKey = if (stored.encryptedApiKey.isBlank()) {
                ""
            } else {
                runCatching { decrypt(stored.encryptedApiKey) }.getOrDefault("")
            },
            model = stored.model,
            createdAt = stored.createdAt,
            updatedAt = stored.updatedAt,
            testResult = stored.testResult ?: AiTestResult()
        )
    }

    private fun readLegacyApiKey(): String {
        val encrypted = prefs.getString(KEY_LEGACY_API_KEY, null) ?: return ""
        return runCatching { decrypt(encrypted) }.getOrDefault("")
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        return "$iv:$payload"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":", limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private data class StoredProfile(
        val id: String,
        val name: String,
        val baseUrl: String,
        val encryptedApiKey: String,
        val model: String,
        val createdAt: Long,
        val updatedAt: Long,
        val testResult: AiTestResult?
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com"
        const val DEFAULT_MODEL = "gpt-4.1-mini"
        private const val PREFS_NAME = "ai_assistant_settings"
        private const val KEY_PROFILES_JSON = "profiles_json_v2"
        private const val KEY_DEFAULT_PROFILE_ID = "default_profile_id"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_QUICK_REVIEW_PROMPT = "quick_review_prompt"
        private const val KEY_ANALYSIS_PROMPT = "analysis_prompt"
        private const val KEY_TECHNIQUE_PROMPT = "technique_prompt"
        private const val KEY_MNEMONIC_PROMPT = "mnemonic_prompt"
        private const val KEY_LEGACY_BASE_URL = "base_url"
        private const val KEY_LEGACY_API_KEY = "api_key_encrypted"
        private const val KEY_LEGACY_MODEL = "model"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vision_quiz_ai_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
