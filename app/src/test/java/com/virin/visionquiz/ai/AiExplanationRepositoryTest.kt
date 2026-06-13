package com.virin.visionquiz.ai

import com.virin.visionquiz.dao.AiExplanationCache
import com.virin.visionquiz.dao.AiExplanationCacheDao
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExplanationRepositoryTest {
    private val config = AiConfig(
        enabled = true,
        baseUrl = "https://example.com",
        apiKey = "key",
        model = "model",
        analysisPrompt = "analysis",
        techniquePrompt = "technique",
        mnemonicPrompt = "mnemonic"
    )
    private val prompt = AiPrompt("system", "user")

    @Test
    fun streamsPartialContentAndCachesOnlyCompletedResult() = runBlocking {
        val dao = FakeCacheDao()
        val partials = mutableListOf<String>()
        val repository = AiExplanationRepository(dao) { _, _, onDelta ->
            onDelta("第一段")
            onDelta("第二段")
            "第一段第二段"
        }

        val result = repository.getOrGenerate(
            quizId = 1,
            libraryId = 2,
            type = AiExplanationType.ANALYSIS,
            config = config,
            prompt = prompt,
            forceRefresh = false,
            onPartialContent = partials::add
        )

        assertFalse(result.fromCache)
        assertEquals("第一段第二段", result.content)
        assertEquals("第一段", partials.first())
        assertEquals("第一段第二段", partials.last())
        assertEquals("第一段第二段", dao.cache?.content)
    }

    @Test
    fun failureKeepsPartialCallbackButDoesNotWriteCache() = runBlocking {
        val dao = FakeCacheDao()
        val partials = mutableListOf<String>()
        val repository = AiExplanationRepository(dao) { _, _, onDelta ->
            onDelta("半截内容")
            throw IOException("connection lost")
        }

        val error = runCatching {
            repository.getOrGenerate(
                quizId = 1,
                libraryId = 2,
                type = AiExplanationType.ANALYSIS,
                config = config,
                prompt = prompt,
                forceRefresh = false,
                onPartialContent = partials::add
            )
        }.exceptionOrNull()

        assertEquals("connection lost", error?.message)
        assertEquals(listOf("半截内容"), partials)
        assertEquals(null, dao.cache)
    }

    @Test
    fun cacheHitSkipsStreaming() = runBlocking {
        val dao = FakeCacheDao().apply {
            cache = AiExplanationCache(
                quizId = 1,
                libraryId = 2,
                type = AiExplanationType.ANALYSIS.value,
                fingerprint = prompt.fingerprint(config, AiExplanationType.ANALYSIS),
                content = "缓存内容"
            )
        }
        var requested = false
        val repository = AiExplanationRepository(dao) { _, _, _ ->
            requested = true
            "网络内容"
        }

        val result = repository.getOrGenerate(
            quizId = 1,
            libraryId = 2,
            type = AiExplanationType.ANALYSIS,
            config = config,
            prompt = prompt,
            forceRefresh = false
        )

        assertTrue(result.fromCache)
        assertEquals("缓存内容", result.content)
        assertFalse(requested)
    }

    private class FakeCacheDao : AiExplanationCacheDao {
        var cache: AiExplanationCache? = null

        override suspend fun getCache(quizId: Int, type: String): AiExplanationCache? = cache

        override suspend fun upsertCache(cache: AiExplanationCache) {
            this.cache = cache
        }

        override suspend fun deleteByQuizId(quizId: Int) {
            cache = null
        }

        override suspend fun deleteByLibraryId(libraryId: Int) {
            cache = null
        }

        override suspend fun clearAll() {
            cache = null
        }
    }
}
