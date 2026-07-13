package com.virin.visionquiz.screendetector

/** Keeps page-turn execution and recognition feedback semantically distinct. */
internal object PageTurnFeedback {
    enum class Outcome {
        PAGE_MOVED,
        GESTURE_COMPLETED,
        RECOGNITION_DELAYED,
        GESTURE_FAILED,
        PAGE_NOT_READY,
    }

    data class Feedback(
        val indicator: ScreenDetectorSession.AssistanceIndicator,
        val statusText: String
    )

    fun forOutcome(outcome: Outcome): Feedback {
        return when (outcome) {
            Outcome.PAGE_MOVED -> waiting("页面已翻动，正在识别新题")
            Outcome.GESTURE_COMPLETED -> waiting("翻页手势已完成，等待页面刷新")
            Outcome.RECOGNITION_DELAYED -> waiting("暂未识别到新题，正在继续扫描")
            Outcome.GESTURE_FAILED -> error("系统未能执行翻页，请重试")
            Outcome.PAGE_NOT_READY -> error("页面信息未就绪，请稍后重试")
        }
    }

    private fun waiting(statusText: String) = Feedback(
        indicator = ScreenDetectorSession.AssistanceIndicator.WAITING,
        statusText = statusText
    )

    private fun error(statusText: String) = Feedback(
        indicator = ScreenDetectorSession.AssistanceIndicator.ERROR,
        statusText = statusText
    )
}
