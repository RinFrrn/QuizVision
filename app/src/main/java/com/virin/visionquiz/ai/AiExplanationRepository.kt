package com.virin.visionquiz.ai

import android.content.Context
import com.virin.visionquiz.dao.AiExplanationCache
import com.virin.visionquiz.dao.QuizDatabase

data class AiExplanationResult(
    val content: String,
    val fromCache: Boolean
)

class AiExplanationRepository(
    context: Context,
    private val client: OpenAiCompatibleClient = OpenAiCompatibleClient()
) {
    private val cacheDao = QuizDatabase.getInstance(context).aiExplanationCacheDao()

    suspend fun getOrGenerate(
        quizId: Int,
        libraryId: Int,
        type: AiExplanationType,
        config: AiConfig,
        prompt: AiPrompt,
        forceRefresh: Boolean
    ): AiExplanationResult {
        val fingerprint = prompt.fingerprint(config, type)
        if (!forceRefresh) {
            cacheDao.getCache(quizId, type.value)
                ?.takeIf { it.fingerprint == fingerprint }
                ?.let { return AiExplanationResult(it.content, fromCache = true) }
        }
        val content = client.complete(config, prompt)
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
}
