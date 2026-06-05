package com.virin.visionquiz.screendetector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import com.virin.visionquiz.ScreenSource
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.vision.questiondetector.OriginalRecognitionProcessor
import com.virin.visionquiz.vision.questiondetector.QuizRecognitionProcessor
import java.io.IOException

object ScreenDetectorController : ScreenDetectorSession.Controller {
    private const val TAG = "ScreenDetectorController"

    private var hostActivity: FragmentActivity? = null
    private var cameraSource: ScreenSource? = null
    private var accessibilitySource: AccessibilityTextSource? = null
    private var libId = 0
    private var isDetectionRunning = false
    private var isDetectionPaused = false
    private var pendingStartRequest: StartRequest? = null
    private var pendingPermissionPrompt: PendingPermissionPrompt? = null

    fun startQuizDetection(
        activity: FragmentActivity,
        libraryId: Int,
        quizzes: LiveData<List<Quiz>>
    ) {
        pendingPermissionPrompt = null
        val request = StartRequest.Quiz(libraryId, quizzes)
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        startInternal(activity, request)
    }

    fun startAccessibilityQuizDetection(
        activity: FragmentActivity,
        libraryId: Int,
        quizzes: LiveData<List<Quiz>>
    ) {
        pendingPermissionPrompt = null
        val request = StartRequest.AccessibilityQuiz(libraryId, quizzes)
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        startInternal(activity, request)
    }

    fun startProcessorTest(activity: FragmentActivity) {
        pendingPermissionPrompt = null
        val request = StartRequest.ProcessorTest
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        startInternal(activity, request)
    }

    fun onHostResumed(activity: FragmentActivity) {
        val request = pendingStartRequest ?: return
        if (!ensureStartPermissions(activity, request)) {
            return
        }
        pendingStartRequest = null
        pendingPermissionPrompt = null
        startInternal(activity, request)
    }

    private fun startInternal(activity: FragmentActivity, request: StartRequest) {
        stopAndResetSources(release = true)
        stopOverlayService()

        hostActivity = activity
        libId = request.libraryId
        ScreenDetectorSession.attachController(this)

        try {
            when (request) {
                is StartRequest.ProcessorTest -> {
                    Log.i(TAG, "Using on-device OCR Processor for screen text")
                    cameraSource = ScreenSource(activity)
                    cameraSource?.setMachineLearningFrameProcessor(
                        OriginalRecognitionProcessor(activity) { matches ->
                            ScreenDetectorSession.publishMatches(matches)
                        }
                    )
                }
                is StartRequest.Quiz -> {
                    Log.i(TAG, "Using on-device Quiz recognition Processor for Quiz")
                    cameraSource = ScreenSource(activity)
                    cameraSource?.setMachineLearningFrameProcessor(
                        QuizRecognitionProcessor(
                            activity,
                            request.quizzes,
                            onMatchesDetected = { matches ->
                                ScreenDetectorSession.publishMatches(matches)
                            },
                            minMatchScore = PreferenceUtils.getScreenSearchMinMatchScore(activity)
                        )
                    )
                }
                is StartRequest.AccessibilityQuiz -> {
                    Log.i(TAG, "Using accessibility text source for Quiz")
                    accessibilitySource = AccessibilityTextSource(activity, request.quizzes) { matches ->
                        ScreenDetectorSession.publishMatches(matches)
                    }
                }
            }
            maybeStartOverlayService()
            startCurrentSource()
        } catch (e: Exception) {
            Log.e(TAG, "Can not create screen processor.", e)
            Toast.makeText(
                activity.applicationContext,
                "Can not create screen processor: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopScreenDetection()
        }
    }

    private fun startCurrentSource() {
        if (isDetectionRunning) {
            return
        }
        try {
            cameraSource?.start()
            accessibilitySource?.start()
            isDetectionRunning = true
            isDetectionPaused = false
            ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.RUNNING)
        } catch (e: IOException) {
            Log.e(TAG, "Unable to start screen source.", e)
            stopAndResetSources(release = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start accessibility source.", e)
            hostActivity?.applicationContext?.let { context ->
                Toast.makeText(
                    context,
                    e.message ?: "Unable to start accessibility search",
                    Toast.LENGTH_LONG
                ).show()
            }
            stopAndResetSources(release = true)
        }
    }

    private fun pauseCurrentSource() {
        cameraSource?.pause()
        accessibilitySource?.pause()
        isDetectionRunning = false
        isDetectionPaused = true
        ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.PAUSED)
    }

    private fun resumeCurrentSource() {
        if (!isDetectionPaused) {
            return
        }
        maybeStartOverlayService()
        cameraSource?.resume()
        accessibilitySource?.resume()
        isDetectionRunning = true
        isDetectionPaused = false
        ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.RUNNING)
    }

    private fun stopAndResetSources(release: Boolean = false) {
        if (release) {
            cameraSource?.release()
            cameraSource = null
        } else {
            cameraSource?.stop()
        }
        accessibilitySource?.stop()
        if (release) {
            accessibilitySource = null
        }
        isDetectionRunning = false
        isDetectionPaused = false
        ScreenDetectorSession.setState(ScreenDetectorSession.DetectionState.STOPPED)
        ScreenDetectorSession.clearMatches()
        ScreenDetectorSession.clearScreenFrameInfo()
    }

    private fun maybeStartOverlayService() {
        val activity = hostActivity ?: return
        if (!commonROMPermissionCheck(activity)) {
            return
        }
        activity.startService(
            Intent(activity, ScreenDetectorService::class.java).apply {
                putExtra(ScreenDetectorService.LIBRARY_ID, libId)
            }
        )
    }

    private fun stopOverlayService() {
        hostActivity?.stopService(Intent(hostActivity, ScreenDetectorService::class.java))
    }

    override fun stopScreenDetection() {
        val activity = hostActivity
        if (activity == null) {
            stopAndResetSources(release = true)
            ScreenDetectorSession.detachController(this)
            return
        }
        activity.runOnUiThread {
            stopAndResetSources(release = true)
            stopOverlayService()
            ScreenDetectorSession.detachController(this)
            hostActivity = null
            pendingStartRequest = null
            pendingPermissionPrompt = null
        }
    }

    override fun pauseScreenDetection() {
        hostActivity?.runOnUiThread {
            if (isDetectionRunning) {
                pauseCurrentSource()
            }
        }
    }

    override fun resumeScreenDetection() {
        hostActivity?.runOnUiThread {
            if (isDetectionPaused) {
                resumeCurrentSource()
            }
        }
    }

    override fun retryScreenDetectionOnce() {
        hostActivity?.runOnUiThread {
            if (isDetectionPaused) {
                cameraSource?.retryOnce()
                accessibilitySource?.retryOnce()
            }
        }
    }

    private fun ensureStartPermissions(
        activity: FragmentActivity,
        request: StartRequest
    ): Boolean {
        if (!commonROMPermissionCheck(activity)) {
            pendingStartRequest = request
            pendingPermissionPrompt = PendingPermissionPrompt.OVERLAY
            Toast.makeText(activity, "请允许悬浮窗权限", Toast.LENGTH_LONG).show()
            activity.startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
                REQUEST_FLOAT_CODE
            )
            return false
        }
        if (request.requiresAccessibility && !AccessibilityPermissionHelper.isServiceEnabled(activity)) {
            pendingStartRequest = request
            if (pendingPermissionPrompt != PendingPermissionPrompt.ACCESSIBILITY) {
                pendingPermissionPrompt = PendingPermissionPrompt.ACCESSIBILITY
                showAccessibilityPermissionDialog(activity)
            }
            return false
        }
        return true
    }

    private fun showAccessibilityPermissionDialog(activity: FragmentActivity) {
        val appName = activity.getString(R.string.app_name)
        val hasShownIntro = AccessibilityPermissionHelper.hasShownIntro(activity)
        val titleRes = if (hasShownIntro) {
            R.string.accessibility_permission_required
        } else {
            R.string.accessibility_search_intro_title
        }
        val messageRes = if (hasShownIntro) {
            R.string.accessibility_search_prompt_message
        } else {
            R.string.accessibility_search_intro_message
        }
        if (!hasShownIntro) {
            AccessibilityPermissionHelper.markIntroShown(activity)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(titleRes, appName))
            .setMessage(activity.getString(messageRes, appName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.accessibility_search_enable_cta) { _, _ ->
                AccessibilityPermissionHelper.openAccessibilitySettings(activity)
            }
            .show()
    }

    private fun commonROMPermissionCheck(context: Context?): Boolean {
        var result = true
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val clazz: Class<*> = Settings::class.java
                val canDrawOverlays =
                    clazz.getDeclaredMethod("canDrawOverlays", Context::class.java)
                result = canDrawOverlays.invoke(null, context) as Boolean
            } catch (e: Exception) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }
        return result
    }

    private sealed class StartRequest(
        val libraryId: Int,
        val requiresAccessibility: Boolean = false
    ) {
        data class Quiz(
            val libId: Int,
            val quizzes: LiveData<List<com.virin.visionquiz.dao.Quiz>>
        ) : StartRequest(libId)

        data class AccessibilityQuiz(
            val libId: Int,
            val quizzes: LiveData<List<com.virin.visionquiz.dao.Quiz>>
        ) : StartRequest(libId, requiresAccessibility = true)

        object ProcessorTest : StartRequest(0)
    }

    private enum class PendingPermissionPrompt {
        OVERLAY,
        ACCESSIBILITY
    }

    private const val REQUEST_FLOAT_CODE = 1001
}
