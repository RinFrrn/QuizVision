package com.virin.visionquiz.ai

import android.content.Context
import com.virin.visionquiz.dao.AiExplanationCache
import com.virin.visionquiz.dao.AiExplanationCacheDao
import com.virin.visionquiz.dao.QuizDatabase
import java.io.IOException
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

            fun emitDelta(delta: String) {
                partialContent.append(delta)
                val now = System.nanoTime()
                if (lastEmittedContent.isEmpty() ||
                    now - lastEmissionNanos >= PARTIAL_UPDATE_INTERVAL_NANOS
                ) {
                    lastEmittedContent = partialContent.toString()
                    lastEmissionNanos = now
                    onPartialContent(lastEmittedContent)
                }
            }

            fun emitContent(content: String) {
                if (content != lastEmittedContent) {
                    lastEmittedContent = content
                    onPartialContent(content)
                }
            }

            suspend fun requestContent(requestPrompt: AiPrompt): String {
                return completeStreaming(config, requestPrompt, ::emitDelta).trim()
            }

            var content = requestContent(prompt)
            if (type == AiExplanationType.EXISTING_SIMILAR_ANALYSIS) {
                var continuationAttempts = 0
                while (
                    existingSimilarAnalysisNeedsContinuation(content) &&
                    continuationAttempts < MAX_EXISTING_SIMILAR_CONTINUATIONS
                ) {
                    continuationAttempts++
                    val continuation = requestContent(
                        buildExistingSimilarAnalysisContinuationPrompt(prompt, content)
                    )
                    content = listOf(content, continuation)
                        .filter(String::isNotBlank)
                        .joinToString("\n")
                        .trim()
                    emitContent(content)
                }
                if (existingSimilarAnalysisNeedsContinuation(content)) {
                    throw IOException("相似题解析生成不完整，请重试")
                }
            }
            if (content != lastEmittedContent) {
                emitContent(content)
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
        private const val MAX_EXISTING_SIMILAR_CONTINUATIONS = 1
        private val EXISTING_SIMILAR_ANALYSIS_REQUIRED_HEADINGS = listOf(
            "### 考点关系",
            "### 题目对照",
            "### 混淆点",
            "### 做题抓手"
        )
        private val COMPLETE_END_CHARS = setOf(
            '。', '！', '？', '.', '!', '?', '）', ')', '”', '"', '】', '」', '》'
        )

        private fun existingSimilarAnalysisNeedsContinuation(content: String): Boolean {
            val trimmed = content.trim()
            if (trimmed.isBlank()) return true
            if (EXISTING_SIMILAR_ANALYSIS_REQUIRED_HEADINGS.any { it !in trimmed }) return true
            return trimmed.lastOrNull() !in COMPLETE_END_CHARS
        }

        private fun buildExistingSimilarAnalysisContinuationPrompt(
            originalPrompt: AiPrompt,
            partialContent: String
        ): AiPrompt {
            return AiPrompt(
                system = originalPrompt.system,
                user = buildString {
                    appendLine("以下相似题辨析内容生成中断，请从中断处继续补全。")
                    appendLine("不要重复已完整写出的段落，不要重新开始，不要输出说明性开场。")
                    appendLine("必须补齐并完整写完以下四个标题：")
                    EXISTING_SIMILAR_ANALYSIS_REQUIRED_HEADINGS.forEach(::appendLine)
                    appendLine()
                    appendLine("原任务：")
                    appendLine(originalPrompt.user)
                    appendLine()
                    appendLine("已生成内容：")
                    appendLine(partialContent.trim())
                    appendLine()
                    appendLine("请只输出剩余内容，保持同一 Markdown 格式，并以完整句子收尾。")
                }
            )
        }
    }
}
