package com.virin.visionquiz.quizlist

import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.answerOptionsString
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.databinding.ItemQuizBinding

class QuizListAdapter constructor(private val listener: OnQuizClickListener) :
    ListAdapter<Quiz, QuizListAdapter.ViewHolder>(DiffCallback()) {

    private var keywordHighlightConfig = KeywordHighlightConfig()
    private var statsByQuizId: Map<Int, QuizItemStats> = emptyMap()

    fun setKeywordHighlightConfig(config: KeywordHighlightConfig) {
        if (keywordHighlightConfig == config) return
        keywordHighlightConfig = config
        notifyDataSetChanged()
    }

    fun setQuizStats(stats: Map<Int, QuizItemStats>) {
        if (statsByQuizId == stats) return
        statsByQuizId = stats
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemQuizBinding.inflate(inflater, parent, false)

        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val quiz = getItem(position)
        holder.bind(quiz, listener, keywordHighlightConfig, statsByQuizId[quiz.id])
    }

    class ViewHolder(
        private val binding: ItemQuizBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            quiz: Quiz,
            listener: OnQuizClickListener,
            highlightConfig: KeywordHighlightConfig,
            stats: QuizItemStats?
        ) {
            binding.root.setOnClickListener {
                listener.onQuizClicked(quiz)
            }

            binding.quiz = quiz

            val idx = adapterPosition + 1
            binding.title = buildTitle(idx, quiz.prompt, highlightConfig)

            val answerChars = quiz.answerOptionsString()
            val correctOpts = quiz.options.filterIndexed { index, s -> quiz.answer.contains(index) }.joinToString(", ")
//            val optsSpan = quiz.options.filterIndexed { index, s -> quiz.answer.contains(index) }.joinToString(", ")

            val spannableSubtitle = if (highlightConfig.shouldHighlightAnswers()) {
                buildOptionsSubtitle(quiz, answerChars, highlightConfig)
            } else {
                SpannableString("($answerChars)  $correctOpts")
            }
//            spannableString.setSpan(
//                ForegroundColorSpan(Color.RED),
//                5,
//                7,
//                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
//            )
//            spannableSubtitle.setSpan(
//                StyleSpan(Typeface.BOLD),
//                0,
//                answerChars.length + 2,
//                Spannable.SPAN_INCLUSIVE_INCLUSIVE
//            )

            binding.subtitle = spannableSubtitle
            binding.statsTv.isVisible = stats != null
            binding.statsTv.text = stats?.displayText().orEmpty()
            val type = quiz.inferredUiType()
            binding.typeLabel = type.label
            applyTypeStyle(type)

//            binding.name.text = quizLibrary.name
//            binding.quizCount.text = binding.root.context.getString(R.string.quiz_count, quizLibrary.quizCount)
        }

        private fun buildTitle(
            idx: Int,
            prompt: String,
            highlightConfig: KeywordHighlightConfig
        ): CharSequence {
            val prefix = "$idx. "
            val title = SpannableString(prefix + prompt)
            if (!highlightConfig.shouldHighlightPrompt()) return title

            val highlightColors = highlightColors()
            QuizKeywordSearch.findRanges(prompt, highlightConfig.query).forEach { range ->
                applyHighlightSpan(title, prefix.length + range.first, prefix.length + range.last + 1, highlightColors)
            }
            return title
        }

        private fun buildOptionsSubtitle(
            quiz: Quiz,
            answerChars: String,
            highlightConfig: KeywordHighlightConfig
        ): CharSequence {
            val highlightColors = highlightColors()
            val builder = SpannableStringBuilder("($answerChars)  ")
            var appendedOption = false

            quiz.options.forEachIndexed { index, option ->
                if (option.isBlank()) return@forEachIndexed
                if (appendedOption) builder.append("  ")

                val optionPrefix = "${'A' + index}. "
                val optionStart = builder.length + optionPrefix.length
                builder.append(optionPrefix)
                builder.append(option)
                QuizKeywordSearch.findRanges(option, highlightConfig.query).forEach { range ->
                    applyHighlightSpan(builder, optionStart + range.first, optionStart + range.last + 1, highlightColors)
                }
                appendedOption = true
            }

            return if (appendedOption) builder else SpannableString("($answerChars)")
        }

        private fun applyHighlightSpan(
            text: Spannable,
            start: Int,
            end: Int,
            colors: HighlightColors
        ) {
            text.setSpan(
                BackgroundColorSpan(colors.background),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                ForegroundColorSpan(colors.foreground),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        private fun highlightColors(): HighlightColors {
            return HighlightColors(
                background = MaterialColors.getColor(binding.root, R.attr.colorPrimaryContainer),
                foreground = MaterialColors.getColor(binding.root, R.attr.colorOnPrimaryContainer)
            )
        }

        private fun applyTypeStyle(type: QuizUiType) {
            val (backgroundColor, textColor) = when (type) {
                QuizUiType.SINGLE_CHOICE -> typeColors(
                    R.attr.colorPrimaryContainer,
                    R.attr.colorOnPrimaryContainer
                )

                QuizUiType.MULTIPLE_CHOICE -> typeColors(
                    R.attr.colorSecondaryContainer,
                    R.attr.colorOnSecondaryContainer
                )

                QuizUiType.JUDGEMENT -> typeColors(
                    R.attr.colorTertiaryContainer,
                    R.attr.colorOnTertiaryContainer
                )

                QuizUiType.FILL_BLANK -> typeColors(
                    R.attr.colorErrorContainer,
                    R.attr.colorOnErrorContainer
                )

                QuizUiType.SUBJECTIVE -> typeColors(
                    R.attr.colorSurfaceContainerHighest,
                    R.attr.colorOnSurfaceVariant
                )
            }

            binding.typeTv.backgroundTintList = ColorStateList.valueOf(backgroundColor)
            binding.typeTv.setTextColor(textColor)
        }

        private fun typeColors(backgroundAttr: Int, textAttr: Int): Pair<Int, Int> {
            val background = MaterialColors.getColor(binding.root, backgroundAttr)
            val text = MaterialColors.getColor(binding.root, textAttr)
            return background to text
        }

        private data class HighlightColors(
            val background: Int,
            val foreground: Int
        )
    }

    private class DiffCallback : DiffUtil.ItemCallback<Quiz>() {
        override fun areItemsTheSame(
            oldItem: Quiz,
            newItem: Quiz
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: Quiz,
            newItem: Quiz
        ): Boolean {
            return oldItem == newItem
        }
    }


    interface OnQuizClickListener {

        fun onQuizClicked(quiz: Quiz)
    }

    data class QuizItemStats(
        val correctCount: Int,
        val wrongCount: Int
    ) {
        fun displayText(): String {
            val total = correctCount + wrongCount
            val accuracy = if (total == 0) 0 else correctCount * 100 / total
            return "正确 $correctCount · 错误 $wrongCount · 正确率 $accuracy%"
        }
    }

    data class KeywordHighlightConfig(
        val enabled: Boolean = false,
        val query: String = "",
        val scope: KeywordSearchScope = KeywordSearchScope()
    ) {
        fun shouldHighlightPrompt(): Boolean {
            return enabled && query.isNotBlank() && scope.includePrompt
        }

        fun shouldHighlightAnswers(): Boolean {
            return enabled && query.isNotBlank() && scope.includeAnswers
        }
    }

    companion object {
        const val ROOT_VIEW = 0
        const val CAMERA_BUTTON = 1
        const val SCREEN_RECORD_BUTTON = 2
        const val RENAME_BUTTON = 3
        const val DELETE_BUTTON = 4
    }
}
