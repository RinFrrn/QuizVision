package com.virin.visionquiz.ai

import android.content.Context
import com.virin.visionquiz.dao.AiExplanationCache
import com.virin.visionquiz.dao.AiExplanationCacheDao
import com.virin.visionquiz.dao.QuizDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AiExplanationResult(
    val content: String,
    val fromCache: Boolean
)

class AiExplanationRepository internal constructor(
    private val cacheDao: AiExplanationCacheDao,
    private val completeStreaming: suspend (
        AiConfig,
        AiPrompt,
        (String) -> Unit
    ) -> String
) {
    private val generationMutex = Mutex()

    constructor(
        context: Context,
        client: OpenAiCompatibleClient = OpenAiCompatibleClient()
    ) : this(
        QuizDatabase.getInstance(context).aiExplanationCacheDao(),
        client::completeStreaming
    )

    suspend fun getOrGenerate(
        quizId: Int,
        libraryId: Int,
        type: AiExplanationType,
        config: AiConfig,
        prompt: AiPrompt,
        forceRefresh: Boolean,
        strictFingerprint: Boolean = false,
        onPartialContent: (String) -> Unit = {}
    ): AiExplanationResult {
        val fingerprint = prompt.fingerprint(config, type)
        if (!forceRefresh) {
            findMatchingCache(quizId, type, fingerprint, strictFingerprint)?.let { return it }
        }
        return generationMutex.withLock {
            if (!forceRefresh) {
                findMatchingCache(quizId, type, fingerprint, strictFingerprint)?.let { return@withLock it }
            }
            val partialContent = StringBuilder()
            var lastEmittedContent = ""
            var lastEmissionNanos = 0L
            val content = completeStreaming(config, prompt) { delta ->
                partialContent.append(delta)
                val now = System.nanoTime()
                if (lastEmittedContent.isEmpty() ||
                    now - lastEmissionNanos >= PARTIAL_UPDATE_INTERVAL_NANOS
                ) {
                    lastEmittedContent = partialContent.toString()
                    lastEmissionNanos = now
                    onPartialContent(lastEmittedContent)
                }
            }.trim()
            if (content != lastEmittedContent) {
                onPartialContent(content)
            }
            val now = System.currentTimeMillis()
            val existing = cacheDao.getCache(quizId, type.value)
            cacheDao.upsertCache(
                AiExplanationCache(
                    id = existing?.id ?: 0,
                    quizId = quizId,
                    libraryId = libraryId,
                    type = type.value,
                    fingerprint = fingerprint,
                    content = content,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
            AiExplanationResult(content, fromCache = false)
        }
    }

    private suspend fun findMatchingCache(
        quizId: Int,
        type: AiExplanationType,
        fingerprint: String,
        strictFingerprint: Boolean
    ): AiExplanationResult? {
        val cached = cacheDao.getCache(quizId, type.value) ?: return null
        if (strictFingerprint && cached.fingerprint != fingerprint) return null
        // Return cached content if fingerprint matches exactly,
        // or if a cache exists for the same quiz+type (e.g. batch-generated
        // with selectedAnswer=null, requested during study with a real answer)
        return AiExplanationResult(cached.content, fromCache = true)
    }

    suspend fun clearAll() = cacheDao.clearAll()

    companion object {
        private const val PARTIAL_UPDATE_INTERVAL_NANOS = 80_000_000L
    }
}
