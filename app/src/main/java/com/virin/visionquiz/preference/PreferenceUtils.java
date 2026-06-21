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

package com.virin.visionquiz.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build.VERSION_CODES;
import android.preference.PreferenceManager;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.camera.core.CameraSelector;
import com.google.android.gms.common.images.Size;
import com.google.common.base.Preconditions;
import com.google.mlkit.common.model.LocalModel;
import com.virin.visionquiz.CameraSource;
import com.virin.visionquiz.CameraSource.SizePair;
import com.virin.visionquiz.R;
import com.virin.visionquiz.dao.QuizManager;
import java.util.Arrays;
//import com.google.mlkit.vision.face.FaceDetectorOptions;
//import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions;
//import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase.DetectorMode;
//import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions;
//import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
//import com.google.mlkit.vision.pose.PoseDetectorOptionsBase;
//import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;
//import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

/** Utility class to retrieve shared preferences. */
public class PreferenceUtils {

  private static final int POSE_DETECTOR_PERFORMANCE_MODE_FAST = 1;
  private static final int DEFAULT_SCREEN_SEARCH_INTERVAL_MS = 750;
  private static final int DEFAULT_ACCESSIBILITY_SEARCH_INTERVAL_MS = 250;
  private static final int MIN_SCREEN_SEARCH_INTERVAL_MS = 250;
  private static final int DEFAULT_SCREEN_CAPTURE_FRAME_RATE = 30;
  private static final int ACCESSIBILITY_ANSWER_DOT_ALPHA = 0xDD;
  private static final String DEFAULT_ACCESSIBILITY_ANSWER_DOT_COLOR = "#000000";
  private static final double MIN_SEARCH_MATCH_SCORE = 0.60;
  private static final double MAX_SEARCH_MATCH_SCORE = 1.00;
  private static final android.util.Size DEFAULT_CAMERAX_TARGET_RESOLUTION =
      new android.util.Size(1280, 720);

  static void saveString(Context context, @StringRes int prefKeyId, @Nullable String value) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putString(context.getString(prefKeyId), value)
        .apply();
  }

  @Nullable
  public static SizePair getCameraPreviewSizePair(Context context, int cameraId) {
    Preconditions.checkArgument(
        cameraId == CameraSource.CAMERA_FACING_BACK
            || cameraId == CameraSource.CAMERA_FACING_FRONT);
    String previewSizePrefKey;
    String pictureSizePrefKey;
    if (cameraId == CameraSource.CAMERA_FACING_BACK) {
      previewSizePrefKey = context.getString(R.string.pref_key_rear_camera_preview_size);
      pictureSizePrefKey = context.getString(R.string.pref_key_rear_camera_picture_size);
    } else {
      previewSizePrefKey = context.getString(R.string.pref_key_front_camera_preview_size);
      pictureSizePrefKey = context.getString(R.string.pref_key_front_camera_picture_size);
    }

    try {
      SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
      return new SizePair(
          Size.parseSize(sharedPreferences.getString(previewSizePrefKey, null)),
          Size.parseSize(sharedPreferences.getString(pictureSizePrefKey, null)));
    } catch (Exception e) {
      return null;
    }
  }

  @RequiresApi(VERSION_CODES.LOLLIPOP)
  @Nullable
  public static android.util.Size getCameraXTargetResolution(Context context, int lensfacing) {
    Preconditions.checkArgument(
        lensfacing == CameraSelector.LENS_FACING_BACK
            || lensfacing == CameraSelector.LENS_FACING_FRONT);
    String prefKey =
        lensfacing == CameraSelector.LENS_FACING_BACK
            ? context.getString(R.string.pref_key_camerax_rear_camera_target_resolution)
            : context.getString(R.string.pref_key_camerax_front_camera_target_resolution);
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    try {
      return android.util.Size.parseSize(
          sharedPreferences.getString(prefKey, getDefaultCameraXTargetResolutionString()));
    } catch (Exception e) {
      return DEFAULT_CAMERAX_TARGET_RESOLUTION;
    }
  }

  public static android.util.Size getDefaultCameraXTargetResolution() {
    return DEFAULT_CAMERAX_TARGET_RESOLUTION;
  }

  public static String getDefaultCameraXTargetResolutionString() {
    return DEFAULT_CAMERAX_TARGET_RESOLUTION.getWidth()
        + "x"
        + DEFAULT_CAMERAX_TARGET_RESOLUTION.getHeight();
  }

  public static boolean shouldHideDetectionInfo(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_info_hide);
    return sharedPreferences.getBoolean(prefKey, false);
  }

//  public static ObjectDetectorOptions getObjectDetectorOptionsForStillImage(Context context) {
//    return getObjectDetectorOptions(
//        context,
//        R.string.pref_key_still_image_object_detector_enable_multiple_objects,
//        R.string.pref_key_still_image_object_detector_enable_classification,
//        ObjectDetectorOptions.SINGLE_IMAGE_MODE);
//  }
//
//  public static ObjectDetectorOptions getObjectDetectorOptionsForLivePreview(Context context) {
//    return getObjectDetectorOptions(
//        context,
//        R.string.pref_key_live_preview_object_detector_enable_multiple_objects,
//        R.string.pref_key_live_preview_object_detector_enable_classification,
//        ObjectDetectorOptions.STREAM_MODE);
//  }
//

//  private static ObjectDetectorOptions getObjectDetectorOptions(
//      Context context,
//      @StringRes int prefKeyForMultipleObjects,
//      @StringRes int prefKeyForClassification,
//      @DetectorMode int mode) {
//
//    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
//
//    boolean enableMultipleObjects =
//        sharedPreferences.getBoolean(context.getString(prefKeyForMultipleObjects), false);
//    boolean enableClassification =
//        sharedPreferences.getBoolean(context.getString(prefKeyForClassification), true);
//
//    ObjectDetectorOptions.Builder builder =
//        new ObjectDetectorOptions.Builder().setDetectorMode(mode);
//    if (enableMultipleObjects) {
//      builder.enableMultipleObjects();
//    }
//    if (enableClassification) {
//      builder.enableClassification();
//    }
//    return builder.build();
//  }
//
//  public static CustomObjectDetectorOptions getCustomObjectDetectorOptionsForStillImage(
//      Context context, LocalModel localModel) {
//    return getCustomObjectDetectorOptions(
//        context,
//        localModel,
//        R.string.pref_key_still_image_object_detector_enable_multiple_objects,
//        R.string.pref_key_still_image_object_detector_enable_classification,
//        CustomObjectDetectorOptions.SINGLE_IMAGE_MODE);
//  }
//
//  public static CustomObjectDetectorOptions getCustomObjectDetectorOptionsForLivePreview(
//      Context context, LocalModel localModel) {
//    return getCustomObjectDetectorOptions(
//        context,
//        localModel,
//        R.string.pref_key_live_preview_object_detector_enable_multiple_objects,
//        R.string.pref_key_live_preview_object_detector_enable_classification,
//        CustomObjectDetectorOptions.STREAM_MODE);
//  }
//
//  private static CustomObjectDetectorOptions getCustomObjectDetectorOptions(
//      Context context,
//      LocalModel localModel,
//      @StringRes int prefKeyForMultipleObjects,
//      @StringRes int prefKeyForClassification,
//      @DetectorMode int mode) {
//
//    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
//
//    boolean enableMultipleObjects =
//        sharedPreferences.getBoolean(context.getString(prefKeyForMultipleObjects), false);
//    boolean enableClassification =
//        sharedPreferences.getBoolean(context.getString(prefKeyForClassification), true);
//
//    CustomObjectDetectorOptions.Builder builder =
//        new CustomObjectDetectorOptions.Builder(localModel).setDetectorMode(mode);
//    if (enableMultipleObjects) {
//      builder.enableMultipleObjects();
//    }
//    if (enableClassification) {
//      builder.enableClassification().setMaxPerObjectLabelCount(1);
//    }
//    return builder.build();
//  }
//
//  public static FaceDetectorOptions getFaceDetectorOptions(Context context) {
//    int landmarkMode =
//        getModeTypePreferenceValue(
//            context,
//            R.string.pref_key_live_preview_face_detection_landmark_mode,
//            FaceDetectorOptions.LANDMARK_MODE_NONE);
//    int contourMode =
//        getModeTypePreferenceValue(
//            context,
//            R.string.pref_key_live_preview_face_detection_contour_mode,
//            FaceDetectorOptions.CONTOUR_MODE_ALL);
//    int classificationMode =
//        getModeTypePreferenceValue(
//            context,
//            R.string.pref_key_live_preview_face_detection_classification_mode,
//            FaceDetectorOptions.CLASSIFICATION_MODE_NONE);
//    int performanceMode =
//        getModeTypePreferenceValue(
//            context,
//            R.string.pref_key_live_preview_face_detection_performance_mode,
//            FaceDetectorOptions.PERFORMANCE_MODE_FAST);
//
//    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
//    boolean enableFaceTracking =
//        sharedPreferences.getBoolean(
//            context.getString(R.string.pref_key_live_preview_face_detection_face_tracking), false);
//    float minFaceSize =
//        Float.parseFloat(
//            sharedPreferences.getString(
//                context.getString(R.string.pref_key_live_preview_face_detection_min_face_size),
//                "0.1"));
//
//    FaceDetectorOptions.Builder optionsBuilder =
//        new FaceDetectorOptions.Builder()
//            .setLandmarkMode(landmarkMode)
//            .setContourMode(contourMode)
//            .setClassificationMode(classificationMode)
//            .setPerformanceMode(performanceMode)
//            .setMinFaceSize(minFaceSize);
//    if (enableFaceTracking) {
//      optionsBuilder.enableTracking();
//    }
//    return optionsBuilder.build();
//  }
//
//  public static PoseDetectorOptionsBase getPoseDetectorOptionsForLivePreview(Context context) {
//    int performanceMode =
//        getModeTypePreferenceValue(
//            context,
//            R.string.pref_key_live_preview_pose_detection_performance_mode,
//            POSE_DETECTOR_PERFORMANCE_MODE_FAST);
//    boolean preferGPU = preferGPUForPoseDetection(context);
//    if (performanceMode == POSE_DETECTOR_PERFORMANCE_MODE_FAST) {
//      PoseDetectorOptions.Builder builder =
//          new PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE);
//      if (preferGPU) {
//        builder.setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU);
//      }
//      return builder.build();
//    } else {
//      AccuratePoseDetectorOptions.Builder builder =
//          new AccuratePoseDetectorOptions.Builder()
//              .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE);
//      if (preferGPU) {
//        builder.setPreferredHardwareConfigs(AccuratePoseDetectorOptions.CPU_GPU);
//      }
//      return builder.build();
//    }
//  }
//
//  public static PoseDetectorOptionsBase getPoseDetectorOptionsForStillImage(Context context) {
//    int performanceMode =
//        getModeTypePreferenceValue(
//            context,
//            R.string.pref_key_still_image_pose_detection_performance_mode,
//            POSE_DETECTOR_PERFORMANCE_MODE_FAST);
//    boolean preferGPU = preferGPUForPoseDetection(context);
//    if (performanceMode == POSE_DETECTOR_PERFORMANCE_MODE_FAST) {
//      PoseDetectorOptions.Builder builder =
//          new PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE);
//      if (preferGPU) {
//        builder.setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU);
//      }
//      return builder.build();
//    } else {
//      AccuratePoseDetectorOptions.Builder builder =
//          new AccuratePoseDetectorOptions.Builder()
//              .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE);
//      if (preferGPU) {
//        builder.setPreferredHardwareConfigs(AccuratePoseDetectorOptions.CPU_GPU);
//      }
//      return builder.build();
//    }
//  }

  public static boolean shouldGroupRecognizedTextInBlocks(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_group_recognized_text_in_blocks);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static boolean showLanguageTag(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_show_language_tag);
    return sharedPreferences.getBoolean(prefKey, false);
  }

  public static boolean shouldShowTextConfidence(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_show_text_confidence);
    return sharedPreferences.getBoolean(prefKey, false);
  }

  public static boolean shouldUseBriefAnswerDisplay(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_brief_answer_display);
    return sharedPreferences.getBoolean(prefKey, false);
  }

  public static float getQuizOverlayTextSizeSp(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_quiz_overlay_text_size);
    try {
      return Float.parseFloat(sharedPreferences.getString(prefKey, "12"));
    } catch (Exception e) {
      return 12f;
    }
  }

  public static int getScreenSearchIntervalMs(Context context) {
    return getSearchIntervalMs(
        context,
        R.string.pref_key_screen_search_interval_ms,
        DEFAULT_SCREEN_SEARCH_INTERVAL_MS);
  }

  public static int getAccessibilitySearchIntervalMs(Context context) {
    return getSearchIntervalMs(
        context,
        R.string.pref_key_accessibility_search_interval_ms,
        DEFAULT_ACCESSIBILITY_SEARCH_INTERVAL_MS);
  }

  public static int getScreenCaptureFrameRate(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_screen_capture_frame_rate);
    try {
      int frameRate =
          Integer.parseInt(
              sharedPreferences.getString(
                  prefKey,
                  String.valueOf(DEFAULT_SCREEN_CAPTURE_FRAME_RATE)));
      if (frameRate == 15 || frameRate == 30 || frameRate == 60) {
        return frameRate;
      }
    } catch (Exception e) {
      // Fall through to the default below.
    }
    return DEFAULT_SCREEN_CAPTURE_FRAME_RATE;
  }

  public static boolean shouldDetectScreenChangesBeforeSearch(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_screen_search_detect_changes);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static boolean shouldShowScreenOcrAnswerFrames(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_screen_search_show_answer_frames);
    return sharedPreferences.getBoolean(prefKey, false);
  }

  public static double getCameraSearchMinMatchScore(Context context) {
    return getSearchMinMatchScore(
        context,
        R.string.pref_key_camera_search_min_match_score,
        QuizManager.DEFAULT_MIN_MATCH_SCORE);
  }

  public static double getScreenSearchMinMatchScore(Context context) {
    return getSearchMinMatchScore(
        context,
        R.string.pref_key_screen_search_min_match_score,
        QuizManager.DEFAULT_MIN_MATCH_SCORE);
  }

  public static double getAccessibilitySearchMinMatchScore(Context context) {
    return getSearchMinMatchScore(
        context,
        R.string.pref_key_accessibility_search_min_match_score,
        MAX_SEARCH_MATCH_SCORE);
  }

  public static boolean shouldShowAccessibilityFloatingControl(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_accessibility_show_floating_control);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static void setShowAccessibilityFloatingControl(Context context, boolean enabled) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putBoolean(
            context.getString(R.string.pref_key_accessibility_show_floating_control), enabled)
        .apply();
  }

  public static boolean shouldUseAccessibilitySimplifiedAnswerDisplay(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey =
        context.getString(R.string.pref_key_accessibility_simplified_answer_display);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static void setAccessibilitySimplifiedAnswerDisplay(Context context, boolean enabled) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putBoolean(
            context.getString(R.string.pref_key_accessibility_simplified_answer_display), enabled)
        .apply();
  }

  public static int getAccessibilityAnswerDotColor(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_accessibility_answer_dot_color);
    String storedValue =
        sharedPreferences.getString(prefKey, DEFAULT_ACCESSIBILITY_ANSWER_DOT_COLOR);
    String selectedValue = isAccessibilityAnswerDotColorValue(context, storedValue)
        ? storedValue
        : DEFAULT_ACCESSIBILITY_ANSWER_DOT_COLOR;
    try {
      int rgb = Color.parseColor(selectedValue) & 0x00FFFFFF;
      return (ACCESSIBILITY_ANSWER_DOT_ALPHA << 24) | rgb;
    } catch (Exception e) {
      return (ACCESSIBILITY_ANSWER_DOT_ALPHA << 24) | Color.BLACK;
    }
  }

  private static boolean isAccessibilityAnswerDotColorValue(Context context, @Nullable String value) {
    if (value == null) {
      return false;
    }
    String[] values =
        context.getResources().getStringArray(R.array.pref_entry_values_accessibility_answer_dot_color);
    return Arrays.asList(values).contains(value);
  }

  public static boolean shouldUseSmartAccessibilityVerticalSwipe(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_accessibility_vertical_swipe_mode);
    String smartValue =
        context.getString(R.string.pref_value_accessibility_vertical_swipe_smart);
    String fixedValue =
        context.getString(R.string.pref_value_accessibility_vertical_swipe_fixed);
    String storedValue = sharedPreferences.getString(prefKey, fixedValue);
    return smartValue.equals(storedValue);
  }

  private static double getSearchMinMatchScore(
      Context context,
      @StringRes int prefKeyId,
      double defaultValue) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(prefKeyId);
    try {
      double minScore =
          Double.parseDouble(
              sharedPreferences.getString(
                  prefKey,
                  String.valueOf(defaultValue)));
      if (Double.isFinite(minScore)
          && minScore >= MIN_SEARCH_MATCH_SCORE
          && minScore <= MAX_SEARCH_MATCH_SCORE) {
        return minScore;
      }
    } catch (Exception e) {
      // Fall through to the default below.
    }
    return defaultValue;
  }

  private static int getSearchIntervalMs(
      Context context,
      @StringRes int prefKeyId,
      int defaultIntervalMs) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(prefKeyId);
    try {
      int interval =
          Integer.parseInt(
              sharedPreferences.getString(
                  prefKey,
                  String.valueOf(defaultIntervalMs)));
      return Math.max(interval, MIN_SCREEN_SEARCH_INTERVAL_MS);
    } catch (Exception e) {
      return defaultIntervalMs;
    }
  }

  public static boolean preferGPUForPoseDetection(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_pose_detector_prefer_gpu);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static boolean shouldShowPoseDetectionInFrameLikelihoodLivePreview(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey =
        context.getString(R.string.pref_key_live_preview_pose_detector_show_in_frame_likelihood);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static boolean shouldShowPoseDetectionInFrameLikelihoodStillImage(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey =
        context.getString(R.string.pref_key_still_image_pose_detector_show_in_frame_likelihood);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static boolean shouldPoseDetectionVisualizeZ(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_pose_detector_visualize_z);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static boolean shouldPoseDetectionRescaleZForVisualization(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_pose_detector_rescale_z);
    return sharedPreferences.getBoolean(prefKey, true);
  }

  public static boolean shouldPoseDetectionRunClassification(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_pose_detector_run_classification);
    return sharedPreferences.getBoolean(prefKey, false);
  }

  public static boolean shouldSegmentationEnableRawSizeMask(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_segmentation_raw_size_mask);
    return sharedPreferences.getBoolean(prefKey, false);
  }

  /**
   * Mode type preference is backed by {@link android.preference.ListPreference} which only support
   * storing its entry value as string type, so we need to retrieve as string and then convert to
   * integer.
   */
  private static int getModeTypePreferenceValue(
      Context context, @StringRes int prefKeyResId, int defaultValue) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(prefKeyResId);
    return Integer.parseInt(sharedPreferences.getString(prefKey, String.valueOf(defaultValue)));
  }

  public static boolean isCameraLiveViewportEnabled(Context context) {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    String prefKey = context.getString(R.string.pref_key_camera_live_viewport);
    return sharedPreferences.getBoolean(prefKey, true);
  }

//  public static int getFaceMeshUseCase(Context context) {
//    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
//    String prefKey = context.getString(R.string.pref_key_face_mesh_use_case);
//    return Integer.parseInt(
//        sharedPreferences.getString(prefKey, String.valueOf(FaceMeshDetectorOptions.FACE_MESH)));
//  }

  private PreferenceUtils() {}
}
