package com.virin.visionquiz.screendetector

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.common.MlKitException
import com.virin.visionquiz.FrameMetadata
import com.virin.visionquiz.R
import com.virin.visionquiz.ScreenSource
import com.virin.visionquiz.VisionImageProcessor
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import java.nio.ByteBuffer

class ScreenDetectorFloatWindow(val context: Context) {

    private var cameraSource: ScreenSource? = null
    private var graphicOverlay: GraphicOverlay? = null
    private var selectedModel = TEXT_RECOGNITION_QUESTION

    private var imageProcessor: VisionImageProcessor? = null

    fun startFloatWindow() {

    }

    fun handelH264(buffer: ByteBuffer, width: Int, height: Int) {
//        if (needUpdateGraphicOverlayImageSourceInfo) {
//            graphicOverlay!!.setImageSourceInfo(width, height, false)
//            needUpdateGraphicOverlayImageSourceInfo = false
//        }
        try {
            val meta = FrameMetadata.Builder()
                .setWidth(width)
                .setHeight(height)
                .setRotation(0)
                .build()

            imageProcessor!!.processByteBuffer(buffer, meta, graphicOverlay)

        } catch (e: MlKitException) {
            Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
            Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT)
                .show()
        }
    }


    companion object {
        private const val TEXT_RECOGNITION_QUESTION = "Text Recognition Quiz"

        private const val TAG = "ScreenDetectorFloatWindow"

        public const val LIBRARY_ID = "LibraryId"
    }
}