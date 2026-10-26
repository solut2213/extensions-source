package eu.kanade.tachiyomi.extension.en.comix2

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/** Hosts an offscreen [WebView] on a private virtual display, so it renders like one on screen. */
internal class BackgroundSurface private constructor(
    private val imageReader: ImageReader,
    private val virtualDisplay: VirtualDisplay,
    private val presentation: Presentation,
    private val drainThread: HandlerThread,
) {
    private fun release() {
        try {
            presentation.dismiss()
            virtualDisplay.release()
            stopDrain(imageReader, drainThread)
        } catch (_: Throwable) {}
    }

    companion object {
        // Released when the WebView leaves it
        fun host(webView: WebView) {
            try {
                val metrics = webView.resources?.displayMetrics ?: return
                val width = metrics.widthPixels
                val height = metrics.heightPixels

                val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val drainThread = HandlerThread("comix-webview-drain").apply { start() }
                imageReader.setOnImageAvailableListener(
                    { reader -> reader.acquireLatestImage()?.close() },
                    Handler(drainThread.looper),
                )
                var virtualDisplay: VirtualDisplay? = null
                try {
                    val displayManager = webView.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                        ?: return
                    virtualDisplay = displayManager.createVirtualDisplay(
                        "comix-webview",
                        width,
                        height,
                        metrics.densityDpi,
                        imageReader.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                    ) ?: return

                    val presentation = Presentation(webView.context.applicationContext, virtualDisplay.display).apply {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        setContentView(webView, ViewGroup.LayoutParams(width, height))
                        show()
                    }
                    val surface = BackgroundSurface(imageReader, virtualDisplay, presentation, drainThread)
                    (webView.parent as? ViewGroup)?.setOnHierarchyChangeListener(
                        object : ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewAdded(parent: View, child: View) {}

                            override fun onChildViewRemoved(parent: View, child: View) {
                                if (child === webView) surface.release()
                            }
                        },
                    )
                } catch (e: Throwable) {
                    virtualDisplay?.release()
                    stopDrain(imageReader, drainThread)
                }
            } catch (_: Throwable) {
                // Desktop Suwayomi does not support Android VirtualDisplay
            }
        }

        private fun stopDrain(imageReader: ImageReader, drainThread: HandlerThread) {
            try {
                imageReader.setOnImageAvailableListener(null, null)
                drainThread.quitSafely()
                drainThread.join()
                imageReader.close()
            } catch (_: Throwable) {}
        }
    }
}
