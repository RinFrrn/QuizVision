package com.virin.visionquiz.quizstudy

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizAnswerRecord
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.answerString
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.databinding.FragmentQuizHistoryBinding
import com.virin.visionquiz.databinding.ItemQuizHistoryBinding
import com.virin.visionquiz.quizlist.quizcontent.showQuizContentDialog
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.convertNumToChar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class QuizHistoryFragment : BaseQuizFragment() {

    private var _binding: FragmentQuizHistoryBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(QuizLibraryFeaturesFragment.LIBRARY_ID)

    private val viewModel: QuizCollectionViewModel by viewModels {
        QuizCollectionViewModel.factory(requireActivity().application, libraryId)
    }

    private lateinit var adapter: QuizHistoryAdapter
    private var quizzes: List<Quiz> = emptyList()
    private var records: List<QuizAnswerRecord> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuizHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, "作答历史")
        setupMenu()

        adapter = QuizHistoryAdapter { quiz ->
            showQuizContentDialog(requireContext(), quiz)
        }
        binding.recyclerView.adapter = adapter

        viewModel.quizList.observe(viewLifecycleOwner) {
            quizzes = it.orEmpty()
            render()
        }
        viewModel.answerRecords.observe(viewLifecycleOwner) {
            records = it.orEmpty()
            render()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render() {
        val quizById = quizzes.associateBy { it.id }
        val rows = records.mapNotNull { record ->
            quizById[record.quizId]?.let { quiz ->
                QuizHistoryRow(record, quiz)
            }
        }
        binding.emptyView.isVisible = rows.isEmpty()
        adapter.submitList(rows)
    }

    private fun setupMenu() {
        binding.toolbar.menu.clear()
        binding.toolbar.menu.add(Menu.NONE, MENU_DELETE_HISTORY, Menu.NONE, "删除历史")
            .setIcon(R.drawable.round_delete_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId != MENU_DELETE_HISTORY) return@setOnMenuItemClickListener false
            showHistoryDeleteDialog()
            true
        }
    }

    private fun showHistoryDeleteDialog() {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sevenDaysAgo = now - 7L * DAY_MILLIS
        val thirtyDaysAgo = now - 30L * DAY_MILLIS
        val labels = arrayOf("今天", "7天前", "30天前", "全部", "自定义范围")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除作答历史")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> confirmDeleteRange(todayStart, now, "今天")
                    1 -> confirmDeleteRange(0L, sevenDaysAgo, "7天前")
                    2 -> confirmDeleteRange(0L, thirtyDaysAgo, "30天前")
                    3 -> confirmDeleteRange(0L, Long.MAX_VALUE, "全部")
                    4 -> showCustomRangePicker()
                }
            }
            .show()
    }

    private fun showCustomRangePicker() {
        if (records.isEmpty()) {
            Snackbar.make(binding.root, "暂无可删除的作答历史", Snackbar.LENGTH_SHORT).show()
            return
        }

        val minAnsweredAt = records.minOf { it.answeredAt }
        val maxAnsweredAt = records.maxOf { it.answeredAt }
        val firstSelectableDay = startOfLocalDay(minAnsweredAt)
        val lastSelectableDay = startOfLocalDay(maxAnsweredAt)
        val constraints = CalendarConstraints.Builder()
            .setStart(firstSelectableDay)
            .setEnd(lastSelectableDay)
            .setOpenAt(lastSelectableDay)
            .setValidator(
                CompositeDateValidator.allOf(
                    listOf(
                        DateValidatorPointForward.from(firstSelectableDay),
                        DateValidatorPointBackward.before(endOfLocalDay(maxAnsweredAt))
                    )
                )
            )
            .build()
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("选择删除范围")
            .setPositiveButtonText("确定范围")
            .setCalendarConstraints(constraints)
            .build()
        picker.addOnPositiveButtonClickListener { range ->
            val selectedStart = range.first ?: return@addOnPositiveButtonClickListener
            val selectedEnd = range.second ?: selectedStart
            val start = selectedUtcDayToLocalStart(selectedStart)
            val end = selectedUtcDayToLocalEnd(selectedEnd)
            confirmDeleteRange(start, end, "自定义范围")
        }
        picker.show(parentFragmentManager, "delete_history_range")
    }

    private fun confirmDeleteRange(startTime: Long, endTime: Long, label: String) {
        val rangeText = if (endTime == Long.MAX_VALUE) {
            "全部作答历史"
        } else {
            "${dateTimeFormat.format(Date(startTime))} 至 ${dateTimeFormat.format(Date(endTime))}"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认删除 $label？")
            .setMessage("将删除 $rangeText 内的作答记录，并同步清理该范围内结束的考试场次。此操作不可恢复。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteHistoryByRange(startTime, endTime)
            }
            .show()
    }

    companion object {
        private const val MENU_DELETE_HISTORY = 2001
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        private fun startOfLocalDay(timeMillis: Long): Long {
            return Calendar.getInstance().apply {
                timeInMillis = timeMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun endOfLocalDay(timeMillis: Long): Long {
            return Calendar.getInstance().apply {
                timeInMillis = timeMillis
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
        }

        private fun selectedUtcDayToLocalStart(selectionMillis: Long): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = selectionMillis
            }
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, utc.get(Calendar.YEAR))
                set(Calendar.MONTH, utc.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun selectedUtcDayToLocalEnd(selectionMillis: Long): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = selectionMillis
            }
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, utc.get(Calendar.YEAR))
                set(Calendar.MONTH, utc.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
        }
    }
}

data class QuizHistoryRow(
    val record: QuizAnswerRecord,
    val quiz: Quiz,
    val originalIndex: Int = 0
)

class QuizHistoryAdapter(
    private val onQuizClick: ((Quiz) -> Unit)? = null
) : ListAdapter<QuizHistoryRow, QuizHistoryAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuizHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), dateFormat, onQuizClick)
    }

    class ViewHolder(private val binding: ItemQuizHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: QuizHistoryRow, dateFormat: SimpleDateFormat, onQuizClick: ((Quiz) -> Unit)?) {
            val quiz = row.quiz
            val modeLabel = QuizStudyMode.values()
                .firstOrNull { it.value == row.record.mode }
                ?.label ?: "作答"
            binding.titleText.text = quiz.prompt
            binding.metaText.text =
                "$modeLabel · ${dateFormat.format(Date(row.record.answeredAt))}"
            binding.resultText.text = if (row.record.isCorrect) "正确" else "错误"
            binding.resultText.setTextColor(
                MaterialColors.getColor(binding.root, if (row.record.isCorrect) R.attr.colorPrimary else R.attr.colorError)
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
