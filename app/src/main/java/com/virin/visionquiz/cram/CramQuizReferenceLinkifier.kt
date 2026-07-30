package com.virin.visionquiz.cram

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.quizlist.quizcontent.QuizContentExtras
import com.virin.visionquiz.quizlist.quizcontent.QuizContentMemoryPoint
import java.text.Normalizer

internal enum class CramQuizReferenceKind {
    DATABASE_ID,
    SOURCE_ROW,
    LEGACY_NUMBER
}

internal data class CramQuizReferenceTarget(
    val kind: CramQuizReferenceKind,
    val value: Int
) {
    val displayLabel: String
        get() = when (kind) {
            CramQuizReferenceKind.DATABASE_ID -> "题号 $value"
            CramQuizReferenceKind.SOURCE_ROW -> "题#$value"
            CramQuizReferenceKind.LEGACY_NUMBER -> "题号 $value"
        }
}

internal data class CramQuizTextLink(
    val target: CramQuizReferenceTarget,
    val start: Int,
    val endExclusive: Int,
    val referenceStart: Int,
    val referenceEndExclusive: Int,
    val isWeakReference: Boolean = false
)

internal data class CramQuizReferenceContext(
    val target: CramQuizReferenceTarget,
    val occurrenceOrdinal: Int,
    val memoryPoint: QuizContentMemoryPoint?
)

/**
 * Finds only explicit question references. Ordinary years, durations, amounts
 * and other numbers are intentionally ignored.
 */
internal object CramQuizReferenceParser {
    private data class ReferencePattern(
        val regex: Regex,
        val kind: CramQuizReferenceKind,
        val isWeakReference: Boolean = false
    )

    private val referencePatterns = listOf(
        ReferencePattern(
            regex = Regex(
                """题#\s*((?:\d{1,10})(?:\s*[、，,;/；]\s*(?:题#\s*)?\d{1,10})*)"""
            ),
            kind = CramQuizReferenceKind.SOURCE_ROW
        ),
        ReferencePattern(
            regex = Regex(
                """(?:源题号|源序号)\s*[:：]?\s*((?:\d{1,10})(?:\s*[、，,;/；]\s*\d{1,10})*)"""
            ),
            kind = CramQuizReferenceKind.SOURCE_ROW
        ),
        ReferencePattern(
            regex = Regex(
                """\[(?i:ID)\s*[:：]\s*((?:\d{1,10})(?:\s*[、，,;/；]\s*(?:(?i:ID)\s*[:：]\s*)?\d{1,10})*)\]"""
            ),
            kind = CramQuizReferenceKind.DATABASE_ID
        ),
        // Compatibility with existing synthesized reports that shortened
        // [ID:28660] references to [28660, 28661].
        ReferencePattern(
            regex = Regex(
                """\[\s*((?:\d{1,10})(?:\s*[、，,;/；]\s*\d{1,10})*)\s*\]"""
            ),
            kind = CramQuizReferenceKind.LEGACY_NUMBER,
            isWeakReference = true
        ),
        ReferencePattern(
            regex = Regex(
                """(?<!源)题号\s*[:：]?\s*((?:\d{1,10})(?:\s*[、，,;/；]\s*\d{1,10})*)"""
            ),
            kind = CramQuizReferenceKind.DATABASE_ID
        )
    )
    private val numberRegex = Regex("""\d{1,10}""")

    fun find(text: CharSequence): List<CramQuizTextLink> {
        val source = text.toString()
        return referencePatterns
            .flatMap { pattern ->
                pattern.regex.findAll(source).flatMap { match ->
                    val valueGroup = match.groups[1] ?: return@flatMap emptySequence()
                    numberRegex.findAll(valueGroup.value).mapNotNull { numberMatch ->
                        val value = numberMatch.value.toIntOrNull()
                            ?.takeIf { it > 0 }
                            ?: return@mapNotNull null
                        val start = valueGroup.range.first + numberMatch.range.first
                        CramQuizTextLink(
                            target = CramQuizReferenceTarget(pattern.kind, value),
                            start = start,
                            endExclusive = start + numberMatch.value.length,
                            referenceStart = match.range.first,
                            referenceEndExclusive = match.range.last + 1,
                            isWeakReference = pattern.isWeakReference
                        )
                    }
                }.toList()
            }
            .distinctBy { it.start to it.endExclusive }
            .sortedBy(CramQuizTextLink::start)
    }

    fun removeReferences(
        text: String,
        includeWeakReferences: Boolean = true
    ): String {
        return referencePatterns
            .filter { includeWeakReferences || !it.isWeakReference }
            .fold(text) { value, pattern ->
                pattern.regex.replace(value, " ")
            }
    }
}

/**
 * Extracts the smallest useful sentence around every explicit question
 * reference in the source Markdown. Occurrence ordinals let the rendered
 * TextView bind the right sentence even after Markdown markers disappear.
 */
internal object CramQuizMemoryPointExtractor {
    private data class MarkdownBlock(
        val text: String,
        val sectionTitle: String?,
        val canBecomeMemoryPoint: Boolean
    )

    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val listItemRegex = Regex("""^\s*(?:>\s*)?(?:[-+*]|\d+[.)、])\s+""")
    private val fenceRegex = Regex("""^\s*(```+|~~~+)""")
    private val strongBoundaryRegex = Regex("""[。！？!?；;]+""")
    private val inlineMarkdownLinkRegex = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val markdownLinkDefinitionRegex = Regex(
        """(?m)^\s{0,3}\[[^\]\n]+]:\s+\S.*$"""
    )
    private val htmlCommentRegex = Regex("""(?s)<!--.*?-->""")
    private val htmlTagRegex = Regex(
        """</?[A-Za-z][A-Za-z0-9-]*(?:\s+[^>\n]*?)?\s*/?>"""
    )
    private val leadingMarkdownRegex = Regex(
        """(?m)^\s*(?:>\s*)?(?:(?:[-+*]|\d+[.)、])\s+)?"""
    )
    private val genericEvidenceRegex = Regex(
        """^(?:(?:题库)?支持\s*\d+\s*次|(?:请)?(?:参见|查看|回看|核对)(?:本题|原题|题目)?|(?:相关|对应)?题目?|题号索引|索引)$"""
    )
    private val nonMemorySectionRegex = Regex(
        """题号索引|(?:三|3)天安排|30题自测|优先模块|先看结论|统计观察|""" +
            """重复题与冲突核对|数字冲突|禁止直接背|无可靠|待核对|完全不会"""
    )
    private const val MAX_CUE_LENGTH = 120

    fun extract(
        markdown: String,
        sourceLabel: String,
        sourceKey: String = sourceLabel,
        allowLegacyNumericReferences: Boolean = false
    ): List<CramQuizReferenceContext> {
        if (markdown.isBlank()) return emptyList()

        val occurrenceCounts = mutableMapOf<CramQuizReferenceTarget, Int>()
        val sourceId = sourceKey.hashCode().toString()
        return markdownBlocks(visibleMarkdownForReferences(markdown)).flatMap { block ->
            val links = CramQuizReferenceParser.find(block.text)
            links.map { link ->
                val ordinal = occurrenceCounts.getOrDefault(link.target, 0)
                occurrenceCounts[link.target] = ordinal + 1
                val cue = extractCue(
                    block = block,
                    link = link,
                    allowLegacyNumericReferences = allowLegacyNumericReferences
                )
                CramQuizReferenceContext(
                    target = link.target,
                    occurrenceOrdinal = ordinal,
                    memoryPoint = cue?.let {
                        QuizContentMemoryPoint(
                            id = "cram-report-$sourceId-${link.target.kind.name}-" +
                                "${link.target.value}-$ordinal",
                            sourceLabel = sourceLabel,
                            cue = it,
                            supportingText = cleanMarkdown(block.sectionTitle.orEmpty())
                                .takeIf(String::isNotBlank)
                                ?.take(48)
                        )
                    }
                )
            }
        }
    }

    private fun extractCue(
        block: MarkdownBlock,
        link: CramQuizTextLink,
        allowLegacyNumericReferences: Boolean
    ): String? {
        if (!block.canBecomeMemoryPoint) return null
        if (nonMemorySectionRegex.containsMatchIn(block.sectionTitle.orEmpty())) return null
        if (link.isWeakReference && !allowLegacyNumericReferences) {
            return null
        }

        val left = block.text.substring(0, link.referenceStart)
        val leftCandidate = strongBoundaryRegex
            .split(left)
            .asReversed()
            .asSequence()
            .map { cleanMarkdown(it, allowLegacyNumericReferences) }
            .firstOrNull(::isUsefulCue)
        if (leftCandidate != null) return leftCandidate.takeLast(MAX_CUE_LENGTH)

        val right = block.text.substring(link.referenceEndExclusive)
        return strongBoundaryRegex
            .split(right)
            .asSequence()
            .map { cleanMarkdown(it, allowLegacyNumericReferences) }
            .firstOrNull(::isUsefulCue)
            ?.take(MAX_CUE_LENGTH)
    }

    private fun cleanMarkdown(
        value: String,
        removeLegacyNumericReferences: Boolean = false
    ): String {
        return CramQuizReferenceParser.removeReferences(
            text = value,
            includeWeakReferences = removeLegacyNumericReferences
        )
            .replace(inlineMarkdownLinkRegex, "$1")
            .replace(leadingMarkdownRegex, "")
            .replace(Regex("""[*_~`]"""), "")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('，', ',', '、', '。', '；', ';', '：', ':', '（', '）', '(', ')',
                '[', ']', '【', '】', '-', '—', '·', ' ')
    }

    private fun visibleMarkdownForReferences(markdown: String): String {
        return markdown
            .replace(htmlCommentRegex, " ")
            .replace(markdownLinkDefinitionRegex, "")
            .replace(inlineMarkdownLinkRegex, "$1")
            .replace(htmlTagRegex, " ")
    }

    private fun isUsefulCue(value: String): Boolean {
        if (value.isBlank()) return false
        val compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("""[\p{P}\p{S}\s]+"""), "")
        if (compact.length < 4) return false
        return !genericEvidenceRegex.matches(value.replace(Regex("""\s+"""), ""))
    }

    private fun markdownBlocks(markdown: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val current = StringBuilder()
        var currentSection: String? = null
        var blockSection: String? = null
        var inFence = false
        var fenceMarker: String? = null

        fun flush(canBecomeMemoryPoint: Boolean = !inFence) {
            if (current.isEmpty()) return
            blocks += MarkdownBlock(
                text = current.toString(),
                sectionTitle = blockSection,
                canBecomeMemoryPoint = canBecomeMemoryPoint
            )
            current.clear()
        }

        markdown.lineSequence().forEach { line ->
            val fence = fenceRegex.find(line)?.groupValues?.getOrNull(1)
            if (inFence) {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
                if (fence != null && fence.firstOrNull() == fenceMarker?.firstOrNull()) {
                    flush(canBecomeMemoryPoint = false)
                    inFence = false
                    fenceMarker = null
                }
                return@forEach
            }
            if (fence != null) {
                flush()
                inFence = true
                fenceMarker = fence
                blockSection = currentSection
                current.append(line)
                return@forEach
            }

            val heading = headingRegex.matchEntire(line)
            if (heading != null) {
                flush()
                currentSection = cleanMarkdown(heading.groupValues[2]).takeIf(String::isNotBlank)
                blocks += MarkdownBlock(
                    text = line,
                    sectionTitle = currentSection,
                    canBecomeMemoryPoint = false
                )
                return@forEach
            }
            if (line.isBlank()) {
                flush()
                return@forEach
            }
            if (listItemRegex.containsMatchIn(line)) {
                flush()
            }
            if (current.isEmpty()) {
                blockSection = currentSection
            } else {
                current.append('\n')
            }
            current.append(line)
        }
        flush(canBecomeMemoryPoint = !inFence)
        return blocks
    }
}

internal data class CramQuizSheetSelection(
    val quizzes: List<Quiz>,
    val initialIndex: Int,
    val allQuizzes: List<Quiz> = quizzes,
    val extras: QuizContentExtras = QuizContentExtras()
)

/**
 * Keeps the exact list used by the existing quiz-content sheet so resolving a
 * reference and calculating its initial page cannot drift apart.
 */
internal class CramQuizReferenceIndex(quizzes: List<Quiz>) {
    private val quizzes = quizzes.toList()
    private val indexByDatabaseId = this.quizzes
        .mapIndexed { index, quiz -> quiz.id to index }
        .toMap()
    private val indexBySourceRow = buildMap {
        val ambiguousRows = mutableSetOf<Int>()
        this@CramQuizReferenceIndex.quizzes.forEachIndexed { index, quiz ->
            quiz.sourceRow?.let { sourceRow ->
                when {
                    sourceRow in ambiguousRows -> Unit
                    containsKey(sourceRow) -> {
                        remove(sourceRow)
                        ambiguousRows += sourceRow
                    }
                    else -> put(sourceRow, index)
                }
            }
        }
    }

    fun contains(target: CramQuizReferenceTarget): Boolean = resolveIndex(target) != null

    fun selection(target: CramQuizReferenceTarget): CramQuizSheetSelection? {
        val index = resolveIndex(target) ?: return null
        return CramQuizSheetSelection(
            quizzes = quizzes,
            initialIndex = index,
            allQuizzes = quizzes
        )
    }

    fun selectionForDatabaseIds(databaseIds: List<Int>): CramQuizSheetSelection? {
        val selectedQuizzes = databaseIds
            .asSequence()
            .distinct()
            .mapNotNull(indexByDatabaseId::get)
            .map(quizzes::get)
            .toList()
        if (selectedQuizzes.isEmpty()) return null

        return CramQuizSheetSelection(
            quizzes = selectedQuizzes,
            initialIndex = 0,
            allQuizzes = quizzes
        )
    }

    fun extrasFor(
        contexts: List<CramQuizReferenceContext>,
        preferredMemoryPointId: String? = null,
        showMemoryPointEmptyState: Boolean = true
    ): QuizContentExtras {
        val orderedContexts = if (preferredMemoryPointId == null) {
            contexts
        } else {
            contexts.sortedByDescending { it.memoryPoint?.id == preferredMemoryPointId }
        }
        val pointsByQuizId = linkedMapOf<Int, MutableList<QuizContentMemoryPoint>>()
        val signaturesByQuizId = mutableMapOf<Int, MutableSet<String>>()
        orderedContexts.forEach { context ->
            val point = context.memoryPoint ?: return@forEach
            val index = resolveIndex(context.target) ?: return@forEach
            val quizId = quizzes[index].id.takeIf { it > 0 } ?: return@forEach
            val signature = memoryPointSignature(point)
            val seenSignatures = signaturesByQuizId.getOrPut(quizId, ::linkedSetOf)
            if (!seenSignatures.add(signature)) return@forEach
            val points = pointsByQuizId.getOrPut(quizId, ::mutableListOf)
            if (points.size < MAX_MEMORY_POINTS_PER_QUIZ) {
                points += point
            }
        }
        return QuizContentExtras(
            memoryPointsByQuizId = pointsByQuizId.mapValues { (_, value) -> value.toList() },
            preferredMemoryPointId = preferredMemoryPointId,
            showMemoryPointEmptyState = showMemoryPointEmptyState
        )
    }

    private fun resolveIndex(target: CramQuizReferenceTarget): Int? {
        return when (target.kind) {
            CramQuizReferenceKind.DATABASE_ID -> indexByDatabaseId[target.value]
            CramQuizReferenceKind.SOURCE_ROW -> indexBySourceRow[target.value]
            CramQuizReferenceKind.LEGACY_NUMBER -> {
                val databaseIndex = indexByDatabaseId[target.value]
                val sourceRowIndex = indexBySourceRow[target.value]
                when {
                    databaseIndex == null -> sourceRowIndex
                    sourceRowIndex == null -> databaseIndex
                    databaseIndex == sourceRowIndex -> databaseIndex
                    else -> null
                }
            }
        }
    }

    private fun memoryPointSignature(point: QuizContentMemoryPoint): String {
        return Normalizer.normalize(
            "${point.cue}|${point.context}",
            Normalizer.Form.NFKC
        )
            .lowercase()
            .replace(Regex("""[\p{P}\p{S}\s]+"""), "")
    }

    private companion object {
        const val MAX_MEMORY_POINTS_PER_QUIZ = 12
    }
}

internal object CramQuizReferenceLinkifier {
    fun linkify(
        textView: TextView,
        linkColor: Int,
        isResolvable: (CramQuizReferenceTarget) -> Boolean,
        referenceContexts: List<CramQuizReferenceContext> = emptyList(),
        onQuizClick: (CramQuizReferenceContext) -> Unit
    ): Int {
        val links = CramQuizReferenceParser.find(textView.text)
            .filter { isResolvable(it.target) }
        if (links.isEmpty()) return 0

        val contextByOccurrence = referenceContexts.associateBy {
            it.target to it.occurrenceOrdinal
        }
        val occurrenceCounts = mutableMapOf<CramQuizReferenceTarget, Int>()
        val linkedText = SpannableStringBuilder(textView.text)
        links.forEach { link ->
            val ordinal = occurrenceCounts.getOrDefault(link.target, 0)
            occurrenceCounts[link.target] = ordinal + 1
            val referenceContext = contextByOccurrence[link.target to ordinal]
                ?: CramQuizReferenceContext(
                    target = link.target,
                    occurrenceOrdinal = ordinal,
                    memoryPoint = null
                )
            linkedText.setSpan(
                QuizReferenceClickableSpan(
                    referenceContext = referenceContext,
                    linkColor = linkColor,
                    onQuizClick = onQuizClick
                ),
                link.start,
                link.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        textView.text = linkedText
        textView.linksClickable = true
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = ColorUtils.setAlphaComponent(linkColor, 32)
        return links.size
    }

    private class QuizReferenceClickableSpan(
        private val referenceContext: CramQuizReferenceContext,
        private val linkColor: Int,
        private val onQuizClick: (CramQuizReferenceContext) -> Unit
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            onQuizClick(referenceContext)
        }

        override fun updateDrawState(drawState: TextPaint) {
            super.updateDrawState(drawState)
            drawState.color = linkColor
            drawState.isUnderlineText = true
        }
    }
}
