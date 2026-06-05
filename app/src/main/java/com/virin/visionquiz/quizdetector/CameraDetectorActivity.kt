package com.virin.visionquiz.quizdetector

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.lifecycle.ViewModelProvider
import com.virin.visionquiz.CameraSource
import com.virin.visionquiz.CameraSourcePreview
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import com.virin.visionquiz.R
import com.virin.visionquiz.vision.questiondetector.QuizRecognitionProcessor
import java.io.IOException
import java.util.ArrayList

class CameraDetectorActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener,
    CompoundButton.OnCheckedChangeListener {

    private lateinit var viewModel: CameraDetectorViewModel

    private var cameraSource: CameraSource? = null
    private var preview: CameraSourcePreview? = null
    private var graphicOverlay: GraphicOverlay? = null
    private var selectedModel = TEXT_RECOGNITION_QUESTION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_camera_detector)

        val defaultIntExtra = 114514
        val libId = intent.getIntExtra(LIBRARY_ID, defaultIntExtra)
        if (libId != defaultIntExtra) {
            viewModel = ViewModelProvider(
                this,
                CameraDetectorViewModel.factory(application, libId)
            )[CameraDetectorViewModel::class.java]
        } else {
            Toast.makeText(this, "Library ID not found.", Toast.LENGTH_SHORT).show()
        }
        viewModel.quizList.observe(this) {
            Log.e("###", "Act ${it.size}")
        }

        preview = findViewById(R.id.preview_view)
        if (preview == null) {
            Log.d(TAG, "Preview is null")
        }

        graphicOverlay = findViewById(R.id.graphic_overlay)
        if (graphicOverlay == null) {
            Log.d(TAG, "graphicOverlay is null")
        }

        val options: MutableList<String> = ArrayList()
        options.add(TEXT_RECOGNITION_QUESTION)

        createCameraSource(selectedModel)
    }

    @Synchronized
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
        // An item was selected. You can retrieve the selected item using
        // parent.getItemAtPosition(pos)
        selectedModel = parent?.getItemAtPosition(pos).toString()
        Log.d(TAG, "Selected model: $selectedModel")
        preview?.stop()
        createCameraSource(selectedModel)
        startCameraSource()
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // Do nothing.
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        Log.d(TAG, "Set facing")
        if (cameraSource != null) {
            if (isChecked) {
                cameraSource?.setFacing(CameraSource.CAMERA_FACING_FRONT)
            } else {
                cameraSource?.setFacing(CameraSource.CAMERA_FACING_BACK)
            }
        }
        preview?.stop()
        startCameraSource()
    }

    private fun createCameraSource(model: String) {
        // If there's no existing cameraSource, create one.
        if (cameraSource == null) {
            cameraSource = CameraSource(this, graphicOverlay)
        }
        try {
            when (model) {
                TEXT_RECOGNITION_QUESTION -> {
                    Log.i(TAG, "Using on-device Quiz recognition Processor for Quiz")
                    cameraSource!!.setMachineLearningFrameProcessor(
                        QuizRecognitionProcessor(this, viewModel.quizList)
                    )
                }
                else -> Log.e(TAG, "Unknown model: $model")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Can not create image processor: $model", e)
            Toast.makeText(
                applicationContext,
                "Can not create image processor: " + e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Starts or restarts the camera source, if it exists. If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private fun startCameraSource() {
        if (cameraSource != null) {
            try {
                if (preview == null) {
                    Log.d(TAG, "resume: Preview is null")
                }
                if (graphicOverlay == null) {
                    Log.d(TAG, "resume: graphOverlay is null")
                }
                preview!!.start(cameraSource, graphicOverlay)
            } catch (e: IOException) {
                Log.e(TAG, "Unable to start camera source.", e)
                cameraSource!!.release()
                cameraSource = null
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        createCameraSource(selectedModel)
        startCameraSource()
    }

    /** Stops the camera. */
    override fun onPause() {
        super.onPause()
        preview?.stop()
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (cameraSource != null) {
            cameraSource?.release()
        }
    }

    companion object {
        private const val TEXT_RECOGNITION_QUESTION = "Text Recognition Quiz"

        private const val TAG = "CameraDetectorActivity"

        public const val LIBRARY_ID = "LibraryId"
    }
}
