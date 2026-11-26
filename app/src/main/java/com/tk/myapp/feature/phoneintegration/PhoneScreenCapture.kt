package com.tk.myapp.feature.phoneintegration

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.tk.myapp.MyAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Captures the phone screen as PNG through the app's accessibility service (Android 11+). */
object PhoneScreenCapture {
    private val executor = Executors.newSingleThreadExecutor()

    suspend fun capturePng(): ByteArray {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Phone screenshots need Android 11 or newer."
        }
        val service = MyAccessibilityService.instance
            ?: error("Turn on the My Toolkit accessibility service on Android to take screenshots.")

        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val result = runCatching {
                            val buffer = screenshot.hardwareBuffer
                            try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: error("Android returned an empty screenshot.")
                                val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBitmap.recycle()
                                ByteArrayOutputStream().use { output ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                                    bitmap.recycle()
                                    output.toByteArray()
                                }
                            } finally {
                                buffer.close()
                            }
                        }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }

                    override fun onFailure(errorCode: Int) {
                        val message = when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                "Screenshots were requested too quickly. Try again in a moment."
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                                "The current screen is protected and can't be captured."
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                                "Re-enable the My Toolkit accessibility service on Android to allow screenshots."
                            else -> "Android screenshot failed (code $errorCode)."
                        }
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                }
            )
        }
    }
}
