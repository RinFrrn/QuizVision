package com.virin.visionquiz.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.virin.visionquiz.BuildConfig
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.dao.answerString
import com.virin.visionquiz.dao.optionsString
import com.virin.visionquiz.dao.typeString
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object QuizExportUtil {
    enum class FileType(
        val displayName: String,
        val extension: String,
        val mimeType: String
    ) {
        DOCX(
            "Word 文档 (.docx)",
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ),
        XLSX_KUAISOU(
            "Excel 工作簿 (.xlsx) for 快搜搜题",
            "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ),
        XLSX_MTB(
            "Excel 工作簿 (.xlsx) for 磨题帮",
            "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ),
        XLSX_ANGUI_FOR_IOS(
            "Excel 工作簿 (.xlsx) for 安规题库(iOS)",
            "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ),
        XLS_ANGUI_FOR_ANDROID(
            "Excel 97-2004 工作簿 (.xls) for 安规题库(Android)",
            "xls",
            "application/vnd.ms-excel"
        ),
        TSV_ANKI(
            "Anki 文本文件 (.txt)",
            "txt",
            "text/plain"
        );

        fun getIndex(): Int {
            return values().indexOf(this)
        }
    }

    data class ExportFile(
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    fun createExportFile(
        library: QuizLibrary,
        quizzes: List<Quiz>,
        fileType: FileType
    ): ExportFile {
        val bytes = when (fileType) {
            FileType.DOCX -> createWordBytes(quizzes)
            FileType.XLSX_KUAISOU -> createExcel4KuaiSouBytes(quizzes)
            FileType.XLSX_MTB -> createExcel4MTBBytes(quizzes)
            FileType.XLSX_ANGUI_FOR_IOS -> createExcel4AnGuiBytes(true, quizzes)
            FileType.XLS_ANGUI_FOR_ANDROID -> createExcel4AnGuiBytes(false, quizzes)
            FileType.TSV_ANKI -> createAnkiTSVBytes(library.name, quizzes)
        }
        return ExportFile(
            fileName = "${sanitizeFileName(library.name)}.${fileType.extension}",
            mimeType = fileType.mimeType,
            bytes = bytes
        )
    }

    fun writeExportFileToUri(context: Context, exportFile: ExportFile, uri: Uri) {
        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: error("无法打开保存位置")
        outputStream.use { it.write(exportFile.bytes) }
    }

    fun createShareUri(context: Context, exportFile: ExportFile): Uri {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val targetFile = File(exportDir, exportFile.fileName)
        FileOutputStream(targetFile).use { it.write(exportFile.bytes) }
        val authority = "${BuildConfig.APPLICATION_ID}.file.provider.authority"
        return FileProvider.getUriForFile(context, authority, targetFile)
    }

    fun createAndSaveWordFile(library: QuizLibrary, quizzes: List<Quiz>): String {
        return saveExportFileToDownload(createExportFile(library, quizzes, FileType.DOCX))
    }

    fun createAndSaveExcel4AnGui(
        isX: Boolean,
        library: QuizLibrary,
        quizzes: List<Quiz>
    ): String {
        val fileType = if (isX) FileType.XLSX_ANGUI_FOR_IOS else FileType.XLS_ANGUI_FOR_ANDROID
        return saveExportFileToDownload(createExportFile(library, quizzes, fileType))
    }

    fun createAndSaveExcel4KuaiSou(library: QuizLibrary, quizzes: List<Quiz>): String {
        return saveExportFileToDownload(createExportFile(library, quizzes, FileType.XLSX_KUAISOU))
    }

    fun createAndSaveExcel4MTB(library: QuizLibrary, quizzes: List<Quiz>): String {
        return saveExportFileToDownload(createExportFile(library, quizzes, FileType.XLSX_MTB))
    }

    fun createAndSaveAnkiTSV(library: QuizLibrary, quizzes: List<Quiz>): String {
        return saveExportFileToDownload(createExportFile(library, quizzes, FileType.TSV_ANKI))
    }

    private fun createWordBytes(quizzes: List<Quiz>): ByteArray {
        val document = XWPFDocument()
        val paragraph = document.createParagraph()
        paragraph.alignment = ParagraphAlignment.LEFT
        paragraph.createRun().apply {
            fontSize = 12
            quizzes.forEachIndexed { index, quiz ->
                setText("${index + 1}. ${quiz.prompt}")
                addBreak()
                setText(quiz.optionsString().joinToString("    "))
                addBreak()
                setText("答案：${quiz.answerString()}")
                addBreak()
                addBreak()
            }
        }
        return ByteArrayOutputStream().use { output ->
            document.write(output)
            document.close()
            output.toByteArray()
        }
    }

    private fun createExcel4AnGuiBytes(isX: Boolean, quizzes: List<Quiz>): ByteArray {
        val titles = listOf(
            "序号",
            "题型",
            "题目",
            "选项A",
            "选项B",
            "选项C",
            "选项D",
            "选项E",
            "选项F",
            "答案",
            "解析",
            "备用",
            "边界标识"
        )
        val workbook = if (isX) XSSFWorkbook() else HSSFWorkbook()
        val sheet = workbook.createSheet("data")
        val titleRow = sheet.createRow(0)
        titles.forEachIndexed { index, title ->
            titleRow.createCell(index, CellType.STRING).setCellValue(title)
        }
        quizzes.forEachIndexed { index, quiz ->
            val row = sheet.createRow(index + 1)
            row.createCell(0, CellType.STRING).setCellValue((index + 1).toString())
            row.createCell(1, CellType.STRING).setCellValue(quiz.typeString())
            row.createCell(2, CellType.STRING).setCellValue(quiz.prompt)
            quiz.options.take(6).forEachIndexed { optionIndex, option ->
                row.createCell(optionIndex + 3, CellType.STRING).setCellValue(option)
            }
            row.createCell(9, CellType.STRING).setCellValue(quiz.answerString())
            row.createCell(12, CellType.STRING).setCellValue("1")
        }
        return workbookToBytes(workbook)
    }

    private fun createExcel4KuaiSouBytes(quizzes: List<Quiz>): ByteArray {
        val optionColumnCount = maxOf(6, quizzes.maxOfOrNull { it.options.size } ?: 0)
            .coerceAtMost(26)
        val titles = listOf("题目", "答案") +
            (0 until optionColumnCount).map { "选项${convertNumToChar(it)}" }
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Sheet1")
        val titleRow = sheet.createRow(0)
        titles.forEachIndexed { index, title ->
            titleRow.createCell(index, CellType.STRING).setCellValue(title)
        }
        quizzes.forEachIndexed { index, quiz ->
            val row = sheet.createRow(index + 1)
            row.createCell(0, CellType.STRING).setCellValue(quiz.prompt)
            row.createCell(1, CellType.STRING).setCellValue(quiz.answerString())
            quiz.options.take(optionColumnCount).forEachIndexed { optionIndex, option ->
                row.createCell(optionIndex + 2, CellType.STRING).setCellValue(option)
            }
        }
        return workbookToBytes(workbook)
    }

    private fun createExcel4MTBBytes(quizzes: List<Quiz>): ByteArray {
        val titles = listOf(
            "题目",
            "题型",
            "选项A",
            "选项B",
            "选项C",
            "选项D",
            "选项E",
            "选项F",
            "答案"
        )
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Sheet0")
        val titleRow = sheet.createRow(0)
        titles.forEachIndexed { index, title ->
            titleRow.createCell(index, CellType.STRING).setCellValue(title)
        }
        quizzes.forEachIndexed { index, quiz ->
            val row = sheet.createRow(index + 1)
            row.createCell(0, CellType.STRING).setCellValue(quiz.prompt)
            row.createCell(1, CellType.STRING).setCellValue(quiz.typeString())
            quiz.options.take(6).forEachIndexed { optionIndex, option ->
                row.createCell(optionIndex + 2, CellType.STRING).setCellValue(option)
            }
            row.createCell(8, CellType.STRING).setCellValue(quiz.answerString())
        }
        return workbookToBytes(workbook)
    }

    private fun createAnkiTSVBytes(
        libraryName: String,
        quizzes: List<Quiz>
    ): ByteArray {
        val sb = StringBuilder()
        quizzes.forEachIndexed { _, quiz ->
            val question = quiz.prompt
            val options = quiz.options.filter { it.isNotEmpty() }.joinToString("<br>")
            val answer = quiz.answer.sorted().joinToString(",") { convertNumToChar(it).toString() }
            sb.appendLine("$question	$options	$answer	$libraryName")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun workbookToBytes(workbook: Workbook): ByteArray {
        return ByteArrayOutputStream().use { output ->
            workbook.write(output)
            workbook.close()
            output.toByteArray()
        }
    }

    fun saveExportFileToDownload(exportFile: ExportFile): String {
        val fileDir = "/storage/emulated/0/Download/"
        val uniqueFileName = getUniqueFileName(fileDir, exportFile.fileName)
        val targetFile = File(fileDir, uniqueFileName)
        FileOutputStream(targetFile).use { it.write(exportFile.bytes) }
        return targetFile.path
    }

    fun openXLSXFile(path: String, context: Context) {
        openFile(
            path,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            context
        )
    }

    fun openXLSFile(path: String, context: Context) {
        openFile(path, "application/vnd.ms-excel", context)
    }

    fun openWordFile(path: String, context: Context) {
        openFile(
            path,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            context
        )
    }

    fun openFile(path: String, type: String, context: Context) {
        val file = File(path)
        val openIntent = Intent(Intent.ACTION_VIEW)
        val authority = "${BuildConfig.APPLICATION_ID}.file.provider.authority"
        val fileUri = FileProvider.getUriForFile(context, authority, file)
        openIntent.setDataAndType(fileUri, type)
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(openIntent)
    }

    fun getUniqueFileName(directory: String, fileName: String): String {
        var count = 0
        var uniqueName = fileName
        val extension = getFileExtension(fileName)
        val nameWithoutExtension = getNameWithoutExtension(fileName)

        while (File(directory, uniqueName).exists()) {
            count++
            uniqueName = "$nameWithoutExtension ($count).$extension"
        }
        return uniqueName
    }

    fun getFileExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf(".")
        return if (lastDot == -1) "" else fileName.substring(lastDot + 1)
    }

    fun getNameWithoutExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf(".")
        return if (lastDot == -1) fileName else fileName.substring(0, lastDot)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "题库导出" }
    }
}
