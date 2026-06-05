/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.virin.visionquiz.vision

import androidx.sqlite.db.SupportSQLiteCompat.Api16Impl.cancel
import com.google.android.gms.tasks.OnCanceledListener
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resumeWithException

/**
 * Quality-of-life helper to allow using trailing lambda syntax for adding a success listener to a
 * [Task].
 */
fun <TResult> Task<TResult>.addOnSuccessListener(
  executor: Executor,
  listener: (TResult) -> Unit
): Task<TResult> {
  return addOnSuccessListener(executor, OnSuccessListener(listener))
}

/**
 * Quality-of-life helper to allow using trailing lambda syntax for adding a failure listener to a
 * [Task].
 */
fun <TResult> Task<TResult>.addOnFailureListener(
  executor: Executor,
  listener: (Exception) -> Unit
): Task<TResult> {
  return addOnFailureListener(executor, OnFailureListener(listener))
}

/**
 * Quality-of-life helper to allow using trailing lambda syntax for adding a completion listener to
 * a [Task].
 */
fun <TResult> Task<TResult>.addOnCompleteListener(
  executor: Executor,
  listener: (Task<TResult>) -> Unit
): Task<TResult> {
  return addOnCompleteListener(executor, OnCompleteListener(listener))
}

/**
 * Quality-of-life helper to allow using trailing lambda syntax for adding a cancellation listener
 * to a [Task].
 */
fun <TResult> Task<TResult>.addOnCanceledListener(
  executor: Executor,
  listener: () -> Unit
): Task<TResult> {
  return addOnCanceledListener(executor, OnCanceledListener(listener))
}

// 将 Task<T> 转换为 suspend 函数
//@OptIn(ExperimentalCoroutinesApi::class)
//suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
//  // 添加监听器以处理任务完成
//  addOnCompleteListener { task ->
//    if (task.isSuccessful) {
//      // 如果任务成功，调用 resume 恢复协程并传递结果
//      continuation.resume(task.result!!, null)
//    } else {
//      // 如果任务失败，调用 resumeWithException 恢复协程并传递异常
//      continuation.resumeWithException(task.exception!!)
//    }
//  }
//
//  // 在取消协程时取消任务
////  continuation.invokeOnCancellation { cancel() }
//}