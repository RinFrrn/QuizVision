package com.virin.visionquiz.preference

import android.hardware.Camera
import android.os.Bundle
import android.preference.*
import android.preference.Preference.OnPreferenceChangeListener
import android.widget.Toast
import androidx.annotation.StringRes
import com.virin.visionquiz.CameraSource
import com.virin.visionquiz.R

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

/**
 * Configures quiz camera settings.
 */
open class QuizCameraPreferenceFragment : PreferenceFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preference_quiz_camera)
        setUpCameraPreferences()
    }

    open fun setUpCameraPreferences() {
        val cameraPreference =
            findPreference(getString(R.string.pref_category_key_camera)) as PreferenceCategory
        cameraPreference.removePreference(
            findPreference(getString(R.string.pref_key_camerax_rear_camera_target_resolution))
        )
        cameraPreference.removePreference(
            findPreference(getString(R.string.pref_key_camerax_front_camera_target_resolution))
        )
        setUpCameraPreviewSizePreference(
            R.string.pref_key_rear_camera_preview_size,
            R.string.pref_key_rear_camera_picture_size,
            CameraSource.CAMERA_FACING_BACK
        )
        setUpCameraPreviewSizePreference(
            R.string.pref_key_front_camera_preview_size,
            R.string.pref_key_front_camera_picture_size,
            CameraSource.CAMERA_FACING_FRONT
        )
    }

    private fun setUpCameraPreviewSizePreference(
        @StringRes previewSizePrefKeyId: Int, @StringRes pictureSizePrefKeyId: Int, cameraId: Int
    ) {
        val previewSizePreference =
            findPreference(getString(previewSizePrefKeyId)) as ListPreference
        var camera: Camera? = null
        try {
            camera = Camera.open(cameraId)
            val previewSizeList = CameraSource.generateValidPreviewSizeList(camera)
            val previewSizeStringValues = arrayOfNulls<String>(previewSizeList.size)
            val previewToPictureSizeStringMap: MutableMap<String, String> = HashMap()
            for (i in previewSizeList.indices) {
                val sizePair = previewSizeList[i]
                previewSizeStringValues[i] = sizePair.preview.toString()
                if (sizePair.picture != null) {
                    previewToPictureSizeStringMap[sizePair.preview.toString()] =
                        sizePair.picture.toString()
                }
            }
            previewSizePreference.entries = previewSizeStringValues
            previewSizePreference.entryValues = previewSizeStringValues
            if (previewSizePreference.entry == null) {
                // First time of opening the Settings page.
                val sizePair = CameraSource.selectSizePair(
                    camera,
                    CameraSource.DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH,
                    CameraSource.DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT
                )
                val previewSizeString = sizePair.preview.toString()
                previewSizePreference.value = previewSizeString
                previewSizePreference.summary = previewSizeString
                PreferenceUtils.saveString(
                    activity,
                    pictureSizePrefKeyId,
                    if (sizePair.picture != null) sizePair.picture.toString() else null
                )
            } else {
                previewSizePreference.summary = previewSizePreference.entry
            }
            previewSizePreference.onPreferenceChangeListener =
                OnPreferenceChangeListener { preference: Preference?, newValue: Any ->
                    val newPreviewSizeStringValue = newValue as String
                    previewSizePreference.summary = newPreviewSizeStringValue
                    PreferenceUtils.saveString(
                        activity,
                        pictureSizePrefKeyId,
                        previewToPictureSizeStringMap[newPreviewSizeStringValue]
                    )
                    true
                }
        } catch (e: RuntimeException) {
            // If there's no camera for the given camera id, hide the corresponding preference.
            (findPreference(getString(R.string.pref_category_key_camera)) as PreferenceCategory)
                .removePreference(previewSizePreference)
        } finally {
            camera?.release()
        }
    }
}