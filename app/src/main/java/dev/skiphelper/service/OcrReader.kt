package dev.skiphelper.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.skiphelper.engine.Element
import dev.skiphelper.engine.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asExecutor
import kotlin.coroutines.resume

class OcrReader {
    private val chineseClient = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val latinClient = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var activeReads = 0
    private var closing = false
    val busy: Boolean get() = synchronized(this) { activeReads > 0 }

    suspend fun read(service: AccessibilityService, screen: Screen): Screen? {
        if (Build.VERSION.SDK_INT < 30) return null
        synchronized(this) { if (closing || activeReads > 0) return null; activeReads++ }
        try {
            val bitmap = capture(service) ?: return null
            // ML Kit tasks are not cancellable: retain the bitmap until all consumers finish.
            return withContext(NonCancellable + Dispatchers.Default) {
                try {
                    if (bitmap.width != screen.width || bitmap.height != screen.height) return@withContext null
                    val input = InputImage.fromBitmap(bitmap, 0)
                    val tasks = listOfNotNull(
                        runCatching { chineseClient.value.process(input) }.getOrNull(),
                        runCatching { latinClient.value.process(input) }.getOrNull(),
                    )
                    val results = tasks.mapNotNull { runCatching { it.await() }.getOrNull() }
                    if (results.isEmpty()) return@withContext null
                    val elements = results.flatMap { result ->
                        result.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
                            line.boundingBox?.let { Element(line.text, "", Rect(it)) }
                        } }
                    }.distinctBy { it.text to it.bounds.toShortString() }
                    screen.copy(elements = elements)
                } catch (e: Exception) { null } finally { bitmap.recycle() }
            }
        } finally {
            synchronized(this) { activeReads--; if (closing && activeReads == 0) closeClients() }
        }
    }

    private suspend fun capture(service: AccessibilityService): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT < 30) { continuation.resume(null); return@suspendCancellableCoroutine }
        try {
            // Hardware-buffer conversion copies a full frame; keep it off the UI thread.
            service.takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = try {
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            try { hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                        } catch (e: Exception) { null } finally { buffer.close() }
                        continuation.resume(bitmap) { _, abandoned, _ -> abandoned?.recycle() }
                    }
                    override fun onFailure(errorCode: Int) { continuation.resume(null) }
                })
        } catch (e: Exception) { continuation.resume(null) }
    }

    @Synchronized
    fun close() { closing = true; if (activeReads == 0) closeClients() }

    private fun closeClients() {
        if (chineseClient.isInitialized()) chineseClient.value.close()
        if (latinClient.isInitialized()) latinClient.value.close()
    }
}
