package com.virin.visionquiz.quizentry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.QuizManager
import com.virin.visionquiz.databinding.ActivityQuizEntryBinding
import com.virin.visionquiz.quizstudy.QuizRunnerFragment
import com.virin.visionquiz.util.BaseQuizActivity
import com.virin.visionquiz.util.NavigationBackAnimationSource


class QuizEntryActivity : BaseQuizActivity() {

    companion object {
        private const val TAG = "QuizEntryActivity"
        private const val HANDLED_IMPORT_PREFS = "handled_external_imports"
        private const val KEY_HANDLED_IMPORTS = "handled_import_intents"
        private const val IMPORT_DEDUP_WINDOW_MS = 2 * 60 * 1000L

        private val SUPPORTED_EXCEL_EXTENSIONS = setOf(
            "xls",
            "xlsx",
            "xlsm",
            "xltx",
            "xltm",
            "xlam",
            "docx",
            "doc"
        )
    }

    private lateinit var binding: ActivityQuizEntryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

//        val defaultColor = resources.getColor(android.R.color.black, theme)
//        window.navigationBarColor = MaterialColors.getColor(this, R.attr.colorSurface, defaultColor)
//        BarsUtil.transparentNavBar(this)
//        window.navigationBarColor = Color.argb(0,255,0,0,)
//        window.setFlags(
//            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
//            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
//        )


        binding = ActivityQuizEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 从分享菜单进入时
        if (savedInstanceState == null) {
            handleExternalIntent(intent)
        }

//        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
//            // 获取状态栏和导航栏的WindowInsets
//            val systemBarsInsets =
//                insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            // 获取输入法窗口的WindowInsets
//            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
//            // 获取系统手势区域的WindowInsets
//            val mandatorySystemGesturesInsets =
//                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
//
//            val typedValue = TypedValue()
//            theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
//            val actionBarHeight = resources.getDimensionPixelSize(typedValue.resourceId)
//
//            binding.toolbar.apply {
//                setPadding(0, systemBarsInsets.top, 0, 0)
////                layoutParams = layoutParams.also {
////                    it.height = LayoutParams.WRAP_CONTENT//systemBarsInsets.top + actionBarHeight
////                }
//            }
//
//            insets
//        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val runnerFragment = currentQuizRunnerFragment()
        if (runnerFragment?.requestExitConfirmation(fromNavigationButton = true) == true) {
            return true
        }
        val navController = findNavController(R.id.nav_host_fragment_content_quiz_entry)
        NavigationBackAnimationSource.markNextPopFromNavigationButton()
        return navController.popBackStack() || super.onSupportNavigateUp()
    }

    private fun currentQuizRunnerFragment(): QuizRunnerFragment? {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_quiz_entry) as? NavHostFragment
            ?: return null
        return navHost.childFragmentManager.primaryNavigationFragment as? QuizRunnerFragment
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(receivedIntent: Intent?) {
        if (receivedIntent == null) return

        val action = receivedIntent.action
        if (action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) {
            return
        }

        if (receivedIntent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            consumeHandledIntent()
            return
        }

        val fileUris = extractExcelUris(receivedIntent)
        if (fileUris.isEmpty()) {
            Toast.makeText(this, "未识别到可导入的 Excel 文件", Toast.LENGTH_SHORT).show()
            consumeHandledIntent()
            return
        }

        val importKey = buildExternalImportKey(action, fileUris)
        if (isRecentlyHandledExternalImport(importKey)) {
            Log.d(TAG, "跳过重复的外部导入 Intent: $fileUris")
            consumeHandledIntent()
            return
        }
        markExternalImportHandled(importKey)

        fileUris.forEach { grantTemporaryReadPermission(receivedIntent, it) }

        if (fileUris.size == 1) {
            QuizManager.importExcel(this, fileUris.first())
        } else {
            QuizManager.importExcels(this, ArrayList(fileUris))
        }
        consumeHandledIntent()
    }

    private fun buildExternalImportKey(action: String?, fileUris: List<Uri>): String {
        val normalizedUris = fileUris
            .map { it.normalizeScheme().toString() }
            .sorted()
            .joinToString(separator = "||")
        return "${action.orEmpty()}::$normalizedUris"
    }

    private fun isRecentlyHandledExternalImport(importKey: String): Boolean {
        val now = System.currentTimeMillis()
        val activeEntries = activeHandledExternalImports(now)
        return activeEntries.any { it.second == importKey }
    }

    private fun markExternalImportHandled(importKey: String) {
        val now = System.currentTimeMillis()
        val activeEntries = activeHandledExternalImports(now)
            .takeLast(20)
            .mapTo(mutableSetOf()) { "${it.first}|${it.second}" }
        activeEntries += "$now|$importKey"
        handledExternalImportPrefs()
            .edit()
            .putStringSet(KEY_HANDLED_IMPORTS, activeEntries)
            .apply()
    }

    private fun activeHandledExternalImports(now: Long): List<Pair<Long, String>> {
        val prefs = handledExternalImportPrefs()
        val entries = prefs.getStringSet(KEY_HANDLED_IMPORTS, emptySet()).orEmpty()
        val active = entries.mapNotNull { entry ->
            val separatorIndex = entry.indexOf('|')
            if (separatorIndex <= 0) {
                return@mapNotNull null
            }
            val timestamp = entry.substring(0, separatorIndex).toLongOrNull()
                ?: return@mapNotNull null
            val key = entry.substring(separatorIndex + 1)
            if (now - timestamp <= IMPORT_DEDUP_WINDOW_MS) {
                timestamp to key
            } else {
                null
            }
        }.sortedBy { it.first }

        if (active.size != entries.size) {
            prefs.edit()
                .putStringSet(
                    KEY_HANDLED_IMPORTS,
                    active.mapTo(mutableSetOf()) { "${it.first}|${it.second}" }
                )
                .apply()
        }

        return active
    }

    private fun handledExternalImportPrefs() =
        getSharedPreferences(HANDLED_IMPORT_PREFS, Context.MODE_PRIVATE)

    private fun extractExcelUris(receivedIntent: Intent): List<Uri> {
        val candidates = linkedSetOf<Uri>()

        when (receivedIntent.action) {
            Intent.ACTION_SEND -> {
                extractParcelableStream(receivedIntent)?.let(candidates::add)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                candidates += extractParcelableStreams(receivedIntent)
            }

            Intent.ACTION_VIEW -> {
                receivedIntent.data?.let(candidates::add)
            }
        }

        val clipData = receivedIntent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(candidates::add)
            }
        }

        return candidates.filter(::isSupportedExcelUri)
    }

    private fun extractParcelableStream(receivedIntent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            receivedIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            receivedIntent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractParcelableStreams(receivedIntent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            receivedIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            receivedIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }

    private fun isSupportedExcelUri(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme != "content" && scheme != "file") {
            return false
        }

        val mimeType = contentResolver.getType(uri).orEmpty().lowercase()
        if (mimeType in SUPPORTED_MIME_TYPES) {
            return true
        }

        val fileName = queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXCEL_EXTENSIONS
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") {
            return null
        }
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun grantTemporaryReadPermission(receivedIntent: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        val flags = receivedIntent.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        runCatching {
            if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun consumeHandledIntent() {
        setIntent(
            Intent(Intent.ACTION_MAIN).apply {
                setClass(this@QuizEntryActivity, QuizEntryActivity::class.java)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
    }

    private val SUPPORTED_MIME_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel.sheet.macroenabled.12",
        "application/vnd.ms-excel",
        "application/vnd.ms-excel.addin.macroenabled.12",
        "application/vnd.ms-excel.template.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "application/x-msexcel",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword"
    )
}
