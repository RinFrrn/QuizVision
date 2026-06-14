package com.virin.visionquiz.quizstudy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizAnswerRecord
import com.virin.visionquiz.dao.isSupportedStudyType
import com.virin.visionquiz.databinding.FragmentQuizFavoritesBinding
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.quizlist.QuizListAdapter
import com.virin.visionquiz.quizlist.quizcontent.showQuizContentDialog
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.configureQuizTopBar

class QuizFavoritesFragment : BaseQuizFragment() {

    private var _binding: FragmentQuizFavoritesBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(QuizLibraryFeaturesFragment.LIBRARY_ID)
    private val collectionType: QuizCollectionType by lazy {
        QuizCollectionType.from(arguments?.getString(COLLECTION_TYPE))
    }

    private val viewModel: QuizCollectionViewModel by viewModels {
        QuizCollectionViewModel.factory(requireActivity().application, libraryId)
    }

    private lateinit var adapter: QuizListAdapter
    private var quizzes: List<Quiz> = emptyList()
    private var favoriteIds: Set<Int> = emptySet()
    private var answerRecords: List<QuizAnswerRecord> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuizFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, collectionType.title)
        binding.emptyView.text = collectionType.emptyText

        adapter = QuizListAdapter(object : QuizListAdapter.OnQuizClickListener {
            override fun onQuizClicked(quiz: Quiz) {
                val list = currentCollectionQuizzes()
                val index = list.indexOfFirst { it.id == quiz.id }.coerceAtLeast(0)
                showQuizContentDialog(requireContext(), list, index, quizzes)
            }
        })
        binding.recyclerView.adapter = adapter

        viewModel.quizList.observe(viewLifecycleOwner) {
            quizzes = it.orEmpty()
            render()
        }
        viewModel.favoriteQuizIds.observe(viewLifecycleOwner) {
            favoriteIds = it.orEmpty().toSet()
            render()
        }
        viewModel.answerRecords.observe(viewLifecycleOwner) {
            answerRecords = it.orEmpty()
            render()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render() {
        val list = currentCollectionQuizzes()
        adapter.setQuizStats(currentQuizStats())
        binding.emptyView.isVisible = list.isEmpty()
        adapter.submitList(list)
    }

    private fun currentCollectionQuizzes(): List<Quiz> {
        val supported = quizzes.filter { it.isSupportedStudyType() }
        return when (collectionType) {
            QuizCollectionType.FAVORITES -> supported.filter { it.id in favoriteIds }
            QuizCollectionType.WRONG -> {
                val wrongIds = buildActiveWrongQuizIds(answerRecords)
                supported.filter { it.id in wrongIds }
            }
        }
    }

    private fun currentQuizStats(): Map<Int, QuizListAdapter.QuizItemStats> {
        if (collectionType != QuizCollectionType.WRONG) return emptyMap()
        return answerRecords.groupBy { it.quizId }.mapValues { (_, records) ->
            QuizListAdapter.QuizItemStats(
                correctCount = records.count { it.isCorrect },
                wrongCount = records.count { !it.isCorrect }
            )
        }
    }

    companion object {
        const val COLLECTION_TYPE = "collection_type"
        const val TYPE_FAVORITES = "favorites"
        const val TYPE_WRONG = "wrong"
    }

    private enum class QuizCollectionType(
        val value: String,
        val title: String,
        val emptyText: String
    ) {
        FAVORITES(TYPE_FAVORITES, "我的收藏", "还没有收藏题目"),
        WRONG(TYPE_WRONG, "错题库", "暂无错题");

        companion object {
            fun from(value: String?): QuizCollectionType {
                return values().firstOrNull { it.value == value } ?: FAVORITES
            }
        }
    }
}
