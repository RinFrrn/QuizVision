package com.virin.visionquiz.ai

import com.virin.visionquiz.dao.AiExplanationCache
import com.virin.visionquiz.dao.AiExplanationCacheDao
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun strictFingerprintMissesStaleCache() = runBlocking {
        val dao = FakeCacheDao().apply {
            cache = AiExplanationCache(
                quizId = 1,
                libraryId = 2,
                type = AiExplanationType.EXISTING_SIMILAR_ANALYSIS.value,
                fingerprint = "old-fingerprint",
                content = "旧相似题分析"
            )
        }
        var requested = false
        val repository = AiExplanationRepository(dao) { _, _, _ ->
            requested = true
            COMPLETE_EXISTING_SIMILAR_ANALYSIS
        }

        val result = repository.getOrGenerate(
            quizId = 1,
            libraryId = 2,
            type = AiExplanationType.EXISTING_SIMILAR_ANALYSIS,
            config = config,
            prompt = prompt,
            forceRefresh = false,
            strictFingerprint = true
        )

        assertFalse(result.fromCache)
        assertTrue(requested)
        assertEquals(COMPLETE_EXISTING_SIMILAR_ANALYSIS, result.content)
        assertEquals(
            prompt.fingerprint(config, AiExplanationType.EXISTING_SIMILAR_ANALYSIS),
            dao.cache?.fingerprint
        )
    }

    @Test
    fun existingSimilarAnalysisContinuesWhenRequiredSectionsAreMissing() = runBlocking {
        val dao = FakeCacheDao()
        var requestCount = 0
        val repository = AiExplanationRepository(dao) { _, _, onDelta ->
            requestCount++
            if (requestCount == 1) {
                val partial = "### 考点关系\n**共同考点**\n### 题目对照\n相似题 1：**相同点**"
                onDelta(partial)
                partial
            } else {
                val continuation = "\n### 混淆点\n注意**限定条件**。\n### 做题抓手\n核验**答案依据**。"
                onDelta(continuation)
                continuation
            }
        }

        val result = repository.getOrGenerate(
            quizId = 1,
            libraryId = 2,
            type = AiExplanationType.EXISTING_SIMILAR_ANALYSIS,
            config = config,
            prompt = prompt,
            forceRefresh = false,
            strictFingerprint = true
        )

        assertEquals(2, requestCount)
        assertTrue(result.content.contains("### 考点关系"))
        assertTrue(result.content.contains("### 题目对照"))
        assertTrue(result.content.contains("### 混淆点"))
        assertTrue(result.content.contains("### 做题抓手"))
        assertEquals(result.content, dao.cache?.content)
    }

    @Test
    fun concurrentRequestsForSameContentUseOneNetworkGeneration() = runBlocking {
        val dao = FakeCacheDao()
        val generationStarted = CompletableDeferred<Unit>()
        val releaseGeneration = CompletableDeferred<Unit>()
        var requestCount = 0
        val repository = AiExplanationRepository(dao) { _, _, _ ->
            requestCount++
            generationStarted.complete(Unit)
            releaseGeneration.await()
            "完整内容"
        }

        val first = async {
            repository.getOrGenerate(
                quizId = 1,
                libraryId = 2,
                type = AiExplanationType.QUICK_REVIEW,
                config = config,
                prompt = prompt,
                forceRefresh = false
            )
        }
        generationStarted.await()
        val second = async {
            repository.getOrGenerate(
                quizId = 1,
                libraryId = 2,
                type = AiExplanationType.QUICK_REVIEW,
                config = config,
                prompt = prompt,
                forceRefresh = false
            )
        }
        delay(20)
        releaseGeneration.complete(Unit)

        assertFalse(first.await().fromCache)
        assertTrue(second.await().fromCache)
        assertEquals(1, requestCount)
    }

    @Test
    fun cacheHitDoesNotWaitForActiveNetworkGeneration() = runBlocking {
        val dao = FakeCacheDao().apply {
            upsertCache(
                AiExplanationCache(
                    quizId = 2,
                    libraryId = 2,
                    type = AiExplanationType.QUICK_REVIEW.value,
                    fingerprint = prompt.fingerprint(config, AiExplanationType.QUICK_REVIEW),
                    content = "已缓存"
                )
            )
        }
        val generationStarted = CompletableDeferred<Unit>()
        val releaseGeneration = CompletableDeferred<Unit>()
        val repository = AiExplanationRepository(dao) { _, _, _ ->
            generationStarted.complete(Unit)
            releaseGeneration.await()
            "网络内容"
        }
        val activeRequest = async {
            repository.getOrGenerate(
                quizId = 1,
                libraryId = 2,
                type = AiExplanationType.DETAILED_ANALYSIS,
                config = config,
                prompt = prompt,
                forceRefresh = false
            )
        }
        generationStarted.await()

        val cached = withTimeout(500) {
            repository.getOrGenerate(
                quizId = 2,
                libraryId = 2,
                type = AiExplanationType.QUICK_REVIEW,
                config = config,
                prompt = prompt,
                forceRefresh = false
            )
        }

        assertTrue(cached.fromCache)
        assertEquals("已缓存", cached.content)
        releaseGeneration.complete(Unit)
        activeRequest.await()
        Unit
    }

    private companion object {
        private const val COMPLETE_EXISTING_SIMILAR_ANALYSIS =
            "### 考点关系\n**共同考点**。\n" +
                "### 题目对照\n相似题 1：**相同点** / **关键差异** / **答案影响**。\n" +
                "### 混淆点\n注意**限定条件**。\n" +
                "### 做题抓手\n核验**答案依据**。"
    }

    private class FakeCacheDao : AiExplanationCacheDao {
        private val caches = mutableMapOf<Pair<Int, String>, AiExplanationCache>()
        var cache: AiExplanationCache?
            get() = caches.values.firstOrNull()
            set(value) {
                caches.clear()
                value?.let { caches[it.quizId to it.type] = it }
            }

        override suspend fun getCache(quizId: Int, type: String): AiExplanationCache? {
            return caches[quizId to type]
        }

        override suspend fun upsertCache(cache: AiExplanationCache) {
            caches[cache.quizId to cache.type] = cache
        }

        override suspend fun deleteByQuizId(quizId: Int) {
            caches.keys.removeAll { it.first == quizId }
        }

        override suspend fun deleteByLibraryId(libraryId: Int) {
            caches.entries.removeAll { it.value.libraryId == libraryId }
        }

        override suspend fun clearAll() {
            caches.clear()
        }

        override suspend fun countByLibraryAndType(libraryId: Int, type: String): Int {
            return caches.values.count { it.libraryId == libraryId && it.type == type }
        }
    }
}
