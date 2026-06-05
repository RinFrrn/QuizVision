package com.virin.visionquiz.preference

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.preference.ListPreference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import com.virin.visionquiz.CameraSource
import com.virin.visionquiz.R
import kotlin.math.abs

/**
 * Configures quiz camera settings.
 */
open class QuizCameraXPreferenceFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preference_quiz_camera)
        setUpCameraPreferences()
        setUpListPreferenceSummary(R.string.pref_key_screen_search_interval_ms)
        setUpListPreferenceSummary(R.string.pref_key_screen_capture_frame_rate)
        setUpListPreferenceSummary(R.string.pref_key_accessibility_search_interval_ms)
    }

    open fun setUpCameraPreferences() {
        val cameraPreference =
            findPreference(getString(R.string.pref_category_key_camera)) as PreferenceCategory
        cameraPreference.removePreference(
            findPreference(getString(R.string.pref_key_rear_camera_preview_size))
        )
        cameraPreference.removePreference(
            findPreference(getString(R.string.pref_key_front_camera_preview_size))
        )
        setUpCameraXTargetAnalysisSizePreference(
            R.string.pref_key_camerax_rear_camera_target_resolution, CameraSelector.LENS_FACING_BACK
        )
        setUpCameraXTargetAnalysisSizePreference(
            R.string.pref_key_camerax_front_camera_target_resolution,
            CameraSelector.LENS_FACING_FRONT
        )
    }

    private fun setUpCameraXTargetAnalysisSizePreference(
        @StringRes previewSizePrefKeyId: Int, lensFacing: Int
    ) {
        val pref = findPreference(getString(previewSizePrefKeyId)) as ListPreference
        val cameraCharacteristics = getCameraCharacteristics(activity, lensFacing)
        val entries: Array<String?>
        if (cameraCharacteristics != null) {
            val map =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map!!.getOutputSizes(
                SurfaceTexture::class.java
            )
            entries = arrayOfNulls(outputSizes.size)
            for (i in outputSizes.indices) {
                entries[i] = outputSizes[i].toString()
            }
        } else {
            entries = arrayOf(
                "2000x2000",
                "1600x1600",
                "1200x1200",
                "1000x1000",
                "800x800",
                "600x600",
                "400x400",
                "200x200",
                "100x100"
            )
        }
        pref.entries = entries
        pref.entryValues = entries
        if (pref.entry == null) {
            val defaultValue = selectClosestSize(
                entries.filterNotNull(),
                PreferenceUtils.getDefaultCameraXTargetResolution()
            )
            pref.value = defaultValue
            pref.summary = defaultValue
            PreferenceUtils.saveString(activity, previewSizePrefKeyId, defaultValue)
        } else {
            pref.summary = pref.entry
        }
        pref.onPreferenceChangeListener =
            OnPreferenceChangeListener { _, newValue: Any? ->
                val newStringValue = newValue as String?
                pref.summary = newStringValue
                PreferenceUtils.saveString(
                    activity,
                    previewSizePrefKeyId,
                    newStringValue
                )
                true
            }
    }

    private fun setUpListPreferenceSummary(@StringRes prefKeyId: Int) {
        val pref = findPreference(getString(prefKeyId)) as ListPreference
        pref.summary = pref.entry ?: pref.value
        pref.onPreferenceChangeListener =
            OnPreferenceChangeListener { _, newValue: Any? ->
                val newStringValue = newValue as String
                val entryIndex = pref.findIndexOfValue(newStringValue)
                pref.summary = if (entryIndex >= 0) pref.entries[entryIndex] else newStringValue
                true
            }
    }

    open fun getCameraCharacteristics(
        context: Context, lensFacing: Int
    ): CameraCharacteristics? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraList = cameraManager.cameraIdList
            for (availableCameraId in cameraList) {
                val availableCameraCharacteristics = cameraManager.getCameraCharacteristics(
                    availableCameraId!!
                )
                val availableLensFacing =
                    availableCameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
                        ?: continue
                if (availableLensFacing == lensFacing) {
                    return availableCameraCharacteristics
                }
            }
        } catch (e: CameraAccessException) {
            // Accessing camera ID info got error
        }
        return null
    }

    private fun selectClosestSize(
        supportedSizes: List<String>,
        targetSize: android.util.Size
    ): String {
        return supportedSizes.minByOrNull { sizeString ->
            val parsedSize = android.util.Size.parseSize(sizeString)
            abs(parsedSize.width - targetSize.width) + abs(parsedSize.height - targetSize.height)
        } ?: PreferenceUtils.getDefaultCameraXTargetResolutionString()
    }
}
