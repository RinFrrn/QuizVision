package com.virin.visionquiz.quizstudy

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.ExamSession
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizAnswerRecord
import com.virin.visionquiz.dao.answerString
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.databinding.FragmentExamDetailBinding
import com.virin.visionquiz.databinding.FragmentExamHistoryBinding
import com.virin.visionquiz.databinding.ItemExamHistoryBinding
import com.virin.visionquiz.databinding.ItemQuizHistoryBinding
import com.virin.visionquiz.quizlist.quizcontent.showQuizContentDialog
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.convertNumToChar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExamHistoryFragment : BaseQuizFragment() {

    private var _binding: FragmentExamHistoryBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(QuizLibraryFeaturesFragment.LIBRARY_ID)
    private val viewModel: ExamHistoryViewModel by viewModels {
        ExamHistoryViewModel.factory(requireActivity().application, libraryId)
    }
    private lateinit var adapter: ExamHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExamHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, "考试历史")
        adapter = ExamHistoryAdapter { session ->
            findNavController().navigate(
                R.id.ExamDetailFragment,
                bundleOf(
                    QuizLibraryFeaturesFragment.LIBRARY_ID to libraryId,
                    ExamDetailFragment.EXAM_SESSION_ID to session.id
                )
            )
        }
        binding.recyclerView.adapter = adapter
        viewModel.examSessions.observe(viewLifecycleOwner) { sessions ->
            val rows = sessions.orEmpty()
            binding.emptyView.isVisible = rows.isEmpty()
            adapter.submitList(rows)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ExamDetailFragment : BaseQuizFragment() {

    private var _binding: FragmentExamDetailBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(QuizLibraryFeaturesFragment.LIBRARY_ID)
    private val sessionId: Int
        get() = requireArguments().getInt(EXAM_SESSION_ID)
    private val viewModel: ExamDetailViewModel by viewModels {
        ExamDetailViewModel.factory(requireActivity().application, libraryId, sessionId)
    }
    private lateinit var adapter: ExamDetailAdapter
    private var quizzes: List<Quiz> = emptyList()
    private var records: List<QuizAnswerRecord> = emptyList()
    private var session: ExamSession? = null
    private var filter = ExamDetailFilter.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExamDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, "考试详情")
        adapter = ExamDetailAdapter { quiz ->
            showQuizContentDialog(requireContext(), quiz, quizzes)
        }
        binding.recyclerView.adapter = adapter
        binding.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filter = when (checkedId) {
                R.id.filter_correct_button -> ExamDetailFilter.CORRECT
                R.id.filter_wrong_button -> ExamDetailFilter.WRONG
                else -> ExamDetailFilter.ALL
            }
            render()
        }

        viewModel.quizList.observe(viewLifecycleOwner) {
            quizzes = it.orEmpty()
            render()
        }
        viewModel.answerRecords.observe(viewLifecycleOwner) {
            records = it.orEmpty()
            render()
        }
        viewModel.examSession.observe(viewLifecycleOwner) {
            session = it
            render()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render() {
        val currentSession = session
        val quizById = quizzes.associateBy { it.id }
        val rows = records.mapIndexedNotNull { index, record ->
            quizById[record.quizId]?.let { quiz ->
                QuizHistoryRow(record, quiz, index)
            }
        }
        val filteredRows = when (filter) {
            ExamDetailFilter.ALL -> rows
            ExamDetailFilter.CORRECT -> rows.filter { it.record.isCorrect }
            ExamDetailFilter.WRONG -> rows.filter { it.record.isCorrect.not() }
        }
        val incomplete = currentSession != null && rows.size < currentSession.totalCount
        binding.summaryText.text = if (currentSession == null) {
            "记录已清理或不存在"
        } else {
            val total = currentSession.totalCount.coerceAtLeast(0)
            val rate = if (total == 0) 0 else currentSession.correctCount * 100 / total
            val incompleteText = if (incomplete) " · 记录已清理/不完整" else ""
            "答对 ${currentSession.correctCount} / $total · 正确率 $rate% · 用时 ${formatDuration(currentSession)}$incompleteText"
        }
        binding.emptyView.isVisible = filteredRows.isEmpty()
        binding.emptyView.text = buildEmptyText(rows, incomplete)
        adapter.submitList(filteredRows)
    }

    private fun buildEmptyText(rows: List<QuizHistoryRow>, incomplete: Boolean): String {
        if (rows.isEmpty()) {
            return if (incomplete) "记录已清理/不完整" else "记录已清理或不存在"
        }
        return when (filter) {
            ExamDetailFilter.ALL -> "记录已清理或不存在"
            ExamDetailFilter.CORRECT -> "没有正确记录"
            ExamDetailFilter.WRONG -> "没有错题记录"
        }
    }

    companion object {
        const val EXAM_SESSION_ID = "exam_session_id"
    }
}

private enum class ExamDetailFilter {
    ALL,
    CORRECT,
    WRONG
}

private class ExamHistoryAdapter(
    private val onClick: (ExamSession) -> Unit
) : ListAdapter<ExamSession, ExamHistoryAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExamHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), dateFormat, onClick)
    }

    class ViewHolder(private val binding: ItemExamHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            session: ExamSession,
            dateFormat: SimpleDateFormat,
            onClick: (ExamSession) -> Unit
        ) {
            val total = session.totalCount.coerceAtLeast(0)
            val rate = if (total == 0) 0 else session.correctCount * 100 / total
            binding.timeText.text = dateFormat.format(Date(session.endedAt ?: session.startedAt))
            binding.scoreText.text = "答对 ${session.correctCount} / $total · 正确率 $rate%"
            binding.metaText.text = "用时 ${formatDuration(session)} · 点击查看详情"
            binding.root.setOnClickListener { onClick(session) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ExamSession>() {
        override fun areItemsTheSame(oldItem: ExamSession, newItem: ExamSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ExamSession, newItem: ExamSession): Boolean {
            return oldItem == newItem
        }
    }
}

private class ExamDetailAdapter(
    private val onQuizClick: ((Quiz) -> Unit)? = null
) : ListAdapter<QuizHistoryRow, ExamDetailAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuizHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemQuizHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: QuizHistoryRow) {
            val quiz = row.quiz
            binding.titleText.text = quiz.prompt
            binding.metaText.text = "第 ${row.originalIndex + 1} 题"
            binding.resultText.text = if (row.record.isCorrect) "正确" else "错误"
            binding.resultText.setTextColor(
                MaterialColors.getColor(
                    binding.root,
                    if (row.record.isCorrect) R.attr.colorPrimary else R.attr.colorError
                )
            )

            // Show correct options
            val optionsText = quiz.options.mapIndexed { idx, opt ->
                "${convertNumToChar(idx)}. $opt"
            }.joinToString("\n")
            binding.optionsText.text = optionsText
            binding.optionsText.visibility = if (quiz.options.any { it.isNotBlank() }) View.VISIBLE else View.GONE

            binding.answerText.text =
                "你的答案：${formatAnswer(row.record.selectedAnswer)}   正确答案：${quiz.answerString()}"

            // Type label
            val type = quiz.inferredUiType()
            binding.typeText.text = type.label
            val (bgAttr, textAttr) = when (type) {
                com.virin.visionquiz.dao.QuizUiType.SINGLE_CHOICE -> R.attr.colorPrimaryContainer to R.attr.colorOnPrimaryContainer
                com.virin.visionquiz.dao.QuizUiType.MULTIPLE_CHOICE -> R.attr.colorSecondaryContainer to R.attr.colorOnSecondaryContainer
                com.virin.visionquiz.dao.QuizUiType.JUDGEMENT -> R.attr.colorTertiaryContainer to R.attr.colorOnTertiaryContainer
                com.virin.visionquiz.dao.QuizUiType.FILL_BLANK -> R.attr.colorErrorContainer to R.attr.colorOnErrorContainer
                com.virin.visionquiz.dao.QuizUiType.SUBJECTIVE -> R.attr.colorSurfaceContainerHighest to R.attr.colorOnSurfaceVariant
            }
            binding.typeText.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(binding.root, bgAttr))
            binding.typeText.setTextColor(MaterialColors.getColor(binding.root, textAttr))

            binding.root.setOnClickListener { onQuizClick?.invoke(quiz) }
        }

        private fun formatAnswer(answer: Set<Int>): String {
            if (answer.isEmpty()) return "未作答"
            return answer.sorted().joinToString("") { convertNumToChar(it).toString() }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<QuizHistoryRow>() {
        override fun areItemsTheSame(oldItem: QuizHistoryRow, newItem: QuizHistoryRow): Boolean {
            return oldItem.record.id == newItem.record.id
        }

        override fun areContentsTheSame(oldItem: QuizHistoryRow, newItem: QuizHistoryRow): Boolean {
            return oldItem == newItem
        }
    }
}

private fun formatDuration(session: ExamSession): String {
    val endedAt = session.endedAt ?: return "--:--"
    val seconds = ((endedAt - session.startedAt).coerceAtLeast(0L)) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val restSeconds = seconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, restSeconds)
    } else {
        "%02d:%02d".format(minutes, restSeconds)
    }
}
