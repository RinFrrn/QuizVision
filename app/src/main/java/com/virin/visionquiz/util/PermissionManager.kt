package com.virin.visionquiz.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.ArrayList

class PermissionManager(val activity: Activity) {

    public fun getPermissionIfNotGranted() {
        if (!allRuntimePermissionsGranted()) {
            getRuntimePermissions()
        }
    }

    private fun allRuntimePermissionsGranted(): Boolean {
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(activity, it)) {
                    return false
                }
            }
        }
        return true
    }

    private fun getRuntimePermissions() {
        val permissionsToRequest = ArrayList<String>()
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(activity, it)) {
                    permissionsToRequest.add(permission)
                }
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsToRequest.forEach(::markPermissionRequested)
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUESTS
            )
        }
    }

    private fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Permission granted: $permission")
            return true
        }
        Log.i(TAG, "Permission NOT granted: $permission")
        return false
    }

    companion object {
        private const val TAG = "PermissionManager"
        private const val PERMISSION_REQUESTS = 1
        const val PERMISSION_STATE_PREFS = "permission_state"
        const val KEY_CAMERA_PERMISSION_REQUESTED = "camera_permission_requested"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        const val KEY_ACCESSIBILITY_SEARCH_INTRO_SHOWN = "accessibility_search_intro_shown"

        private val REQUIRED_RUNTIME_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                arrayOf(
                    Manifest.permission.CAMERA,
////                    Manifest.permission.MANAGE_EXTERNAL_STORAGE,
//                    Manifest.permission.READ_MEDIA_VIDEO,
//                    Manifest.permission.READ_MEDIA_AUDIO,
//                    Manifest.permission.READ_MEDIA_IMAGES
                )
            else
                arrayOf(
                    Manifest.permission.CAMERA,
////                    Manifest.permission.MANAGE_EXTERNAL_STORAGE,
//                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
//                    Manifest.permission.READ_EXTERNAL_STORAGE
                )

//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
//                arrayOf(
//                    Manifest.permission.CAMERA,
//                    Manifest.permission.MANAGE_EXTERNAL_STORAGE,
//                    Manifest.permission.READ_EXTERNAL_STORAGE
//                )
//            else
//                arrayOf(
//                    Manifest.permission.CAMERA,
//                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
//                    Manifest.permission.READ_EXTERNAL_STORAGE
//                )


    }

    private fun markPermissionRequested(permission: String) {
        if (permission == Manifest.permission.CAMERA) {
            activity.getSharedPreferences(PERMISSION_STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CAMERA_PERMISSION_REQUESTED, true)
                .apply()
        }
    }
}
