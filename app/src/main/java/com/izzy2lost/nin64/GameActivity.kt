package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView

    @Volatile private var running = false
    private var emulationThread: Thread? = null

    private val rootPath: String get() = intent.getStringExtra(EXTRA_ROOT_PATH) ?: ""
    private val romPath: String get() = intent.getStringExtra(EXTRA_ROM_PATH) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        surfaceView = SurfaceView(this)
        surfaceView.holder.setFormat(PixelFormat.RGBX_8888)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (rootPath.isEmpty() || romPath.isEmpty()) {
            finish()
            return
        }
        startEmulation(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopEmulation()
    }

    private fun startEmulation(holder: SurfaceHolder) {
        running = true
        emulationThread = Thread {
            NativeBridge.bootRomForPlay(rootPath, romPath)
            emulationLoop(holder)
        }.apply {
            name = "Nin64-Emu"
            start()
        }
    }

    private fun stopEmulation() {
        running = false
        emulationThread?.join(3000)
        emulationThread = null
    }

    private fun emulationLoop(holder: SurfaceHolder) {
        val paint = Paint().apply { isFilterBitmap = true }
        var frameBitmap: Bitmap? = null
        var lastSwap = NativeBridge.getSwapCount()

        while (running) {
            NativeBridge.runFrame(OPS_PER_CHUNK)

            val swap = NativeBridge.getSwapCount()
            if (swap == lastSwap) continue
            lastSwap = swap

            val w = NativeBridge.getFrameWidth()
            val h = NativeBridge.getFrameHeight()
            if (w <= 0 || h <= 0) continue

            val pixels = NativeBridge.copyFrameBufferArgb()
            if (pixels.size < w * h) continue

            if (frameBitmap == null || frameBitmap!!.width != w || frameBitmap!!.height != h) {
                frameBitmap?.recycle()
                frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            frameBitmap!!.setPixels(pixels, 0, w, 0, 0, w, h)

            val canvas: Canvas = holder.lockCanvas() ?: continue
            try {
                blit(canvas, frameBitmap!!, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        frameBitmap?.recycle()
    }

    private fun blit(canvas: Canvas, bitmap: Bitmap, paint: Paint) {
        canvas.drawColor(Color.BLACK)

        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstAspect = canvas.width.toFloat() / canvas.height.toFloat()

        val dst = if (srcAspect > dstAspect) {
            val h = canvas.width / srcAspect
            RectF(0f, (canvas.height - h) / 2f, canvas.width.toFloat(), (canvas.height + h) / 2f)
        } else {
            val w = canvas.height * srcAspect
            RectF((canvas.width - w) / 2f, 0f, (canvas.width + w) / 2f, canvas.height.toFloat())
        }

        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        stopEmulation()
        super.onBackPressed()
    }

    override fun onDestroy() {
        stopEmulation()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROOT_PATH = "extra_root_path"
        private const val EXTRA_ROM_PATH = "extra_rom_path"
        private const val OPS_PER_CHUNK = 500_000

        fun launch(context: Context, rootPath: String, romPath: String) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_ROOT_PATH, rootPath)
                    .putExtra(EXTRA_ROM_PATH, romPath)
            )
        }
    }
}
