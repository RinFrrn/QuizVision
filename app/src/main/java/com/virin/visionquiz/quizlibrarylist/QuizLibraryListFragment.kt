package com.virin.visionquiz.quizlibrarylist

import RenameDialogFragment
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.virin.visionquiz.BuildConfig
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.dao.QuizManager
import com.virin.visionquiz.databinding.FragmentQuizLibraryListBinding
import com.virin.visionquiz.preference.SettingsActivity
import com.virin.visionquiz.quizdetector.CameraXDetectorActivity
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.screendetector.ScreenDetectorController
import com.virin.visionquiz.util.MdcThemeBridge
import com.virin.visionquiz.util.BaseQuizFragment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.virin.visionquiz.ai.BatchAiExplanationService
import com.virin.visionquiz.quizlibrarylist.QuizLibraryListScreen
import com.virin.visionquiz.util.SimilarQuizStore
import com.virin.visionquiz.util.PermissionManager
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.dp
import com.virin.visionquiz.util.refreshQuizTopBarMenu
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max


/**
 * A simple [Fragment] subclass.
 * Use the [QuizLibraryListFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class QuizLibraryListFragment : BaseQuizFragment() {


    private var _binding: FragmentQuizLibraryListBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var viewModel: QuizLibraryListViewModel

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private var systemBottomInset = 0
    private var baseRecyclerPaddingBottom = 0
    private var baseFabBottomMargin = 0
    private var basePermissionNoticeBottomMargin = 0

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionNotice()
        }

    private val aiProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BatchAiExplanationService.ACTION_PROGRESS_UPDATE) {
                val cached = intent.getIntExtra(BatchAiExplanationService.EXTRA_CACHED_COUNT, 0)
                val total = intent.getIntExtra(BatchAiExplanationService.EXTRA_TOTAL_COUNT, 0)
                val isGenerating = intent.getBooleanExtra(BatchAiExplanationService.EXTRA_IS_GENERATING, false)
                val libraryId = intent.getIntExtra("library_id", 0)
                if (libraryId > 0) {
                    viewModel.updateAiExplanationProgress(libraryId, cached, total, isGenerating)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenDetectorController.onHostResumed(requireActivity())
        if (_binding != null) {
            refreshPermissionNotice()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(aiProgressReceiver)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()

        if (_binding != null) {
            hideImportMenu(animated = false)
        }
        viewModel.exitSelectionMode()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {

        _binding = FragmentQuizLibraryListBinding.inflate(layoutInflater, container, false)

        return binding.root
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (resources.configuration.screenWidthDp != newConfig.screenWidthDp) {
            updateLayoutManager(newConfig.screenWidthDp)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onBackPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this, false) {
            viewModel.exitSelectionMode()
        }

        // Set up adapter (still needed for existing code references)
        val adapter = QuizLibraryListAdapter(onItemClickListBtnListener, onSelectionListener)
        binding.recyclerView.adapter = adapter
        SimilarQuizStore.progress.observe(viewLifecycleOwner) { map ->
            adapter.updateSimilarProgress(map)
        }
        
        // Get the ViewModel (must be before refreshTopBarMenu which reads selection state)
        viewModel = ViewModelProvider(this)[QuizLibraryListViewModel::class.java]
        binding.viewModel = viewModel

        applySort()
        configureQuizTopBar(binding.toolbar, TITLE, showNavigation = false)
        refreshTopBarMenu()
        setupPermissionNotice()
        setupImportMenu()

        // Observe selection state to update toolbar and FAB
        viewModel.isSelectionMode.observe(viewLifecycleOwner) { isSelecting ->
            if (isSelecting) {
                hideImportMenu(animated = false)
                configureQuizTopBar(
                    binding.toolbar,
                    "选择项目",
                    navigationIconRes = R.drawable.round_close_24,
                    onNavigationClick = { viewModel.exitSelectionMode() }
                )
                binding.fabAddQuizLibrary.hide()
                onBackPressedCallback.isEnabled = true
            } else {
                configureQuizTopBar(binding.toolbar, TITLE, showNavigation = false)
                binding.fabAddQuizLibrary.show()
                onBackPressedCallback.isEnabled = false
            }
            refreshTopBarMenu()
        }
        viewModel.selectedIds.observe(viewLifecycleOwner) { ids ->
            if (viewModel.isSelectionMode.value == true) {
                binding.toolbar.title = if (ids.isEmpty()) "选择项目" else "已选择 ${ids.size} 项"
            }
        }

        // Hide old RecyclerView and empty view
        binding.recyclerView.visibility = View.GONE
        binding.emptyLl.visibility = View.GONE
        
        // Add ComposeView for the library list
        val composeView = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MdcThemeBridge {
                QuizLibraryListScreen(
                    viewModel = viewModel,
                    onLibraryClick = { library ->
                        onItemClickListBtnListener.onButtonClicked(library, QuizLibraryListAdapter.ROOT_VIEW)
                    },
                    onLibraryLongClick = { library ->
                        // handled by original adapter
                    },
                    onCameraClick = { library ->
                        onItemClickListBtnListener.onButtonClicked(library, QuizLibraryListAdapter.CAMERA_BUTTON)
                    },
                    onScreenRecordClick = { library ->
                        onItemClickListBtnListener.onButtonClicked(library, QuizLibraryListAdapter.SCREEN_RECORD_BUTTON)
                    },
                    onRename = { library ->
                        onItemClickListBtnListener.onButtonClicked(library, QuizLibraryListAdapter.RENAME_BUTTON)
                    },
                    onDelete = { library ->
                        onItemClickListBtnListener.onButtonClicked(library, QuizLibraryListAdapter.DELETE_BUTTON)
                    }
                )
                }
            }
        }
        val constraintLayout = binding.root as androidx.constraintlayout.widget.ConstraintLayout
        constraintLayout.addView(composeView, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        ).apply {
            topToBottom = binding.toolbar.id
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        })
//        // Observe the quiz library list from the ViewModel
//        viewModel.quizLibraryList.observe(viewLifecycleOwner, { quizLibraries ->
//            // Set the data for the adapter
//            adapter.submitList(quizLibraries)
//
//            // Fetch quiz list for each quiz library
//            quizLibraries.forEach { quizLibrary ->
//                viewModel.getQuizListByLibraryIdAsync(quizLibrary.id)
//                    .observe(viewLifecycleOwner, { quizList ->
//                        quizLibrary.quizCount = quizList.size
//                        adapter.notifyDataSetChanged()
//                    })
//            }
//        })
        binding.fabAddQuizLibrary.setOnClickListener {
            showImportMenu()
        }

//        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
//            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
////                val childView = rv.findChildViewUnder(e.x, e.y)
////                if (childView != null && e.action == MotionEvent.ACTION_UP) {
////                    // 获取视图项的位置
////                    val position = rv.getChildAdapterPosition(childView)
////                    // 在此处处理点击事件
////                    Toast.makeText(rv.context, "您点击了第 ${position + 1} 项", Toast.LENGTH_SHORT).show()
////                    return true
////                }
//                return false
//            }
//
//            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
//
//            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
//        })


        baseRecyclerPaddingBottom = binding.recyclerView.paddingBottom
        baseFabBottomMargin =
            (binding.fabAddQuizLibrary.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        basePermissionNoticeBottomMargin =
            (binding.permissionNoticeCard.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            // 获取状态栏和导航栏的WindowInsets
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 获取输入法窗口的WindowInsets
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            systemBottomInset = if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            updateBottomSpacing()

            insets
        }
        refreshPermissionNotice()
    }

    

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
//            FilePickerManager.REQUEST_CODE -> {
//                if (resultCode == Activity.RESULT_OK) {
//                    val list = FilePickerManager.obtainData()
//                    // do your work
//                    Toast.makeText(requireContext(), "已选择：$list", Toast.LENGTH_SHORT).show()
//
//                    val filePath = list.first()
//                    // TODO
//                    QuizManager.importExcel(requireActivity(), filePath)
//
//                } else {
//                    Toast.makeText(requireContext(), "没有选择任何东西~", Toast.LENGTH_SHORT).show()
//                }
//            }
            // content://com.android.providers.media.documents/document/document%3A1000001285
            REQUEST_CHOOSE_FILE -> {
                if (resultCode == Activity.RESULT_OK) {
                    val selectedFiles: ArrayList<Uri> = ArrayList()

                    if (data?.clipData != null) {
                        // 处理多个文件
                        val clipData = data.clipData!!
                        for (i in 0 until clipData.itemCount) {
                            val uri = clipData.getItemAt(i).uri
                            selectedFiles.add(uri)
                        }
                    } else if (data?.data != null) {
                        // 处理单个文件
                        val uri = data.data!!
                        selectedFiles.add(uri)
                    }

                    // 在 selectedFiles 中处理所选文件
//                    for (fileUri in selectedFiles) {
                    // 处理每个文件的逻辑，例如读取文件内容或执行其他操作
                    // 在这里可以使用文件的 URI 来访问文件数据
//                    }
                    QuizManager.importExcels(requireActivity(), selectedFiles)

//                    data?.data?.let { uri ->

//                        QuizManager.importExcel(requireActivity(), uri)

                    //E/FIRST_FRAGMENT: FileDetails(contentUri=content://media/external/file/1000001285, name=1_党的二十大精神知识竞赛总题库.xlsx, size=130963, mimeType=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, dateAdded=1676808063, dateModified=1676808063, isDownload=true, relativePath=Download/WeiXin/, mediaType=6)
                    // meizu 17 (android 11) Caused by: java.lang.NumberFormatException: For input string: "/storage/emulated/0/Download/三星台区经理_快搜导入.xlsx"
//                        val fileInfo = getFileInfo(requireContext(), fileId.toLong())
//                        Log.e(TAG, fileInfo.toString())
//                        Toast.makeText(requireContext(), MediaStore.Files.getContentUri("external").path, Toast.LENGTH_SHORT).show()
//                    }
                }
            }
        }
    }

    private fun updateLayoutManager(widthDp: Int) {
        val cardMaxWidth = 260
        val span = max(1, widthDp / cardMaxWidth)
        println("$$$ ${widthDp}, $span")
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), span)
    }

    private fun showImportMenu() {
        if (binding.importActionMenu.visibility == View.VISIBLE) {
            hideImportMenu(animated = true)
        } else {
            showImportMenuAnimated()
        }
    }

    private fun setupImportMenu() {
        binding.importMenuScrim.setOnClickListener {
            hideImportMenu(animated = true)
        }
        binding.importChooseFileButton.setOnClickListener {
            hideImportMenu(animated = true)
            startChooseFileIntentForResult()
        }
        binding.importInputTextButton.setOnClickListener {
            hideImportMenu(animated = true)
            showImportDialog()
        }
        binding.importClipboardButton.setOnClickListener {
            hideImportMenu(animated = true)
            val clipText = readClipboardText()
            if (clipText.isBlank()) {
                Snackbar.make(
                    binding.root,
                    R.string.import_text_clipboard_empty,
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                showImportDialog(clipText)
            }
        }
    }

    private fun showImportMenuAnimated() {
        binding.importMenuScrim.visibility = View.VISIBLE
        binding.importActionMenu.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            translationY = 12.dp.toFloat()
            post {
                if (_binding == null || visibility != View.VISIBLE) {
                    return@post
                }
                pivotX = width.toFloat()
                pivotY = height.toFloat()
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(com.google.android.material.animation.AnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR)
                    .start()
            }
        }
    }

    private fun hideImportMenu(animated: Boolean) {
        if (binding.importActionMenu.visibility != View.VISIBLE) {
            binding.importMenuScrim.visibility = View.GONE
            return
        }
        if (!animated) {
            binding.importActionMenu.visibility = View.GONE
            binding.importMenuScrim.visibility = View.GONE
            return
        }
        binding.importActionMenu.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .translationY(8.dp.toFloat())
            .setDuration(120L)
            .setInterpolator(com.google.android.material.animation.AnimationUtils.FAST_OUT_SLOW_IN_INTERPOLATOR)
            .withEndAction {
                if (_binding != null) {
                    binding.importActionMenu.visibility = View.GONE
                    binding.importActionMenu.alpha = 1f
                    binding.importActionMenu.scaleX = 1f
                    binding.importActionMenu.scaleY = 1f
                    binding.importActionMenu.translationY = 0f
                    binding.importMenuScrim.visibility = View.GONE
                }
            }
            .start()
    }

    private fun showImportDialog(initialText: String = "") {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleInputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.import_text_library_title_hint)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dp
            }
        }
        val titleInput = TextInputEditText(titleInputLayout.context).apply {
            setSingleLine(true)
        }
        titleInputLayout.addView(titleInput)
        container.addView(titleInputLayout)

        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.import_text_hint)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val input = TextInputEditText(inputLayout.context).apply {
            minLines = 6
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setText(initialText)
            if (initialText.isNotBlank()) {
                setSelection(initialText.length)
            }
        }
        inputLayout.addView(input)
        val textScroll = NestedScrollView(requireContext()).apply {
            isFillViewport = false
            addView(inputLayout)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(textScroll)

        val actionRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 12.dp, 0, 0)
        }

        val clipboardButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.import_text_from_clipboard)
            backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(requireView(), R.attr.colorSecondaryContainer)
            )
            setTextColor(MaterialColors.getColor(requireView(), R.attr.colorOnSecondaryContainer))
            setOnClickListener {
                val clipText = readClipboardText()
                if (clipText.isBlank()) {
                    Snackbar.make(binding.root, R.string.import_text_clipboard_empty, Snackbar.LENGTH_SHORT).show()
                } else {
                    input.setText(clipText)
                    input.setSelection(input.text?.length ?: 0)
                    inputLayout.error = null
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dp
            }
        }

        val importButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.import_text_confirm)
            setOnClickListener {
                val rawText = input.text?.toString().orEmpty()
                if (rawText.isBlank()) {
                    inputLayout.error = getString(R.string.import_text_empty_error)
                } else {
                    tag?.let { taggedDialog ->
                        (taggedDialog as? android.app.Dialog)?.dismiss()
                    }
                    val libraryTitle = titleInput.text?.toString()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.import_text_default_library_title)
                    QuizManager.importPlainText(requireActivity(), rawText, libraryTitle)
                }
            }
        }
        actionRow.addView(clipboardButton)
        actionRow.addView(importButton)
        container.addView(actionRow)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_text_title)
            .setView(container)
            .setNegativeButton(R.string.import_text_format_help_content_description, null)
            .setPositiveButton(R.string.cancel, null)
            .create()
        importButton.tag = dialog
        dialog.show()
        configureImportTextFormatHelpButton(dialog)
    }

    private fun configureImportTextFormatHelpButton(dialog: androidx.appcompat.app.AlertDialog) {
        val helpButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        val helpIcon = ContextCompat.getDrawable(requireContext(), R.drawable.round_help_outline_24)
            ?.mutate()
            ?.apply {
                setTint(MaterialColors.getColor(helpButton, R.attr.colorPrimary))
            }
        helpButton.text = ""
        helpButton.contentDescription = getString(R.string.import_text_format_help_content_description)
        helpButton.minWidth = 48.dp
        helpButton.minimumWidth = 48.dp
        helpButton.minHeight = 48.dp
        helpButton.setPadding(12.dp, 0, 12.dp, 0)
        helpButton.setCompoundDrawablesRelativeWithIntrinsicBounds(helpIcon, null, null, null)
        helpButton.setOnClickListener {
            showImportTextFormatHelpDialog()
        }
    }

    private fun showImportTextFormatHelpDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_text_format_help_title)
            .setMessage(R.string.import_text_format_help_message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun readClipboardText(): String {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(requireContext())
            ?.toString()
            .orEmpty()
    }

    private fun startChooseFileIntentForResult() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        val mimeTypes = arrayOf(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword"
        )
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) // 允许多选文件
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true) // 仅显示本地文件
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        val chooser = Intent.createChooser(intent, "选择文件")
        startActivityForResult(
            chooser, REQUEST_CHOOSE_FILE
        )

//        val intent = Intent()
//        intent.type = "*/*"
//        intent.action = Intent.ACTION_GET_CONTENT
//        intent.addCategory(Intent.CATEGORY_OPENABLE);
//        startActivityForResult(
//            Intent.createChooser(intent, "Select File"),
//            REQUEST_CHOOSE_FILE
//        )
    }

    private fun getAdapter() = binding.recyclerView.adapter as QuizLibraryListAdapter

    private val onSelectionListener = object : QuizLibraryListAdapter.SelectionListener {
        override fun onEnterSelection() {
            hideImportMenu(animated = false)
            configureQuizTopBar(
                binding.toolbar,
                "选择项目",
                navigationIconRes = R.drawable.round_close_24,
                onNavigationClick = { viewModel.exitSelectionMode() }
            )
            binding.fabAddQuizLibrary.hide()
            onBackPressedCallback.isEnabled = true
            refreshTopBarMenu()
        }

        override fun onSelectionChanged(selectedItems: HashSet<Int>) {
            binding.toolbar.title = if (selectedItems.isEmpty()) "选择项目" else "已选择 ${selectedItems.size} 项"
        }

        override fun onExitSelection() {
            configureQuizTopBar(binding.toolbar, TITLE, showNavigation = false)
            binding.fabAddQuizLibrary.show()
            onBackPressedCallback.isEnabled = false
            refreshTopBarMenu()
        }
    }

    private val onItemClickListBtnListener = object : QuizLibraryListAdapter.OnButtonClickListener {
        override fun onButtonLongClicked(quizLibrary: QuizLibrary) {
            Toast.makeText(context, "Not yet implemented", Toast.LENGTH_SHORT).show()
        }

        override fun onButtonClicked(quizLibrary: QuizLibrary, btnType: Int) {
            when (btnType) {
                QuizLibraryListAdapter.ROOT_VIEW -> {
                    val bundle = Bundle()
                    bundle.putInt(QuizLibraryFeaturesFragment.LIBRARY_ID, quizLibrary.id)

                    findNavController().navigate(
                        R.id.QuizLibraryFeaturesFragment, bundle
                    )
//                    findNavController().navigate(
//                        R.id.action_QuizLibListFragment_to_QuizListFragment, bundle
//                    )


//                    val intent = Intent(
//                        requireActivity(),
//                        QuizListActivity::class.java
//                    )
//                    intent.putExtra(QuizListActivity.LIBRARY_ID, libId!!)
//                    startActivity(intent)

                }

                QuizLibraryListAdapter.CAMERA_BUTTON -> {
                    if (!requestCameraPermissionIfNeeded()) {
                        return
                    }
                    // 启动摄像头
                    val intent = Intent(
                        requireActivity(), CameraXDetectorActivity::class.java
                    )
                    intent.putExtra(CameraXDetectorActivity.LIBRARY_ID, quizLibrary.id)
                    startActivity(intent)
                }

                QuizLibraryListAdapter.SCREEN_RECORD_BUTTON -> {
                    // 屏幕录制
                    startScreenDetection(quizLibrary.id)
//                        Snackbar.make(
//                            binding.root,
//                            "SCREEN_RECORD_BUTTON",
//                            Snackbar.LENGTH_SHORT
//                        ).setAction("Action", null).show()
                }

                QuizLibraryListAdapter.ACCESSIBILITY_SEARCH_BUTTON -> {
                    startAccessibilityDetection(quizLibrary.id)
                }

                QuizLibraryListAdapter.RENAME_BUTTON -> {
                    RenameDialogFragment(
                        "重新命名",
                        quizLibrary.name,
                        object : RenameDialogFragment.RenameDialogListener {
                            override fun onDialogPositiveClick(newName: String) {
                                viewModel.updateQuizLibrary(quizLibrary.copy(name = newName))
                            }
                        }).show(parentFragmentManager)
                }

                QuizLibraryListAdapter.DELETE_BUTTON -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("删除题库“${quizLibrary.name}”？")
                        .setPositiveButton(R.string.delete) { dialog, which ->
                            viewModel.deleteQuizLibrary(quizLibrary)
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.cancel) { dialog, which ->
                            dialog.dismiss()
                        }
                        .show()

                }
            }
        }
    }

    private fun refreshTopBarMenu() {
        val menuRes = if (viewModel.isSelectionMode.value == true) {
            R.menu.quiz_lib_selection_menu
        } else {
            R.menu.quiz_lib_menu
        }
        refreshQuizTopBarMenu(
            binding.toolbar,
            menuRes,
            onPrepareMenu = { menu ->
                MenuCompat.setGroupDividerEnabled(menu, true)
                menu.findItem(R.id.more)?.subMenu?.let { subMenu ->
                    MenuCompat.setGroupDividerEnabled(subMenu, true)
                }
                prepareTopBarMenu(menu)
            },
            onMenuItemSelected = ::onTopBarMenuItemSelected
        )
    }

    @SuppressLint("RestrictedApi")
    private fun prepareTopBarMenu(menu: Menu) {
        val colorOnSurfaceVariant =
            MaterialColors.getColor(requireView(), R.attr.colorOnSurfaceVariant)
        val colorError =
            MaterialColors.getColor(requireView(), R.attr.colorError)

        val menuList = if (viewModel.isSelectionMode.value == true)
            listOf<MenuItem>(
                menu.findItem(R.id.merge),
                menu.findItem(R.id.delete)
            )
        else listOf<MenuItem>(
            menu.findItem(R.id.sort),
            menu.findItem(R.id.more),
            menu.findItem(R.id.search_settings),
            menu.findItem(R.id.import_settings),
            menu.findItem(R.id.ai_settings),
            menu.findItem(R.id.select),
            menu.findItem(R.id.about),
            menu.findItem(R.id.camera_test),
            menu.findItem(R.id.screen_test)
        )

        menuList.forEach {
            it.iconTintList = when (it.title) {
                resources.getString(R.string.delete) -> ColorStateList.valueOf(colorError)
                else -> ColorStateList.valueOf(colorOnSurfaceVariant)

            }
            it.title = SpannableString(it.title).apply {
                setSpan(
                    ForegroundColorSpan(colorOnSurfaceVariant),
                    0,
                    it.title?.length ?: 0,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun onTopBarMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.sort -> {
                showSortDialog()
            }
            R.id.select -> {
                viewModel.enterSelectionMode()
            }
            R.id.import_settings -> {
                findNavController().navigate(R.id.action_QuizLibListFragment_to_ImportCandidateSettingsFragment)
            }
            R.id.search_settings -> {
                val intent = Intent(requireContext(), SettingsActivity::class.java)
                intent.putExtra(
                    SettingsActivity.EXTRA_LAUNCH_SOURCE,
                    SettingsActivity.LaunchSource.QUIZ_CAMERAX
                )
                startActivity(intent)
            }
            R.id.ai_settings -> {
                val intent = Intent(requireContext(), SettingsActivity::class.java)
                intent.putExtra(
                    SettingsActivity.EXTRA_LAUNCH_SOURCE,
                    SettingsActivity.LaunchSource.AI_SETTINGS
                )
                startActivity(intent)
            }
            R.id.about -> {
                showAboutDialog()
            }
            // 批量 - 合并
            R.id.merge -> {
                val libs = viewModel.getSelectedLibraries()

                if (libs.size > 1) {
                    RenameDialogFragment(
                        "合并 ${libs.size} 个题库",
                        "${libs.first().name} 等${libs.size}个题库",
                        object : RenameDialogFragment.RenameDialogListener {
                            override fun onDialogPositiveClick(newName: String) {
                                // 退出选择状态
                                viewModel.exitSelectionMode()
                                // 合并题库
                                viewModel.mergeQuizLibraries(requireContext(), libs, newName)
                            }
                        }).show(parentFragmentManager)

//                    MaterialAlertDialogBuilder(requireContext()).setTitle("合并 ${libs.size} 个题库？")
//                        .setPositiveButton(R.string.confirm) { dialog, which ->
//                            // 退出选择状态
//                            viewModel.exitSelectionMode()
//                            // 合并题库
//                            viewModel.mergeQuizLibraries(libs, "222")
//                            dialog.dismiss() // 关闭对话框
//                        }.setNegativeButton(R.string.cancel) { dialog, which ->
//                            // 点击“取消”按钮后执行的操作
//                            dialog.dismiss() // 关闭对话框
//                        }.show()
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("请至少选择两个项目以合并")
                        .setPositiveButton(R.string.confirm) { dialog, which -> }
                        .show()
                }

            }
            // 批量 - 删除
            R.id.delete -> {
                val libs = viewModel.getSelectedLibraries()

                if (libs.isNotEmpty()) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle("删除 ${libs.size} 个题库？")
                        .setPositiveButton(R.string.delete) { dialog, which ->
                            // 退出选择状态
                            viewModel.exitSelectionMode()
                            // 删除题目和题库
                            viewModel.deleteQuizLibraries(libs)
                            dialog.dismiss() // 关闭对话框
                        }.setNegativeButton(R.string.cancel) { dialog, which ->
                            // 点击“取消”按钮后执行的操作
                            dialog.dismiss() // 关闭对话框
                        }.show()

                } else {
                    Snackbar.make(
                        binding.root, "未选择项目".uppercase(), Snackbar.LENGTH_SHORT
                    ).show()
                }
            }

            R.id.camera_test -> {
                if (!requestCameraPermissionIfNeeded()) {
                    return true
                }
                // 启动摄像头
                val intent = Intent(
                    requireActivity(), CameraXDetectorActivity::class.java
                )
                intent.putExtra(CameraXDetectorActivity.IS_PROCESSOR_TEST, true)
                startActivity(intent)
            }

            R.id.screen_test -> {
                // 屏幕录制
                ScreenDetectorController.startProcessorTest(requireActivity())
            }
        }
        return true
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about)
            .setMessage(
                getString(
                    R.string.about_message,
                    getString(R.string.app_name),
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )
            )
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun setupPermissionNotice() {
        binding.permissionNoticeCard.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                updateBottomSpacing()
            }
        }
        binding.permissionCameraButton.setOnClickListener {
            requestCameraPermissionOrOpenSettings()
        }
        binding.permissionOverlayButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            )
        }
    }

    private fun refreshPermissionNotice() {
        if (_binding == null) {
            return
        }
        val cameraMissing = !hasCameraPermission()
        val overlayMissing = !hasOverlayPermission()
        val hasMissingPermission = cameraMissing || overlayMissing

        binding.permissionNoticeCard.visibility =
            if (hasMissingPermission) View.VISIBLE else View.GONE
        binding.permissionCameraButton.visibility =
            if (cameraMissing) View.VISIBLE else View.GONE
        binding.permissionCameraButton.setText(
            if (canRequestCameraPermission()) {
                R.string.permission_notice_allow_camera
            } else {
                R.string.permission_notice_open_app_settings
            }
        )
        binding.permissionOverlayButton.visibility =
            if (overlayMissing) View.VISIBLE else View.GONE

        binding.permissionNoticeMessage.setText(
            when {
                cameraMissing && overlayMissing -> R.string.permission_notice_camera_and_overlay
                cameraMissing -> R.string.permission_notice_camera
                else -> R.string.permission_notice_overlay
            }
        )

        binding.permissionNoticeCard.post {
            if (_binding != null) {
                updateBottomSpacing()
            }
        }
    }

    private fun requestCameraPermissionIfNeeded(): Boolean {
        if (hasCameraPermission()) {
            return true
        }
        requestCameraPermissionOrOpenSettings()
        refreshPermissionNotice()
        return false
    }

    private fun requestCameraPermissionOrOpenSettings() {
        if (canRequestCameraPermission()) {
            markCameraPermissionRequested()
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            openAppSettings()
        }
    }

    private fun canRequestCameraPermission(): Boolean {
        return hasCameraPermission() ||
            !hasRequestedCameraPermission() ||
            ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(),
                Manifest.permission.CAMERA
            )
    }

    private fun hasRequestedCameraPermission(): Boolean {
        return requireContext()
            .getSharedPreferences(PermissionManager.PERMISSION_STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PermissionManager.KEY_CAMERA_PERMISSION_REQUESTED, false)
    }

    private fun markCameraPermissionRequested() {
        requireContext()
            .getSharedPreferences(PermissionManager.PERMISSION_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PermissionManager.KEY_CAMERA_PERMISSION_REQUESTED, true)
            .apply()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(requireContext())
    }

    private fun updateBottomSpacing() {
        if (_binding == null) {
            return
        }
        val permissionCardLayoutParams =
            binding.permissionNoticeCard.layoutParams as ViewGroup.MarginLayoutParams
        val permissionCardBottomMargin = basePermissionNoticeBottomMargin + systemBottomInset
        if (permissionCardLayoutParams.bottomMargin != permissionCardBottomMargin) {
            permissionCardLayoutParams.bottomMargin = permissionCardBottomMargin
            binding.permissionNoticeCard.layoutParams = permissionCardLayoutParams
        }

        val permissionNoticeLift =
            if (binding.permissionNoticeCard.visibility == View.VISIBLE) {
                max(binding.permissionNoticeCard.height, 0) + 16.dp
            } else {
                0
            }

        val recyclerPaddingBottom =
            baseRecyclerPaddingBottom + systemBottomInset + permissionNoticeLift
        if (binding.recyclerView.paddingBottom != recyclerPaddingBottom) {
            binding.recyclerView.setPadding(
                binding.recyclerView.paddingLeft,
                binding.recyclerView.paddingTop,
                binding.recyclerView.paddingRight,
                recyclerPaddingBottom
            )
        }

        val fabLayoutParams = binding.fabAddQuizLibrary.layoutParams as ViewGroup.MarginLayoutParams
        val fabBottomMargin = baseFabBottomMargin + systemBottomInset + permissionNoticeLift
        if (fabLayoutParams.bottomMargin != fabBottomMargin) {
            fabLayoutParams.bottomMargin = fabBottomMargin
            binding.fabAddQuizLibrary.layoutParams = fabLayoutParams
        }
    }

    private fun startScreenDetection(libId: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val quizzes = QuizRepositoryImpl(requireContext()).getQuizListByLibraryIdOnce(libId)
            ScreenDetectorController.startQuizDetection(
                requireActivity(),
                libId,
                MutableLiveData(quizzes)
            )
        }
    }

    private fun startAccessibilityDetection(libId: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val quizzes = QuizRepositoryImpl(requireContext()).getQuizListByLibraryIdOnce(libId)
            ScreenDetectorController.startAccessibilityQuizDetection(
                requireActivity(),
                libId,
                MutableLiveData(quizzes)
            )
        }
    }

    private fun getSortPrefs(): android.content.SharedPreferences {
        return requireContext().getSharedPreferences(SORT_PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getSortBy(): String {
        return getSortPrefs().getString(SORT_BY_KEY, SORT_BY_DEFAULT) ?: SORT_BY_DEFAULT
    }

    private fun isAscending(): Boolean {
        return getSortPrefs().getBoolean(SORT_ASC_KEY, true)
    }

    private fun sortList(list: List<QuizLibrary>): List<QuizLibrary> {
        val ascending = isAscending()
        return when (getSortBy()) {
            SORT_BY_NAME -> if (ascending) {
                list.sortedWith(compareBy(nullsLast()) { it.name.lowercase(Locale.getDefault()) })
            } else {
                list.sortedWith(compareByDescending(nullsLast()) { it.name.lowercase(Locale.getDefault()) })
            }
            SORT_BY_COUNT -> if (ascending) {
                list.sortedBy { it.quizCount }
            } else {
                list.sortedByDescending { it.quizCount }
            }
            else -> if (ascending) {
                list.sortedBy { it.id }
            } else {
                list.sortedByDescending { it.id }
            }
        }
    }

    private fun showSortDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_sort, null)
        val sortByGroup = dialogView.findViewById<RadioGroup>(R.id.sort_by_group)
        val dirAscRow = dialogView.findViewById<View>(R.id.sort_dir_asc)
        val dirDescRow = dialogView.findViewById<View>(R.id.sort_dir_desc)
        val dirAscCheck = dialogView.findViewById<View>(R.id.sort_dir_asc_check)
        val dirDescCheck = dialogView.findViewById<View>(R.id.sort_dir_desc_check)

        // Set current sort-by selection
        when (getSortBy()) {
            SORT_BY_NAME -> sortByGroup.check(R.id.sort_by_name)
            SORT_BY_COUNT -> sortByGroup.check(R.id.sort_by_count)
            else -> sortByGroup.check(R.id.sort_by_default)
        }

        // Set current direction selection
        fun refreshDirectionIcons(ascending: Boolean) {
            dirAscCheck.visibility = if (ascending) View.VISIBLE else View.INVISIBLE
            dirDescCheck.visibility = if (ascending) View.INVISIBLE else View.VISIBLE
        }
        refreshDirectionIcons(isAscending())

        sortByGroup.setOnCheckedChangeListener { _, checkedId ->
            val sortBy = when (checkedId) {
                R.id.sort_by_name -> SORT_BY_NAME
                R.id.sort_by_count -> SORT_BY_COUNT
                else -> SORT_BY_DEFAULT
            }
            getSortPrefs().edit().putString(SORT_BY_KEY, sortBy).apply()
            applySort()
        }

        dirAscRow.setOnClickListener {
            getSortPrefs().edit().putBoolean(SORT_ASC_KEY, true).apply()
            refreshDirectionIcons(true)
            applySort()
        }
        dirDescRow.setOnClickListener {
            getSortPrefs().edit().putBoolean(SORT_ASC_KEY, false).apply()
            refreshDirectionIcons(false)
            applySort()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sort)
            .setView(dialogView)
            .show()
    }

    private fun applySort() {
        viewModel.setSortConfig(getSortBy(), isAscending())
    }

    companion object {
        private const val TAG = "QuizLibraryListFragment"
        private const val REQUEST_CHOOSE_FILE = 10001
        private const val TITLE = "全部题库"
        private const val SORT_PREF_NAME = "quiz_library_sort"
        private const val SORT_BY_KEY = "sort_by"
        private const val SORT_ASC_KEY = "sort_ascending"
        private const val SORT_BY_DEFAULT = "default"
        private const val SORT_BY_NAME = "name"
        private const val SORT_BY_COUNT = "count"
    }
}


//    // TODO: Rename and change types of parameters
//    private var param1: String? = null
//    private var param2: String? = null
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        arguments?.let {
//            param1 = it.getString(ARG_PARAM1)
//            param2 = it.getString(ARG_PARAM2)
//        }
//    }
//
//    override fun onCreateView(
//        inflater: LayoutInflater, container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View? {
//        // Inflate the layout for this fragment
//        return inflater.inflate(R.layout.fragment_quiz_library_list2, container, false)
//    }
//
//    companion object {
//        /**
//         * Use this factory method to create a new instance of
//         * this fragment using the provided parameters.
//         *
//         * @param param1 Parameter 1.
//         * @param param2 Parameter 2.
//         * @return A new instance of fragment QuizLibraryListFragment.
//         */
//        // TODO: Rename and change types and number of parameters
//        @JvmStatic
//        fun newInstance(param1: String, param2: String) =
//            QuizLibraryListFragment().apply {
//                arguments = Bundle().apply {
//                    putString(ARG_PARAM1, param1)
//                    putString(ARG_PARAM2, param2)
//                }
//            }
//    }
//}
