package com.virin.visionquiz.vision.questiondetector

/**
 * Removes OCR-only question chrome while preserving ordinary prompt text.
 *
 * Prefix removal is anchored to the beginning and requires an explicit question label or numbered
 * question delimiter. Score removal is anchored to the end and requires both parentheses and the
 * unit "分", so years and meaningful parenthesized prompt content are left intact.
 */
internal object OcrQuestionTextCleaner {

    fun clean(text: String, enabled: Boolean = true): String {
        if (!enabled || text.isBlank()) {
            return text
        }

        var cleaned = text.trim()
        cleaned = TRAILING_SCORE_REGEX.replace(cleaned, "").trimEnd()

        val typeMatch = QUESTION_TYPE_PREFIX_REGEX.find(cleaned)
        if (typeMatch != null) {
            cleaned = cleaned.substring(typeMatch.range.last + 1).trimStart()
        } else {
            val questionNumberLabel = QUESTION_NUMBER_LABEL_PREFIX_REGEX.find(cleaned)
            if (questionNumberLabel != null) {
                cleaned = cleaned.substring(questionNumberLabel.range.last + 1).trimStart()
            }
        }

        val numberMatch = NUMBERED_QUESTION_PREFIX_REGEX.find(cleaned)
        if (numberMatch != null) {
            cleaned = cleaned.substring(numberMatch.range.last + 1).trimStart()
        }
        return cleaned
    }

    private const val QUESTION_TYPE = "(?:单选题|多选题|判断题|填空题)"
    private const val DIGIT = "[0-9０-９]"

    private val TRAILING_SCORE_REGEX = Regex(
        """\s*[（(]\s*(?:$DIGIT\s*)+(?:[.．]\s*(?:$DIGIT\s*)+)?分\s*[)）]\s*$"""
    )
    private val QUESTION_TYPE_PREFIX_REGEX = Regex(
        """^\s*(?:[【\[]\s*$QUESTION_TYPE\s*[】\]]\s*(?:[:：、]\s*)?|$QUESTION_TYPE\s*[:：、]\s*|$QUESTION_TYPE(?=\s*(?:第\s*)?$DIGIT)\s*)"""
    )
    private val QUESTION_NUMBER_LABEL_PREFIX_REGEX = Regex(
        """^\s*题号\s*[:：]?\s*(?=(?:第\s*)?$DIGIT)"""
    )
    private val NUMBERED_QUESTION_PREFIX_REGEX = Regex(
        """^\s*(?:(?:第\s*)?$DIGIT{1,4}\s*题\s*[、.．:：)）]?|$DIGIT{1,4}\s*[、.．:：)）])\s*"""
    )
}
