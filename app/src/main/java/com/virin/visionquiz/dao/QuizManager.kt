package com.virin.visionquiz.dao

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import com.virin.visionquiz.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
import java.util.PriorityQueue

object QuizManager {
    const val TAG = "QuizManager"
    const val DEFAULT_MIN_MATCH_SCORE = 0.76
    const val SEARCH_MIN_MATCH_SCORE = 0.6
    private const val DEFAULT_MAX_MATCH_RESULTS = 5
    private const val MIN_LENGTH_RATIO = 0.45
    private const val MIN_SIGNATURE_OVERLAP = 0.35
    private const val CONTAINMENT_BOOST = 0.08
    private const val MIN_CONTAINMENT_LENGTH = 6

    private data class CachedQuizPrompt(
        val rawPrompt: String,
        val normalizedPrompt: String,
        val signature: Set<Char>
    )

    class QuizMatchCandidate internal constructor(
        val question: Quiz,
        val normalizedPrompt: String,
        val signature: Set<Char>
    )

    class QuizMatchIndex internal constructor(
        val candidates: List<QuizMatchCandidate>
    ) {
        val isEmpty: Boolean
            get() = candidates.isEmpty()
    }

    private enum class ImportTemplate(val displayName: String) {
        GENERIC("通用表头"),
        LEGACY("旧格式"),
        TEXT("纯文本")
    }

    private enum class ImportField {
        PROMPT,
        TYPE,
        ANSWER,
        OPTION,
        ANALYSIS
    }

    private data class ImportHeaderMapping(
        val promptIndex: Int,
        val typeIndex: Int? = null,
        val answerIndex: Int? = null,
        val analysisIndex: Int? = null,
        val optionIndexes: List<Int> = emptyList()
    )

    private data class ImportSheetMatch(
        val template: ImportTemplate,
        val sheet: Sheet,
        val headerRowIndex: Int,
        val mapping: ImportHeaderMapping? = null,
        val score: Int = 0,
        val sourceName: String = template.displayName
    )

    private data class ImportedQuizDraft(
        val prompt: String,
        val options: List<String>,
        val answer: Set<Int>,
        val isMultipleChoice: Boolean,
        val questionType: QuizUiType
    )

    private data class ImportWarning(
        val rowIndex: Int,
        val reason: String,
        val prompt: String
    )

    private data class ParsedExcelResult(
        val template: ImportTemplate,
        val drafts: List<ImportedQuizDraft>,
        val warnings: List<ImportWarning>,
        val headerRowNumber: Int? = null,
        val sourceName: String = template.displayName
    )

    private data class ImportRowParseResult(
        val draft: ImportedQuizDraft? = null,
        val reason: String? = null
    )

    private data class ImportFileResult(
        val fileName: String,
        val template: ImportTemplate,
        val headerRowNumber: Int?,
        val sourceName: String,
        val importedCount: Int,
        val skippedWarnings: List<ImportWarning>
    ) {
        val skippedCount: Int
            get() = skippedWarnings.size
    }

    private val quizPromptCache = ConcurrentHashMap<Int, CachedQuizPrompt>()

//    private lateinit var repository: QuizRepository

//    private var categories: List<QuizLibrary>? = null
//    private var questionsDict: MutableMap<Int, List<Quiz>> = LinkedHashMap()

    private suspend fun generateUniqueCategoryName(dao: QuizLibraryDao, name: String): String {
        var newName = name
        var count = 1
        while (dao.getCategoryCountByName(newName) > 0) {
            newName = "$name (${count++})"
        }
        return newName
    }

    fun importExcels(context: Activity, fileUriList: ArrayList<Uri>) {
//        Toast.makeText(context, "正在导入 ${fileUriList.size} 项", Toast.LENGTH_SHORT).show()
        val progressDialog = showProgressAlertDialog(context, "正在导入 ${fileUriList.size} 个项目")

        MainScope().launch {
            var failureCount = 0
            val importResults = mutableListOf<ImportFileResult>()
            val failedFiles = mutableListOf<String>()

            for (fileUri in fileUriList) {
                // 处理每个文件的逻辑，例如读取文件内容或执行其他操作
                // 在这里可以使用文件的 URI 来访问文件数据

                Log.d(
                    TAG, "开始导入 $fileUri"
                )
                try {
                    importResults += handleExcel(context, fileUri)
                } catch (e: Exception) {
                    Log.e(TAG, "导入失败: $fileUri", e)
                    failureCount++
                    val fileName = getFileNameFromContentUri(context, fileUri).ifBlank { fileUri.lastPathSegment ?: "未知文件" }
                    failedFiles += "$fileName：${formatImportError(e)}"
                }
                Log.d(
                    TAG, "完成导入 $fileUri"
                )
            }

            delay(114) // 不然太快

            progressDialog.hide()
            val msg = "导入完成：成功 ${fileUriList.size - failureCount} 个，失败 $failureCount 个"
            val totalImportedCount = importResults.sumOf { it.importedCount }
            val totalSkippedCount = importResults.sumOf { it.skippedCount }
            MaterialAlertDialogBuilder(context)
                .setTitle(msg)
                .setMessage(
                    buildImportSummaryMessage(
                        importResults = importResults,
                        failedFiles = failedFiles,
                        totalImportedCount = totalImportedCount,
                        totalSkippedCount = totalSkippedCount
                    )
                )
                .setPositiveButton(R.string.confirm) { dialog, which -> }
                .show()

//            Toast.makeText(
//                context, "成功导入 ${fileUriList.size - failureCount} 项，失败 $failureCount 项。", Toast.LENGTH_SHORT
//            ).show()
        }
    }

    fun importExcel(context: Activity, fileUri: Uri) {
        importExcels(context, arrayListOf(fileUri))
    }

    fun importPlainText(context: Activity, rawText: String, libraryName: String = "文本导入") {
        val progressDialog = showProgressAlertDialog(context, "正在导入文本题库")

        MainScope().launch {
            var result: ImportFileResult? = null
            var failedMessage: String? = null

            try {
                result = handlePlainText(context, rawText, libraryName)
            } catch (e: Exception) {
                Log.e(TAG, "文本导入失败: $libraryName", e)
                failedMessage = "$libraryName：${formatImportError(e)}"
            }

            delay(114)
            progressDialog.hide()
            val successCount = if (result != null) 1 else 0
            val failureCount = if (result == null) 1 else 0
            MaterialAlertDialogBuilder(context)
                .setTitle("导入完成：成功 $successCount 个，失败 $failureCount 个")
                .setMessage(
                    buildImportSummaryMessage(
                        importResults = listOfNotNull(result),
                        failedFiles = listOfNotNull(failedMessage),
                        totalImportedCount = result?.importedCount ?: 0,
                        totalSkippedCount = result?.skippedCount ?: 0
                    )
                )
                .setPositiveButton(R.string.confirm) { _, _ -> }
                .show()
        }
    }

    private suspend fun handlePlainText(
        context: Activity,
        rawText: String,
        libraryName: String
    ): ImportFileResult {
        return withContext(Dispatchers.IO) {
            val parsedText = parsePlainText(rawText)
            if (parsedText.drafts.isEmpty()) {
                throw ImportException("未找到可导入的题目，或题型暂不支持")
            }
            persistParsedQuizzes(context, libraryName, parsedText)
        }
    }

    private suspend fun handleExcel(context: Activity, fileUri: Uri): ImportFileResult {
        return withContext(Dispatchers.IO) {
            val fileName = getFileNameFromContentUri(context, fileUri)
            val mimeType = context.contentResolver.getType(fileUri).orEmpty().lowercase()
            val isWord = mimeType.contains("wordprocessingml") || mimeType == "application/msword"
            if (isWord) {
                val rawText = extractWordText(context, fileUri)
                if (rawText.isBlank()) {
                    throw ImportException("Word 文档内容为空")
                }
                val parsed = parsePlainText(rawText)
                if (parsed.drafts.isEmpty()) {
                    throw ImportException("未找到可导入的题目，或题型暂不支持")
                }
                persistParsedQuizzes(context, fileName, parsed)
            } else {
                val parsedExcel = parseExcel(context, fileUri)
                if (parsedExcel.drafts.isEmpty()) {
                    throw ImportException("未找到可导入的题目，或题型暂不支持")
                }
                persistParsedQuizzes(context, fileName, parsedExcel)
            }
        }
    }

    private fun extractWordText(context: Context, fileUri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw ImportException("无法打开文件")
        inputStream.use { stream ->
            val doc = XWPFDocument(stream)
            val sb = StringBuilder()
            for (element in doc.bodyElements) {
                if (element is org.apache.poi.xwpf.usermodel.XWPFParagraph) {
                    val text = element.text
                    if (text.isNotBlank()) {
                        sb.appendLine(text)
                    }
                } else if (element is org.apache.poi.xwpf.usermodel.XWPFTable) {
                    for (row in element.rows) {
                        val cellTexts = row.tableCells.map { cell -> cell.text.trim() }
                        if (cellTexts.any { t -> t.isNotBlank() }) {
                            sb.appendLine(cellTexts.joinToString("	"))
                        }
                    }
                }
            }
            doc.close()
            return sb.toString()
        }
    }

    private suspend fun persistParsedQuizzes(
        context: Context,
        fileName: String,
        parsedExcel: ParsedExcelResult
    ): ImportFileResult {
        return withContext(Dispatchers.IO) {
            val db = QuizDatabase.getInstance(context)
            val persistedWarnings = parsedExcel.warnings.toMutableList()
            var insertedCount = 0

            db.withTransaction {
                val categoryDao = db.categoryDao()
                val notDuplicateFileName = generateUniqueCategoryName(categoryDao, fileName)
                val category = QuizLibrary(0, notDuplicateFileName, 0)
                categoryDao.insertCategory(category)

                val currCategory = categoryDao.getQuizLibraryByName(notDuplicateFileName)
                val questionDao = db.questionDao()

                val questionsList = parsedExcel.drafts.mapIndexedNotNull { index, draft ->
                    try {
                        Quiz(
                            0,
                            draft.prompt,
                            draft.options,
                            draft.answer,
                            draft.isMultipleChoice,
                            draft.questionType.label,
                            currCategory.id
                        )
                    } catch (e: IllegalArgumentException) {
                        persistedWarnings += ImportWarning(
                            rowIndex = -1,
                            reason = "题目数据无效",
                            prompt = draft.prompt
                        )
                        Log.e(
                            TAG,
                            "存储错误: ${e.message}. Quiz: prompt '${draft.prompt}', options '${draft.options}', answer '${draft.answer}'."
                        )
                        null
                    }
                }

                questionDao.insertQuizzes(questionsList)
                insertedCount = questionsList.size

                val quizCount = questionDao.getQuizCountByLibraryId(currCategory.id)
                categoryDao.setQuizCount(currCategory.id, quizCount)
            }

            ImportFileResult(
                fileName = fileName,
                template = parsedExcel.template,
                headerRowNumber = parsedExcel.headerRowNumber,
                sourceName = parsedExcel.sourceName,
                importedCount = insertedCount,
                skippedWarnings = persistedWarnings
            )
        }
    }

    fun getFileNameFromContentUri(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val fileName = cursor.getString(columnIndex)
            cursor.close()
            return QuizExportUtil.getNameWithoutExtension(fileName)
        }
        return ""
    }

    /* 匹配题库
     * 返回符合阈值的高分结果
     */
    fun matchQuiz(
        input: String,
        questions: List<Quiz>,
        minScore: Double = DEFAULT_MIN_MATCH_SCORE,
        maxResults: Int = DEFAULT_MAX_MATCH_RESULTS
    ): List<Pair<Quiz, Double>> {
        return matchQuiz(input, buildMatchIndex(questions), minScore, maxResults)
    }

    fun buildMatchIndex(questions: List<Quiz>): QuizMatchIndex {
        return QuizMatchIndex(
            questions.map { question ->
                val cachedPrompt = getCachedPrompt(question)
                QuizMatchCandidate(
                    question = question,
                    normalizedPrompt = cachedPrompt.normalizedPrompt,
                    signature = cachedPrompt.signature
                )
            }
        )
    }

    fun matchQuiz(
        input: String,
        index: QuizMatchIndex,
        minScore: Double = DEFAULT_MIN_MATCH_SCORE,
        maxResults: Int = DEFAULT_MAX_MATCH_RESULTS
    ): List<Pair<Quiz, Double>> {
        if (index.isEmpty) return emptyList()
        if (maxResults <= 0) return emptyList()

        val normalizedInput = normalizeQuestionText(input)
        if (normalizedInput.isBlank()) return emptyList()

        val inputSignature = buildSignature(normalizedInput)
        if (inputSignature.isEmpty()) return emptyList()

        if (maxResults == Int.MAX_VALUE) {
            return index.candidates.asSequence().mapNotNull { candidate ->
                if (!isCandidateMatch(normalizedInput, inputSignature, candidate)) {
                    return@mapNotNull null
                }

                val score = computeMatchScore(normalizedInput, inputSignature, candidate)
                if (score < minScore) {
                    return@mapNotNull null
                }

                candidate.question to score
            }.sortedByDescending { it.second }.toList()
        }

        val topMatches = PriorityQueue<Pair<Quiz, Double>>(compareBy { it.second })
        for (candidate in index.candidates) {
            if (!isCandidateMatch(normalizedInput, inputSignature, candidate)) {
                continue
            }

            val score = computeMatchScore(normalizedInput, inputSignature, candidate)
            if (score < minScore) {
                continue
            }

            val match = candidate.question to score
            if (topMatches.size < maxResults) {
                topMatches.offer(match)
            } else if (score > (topMatches.peek()?.second ?: Double.NEGATIVE_INFINITY)) {
                topMatches.poll()
                topMatches.offer(match)
            }
        }

        return topMatches.toList().sortedByDescending { it.second }
    }

    private fun getCachedPrompt(question: Quiz): CachedQuizPrompt {
        val cached = quizPromptCache[question.id]
        if (cached != null && cached.rawPrompt == question.prompt) {
            return cached
        }

        val normalizedPrompt = normalizeQuestionText(question.prompt)
        val refreshed = CachedQuizPrompt(
            rawPrompt = question.prompt,
            normalizedPrompt = normalizedPrompt,
            signature = buildSignature(normalizedPrompt)
        )
        quizPromptCache[question.id] = refreshed
        return refreshed
    }

    private fun isCandidateMatch(
        normalizedInput: String,
        inputSignature: Set<Char>,
        cachedPrompt: CachedQuizPrompt
    ): Boolean {
        return isCandidateMatch(
            normalizedInput,
            inputSignature,
            cachedPrompt.normalizedPrompt,
            cachedPrompt.signature
        )
    }

    private fun isCandidateMatch(
        normalizedInput: String,
        inputSignature: Set<Char>,
        candidate: QuizMatchCandidate
    ): Boolean {
        return isCandidateMatch(
            normalizedInput,
            inputSignature,
            candidate.normalizedPrompt,
            candidate.signature
        )
    }

    private fun isCandidateMatch(
        normalizedInput: String,
        inputSignature: Set<Char>,
        normalizedPrompt: String,
        signature: Set<Char>
    ): Boolean {
        if (normalizedPrompt.isBlank() || signature.isEmpty()) {
            return false
        }

        if (containsQuestionText(normalizedInput, normalizedPrompt)) {
            return true
        }

        val lengthRatio =
            min(normalizedInput.length, normalizedPrompt.length).toDouble() /
                maxOf(normalizedInput.length, normalizedPrompt.length)
        if (lengthRatio < MIN_LENGTH_RATIO) {
            return false
        }

        return computeSignatureOverlap(inputSignature, signature) >= MIN_SIGNATURE_OVERLAP
    }

    private fun computeMatchScore(
        normalizedInput: String,
        inputSignature: Set<Char>,
        cachedPrompt: CachedQuizPrompt
    ): Double {
        return computeMatchScore(
            normalizedInput,
            inputSignature,
            cachedPrompt.normalizedPrompt,
            cachedPrompt.signature
        )
    }

    private fun computeMatchScore(
        normalizedInput: String,
        inputSignature: Set<Char>,
        candidate: QuizMatchCandidate
    ): Double {
        return computeMatchScore(
            normalizedInput,
            inputSignature,
            candidate.normalizedPrompt,
            candidate.signature
        )
    }

    private fun computeMatchScore(
        normalizedInput: String,
        inputSignature: Set<Char>,
        normalizedPrompt: String,
        signature: Set<Char>
    ): Double {
        val baseScore = JaroWinklerDistance.computeJaroWinklerDistance(
            normalizedInput, normalizedPrompt
        )
        val overlapScore = computeSignatureOverlap(inputSignature, signature)
        val containmentBoost = if (containsQuestionText(normalizedInput, normalizedPrompt)) {
            CONTAINMENT_BOOST
        } else {
            0.0
        }

        return minOf(1.0, baseScore * 0.85 + overlapScore * 0.15 + containmentBoost)
    }

    private fun containsQuestionText(left: String, right: String): Boolean {
        val minLength = min(left.length, right.length)
        if (minLength < MIN_CONTAINMENT_LENGTH) {
            return false
        }
        return left.contains(right) || right.contains(left)
    }

    private fun computeSignatureOverlap(left: Set<Char>, right: Set<Char>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersectionSize = left.count { it in right }
        return intersectionSize.toDouble() / min(left.size, right.size)
    }

    private fun buildSignature(text: String): Set<Char> {
        return text.filter(::isMeaningfulQuestionChar).toSet()
    }

    fun normalizeQuestionText(text: String): String {
        return normalizeMatchText(text, stripQuestionPrefix = true)
    }

    fun normalizeAnswerText(text: String): String {
        return normalizeMatchText(text, stripQuestionPrefix = false)
    }

    private fun normalizeMatchText(text: String, stripQuestionPrefix: Boolean): String {
        val halfWidth = text.trim().map(::normalizeHalfWidthChar).joinToString("")
        val normalizedSource = if (stripQuestionPrefix) {
            QUESTION_PREFIX_REGEX.replace(halfWidth, "")
        } else {
            halfWidth
        }
        return buildString(normalizedSource.length) {
            normalizedSource.lowercase().forEach { ch ->
                if (isMeaningfulQuestionChar(ch)) {
                    append(ch)
                }
            }
        }
    }

    private fun normalizeHalfWidthChar(ch: Char): Char {
        return when (ch.code) {
            12288 -> ' '
            in 65281..65374 -> (ch.code - 65248).toChar()
            else -> ch
        }
    }

    private fun isMeaningfulQuestionChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || isCjkCharacter(ch)
    }

    private fun isCjkCharacter(ch: Char): Boolean {
        return ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'
    }

    private val QUESTION_PREFIX_REGEX = Regex(
        pattern = """^\s*(第?[0-9一二三四五六七八九十]{1,3}[题章节]?\s*[-.．、:：)）]?\s*)+"""
    )

//    fun matchQuiz(input: String, libraryId: Int): List<Pair<Quiz, Double>>? {
//        val questions = questionsDict[libraryId]
//        if (!questions.isNullOrEmpty()) {
//            val matched = questions.map { ques ->
//                val dist = JaroWinklerDistance.computeJaroWinklerDistance(input, ques.prompt)
//                Pair(ques, dist)
//            }.filter { it.second > 0.6 }.sortedByDescending { it.second }
//
//            return matched
//        }
//
//        return null
//    }

    private fun parsePlainText(rawText: String): ParsedExcelResult {
        val lines = rawText.replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .map { it.trim() }

        val blocks = mutableListOf<TextQuizBlock>()
        var currentStartLine = -1
        val currentLines = mutableListOf<String>()

        fun flushBlock() {
            if (currentLines.isNotEmpty()) {
                blocks += TextQuizBlock(currentStartLine, currentLines.toList())
                currentLines.clear()
            }
        }

        lines.forEachIndexed { index, line ->
            val questionMatch = TEXT_QUESTION_START_REGEX.matchEntire(line)
            if (questionMatch != null) {
                flushBlock()
                currentStartLine = index + 1
                val firstLine = questionMatch.groupValues[1].trim()
                if (firstLine.isNotBlank()) {
                    currentLines += firstLine
                }
            } else if (currentLines.isNotEmpty() && line.isNotBlank()) {
                currentLines += line
            }
        }
        flushBlock()

        val imported = mutableListOf<ImportedQuizDraft>()
        val warnings = mutableListOf<ImportWarning>()
        val settings = ImportCandidateConfig()
        blocks.forEach { block ->
            val parsed = parseTextQuizBlock(block, settings)
            parsed.draft?.let(imported::add)
            parsed.reason?.let { reason ->
                warnings += ImportWarning(block.startLine, reason, block.preview)
            }
        }

        return ParsedExcelResult(
            template = ImportTemplate.TEXT,
            drafts = imported,
            warnings = warnings,
            sourceName = "题号 + 选项 + 答案"
        )
    }

    private fun parseTextQuizBlock(
        block: TextQuizBlock,
        settings: ImportCandidateConfig
    ): ImportRowParseResult {
        val promptLines = mutableListOf<String>()
        val options = MutableList(8) { "" }
        val analysisLines = mutableListOf<String>()
        var currentOptionIndex: Int? = null
        var answerCell = ""
        var answerSeen = false

        block.lines.flatMap(::splitInlineOptionLine).forEach { line ->
            val answerMatch = TEXT_ANSWER_REGEX.matchEntire(line)
            if (answerMatch != null) {
                answerCell = answerMatch.groupValues[1].trim()
                answerMatch.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }?.let(analysisLines::add)
                answerSeen = true
                currentOptionIndex = null
                return@forEach
            }

            if (answerSeen) {
                analysisLines += line
                return@forEach
            }

            val optionMatch = TEXT_OPTION_REGEX.matchEntire(line)
            if (optionMatch != null) {
                val optionIndex = optionLetterToIndex(optionMatch.groupValues[1].first())
                if (optionIndex != null && optionIndex in options.indices) {
                    options[optionIndex] = appendText(options[optionIndex], optionMatch.groupValues[2].trim())
                    currentOptionIndex = optionIndex
                    return@forEach
                }
            }

            val optionIndex = currentOptionIndex
            if (optionIndex != null) {
                options[optionIndex] = appendText(options[optionIndex], line)
            } else {
                promptLines += line
            }
        }

        val prompt = promptLines.joinToString("\n").trim()
        val lastOptionIndex = options.indexOfLast { it.isNotBlank() }
        val optionCells = if (lastOptionIndex >= 0) {
            options.take(lastOptionIndex + 1)
        } else {
            emptyList()
        }
        if (prompt.isBlank()) {
            return ImportRowParseResult(reason = "题干为空")
        }
        if (answerCell.isBlank()) {
            return ImportRowParseResult(reason = "未识别到答案")
        }

        return parseImportedQuiz("", prompt, optionCells, answerCell, settings)
    }

    private fun splitInlineOptionLine(line: String): List<String> {
        val trimmed = line.trim()
        val markers = TEXT_INLINE_OPTION_MARKER_REGEX.findAll(trimmed).toList()
        if (markers.size < 2 || markers.first().range.first != 0) {
            return listOf(line)
        }

        val optionIndexes = markers.mapNotNull { match ->
            optionLetterToIndex(match.groupValues[1].first())
        }
        val isConsecutive = optionIndexes.size == markers.size &&
            optionIndexes.zipWithNext().all { (current, next) -> next == current + 1 }
        if (!isConsecutive) {
            return listOf(line)
        }

        val splitLines = markers.mapIndexed { index, marker ->
            val start = marker.range.first
            val end = markers.getOrNull(index + 1)?.range?.first ?: trimmed.length
            trimmed.substring(start, end).trim()
        }.filter { it.isNotBlank() }

        return if (splitLines.size == markers.size) splitLines else listOf(line)
    }

    private data class TextQuizBlock(
        val startLine: Int,
        val lines: List<String>
    ) {
        val preview: String
            get() = lines.firstOrNull().orEmpty()
    }

    private fun appendText(original: String, extra: String): String {
        if (extra.isBlank()) return original
        return if (original.isBlank()) extra else "$original\n$extra"
    }

    private fun optionLetterToIndex(raw: Char): Int? {
        val normalized = normalizeAnswerLetter(raw)
        return convertLetterToNumber(normalized)?.takeIf { it in 0..7 }
    }

    private val TEXT_QUESTION_START_REGEX = Regex(
        pattern = """^\s*(?:第\s*\d{1,4}\s*题\s*|第?\s*\d{1,4}\s*[.。．、:：)）]|[（(]\s*\d{1,4}\s*[)）.。．、:：]|[一二三四五六七八九十百千]{1,6}\s*[、.．])\s*(.*)$"""
    )

    private val TEXT_OPTION_REGEX = Regex(
        pattern = """^\s*[（(]?\s*([A-Ha-hＡ-Ｈａ-ｈ])\s*[)）.。．、:：]\s*(.+)$"""
    )

    private val TEXT_INLINE_OPTION_MARKER_REGEX = Regex(
        pattern = """(?<!\S)[（(]?\s*([A-Ha-hＡ-Ｈａ-ｈ])\s*[)）.。．、:：]"""
    )

    private val TEXT_ANSWER_REGEX = Regex(
        pattern = """^\s*(?:答案|正确答案|参考答案|标准答案|正确选项|答案为)\s*[:：]?\s*([A-Ha-hＡ-Ｈａ-ｈ]{1,8}|正确|错误|对|错|√|×|TRUE|FALSE|true|false)\s*[.。．、,，:：]?\s*(.*)$"""
    )

    private fun parseExcel(context: Context, fileUri: Uri): ParsedExcelResult {
        var excelFile: InputStream? = null
        var workbook: Workbook? = null

        try {
            excelFile = context.contentResolver.openInputStream(fileUri)
            workbook = WorkbookFactory.create(excelFile)
            val formatter = DataFormatter()
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            val importSettings = ImportCandidateSettings.load(context)
            val sheetMatch = detectImportSheet(workbook, formatter, evaluator, importSettings)
                ?: throw ImportException("未识别到题目/答案表头")

            val parsedExcelResult = when (sheetMatch.template) {
                ImportTemplate.GENERIC -> parseMappedSheet(
                    sheetMatch.sheet,
                    sheetMatch.headerRowIndex,
                    sheetMatch.mapping ?: throw ImportException("未识别到题目/答案表头"),
                    formatter,
                    evaluator,
                    importSettings,
                    sheetMatch
                )

                ImportTemplate.LEGACY -> parseLegacySheet(
                    sheetMatch.sheet,
                    sheetMatch.headerRowIndex,
                    formatter,
                    evaluator,
                    importSettings,
                    sheetMatch
                )

                ImportTemplate.TEXT -> throw ImportException("Excel 导入不支持纯文本解析分支")
            }

            if (parsedExcelResult.drafts.isEmpty()) {
                throw ImportException("未找到可导入的题目，或题型暂不支持")
            }

            return parsedExcelResult
        } catch (e: POIXMLException) {
            Log.e(TAG, "Excel OOXML 解析失败: $fileUri", e)
            throw ImportException("Excel 格式无法解析", e)
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "读取 Excel 时找不到文件: $fileUri", e)
            throw ImportException("文件不存在或无法访问", e)
        } catch (e: InvalidFormatException) {
            Log.e(TAG, "Excel 文件格式无效: $fileUri", e)
            throw ImportException("Excel 格式无法解析", e)
        } catch (e: IOException) {
            Log.e(TAG, "读取 Excel 失败: $fileUri", e)
            throw ImportException("读取文件失败", e)
        } catch (e: ImportException) {
            Log.e(TAG, "Excel 导入校验失败: $fileUri", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Excel 导入出现未知异常: $fileUri", e)
            throw ImportException(formatImportError(e, fallback = "未知错误"), e)
        } finally {
            try {
                workbook?.close()
                excelFile?.close()
            } catch (e: IOException) {
                Log.e(TAG, "关闭 Excel 输入流失败: $fileUri", e)
            }
        }
        return ParsedExcelResult(ImportTemplate.LEGACY, emptyList(), emptyList())
    }

    private fun formatImportError(error: Throwable, fallback: String = "导入失败"): String {
        val message = error.localizedMessage ?: error.message
        if (!message.isNullOrBlank() && message != "未知错误") {
            return message
        }

        val cause = error.cause
        val causeMessage = cause?.localizedMessage ?: cause?.message
        if (!causeMessage.isNullOrBlank() && causeMessage != "未知错误") {
            return causeMessage
        }

        val typeName = (cause ?: error).javaClass.simpleName
        return if (typeName.isBlank()) fallback else "$fallback（$typeName）"
    }

    private fun detectImportSheet(
        workbook: Workbook,
        formatter: DataFormatter,
        evaluator: FormulaEvaluator,
        settings: ImportCandidateConfig
    ): ImportSheetMatch? {
        var bestMatch: ImportSheetMatch? = null
        workbook.forEach { sheet ->
            val maxRow = min(sheet.lastRowNum, 9)
            for (rowIndex in 0..maxRow) {
                val headers = readRow(sheet.getRow(rowIndex), formatter, evaluator)
                if (headers.isEmpty()) continue
                val scoredMapping = resolveHeaderMapping(headers, settings) ?: continue
                val match = ImportSheetMatch(
                    template = ImportTemplate.GENERIC,
                    sheet = sheet,
                    headerRowIndex = rowIndex,
                    mapping = scoredMapping.mapping,
                    score = scoredMapping.score,
                    sourceName = ImportTemplate.GENERIC.displayName
                )
                val currentBest = bestMatch
                if (currentBest == null || match.score > currentBest.score) {
                    bestMatch = match
                }
            }
        }

        if (bestMatch != null) return bestMatch

        return workbook.getSheetAt(0)?.let {
            ImportSheetMatch(ImportTemplate.LEGACY, it, 0)
        }
    }

    private fun parseMappedSheet(
        sheet: Sheet,
        headerRowIndex: Int,
        headerMapping: ImportHeaderMapping,
        formatter: DataFormatter,
        evaluator: FormulaEvaluator,
        settings: ImportCandidateConfig,
        sheetMatch: ImportSheetMatch
    ): ParsedExcelResult {
        val imported = mutableListOf<ImportedQuizDraft>()
        val warnings = mutableListOf<ImportWarning>()
        for (rowIndex in headerRowIndex + 1..sheet.lastRowNum) {
            val rowValues = readRow(sheet.getRow(rowIndex), formatter, evaluator)
            if (rowValues.all { it.isBlank() }) continue

            val prompt = rowValues.getOrNull(headerMapping.promptIndex)?.trim().orEmpty()
            val type = headerMapping.typeIndex?.let { rowValues.getOrNull(it)?.trim() }.orEmpty()
            val optionCells = headerMapping.optionIndexes.map { rowValues.getOrNull(it).orEmpty().trim() }
            val answerCell = headerMapping.answerIndex?.let { rowValues.getOrNull(it)?.trim() }.orEmpty()
            if (prompt.isBlank()) continue

            val parsed = parseImportedQuiz(type, prompt, optionCells, answerCell, settings)
            parsed.draft?.let(imported::add)
            parsed.reason?.let {
                warnings += ImportWarning(rowIndex + 1, it, prompt)
            }
        }
        return ParsedExcelResult(
            template = ImportTemplate.GENERIC,
            drafts = imported,
            warnings = warnings,
            headerRowNumber = sheetMatch.headerRowIndex + 1,
            sourceName = sheetMatch.sourceName
        )
    }

    private fun parseLegacySheet(
        sheet: Sheet,
        headerRowIndex: Int,
        formatter: DataFormatter,
        evaluator: FormulaEvaluator,
        settings: ImportCandidateConfig,
        sheetMatch: ImportSheetMatch
    ): ParsedExcelResult {
        val imported = mutableListOf<ImportedQuizDraft>()
        val warnings = mutableListOf<ImportWarning>()
        for (rowIndex in headerRowIndex + 1..sheet.lastRowNum) {
            val rowValues = readRow(sheet.getRow(rowIndex), formatter, evaluator)
            if (rowValues.all { it.isBlank() }) continue

            val prompt = rowValues.getOrNull(0)?.trim().orEmpty()
            val answerCell = rowValues.getOrNull(1)?.trim().orEmpty()
            val options = rowValues.drop(2).map { it.trim() }.filter { it.isNotBlank() }
            if (prompt.isBlank()) continue
            val parsed = parseImportedQuiz("", prompt, options, answerCell, settings)
            parsed.draft?.let(imported::add)
            parsed.reason?.let {
                warnings += ImportWarning(rowIndex + 1, it, prompt)
            }
        }
        return ParsedExcelResult(
            template = ImportTemplate.LEGACY,
            drafts = imported,
            warnings = warnings,
            headerRowNumber = null,
            sourceName = sheetMatch.sourceName
        )
    }

    private data class ScoredHeaderMapping(
        val mapping: ImportHeaderMapping,
        val score: Int
    )

    private data class OptionHeaderMatch(
        val columnIndex: Int,
        val optionOrder: Int
    )

    private fun resolveHeaderMapping(
        headers: List<String>,
        settings: ImportCandidateConfig
    ): ScoredHeaderMapping? {
        var promptIndex: Int? = null
        var typeIndex: Int? = null
        var answerIndex: Int? = null
        var analysisIndex: Int? = null
        val optionMatches = mutableListOf<OptionHeaderMatch>()

        headers.forEachIndexed { index, header ->
            val normalizedHeader = normalizeHeaderText(header)
            if (normalizedHeader.isBlank()) return@forEachIndexed

            if (promptIndex == null && matchesAnyHeader(normalizedHeader, settings.promptHeaders)) {
                promptIndex = index
            }
            if (typeIndex == null && matchesAnyHeader(normalizedHeader, settings.typeHeaders)) {
                typeIndex = index
            }
            if (answerIndex == null && matchesAnyHeader(normalizedHeader, settings.answerHeaders)) {
                answerIndex = index
            }
            if (analysisIndex == null && matchesAnyHeader(normalizedHeader, settings.analysisHeaders)) {
                analysisIndex = index
            }
            resolveOptionHeaderOrder(normalizedHeader, settings.optionPrefixes)?.let { optionOrder ->
                optionMatches += OptionHeaderMatch(index, optionOrder)
            }
        }

        val prompt = promptIndex ?: return null
        val optionIndexes = optionMatches
            .groupBy { it.optionOrder }
            .mapValues { (_, matches) -> matches.minByOrNull { it.columnIndex }!! }
            .values
            .sortedBy { it.optionOrder }
            .map { it.columnIndex }
        val hasAnswer = answerIndex != null
        val hasEnoughOptions = optionIndexes.size >= 2
        if (!hasAnswer && !hasEnoughOptions) {
            return null
        }

        val score = 3 +
            (if (hasAnswer) 3 else 0) +
            (if (typeIndex != null) 2 else 0) +
            min(optionIndexes.size, 4) +
            (if (analysisIndex != null) 1 else 0)

        return ScoredHeaderMapping(
            mapping = ImportHeaderMapping(
                promptIndex = prompt,
                typeIndex = typeIndex,
                answerIndex = answerIndex,
                analysisIndex = analysisIndex,
                optionIndexes = optionIndexes
            ),
            score = score
        )
    }

    private fun matchesAnyHeader(normalizedHeader: String, aliases: List<String>): Boolean {
        return aliases.any { alias ->
            val normalizedAlias = normalizeHeaderText(alias)
            normalizedAlias.isNotBlank() && matchesHeader(normalizedHeader, normalizedAlias)
        }
    }

    private fun matchesHeader(normalizedHeader: String, normalizedAlias: String): Boolean {
        if (normalizedHeader == normalizedAlias) return true
        if (stripBracketSuffix(normalizedHeader) == normalizedAlias) return true
        return normalizedAlias.length >= 3 && normalizedHeader.startsWith(normalizedAlias)
    }

    private fun stripBracketSuffix(text: String): String {
        return text.replace(Regex("""\(.*$"""), "")
            .replace(Regex("""（.*$"""), "")
            .trim()
    }

    private fun resolveOptionHeaderOrder(header: String, optionPrefixes: List<String>): Int? {
        val normalizedPrefixes = optionPrefixes.map(::normalizeHeaderText).filter { it.isNotBlank() }
        for (order in 0 until 8) {
            val letter = ('A' + order).toString()
            val number = (order + 1).toString()
            if (header == letter || header == number) return order
            normalizedPrefixes.forEach { prefix ->
                val candidates = listOf(
                    "$prefix$letter",
                    "$letter$prefix",
                    "$prefix$number"
                )
                if (candidates.any { header == it || header.startsWith(it) }) {
                    return order
                }
            }
        }
        return null
    }

    private fun parseImportedQuiz(
        type: String,
        prompt: String,
        optionCells: List<String>,
        answerCell: String,
        settings: ImportCandidateConfig
    ): ImportRowParseResult {
        val normalizedType = normalizeHeaderText(type)
        return when {
            normalizedType.isBlank() -> inferImportedQuiz(prompt, optionCells, answerCell)
            matchesCandidateValue(normalizedType, settings.singleChoiceTypes) -> {
                parseChoiceQuiz(prompt, optionCells, answerCell, QuizUiType.SINGLE_CHOICE, "单选")
            }
            matchesCandidateValue(normalizedType, settings.multipleChoiceTypes) -> {
                parseChoiceQuiz(prompt, optionCells, answerCell, QuizUiType.MULTIPLE_CHOICE, "多选")
            }
            matchesCandidateValue(normalizedType, settings.judgementTypes) -> parseJudgementQuiz(prompt, answerCell)
            matchesCandidateValue(normalizedType, settings.fillBlankTypes) -> {
                parseFillBlankQuiz(prompt, optionCells, answerCell)
            }
            matchesCandidateValue(normalizedType, settings.subjectiveTypes) -> parseTextAnswerQuiz(prompt, answerCell)
            normalizedType == "排序题" || normalizedType == "排序" -> ImportRowParseResult(reason = "暂不支持排序题")
            else -> ImportRowParseResult(reason = "未知题型：$normalizedType")
        }
    }

    private fun inferImportedQuiz(
        prompt: String,
        optionCells: List<String>,
        answerCell: String
    ): ImportRowParseResult {
        val answer = parseChoiceAnswer(answerCell, optionCells.size)
        val hasOptions = optionCells.any { it.isNotBlank() }
        parseJudgementAnswerFromOptions(optionCells, answerCell)?.let { answerIndex ->
            return buildJudgementQuiz(prompt, answerIndex)
        }
        if (hasOptions && answer.size == 1) {
            return parseChoiceQuiz(prompt, optionCells, answerCell, QuizUiType.SINGLE_CHOICE, "单选")
        }
        if (hasOptions && answer.size > 1) {
            return parseChoiceQuiz(prompt, optionCells, answerCell, QuizUiType.MULTIPLE_CHOICE, "多选")
        }
        if (parseJudgementAnswer(answerCell) != null) {
            return parseJudgementQuiz(prompt, answerCell)
        }
        return ImportRowParseResult(reason = "缺少题型，且无法根据答案推断")
    }

    private fun matchesCandidateValue(value: String, aliases: List<String>): Boolean {
        return aliases.any { alias -> value == normalizeHeaderText(alias) }
    }

    private fun parseChoiceQuiz(
        prompt: String,
        optionCells: List<String>,
        answerCell: String,
        questionType: QuizUiType,
        typeLabel: String
    ): ImportRowParseResult {
        val answer = parseChoiceAnswer(answerCell, optionCells.size)
        val lastNonBlankIndex = optionCells.indexOfLast { it.isNotBlank() }
        val lastAnswerIndex = answer.maxOrNull() ?: -1
        val lastRelevantIndex = maxOf(lastNonBlankIndex, lastAnswerIndex)
        if (prompt.isBlank() || answer.isEmpty() || lastRelevantIndex < 0) {
            return ImportRowParseResult(reason = "$typeLabel 的答案或选项无效")
        }
        val options = optionCells.take(lastRelevantIndex + 1)
        return ImportRowParseResult(
            draft = ImportedQuizDraft(
                prompt = prompt,
                options = options,
                answer = answer,
                isMultipleChoice = answer.size > 1,
                questionType = questionType
            )
        )
    }

    private fun parseJudgementQuiz(prompt: String, answerCell: String): ImportRowParseResult {
        val answerIndex = parseJudgementAnswer(answerCell)
            ?: return ImportRowParseResult(reason = "判断题答案无效")
        return buildJudgementQuiz(prompt, answerIndex)
    }

    private fun buildJudgementQuiz(prompt: String, answerIndex: Int): ImportRowParseResult {
        return ImportRowParseResult(
            draft = ImportedQuizDraft(
                prompt = prompt,
                options = listOf("正确", "错误"),
                answer = linkedSetOf(answerIndex),
                isMultipleChoice = false,
                questionType = QuizUiType.JUDGEMENT
            )
        )
    }

    private fun parseFillBlankQuiz(
        prompt: String,
        optionCells: List<String>,
        answerCell: String
    ): ImportRowParseResult {
        val answers = extractFillBlankAnswers(optionCells, answerCell)
        if (prompt.isBlank() || answers.isEmpty()) {
            return ImportRowParseResult(reason = "填空题答案为空")
        }
        return ImportRowParseResult(
            draft = ImportedQuizDraft(
                prompt = prompt,
                options = answers,
                answer = answers.indices.toCollection(linkedSetOf()),
                isMultipleChoice = answers.size > 1,
                questionType = QuizUiType.FILL_BLANK
            )
        )
    }

    private fun parseTextAnswerQuiz(prompt: String, answerCell: String): ImportRowParseResult {
        val answerText = answerCell.trim()
        if (prompt.isBlank() || answerText.isBlank()) {
            return ImportRowParseResult(reason = "主观题答案为空")
        }
        return ImportRowParseResult(
            draft = ImportedQuizDraft(
                prompt = prompt,
                options = listOf(answerText),
                answer = linkedSetOf(0),
                isMultipleChoice = false,
                questionType = QuizUiType.SUBJECTIVE
            )
        )
    }

    private fun buildImportSummaryMessage(
        importResults: List<ImportFileResult>,
        failedFiles: List<String>,
        totalImportedCount: Int,
        totalSkippedCount: Int
    ): String {
        val lines = mutableListOf<String>()
        lines += "共导入 $totalImportedCount 题，跳过 $totalSkippedCount 题。"

        importResults.forEach { result ->
            val headerInfo = result.headerRowNumber?.let { "，表头第 ${it} 行" } ?: ""
            lines += "${result.fileName}（${result.sourceName}）：导入 ${result.importedCount} 题，跳过 ${result.skippedCount} 题$headerInfo"
            summarizeWarnings(result.skippedWarnings).forEach { warningSummary ->
                lines += warningSummary
            }
        }

        if (failedFiles.isNotEmpty()) {
            lines += "失败文件："
            lines += failedFiles
        }

        return lines.joinToString("\n")
    }

    private fun summarizeWarnings(warnings: List<ImportWarning>): List<String> {
        if (warnings.isEmpty()) return emptyList()
        return warnings.groupingBy { it.reason }.eachCount()
            .toList()
            .sortedByDescending { it.second }
            .map { (reason, count) -> "  $reason：$count 题" }
    }

    private fun extractFillBlankAnswers(optionCells: List<String>, answerCell: String): List<String> {
        val normalizedOptions = optionCells.map { it.trim() }.filter { it.isNotBlank() }
        if (normalizedOptions.isEmpty() && answerCell.isNotBlank()) {
            return splitAnswerText(answerCell)
        }

        if (normalizedOptions.size == 1 && normalizedOptions.first().contains("|")) {
            return splitAnswerText(normalizedOptions.first())
        }

        return normalizedOptions.flatMap { splitAnswerText(it) }
    }

    private fun splitAnswerText(text: String): List<String> {
        return text.split("|", "｜")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseChoiceAnswer(answerCell: String, optionCount: Int): Set<Int> {
        if (answerCell.isBlank()) return emptySet()
        return answerCell.mapNotNull { convertLetterToNumber(normalizeAnswerLetter(it)) }
            .filter { it in 0 until optionCount }
            .toCollection(linkedSetOf())
    }

    private fun normalizeAnswerLetter(char: Char): Char {
        return when (char) {
            in 'Ａ'..'Ｚ' -> 'A' + (char - 'Ａ')
            in 'ａ'..'ｚ' -> 'A' + (char - 'ａ')
            else -> char.uppercaseChar()
        }
    }

    private fun parseJudgementAnswer(answerCell: String): Int? {
        val normalized = answerCell.trim().uppercase()
        return when (normalized) {
            "A", "对", "正确", "√", "TRUE", "T" -> 0
            "B", "错", "错误", "×", "FALSE", "F" -> 1
            else -> null
        }
    }

    private fun parseJudgementAnswerFromOptions(optionCells: List<String>, answerCell: String): Int? {
        val judgementOptions = resolveJudgementOptionPair(optionCells) ?: return null
        val answerByLetter = parseChoiceAnswer(answerCell, judgementOptions.size).singleOrNull()
        if (answerByLetter != null) {
            return judgementOptions.getOrNull(answerByLetter)
        }

        return parseJudgementAnswer(answerCell)
    }

    private fun resolveJudgementOptionPair(optionCells: List<String>): List<Int>? {
        val normalizedOptions = optionCells.map { normalizeJudgementOptionText(it) }
            .filter { it.isNotBlank() }
        if (normalizedOptions.size != 2) return null

        val first = judgementOptionValue(normalizedOptions[0]) ?: return null
        val second = judgementOptionValue(normalizedOptions[1]) ?: return null
        if (first == second) return null

        return listOf(first, second)
    }

    private fun judgementOptionValue(option: String): Int? {
        return when (option) {
            "对", "正确", "是", "TRUE", "T" -> 0
            "错", "错误", "否", "FALSE", "F" -> 1
            else -> null
        }
    }

    private fun normalizeJudgementOptionText(text: String): String {
        return normalizeHeaderText(text)
            .removePrefix("A.")
            .removePrefix("A、")
            .removePrefix("A:")
            .removePrefix("A：")
            .removePrefix("B.")
            .removePrefix("B、")
            .removePrefix("B:")
            .removePrefix("B：")
    }

    private fun readRow(
        row: Row?,
        formatter: DataFormatter,
        evaluator: FormulaEvaluator
    ): List<String> {
        if (row == null) return emptyList()
        val lastCellIndex = row.lastCellNum.toInt().coerceAtLeast(0)
        return (0 until lastCellIndex).map { cellIndex ->
            formatter.formatCellValue(row.getCell(cellIndex), evaluator).trim()
        }
    }

    private fun normalizeHeaderText(text: String): String {
        return text.replace("\n", "")
            .replace(" ", "")
            .replace("（", "(")
            .replace("）", ")")
            .trim()
            .uppercase()
    }

    @Deprecated("Use parseExcel(context: Context, fileUri: Uri): ArrayList<ArrayList<String>>")
    private fun parseExcel(filePath: String): ArrayList<ArrayList<String>> {
        // 2 dimen array
        val patients: ArrayList<ArrayList<String>> = ArrayList()

        try {
            val inputStream = FileInputStream(filePath)
            val workbook = XSSFWorkbook(inputStream)
            val sheet = workbook.getSheetAt(0)
            val rowCount = sheet.physicalNumberOfRows
            var colCount: Int
            val formulaEvaluator: FormulaEvaluator =
                workbook.creationHelper.createFormulaEvaluator()
            for (r in 0 until rowCount) {
                val row: Row = sheet.getRow(r)
                colCount = row.physicalNumberOfCells
                // rowData
                val cellData: ArrayList<String> = ArrayList()
                for (c in 0 until colCount) {
                    cellData.add(getCellAsString(row, c, formulaEvaluator))
                }
                patients.add(cellData)
            }
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "File Not Found")
//            Toast.makeText(this.requireContext(), "File Not Found", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e(TAG, "IO Error reading input stream")

//            Toast.makeText(
//                this.requireContext(),
//                "IO Error reading input stream",
//                Toast.LENGTH_SHORT
//            ).show()
        }

        return patients
//        binding.textviewFirst.text = patients.joinToString("\n")
//        arrayAdapter =
//            ArrayAdapter<String>(this, R.layout.simple_list_item_1, patients)
//        listView?.setAdapter(arrayAdapter)
    }

    private fun getCellAsString(row: Row, cIdx: Int, formulaEvaluator: FormulaEvaluator): String {
        var value = ""
        try {
            val cell = row.getCell(cIdx)
            val cellValue = formulaEvaluator.evaluate(cell)
            when (cellValue.cellType) {
                CellType.BOOLEAN -> value = "" + cellValue.booleanValue
                CellType.NUMERIC -> {
                    val numericValue = cellValue.numberValue
                    value = if (DateUtil.isCellDateFormatted(cell)) {
                        val date = cellValue.numberValue
                        val formatter = SimpleDateFormat("MM/dd/yy")
                        formatter.format(DateUtil.getJavaDate(date))
                    } else {
                        "" + numericValue
                    }
                }

                CellType.STRING -> value = cellValue.stringValue
                else -> {}
            }
        } catch (e: NullPointerException) {
            Log.e(TAG, "Null Pointer Exception in getCellAsString. ${e.message}")
//            Toast.makeText(
//                this.requireContext(),
//                "Null Pointer Exception in getCellAsString",
//                Toast.LENGTH_SHORT
//            ).show()
        }
        return value
    }
}
