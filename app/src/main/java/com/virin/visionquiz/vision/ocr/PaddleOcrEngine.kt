package com.virin.visionquiz.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class PaddleOcrEngine(context: Context) : OcrEngine {

    override val type: OcrEngineType = OcrEngineType.PADDLE_OCR_V6_SMALL
    override val requiresBitmapInput: Boolean = true

    private val appContext = context.applicationContext
    private val isClosed = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineName("PaddleOcrEngine")
    )

    init {
        SharedPaddleOcrRuntime.retain()
    }

    override fun recognize(image: InputImage): Task<OcrDocument> {
        return Tasks.forException(
            IllegalArgumentException(
                "Paddle OCR requires an upright Bitmap; check requiresBitmapInput before dispatch"
            )
        )
    }

    override fun recognize(bitmap: Bitmap): Task<OcrDocument> {
        if (isClosed.get()) {
            return closedTask()
        }
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return Tasks.forException(IllegalArgumentException("Paddle OCR input Bitmap is invalid"))
        }

        val completion = TaskCompletionSource<OcrDocument>()
        // UNDISPATCHED guarantees that even a close racing this call reaches the cancellation
        // handler, so callers waiting to close/recycle the source frame are never left hanging.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coroutineContext.ensureActive()
                val result = SharedPaddleOcrRuntime.recognize(appContext, bitmap)
                coroutineContext.ensureActive()
                completion.trySetResult(
                    result.toOcrDocument(bitmap.width, bitmap.height)
                )
            } catch (cancelled: CancellationException) {
                completion.trySetException(
                    IllegalStateException("Paddle OCR recognition was cancelled", cancelled)
                )
            } catch (exception: Exception) {
                completion.trySetException(exception)
            } catch (throwable: Throwable) {
                completion.trySetException(
                    IllegalStateException("Paddle OCR recognition failed", throwable)
                )
            }
        }
        return completion.task
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            scope.cancel("Paddle OCR adapter closed")
            SharedPaddleOcrRuntime.release()
        }
    }

    private fun closedTask(): Task<OcrDocument> {
        return Tasks.forException(IllegalStateException("Paddle OCR engine is closed"))
    }
}

/**
 * Process-wide, lazily initialized Paddle runtime.
 *
 * ONNX sessions are expensive and Paddle's pipeline is mutable, so all initialization, inference,
 * and final release are serialized. Reference counting prevents one search surface from releasing
 * the runtime while another one is still using it.
 */
private object SharedPaddleOcrRuntime {
    private val engineMutex = Mutex()
    private val clientLock = Any()
    private val cleanupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("PaddleOcrCleanup")
    )

    @Volatile
    private var engine: PaddleOCR? = null
    private var clientCount: Int = 0

    fun retain() {
        synchronized(clientLock) {
            clientCount++
        }
    }

    suspend fun recognize(context: Context, bitmap: Bitmap): OCRRunResult {
        return engineMutex.withLock {
            val activeEngine = engine ?: withContext(NonCancellable) {
                check(OpenCVUtils.init(context)) { "Unable to initialize OpenCV for Paddle OCR" }
                PaddleOCR.create(
                    context = context,
                    config = PaddleOCRConfig(
                        detLimitType = DET_LIMIT_TYPE,
                        detLimitSideLen = DET_LIMIT_SIDE_LENGTH,
                        detMaxSideLimit = DET_MAX_SIDE_LENGTH,
                        // Keep single-line recognition until the bundled model's dynamic batch
                        // shape has been verified across supported devices.
                        recBatchSize = RECOGNITION_BATCH_SIZE
                    )
                ).also { engine = it }
            }
            activeEngine.recognize(bitmap)
        }
    }

    fun release() {
        val shouldTryRelease = synchronized(clientLock) {
            check(clientCount > 0) { "Paddle OCR runtime released without a retained client" }
            clientCount--
            clientCount == 0
        }
        if (!shouldTryRelease) {
            return
        }

        cleanupScope.launch {
            engineMutex.withLock {
                val hasClients = synchronized(clientLock) { clientCount > 0 }
                if (!hasClients) {
                    val engineToRelease = engine
                    engine = null
                    engineToRelease?.release()
                }
            }
        }
    }

    private const val DET_LIMIT_TYPE = "max"
    private const val DET_LIMIT_SIDE_LENGTH = 1600
    private const val DET_MAX_SIDE_LENGTH = 1600
    private const val RECOGNITION_BATCH_SIZE = 1
}

private fun OCRRunResult.toOcrDocument(imageWidth: Int, imageHeight: Int): OcrDocument {
    val blocks = results.mapNotNull { result ->
        val trimmedText = result.text.trim()
        if (trimmedText.isEmpty()) {
            return@mapNotNull null
        }
        val bounds = PaddleBoxRectMapper.map(
            points = result.box.points.map { point ->
                PaddleBoxRectMapper.Coordinate(point.x, point.y)
            },
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )?.toRect() ?: return@mapNotNull null
        val confidence = result.confidence
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
        val line = OcrTextLine(
            text = trimmedText,
            boundingBox = bounds,
            // The current Paddle SDK exposes optional word boxes without their corresponding word
            // text. Guessing a CJK split would corrupt option matching, so keep elements empty.
            elements = emptyList(),
            confidence = confidence
        )
        OcrTextBlock(
            text = trimmedText,
            boundingBox = Rect(bounds),
            lines = listOf(line)
        )
    }
    return OcrDocument(
        text = blocks.joinToString("\n", transform = OcrTextBlock::text),
        textBlocks = blocks
    )
}
