package com.virin.visionquiz.quizlibrarylist

import androidx.databinding.BindingAdapter
import androidx.lifecycle.LiveData
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.quizlist.QuizListAdapter

@BindingAdapter("quizLibraryList")
fun setQuizLibraryList(recyclerView: RecyclerView, data: LiveData<List<QuizLibrary>>?) {
    val owner = recyclerView.findViewTreeLifecycleOwner() ?: return
    val adapter = recyclerView.adapter as? QuizLibraryListAdapter ?: return
    data?.observe(owner) { categories ->
        adapter.submitList(categories)
    }
}


//@BindingAdapter("app:quizList")
//fun setQuizList(recyclerView: RecyclerView, data: LiveData<List<Pair<Quiz, Double>>?) {
//
//    data?.let {
//        val adapter = recyclerView.adapter as QuizListAdapter?
//
//        it.observeForever { quizzes ->
//            adapter?.submitList(quizzes)
//        }
//    }
//}

@BindingAdapter("quizList")
fun setQuizList(recyclerView: RecyclerView, data: LiveData<List<Quiz>>?) {
    val owner = recyclerView.findViewTreeLifecycleOwner() ?: return
    val adapter = recyclerView.adapter as? QuizListAdapter ?: return
    data?.observe(owner) { quizzes ->
        adapter.submitList(quizzes)
    }
}

//@BindingAdapter("app:quizListViewModel")
//fun setQuizListViewModel(recyclerView: RecyclerView, vm: QuizListViewModel) {
//
//    vm.quizList.let {
//        val adapter = recyclerView.adapter as QuizListAdapter?
//
//        it.observeForever { quizzes ->
//            adapter?.submitList(quizzes)
//        }
//    }
//}
