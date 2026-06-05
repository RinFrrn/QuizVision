package com.virin.visionquiz.quizstudy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.databinding.FragmentExamConfigBinding
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.configureQuizTopBar

class ExamConfigFragment : BaseQuizFragment() {

    private var _binding: FragmentExamConfigBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(QuizLibraryFeaturesFragment.LIBRARY_ID)

    private val viewModel: ExamConfigViewModel by viewModels {
        ExamConfigViewModel.factory(requireActivity().application, libraryId)
    }

    private var stats = SupportedQuizStats()
    private var examSource = ExamQuestionSource.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExamConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, "模拟考试")
        val savedCounts = QuizStudySettings.readExamQuestionCounts(requireContext())
        binding.singleCountEdit.setText(savedCounts.singleChoice.toString())
        binding.multipleCountEdit.setText(savedCounts.multipleChoice.toString())
        binding.judgementCountEdit.setText(savedCounts.judgement.toString())
        binding.examDurationEdit.setText(readSavedExamDurationMinutes().toString())
        binding.optionShuffleSwitch.isChecked = QuizStudySettings.readOptionShuffleEnabled(
            requireContext(),
            libraryId,
            QuizStudyMode.EXAM
        )
        examSource = QuizStudySettings.readExamQuestionSource(requireContext())
        viewModel.setExamSource(examSource)
        binding.examSourceToggleGroup.check(examSource.toButtonId())
        binding.examSourceToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selectedSource = checkedId.toExamQuestionSource()
            examSource = selectedSource
            QuizStudySettings.saveExamQuestionSource(requireContext(), selectedSource)
            viewModel.setExamSource(selectedSource)
            renderStats()
        }

        viewModel.examSource.observe(viewLifecycleOwner) {
            examSource = it
            if (binding.examSourceToggleGroup.checkedButtonId != it.toButtonId()) {
                binding.examSourceToggleGroup.check(it.toButtonId())
            }
            renderStats()
        }

        viewModel.stats.observe(viewLifecycleOwner) {
            stats = it
            renderStats()
        }

        binding.startExamButton.setOnClickListener {
            startExam()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun startExam() {
        val source = viewModel.examQuizzes.value.orEmpty()
        if (source.isEmpty()) {
            Toast.makeText(requireContext(), "当前来源没有可考试题目", Toast.LENGTH_SHORT).show()
            return
        }
        val desiredCounts = readExamQuestionCountsInput()
        val selectedIds = mutableListOf<Int>()
        selectedIds += source.filter { it.inferredUiType() == QuizUiType.SINGLE_CHOICE }
            .shuffled()
            .take(desiredCounts.singleChoice.coerceAtMost(stats.singleChoice))
            .map { it.id }
        selectedIds += source.filter { it.inferredUiType() == QuizUiType.MULTIPLE_CHOICE }
            .shuffled()
            .take(desiredCounts.multipleChoice.coerceAtMost(stats.multipleChoice))
            .map { it.id }
        selectedIds += source.filter { it.inferredUiType() == QuizUiType.JUDGEMENT }
            .shuffled()
            .take(desiredCounts.judgement.coerceAtMost(stats.judgement))
            .map { it.id }

        QuizStudySettings.saveExamQuestionCounts(requireContext(), desiredCounts)
        if (selectedIds.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择 1 道题", Toast.LENGTH_SHORT).show()
            return
        }
        val durationMinutes = readDurationInput()
        val optionShuffleEnabled = binding.optionShuffleSwitch.isChecked
        saveExamDurationMinutes(durationMinutes)
        QuizStudySettings.saveOptionShuffleEnabled(
            requireContext(),
            libraryId,
            QuizStudyMode.EXAM,
            optionShuffleEnabled
        )

        findNavController().navigate(
            R.id.QuizRunnerFragment,
            QuizRunnerFragment.arguments(
                libraryId = libraryId,
                mode = QuizStudyMode.EXAM,
                quizIds = selectedIds.shuffled().toIntArray(),
                examDurationMinutes = durationMinutes,
                optionShuffleEnabled = optionShuffleEnabled
            )
        )
    }

    private fun readExamQuestionCountsInput(): ExamQuestionCounts {
        return ExamQuestionCounts(
            singleChoice = readDesiredCount(binding.singleCountEdit.text?.toString()),
            multipleChoice = readDesiredCount(binding.multipleCountEdit.text?.toString()),
            judgement = readDesiredCount(binding.judgementCountEdit.text?.toString())
        )
    }

    private fun readDesiredCount(text: String?): Int {
        return (text?.toIntOrNull() ?: 0).coerceAtLeast(0)
    }

    private fun readDurationInput(): Int {
        return (binding.examDurationEdit.text?.toString()?.toIntOrNull()
            ?: DEFAULT_EXAM_DURATION_MINUTES).coerceIn(1, MAX_EXAM_DURATION_MINUTES)
    }

    private fun readSavedExamDurationMinutes(): Int {
        return requireContext()
            .getSharedPreferences(QUIZ_STUDY_PREFS, 0)
            .getInt(KEY_EXAM_DURATION_MINUTES, DEFAULT_EXAM_DURATION_MINUTES)
            .coerceIn(1, MAX_EXAM_DURATION_MINUTES)
    }

    private fun saveExamDurationMinutes(minutes: Int) {
        requireContext()
            .getSharedPreferences(QUIZ_STUDY_PREFS, 0)
            .edit()
            .putInt(KEY_EXAM_DURATION_MINUTES, minutes)
            .apply()
    }

    private fun renderStats() {
        binding.summaryText.text =
            "${examSource.label}：单选 ${stats.singleChoice} · 多选 ${stats.multipleChoice} · 判断 ${stats.judgement}"
        binding.startExamButton.isEnabled = stats.total > 0
    }

    private fun ExamQuestionSource.toButtonId(): Int {
        return when (this) {
            ExamQuestionSource.ALL -> R.id.all_source_button
            ExamQuestionSource.FAVORITES -> R.id.favorites_source_button
            ExamQuestionSource.WRONG -> R.id.wrong_source_button
        }
    }

    private fun Int.toExamQuestionSource(): ExamQuestionSource {
        return when (this) {
            R.id.favorites_source_button -> ExamQuestionSource.FAVORITES
            R.id.wrong_source_button -> ExamQuestionSource.WRONG
            else -> ExamQuestionSource.ALL
        }
    }

    companion object {
        private const val QUIZ_STUDY_PREFS = "quiz_study_settings"
        private const val KEY_EXAM_DURATION_MINUTES = "exam_duration_minutes"
        private const val DEFAULT_EXAM_DURATION_MINUTES = 60
        private const val MAX_EXAM_DURATION_MINUTES = 600
    }
}
