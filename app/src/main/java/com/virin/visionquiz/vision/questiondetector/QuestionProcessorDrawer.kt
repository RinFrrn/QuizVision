package com.virin.visionquiz.vision.questiondetector

import com.virin.visionquiz.vision.graphic.GraphicOverlay
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class QuestionProcessorDrawer {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var analyzerJob: Job? = null

    fun drawContent(block: suspend CoroutineScope.() -> Unit) {
        println("### isActive ${analyzerJob?.isActive}")
        if (analyzerJob == null || analyzerJob?.isActive == false) {
            // 使用协程启动一个异步任务
            analyzerJob = coroutineScope.launch {

                println("### start")
                block()
//                delay(500)
                println("### end")
            }
        }
        println("### isActive222 ${analyzerJob?.isActive}")

    }
}