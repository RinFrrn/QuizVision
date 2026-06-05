package com.virin.visionquiz.quizlist.quizcontent

import android.content.Context
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.answerString
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.dao.typeString
import com.virin.visionquiz.util.convertNumToChar

fun showQuizContentDialog(context: Context, quiz: Quiz) {
    showQuizContentDialog(context, listOf(quiz), 0)
}

fun showQuizContentDialog(context: Context, quizzes: List<Quiz>, initialIndex: Int) {
    if (quizzes.isEmpty()) return

    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_quiz_content, null)
    val dialogBuilder = MaterialAlertDialogBuilder(context)
        .setView(dialogView)

    val progressTextView = dialogView.findViewById<TextView>(R.id.progressTextView)
    val typeTextView = dialogView.findViewById<TextView>(R.id.typeTextView)
    val promptTextView = dialogView.findViewById<TextView>(R.id.promptTextView)
    val optionsLabelTextView = dialogView.findViewById<TextView>(R.id.optionsLabelTextView)
    val optionsTextView = dialogView.findViewById<TextView>(R.id.optionsTextView)
    val answerLabelTextView = dialogView.findViewById<TextView>(R.id.answerLabelTextView)
    val answerTextView = dialogView.findViewById<TextView>(R.id.answerTextView)
    val metaTextView = dialogView.findViewById<TextView>(R.id.metaTextView)
    val previousButton = dialogView.findViewById<MaterialButton>(R.id.previousButton)
    val nextButton = dialogView.findViewById<MaterialButton>(R.id.nextButton)
    val closeButton = dialogView.findViewById<MaterialButton>(R.id.closeButton)

    val dialog = dialogBuilder.create()
    var currentIndex = initialIndex.coerceIn(quizzes.indices)

    fun buildOptionsText(quiz: Quiz): SpannableStringBuilder {
        return SpannableStringBuilder().apply {
            quiz.options.mapIndexed { idx, text ->
                val optionText = if (text.isBlank()) "（空）" else text
                val spannable = SpannableString("${convertNumToChar(idx)}. $optionText")
                val color = if (quiz.answer.contains(idx)) {
                    MaterialColors.getColor(dialogView, R.attr.colorPrimary)
                } else {
                    MaterialColors.getColor(dialogView, R.attr.colorOnSurface)
                }
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    0,
                    spannable.length,
                    Spannable.SPAN_INCLUSIVE_EXCLUSIVE
                )
                spannable
            }.forEachIndexed { idx, span ->
                append(span)
                if (idx + 1 < quiz.options.size) appendLine()
            }
        }
    }

    fun applyTypeStyle(type: QuizUiType) {
        val (backgroundColor, textColor) = when (type) {
            QuizUiType.SINGLE_CHOICE -> typeColors(dialogView, R.attr.colorPrimaryContainer, R.attr.colorOnPrimaryContainer)
            QuizUiType.MULTIPLE_CHOICE -> typeColors(dialogView, R.attr.colorSecondaryContainer, R.attr.colorOnSecondaryContainer)
            QuizUiType.JUDGEMENT -> typeColors(dialogView, R.attr.colorTertiaryContainer, R.attr.colorOnTertiaryContainer)
            QuizUiType.FILL_BLANK -> typeColors(dialogView, R.attr.colorErrorContainer, R.attr.colorOnErrorContainer)
            QuizUiType.SUBJECTIVE -> typeColors(dialogView, R.attr.colorSurfaceContainerHighest, R.attr.colorOnSurfaceVariant)
        }
        typeTextView.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        typeTextView.setTextColor(textColor)
    }

    fun render(index: Int) {
        val quiz = quizzes[index]
        progressTextView.text = "第 ${index + 1} / ${quizzes.size} 题"
        promptTextView.text = quiz.prompt
        val type = quiz.inferredUiType()
        typeTextView.text = type.label
        applyTypeStyle(type)

        val hasOptions = quiz.options.any { it.isNotBlank() }
        optionsLabelTextView.visibility = if (hasOptions) View.VISIBLE else View.GONE
        optionsTextView.visibility = if (hasOptions) View.VISIBLE else View.GONE
        if (hasOptions) {
            optionsTextView.text = buildOptionsText(quiz)
        }

        answerLabelTextView.text = if (type == QuizUiType.SUBJECTIVE) "参考答案" else "答案"
        answerTextView.text = "答案：${quiz.answerString()}"
        metaTextView.text = "题型：${quiz.typeString()} · 题库 ID：${quiz.libraryId}"

        previousButton.isEnabled = index > 0
        nextButton.isEnabled = index < quizzes.lastIndex
    }

    previousButton.setOnClickListener {
        if (currentIndex > 0) {
            currentIndex -= 1
            render(currentIndex)
        }
    }
    nextButton.setOnClickListener {
        if (currentIndex < quizzes.lastIndex) {
            currentIndex += 1
            render(currentIndex)
        }
    }
    closeButton.setOnClickListener { dialog.dismiss() }

    render(currentIndex)
    dialog.show()
}

private fun typeColors(view: View, backgroundAttr: Int, textAttr: Int): Pair<Int, Int> {
    val background = MaterialColors.getColor(view, backgroundAttr)
    val text = MaterialColors.getColor(view, textAttr)
    return background to text
}
