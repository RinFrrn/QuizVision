package com.virin.visionquiz

import android.util.Log
import android.widget.Toast
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.multidex.MultiDexApplication
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class MainApplication : MultiDexApplication(), CameraXConfig.Provider {

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
//            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .setMinimumLoggingLevel(Log.ERROR).build()
    }
}