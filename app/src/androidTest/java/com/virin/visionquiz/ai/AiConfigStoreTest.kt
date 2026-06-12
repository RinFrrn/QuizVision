package com.virin.visionquiz.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiConfigStoreTest {
    private lateinit var context: Context
    private lateinit var store: AiConfigStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences().edit().clear().commit()
        store = AiConfigStore(context)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun migratesLegacyConnectionAndGlobalPrompts() {
        preferences().edit()
            .clear()
            .putBoolean("enabled", true)
            .putString("base_url", "https://legacy.example")
            .putString("model", "legacy-model")
            .putString("analysis_prompt", "legacy prompt")
            .commit()

        val migrated = AiConfigStore(context)
        val profile = migrated.getDefaultProfile()!!

        assertEquals("默认配置", profile.name)
        assertEquals("https://legacy.example", profile.baseUrl)
        assertEquals("legacy-model", profile.model)
        assertEquals("legacy prompt", migrated.analysisPrompt())
        assertTrue(migrated.isEnabled())
    }

    @Test
    fun supportsEncryptedProfilesCopyDefaultAndDeleteRules() {
        val first = store.getDefaultProfile()!!
        val saved = store.saveProfile(
            first.copy(
                name = "主配置",
                apiKey = "secret-key",
                model = "model-a"
            )
        )
        val copied = store.duplicateProfile(saved.id)

        assertEquals("secret-key", store.getProfile(copied.id)?.apiKey)
        assertEquals(AiTestStatus.NOT_TESTED, copied.testResult.status)
        assertNotEquals(saved.id, copied.id)
        assertIllegalArgument {
            store.deleteProfile(saved.id)
        }

        store.setDefaultProfile(copied.id)
        store.deleteProfile(saved.id)
        assertEquals(listOf(copied.id), store.listProfiles().map { it.id })
        assertIllegalArgument {
            store.deleteProfile(copied.id)
        }
    }

    @Test
    fun rejectsDuplicateNamesAndPersistsLatestTestResult() {
        val first = store.getDefaultProfile()!!
        val second = store.createProfile("第二配置")
        assertIllegalArgument {
            store.saveProfile(second.copy(name = first.name))
        }
        val result = AiTestResult(
            status = AiTestStatus.FAILURE,
            testedAt = 123L,
            durationMillis = 456L,
            message = "failure",
            configFingerprint = second.connectionFingerprint()
        )
        store.saveTestResult(second.id, result)

        val restored = AiConfigStore(context).getProfile(second.id)!!
        assertEquals(result, restored.testResult)
        assertFalse(restored.isTestResultStale())
    }

    @Test
    fun enabledStateAndPromptsAreSavedIndependently() {
        store.savePrompts("analysis", "technique", "mnemonic")
        store.setEnabled(true)

        val restored = AiConfigStore(context)
        assertTrue(restored.isEnabled())
        assertEquals("analysis", restored.analysisPrompt())
        assertEquals("technique", restored.techniquePrompt())
        assertEquals("mnemonic", restored.mnemonicPrompt())

        store.setEnabled(false)
        assertFalse(store.isEnabled())
        assertEquals("analysis", store.analysisPrompt())
    }

    private fun preferences() = context.getSharedPreferences(
        "ai_assistant_settings",
        Context.MODE_PRIVATE
    )

    private fun assertIllegalArgument(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Expected IllegalArgumentException", thrown)
    }
}
