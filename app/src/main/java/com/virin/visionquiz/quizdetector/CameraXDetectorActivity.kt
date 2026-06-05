package com.virin.visionquiz.quizdetector

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.text.set
import androidx.core.text.toSpannable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.common.annotation.KeepName
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.mlkit.common.MlKitException
import com.virin.visionquiz.CameraXViewModel
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import com.virin.visionquiz.R
import com.virin.visionquiz.VisionImageProcessor
import com.virin.visionquiz.databinding.ActivityCameraxDetectorBinding
import com.virin.visionquiz.vision.questiondetector.QuizRecognitionProcessor
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.vision.VisionProcessorBase
import com.virin.visionquiz.vision.questiondetector.OriginalRecognitionProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.math.min

/*
class CameraXDetectorActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityCameraxDetectorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        binding = ActivityCameraxDetectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_camera_xdetector)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.fab)
                .setAction("Action", null).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_camera_xdetector)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}*/
/** Live preview demo app for ML Kit APIs using CameraX. */
@KeepName
@ExperimentalCamera2Interop
class CameraXDetectorActivity : AppCompatActivity(), AdapterView.OnItemSelectedListener,
    CompoundButton.OnCheckedChangeListener {

    private lateinit var viewModel: CameraDetectorViewModel
    private lateinit var binding: ActivityCameraxDetectorBinding

    private var previewView: PreviewView? = null
    private var graphicOverlay: GraphicOverlay? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var imageProcessor: VisionImageProcessor? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var needUpdateGraphicOverlayImageSourceInfo = false
    private var selectedModel = TEXT_RECOGNITION_QUESTION
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraSelector: CameraSelector? = null

    private var isPaused = false
        set(value) {
            field = value
            binding.toggleButton.visibility = if (value) View.GONE else View.VISIBLE
        }

    private var zoomStateSubject: LiveData<ZoomState>? = null
//    private val availableLens: MutableLiveData<List<Camera2CameraInfo>> = MutableLiveData(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFullBleedCameraPreview()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
//        if (savedInstanceState != null) {
//            selectedModel = savedInstanceState.getString(STATE_SELECTED_MODEL, OBJECT_DETECTION)
//        }

        cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        binding = ActivityCameraxDetectorBinding.inflate(layoutInflater)
        setContentView(binding.root)
//        setContentView(R.layout.activity_camerax_detector)

        val isProcessorTest = intent.getBooleanExtra(IS_PROCESSOR_TEST, false)
        if (isProcessorTest) {
            // 测试，直接输出文字
            selectedModel = TEXT_RECOGNITION_ORIGINAL
        } else {
            // 非测试情况
            val defaultIntExtra = 114514
            val libId = intent.getIntExtra(LIBRARY_ID, defaultIntExtra)
            if (libId != defaultIntExtra) {
                viewModel = CameraDetectorViewModel.provider(this, libId)
            } else {
                Toast.makeText(this, "Library ID not found.", Toast.LENGTH_SHORT).show()
            }
            // do not remove this
            viewModel.quizList.observe(this as LifecycleOwner) {
                Log.e("###", "Act ${it.size}")
            }
            viewModel.library.observe(this as LifecycleOwner) {
                binding.footerTextView.text = it?.name ?: "unknown"
            }
        }



        previewView = binding.previewView
        graphicOverlay = binding.graphicOverlay


//        previewView = findViewById(R.id.preview_view)
//        if (previewView == null) {
//            Log.d(TAG, "previewView is null")
//        }

//        graphicOverlay = findViewById(R.id.graphic_overlay)
//        if (graphicOverlay == null) {
//            Log.d(TAG, "graphicOverlay is null")
//        }

//        val spinner = findViewById<Spinner>(R.id.spinner)
//        val options: MutableList<String> = ArrayList()
//        options.add(TEXT_RECOGNITION_QUESTION)

//        // Creating adapter for spinner
//        val dataAdapter = ArrayAdapter(this, R.layout.spinner_style, options)
//        // Drop down layout style - list view with radio button
//        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//        // attaching data adapter to spinner
//        spinner.adapter = dataAdapter
//        spinner.onItemSelectedListener = this
//        val facingSwitch = findViewById<ToggleButton>(R.id.facing_switch)
//        facingSwitch.setOnCheckedChangeListener(this)

        ViewModelProvider(
            this, ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        ).get(CameraXViewModel::class.java).processCameraProvider.observe(this) { provider: ProcessCameraProvider? ->
            cameraProvider = provider
            bindAllCameraUseCases()
        }

        binding.captureBtn.setOnClickListener {
            if (isPaused) {
                resumePreview()
            } else {
                pausePreview()
            }
        }

        binding.exitButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.toggleButton.addOnButtonCheckedListener { group, checkedId, isChecked ->
//            Log.d(TAG, "isChecked: $isChecked")
            // 添加删除"x"
            val button = findViewById<MaterialButton>(checkedId)
            if (isChecked) {
                button.text = String.format("%s×", button.text)
                button.textSize = 14f
            } else {
                button.text = button.text.removeSuffix("×")
                button.textSize = 12f
            }
        }

//        binding.toggleButton.addOnButtonCheckedListener { group, checkedId, isChecked ->
//            val button = findViewById<MaterialButton>(group.checkedButtonId)
//            val idx = button.text.toString().toInt()
//
//            val newCameraSelector = CameraSelector.Builder().addCameraFilter {
//                it.filter { camInfo ->
//                    // cam2Infos[0] is either EXTERNAL or best built-in camera
//                    val thisCamId = Camera2CameraInfo.from(camInfo).cameraId
//                    thisCamId == availableLens.value!![0].cameraId
//                }
//            }.build()
//
//            try {
//                if (cameraProvider!!.hasCamera(newCameraSelector)) {
////                    Log.d(TAG, "Set facing to " + newLensFacing)
////                    lensFacing = newLensFacing
//                    cameraSelector = newCameraSelector
//                    bindAllCameraUseCases()
//                }
//            } catch (e: CameraInfoUnavailableException) {
//                // Falls through
//            }
//            when (checkedId) {
//
//                R.id.button1 -> {
//
////                    val selector = selectExternalOrBestCamera(cameraProvider!!)
//                }
//            }
//        }
//
//        availableLens.observe(this as LifecycleOwner) { lens ->
//            binding.toggleButton.removeAllViews()
//
//            lens.forEach { info ->
//                val button = MaterialButton(this)
//                button.text = info.cameraId // 设置按钮的文本
//                button.id = View.generateViewId() // 设置按钮的唯一标识符
//
//                binding.toggleButton.addView(button)
//            }
//            Toast.makeText(this, "added ${lens.size}", Toast.LENGTH_SHORT).show()
////            binding.toggleButton.setSingleSelection(1)
//        }

//        getAllAvailableCamera()

        // create a CameraSelector for the USB camera (or highest level internal camera)
//        val selector = selectExternalOrBestCamera(cameraProvider)
//    processCameraProvider.bindToLifecycle(this, selector, preview, analysis)

//        val previewSizesMap: MutableMap<String, Array<Size>> = mutableMapOf()
//
//        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
//        cameraManager.cameraIdList.forEach { cameraId ->
//            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
//            val streamConfigurationMap =
//                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
//            val previewSizes =
//                streamConfigurationMap?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
//            previewSizesMap[cameraId] = previewSizes
//        }
//
//        val readable = previewSizesMap.map { entry ->
//            entry.key to entry.value.map {
//                "(${it.width}, ${it.height})"
//            }
//        }
//        showSnackBarWithCopy(binding.root, readable.toString())

//        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
//            // 获取状态栏和导航栏的WindowInsets
//            val systemBarsInsets =
//                insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            // 获取输入法窗口的WindowInsets
//            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
//            // 获取系统手势区域的WindowInsets
//            val mandatorySystemGesturesInsets =
//                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
//
//            graphicOverlay?.updateLayoutParams<MarginLayoutParams> {
//                setMargins(0, systemBarsInsets.top, 0, 0)
//            }
//
//            insets
//        }
    }

    private fun enableFullBleedCameraPreview() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

//    override fun onSaveInstanceState(bundle: Bundle) {
//        super.onSaveInstanceState(bundle)
//        bundle.putString(STATE_SELECTED_MODEL, selectedModel)
//    }

//    fun getAllAvailableCamera() {
//        cameraProvider?.availableCameraInfos?.map { Camera2CameraInfo.from(it) }?.let {
//            this.availableLens.value = it
//        }
//    }

    @ExperimentalCamera2Interop
    fun selectExternalOrBestCamera(provider: ProcessCameraProvider): CameraSelector? {
        val cam2Infos = provider.availableCameraInfos.map {
            Camera2CameraInfo.from(it)
        }.sortedByDescending {
            // HARDWARE_LEVEL is Int type, with the order of:
            // LEGACY < LIMITED < FULL < LEVEL_3 < EXTERNAL
            it.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        }

//        showSnackBarWithCopy(binding.root, cam2Infos.map { info ->
//            "{${info.cameraId}, LENS_INFO_MINIMUM_FOCUS_DISTANCE (${
//                info.getCameraCharacteristic(
//                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
//                )
//            })}"
//        }.joinToString("; \n"))

        return when {
            cam2Infos.isNotEmpty() -> {
                CameraSelector.Builder().addCameraFilter {
                    it.filter { camInfo ->
                        // cam2Infos[0] is either EXTERNAL or best built-in camera
                        val thisCamId = Camera2CameraInfo.from(camInfo).cameraId
                        thisCamId == cam2Infos[0].cameraId
                    }
                }.build()
            }
            else -> null
        }
    }


    @Synchronized
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
        // An item was selected. You can retrieve the selected item using
        // parent.getItemAtPosition(pos)
        selectedModel = parent?.getItemAtPosition(pos).toString()
        Log.d(TAG, "Selected model: $selectedModel")
        bindAnalysisUseCase()
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // Do nothing.
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        if (cameraProvider == null) {
            return
        }
        val newLensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val newCameraSelector = CameraSelector.Builder().requireLensFacing(newLensFacing).build()
        try {
            if (cameraProvider!!.hasCamera(newCameraSelector)) {
                Log.d(TAG, "Set facing to " + newLensFacing)
                lensFacing = newLensFacing
                cameraSelector = newCameraSelector
                bindAllCameraUseCases()
                return
            }
        } catch (e: CameraInfoUnavailableException) {
            // Falls through
        }
        Toast.makeText(
            applicationContext,
            "This device does not have lens with facing: $newLensFacing",
            Toast.LENGTH_SHORT
        ).show()
    }

    public override fun onResume() {
        super.onResume()

        if (isPaused) {
            resumePreview()
        } else {
            bindAllCameraUseCases()
        }
    }

    override fun onPause() {
        super.onPause()

        cameraProvider?.unbindAll()
        imageProcessor?.run { this.stop() }
    }

    public override fun onDestroy() {
        super.onDestroy()
        imageProcessor?.run { this.stop() }
        analysisExecutor.shutdown()
    }

    private fun bindAllCameraUseCases() {
        if (cameraProvider != null) {
            // As required by CameraX API, unbinds all use cases before trying to re-bind any of them.
            cameraProvider!!.unbindAll()
            bindPreviewUseCase()
            bindAnalysisUseCase()
        }
    }

    private fun bindPreviewUseCase() {
        if (!PreferenceUtils.isCameraLiveViewportEnabled(this)) {
            return
        }
        if (cameraProvider == null) {
            return
        }
        if (previewUseCase != null) {
            cameraProvider!!.unbind(previewUseCase)
        }

        val builder = Preview.Builder()
        val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
//        val targetResolution = TARGET_RESOLUTION
        if (targetResolution != null) {
            builder.setTargetResolution(targetResolution)
//            builder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
        }
        previewUseCase = builder.build()
        previewUseCase!!.setSurfaceProvider(previewView!!.surfaceProvider)
        cameraProvider!!.bindToLifecycle(
            this as LifecycleOwner, cameraSelector!!, previewUseCase
        ).also {
            subscribeZoomState(it)
        }
    }

    private fun subscribeZoomState(camera: Camera) {
        zoomStateSubject?.removeObservers(this)
//        val zoomRange = cameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val cameraInfo = camera.cameraInfo
        zoomStateSubject = cameraInfo.zoomState
        val min = zoomStateSubject!!.value!!.minZoomRatio
        val normal = 1.0f
        val two = min(2.0f, zoomStateSubject!!.value!!.maxZoomRatio)
        val max = min(3.0f, zoomStateSubject!!.value!!.maxZoomRatio)

        binding.toggleButton.removeAllViews()
        setOf(min, normal, two, max).sortedDescending().forEach { zoom ->

            val button = layoutInflater.inflate(
                R.layout.button_toggle_group, binding.toggleButton, false
            ) as MaterialButton
            button.id = View.generateViewId()
            button.text = String.format("%.1f", zoom).removeSuffix(".0").removePrefix("0")
            button.textSize = 12f
            button.letterSpacing = 0.06f
            button.typeface = Typeface.DEFAULT_BOLD
            button.addOnCheckedChangeListener { _, isChecked ->
//                Log.d(TAG, "OnCheckedChangeListener $isChecked")
                if (isChecked) {
                    camera.cameraControl.setZoomRatio(zoom)
                }
            }

            binding.toggleButton.addView(button)
            if (zoom == zoomStateSubject?.value?.zoomRatio) binding.toggleButton.check(button.id)
        }

        zoomStateSubject!!.observe(this) {

        }
    }

    private fun bindAnalysisUseCase() {
        if (cameraProvider == null) {
            return
        }
        if (analysisUseCase != null) {
            cameraProvider!!.unbind(analysisUseCase)
        }
        if (imageProcessor != null) {
            imageProcessor!!.stop()
        }
        imageProcessor = try {
            val processor = when (selectedModel) {
                TEXT_RECOGNITION_QUESTION -> {
                    Log.i(TAG, "Using on-device Quiz recognition Processor for Quiz")
                    QuizRecognitionProcessor(this, viewModel.quizList)
                }
                TEXT_RECOGNITION_ORIGINAL -> {
                    Log.i(TAG, "Using on-device Recognition Processor")
                    OriginalRecognitionProcessor(this)
                }
                else -> throw IllegalStateException("Invalid model name")
            }
            processor.setInferenceInfoListener(::renderInferenceInfo)
            processor
        } catch (e: Exception) {
            Log.e(TAG, "Can not create image processor: $selectedModel", e)
            Toast.makeText(
                applicationContext,
                "Can not create image processor: " + e.localizedMessage,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val builder = ImageAnalysis.Builder()
        val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
//        val targetResolution = TARGET_RESOLUTION
        if (targetResolution != null) {
            builder.setTargetResolution(targetResolution)
//            builder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
        }
        builder.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        analysisUseCase = builder.build()

        needUpdateGraphicOverlayImageSourceInfo = true

        analysisUseCase?.setAnalyzer(
            analysisExecutor
        ) { imageProxy: ImageProxy ->
            val overlay = graphicOverlay
            val processor = imageProcessor
            if (overlay == null || processor == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            if (needUpdateGraphicOverlayImageSourceInfo) {
                val isImageFlipped = lensFacing == CameraSelector.LENS_FACING_FRONT
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                if (rotationDegrees == 0 || rotationDegrees == 180) {
                    overlay.setImageSourceInfo(
                        imageProxy.width, imageProxy.height, isImageFlipped
                    )
                } else {
                    overlay.setImageSourceInfo(
                        imageProxy.height, imageProxy.width, isImageFlipped
                    )
                }
                needUpdateGraphicOverlayImageSourceInfo = false
            }
            try {
                processor.processImageProxy(imageProxy, overlay)
            } catch (e: MlKitException) {
                imageProxy.close()
                Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
                Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        cameraProvider!!.bindToLifecycle(
            this as LifecycleOwner, cameraSelector!!, analysisUseCase
        )
    }

    private fun renderInferenceInfo(info: VisionProcessorBase.InferenceInfo?) {
        val infoView = binding.inferenceInfoTextView
        if (info == null) {
            infoView.visibility = View.GONE
            infoView.text = ""
            return
        }

        infoView.visibility = View.VISIBLE
        infoView.text = buildString {
            append("InputImage size: ${info.imageHeight}x${info.imageWidth}")
            append('\n')
            if (info.framesPerSecond != null) {
                append("FPS: ${info.framesPerSecond}, ")
            }
            append("Frame latency: ${info.frameLatencyMs} ms")
            append('\n')
            append("Detector latency: ${info.detectorLatencyMs} ms")
        }
    }

    //暂停预览
    private fun pausePreview() {
        binding.captureBtn.setIconResource(R.drawable.round_arrow_back_24)
        isPaused = true

        cameraProvider!!.unbindAll()
        imageProcessor?.run { this.stop() }
    }

    //恢复预览
    private fun resumePreview() {
        if (isPaused) {
            binding.captureBtn.setIconResource(R.drawable.round_photo_camera_24)
            isPaused = false

            graphicOverlay?.clear()
            bindAllCameraUseCases()
        }
    }

    companion object {
        public const val LIBRARY_ID = "LibraryId"
        public const val IS_PROCESSOR_TEST = "IsProcessorTest"

        private const val TAG = "CameraXLivePreview"
        private const val TEXT_RECOGNITION_QUESTION = "Text Recognition Quiz"
        private const val TEXT_RECOGNITION_ORIGINAL = "Text Recognition Original"
    }
}
