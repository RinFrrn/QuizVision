package com.virin.visionquiz.ai

import android.content.Context
import com.virin.visionquiz.dao.AiExplanationCache
import com.virin.visionquiz.dao.AiExplanationCacheDao
import com.virin.visionquiz.dao.QuizDatabase

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
        onPartialContent: (String) -> Unit = {}
    ): AiExplanationResult {
        val fingerprint = prompt.fingerprint(config, type)
        if (!forceRefresh) {
            cacheDao.getCache(quizId, type.value)
                ?.takeIf { it.fingerprint == fingerprint }
                ?.let { return AiExplanationResult(it.content, fromCache = true) }
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
        return AiExplanationResult(content, fromCache = false)
    }

    suspend fun clearAll() = cacheDao.clearAll()

    companion object {
        private const val PARTIAL_UPDATE_INTERVAL_NANOS = 80_000_000L
    }
}
