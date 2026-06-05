package com.virin.visionquiz.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.util.Range
import android.util.TypedValue
import android.view.View
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.android.material.snackbar.Snackbar
import com.virin.visionquiz.R
import kotlinx.coroutines.*

/**
 * A -> 0, B -> 1...
 */
fun convertLetterToNumber(letter: Char): Int? {
    if (letter.isLetter().not()) {
        return null
    }
    return letter.uppercaseChar() - 'A'
}

fun convertNumToChar(num: Int): Char? {
    if (Range(0, 25).contains(num)) {
        return (num + 65).toChar()
    }
    return null
}

@OptIn(DelicateCoroutinesApi::class)
fun runIO(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.IO, block = block)

@OptIn(DelicateCoroutinesApi::class)
fun runMain(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Main, block = block)


/**
 * 使用 getSequentialLiveDataResults 函数来按顺序获取多个 LiveData 的结果。
 * 函数接受一个 LiveData 列表作为输入，使用 withContext(Dispatchers.Main) 来切换到主线程并按顺序遍历 LiveData 列表。
 */
suspend fun getSequentialLiveDataResults(liveDataList: List<LiveData<Any>>): List<Any> {
    val resultList = mutableListOf<Any>()
    withContext(Dispatchers.Main) {
        for (liveData in liveDataList) {
            resultList.add(liveData.await())
        }
    }
    return resultList
}

/**
 * 对于每个 LiveData 实例，我们使用自定义的 await() 扩展函数来暂停协程，直到 LiveData 发出新的值。
 * 在 await() 函数中，我们添加一个观察者来监视 LiveData 的更改，当 LiveData 发出新的值时，我们将其从 LiveData 中删除观察者，并使用 continuation.resume(value) 恢复协程。
 *
 * 最后，我们将获取到的 LiveData 结果添加到一个列表中并返回该列表。
 *
 * 请注意，上述示例中的 await() 函数需要在主线程中执行，因此我们使用 withContext(Dispatchers.Main) 来在主线程中调用它。
 * 如果您在后台线程中调用 await() 函数，它将引发异常。
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> LiveData<T>.await(): T = suspendCancellableCoroutine { continuation ->
    val observer = object : Observer<T> {
        override fun onChanged(value: T) {
            removeObserver(this)
            continuation.resume(value, null)
        }
    }
    observeForever(observer)
    continuation.invokeOnCancellation {
        removeObserver(observer)
    }
}

val Int.dp: Int
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        Resources.getSystem().displayMetrics
    ).toInt()


fun Context.showSnackBarWithCopy(view: View, text: CharSequence) {
    Snackbar.make(
        view,
        text,
        Snackbar.LENGTH_SHORT
    ).setAction(R.string.copy) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Creates a new text clip to put on the clipboard
        val clip: ClipData = ClipData.newPlainText("Copied Text", text)
        // Set the clipboard's primary clip.
        clipboard.setPrimaryClip(clip)
    }.show()
}

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
