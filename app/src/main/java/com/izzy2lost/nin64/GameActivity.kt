package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.util.Log
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private val logTag = "Nin64Game"

    private lateinit var rootContainer: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var touchControlsView: TouchControlsView
    private lateinit var visibilityToggle: ImageButton
    private lateinit var controlsConfig: ControlsConfig
    private var touchControlsVisible = true

    @Volatile private var running = false
    private var emulationThread: Thread? = null
    private var keyButtonMask = 0
    private var axisButtonMask = 0
    private var touchButtonMask = 0
    private var analogStickX = 0
    private var analogStickY = 0
    private var touchAnalogStickX = 0
    private var touchAnalogStickY = 0

    private val rootPath: String get() = intent.getStringExtra(EXTRA_ROOT_PATH) ?: ""
    private val romPath: String get() = intent.getStringExtra(EXTRA_ROM_PATH) ?: ""
    private val useTexturePack: Boolean get() = intent.getBooleanExtra(EXTRA_USE_TEXTURE_PACK, false)
    private val disableExpansionPak: Boolean get() = intent.getBooleanExtra(EXTRA_DISABLE_EXPANSION_PAK, false)
    private val romPreferenceKey: String? get() = intent.getStringExtra(EXTRA_ROM_PREFERENCE_KEY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controlsConfig = ControlsRepository.load(this, romPreferenceKey)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBX_8888)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        rootContainer.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        touchControlsView = TouchControlsView(this).apply {
            layout = controlsConfig.touchLayout
            onTouchStateChanged = { buttonMask, stickX, stickY ->
                touchButtonMask = buttonMask
                touchAnalogStickX = stickX
                touchAnalogStickY = stickY
                pushControllerState()
            }
        }
        rootContainer.addView(
            touchControlsView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        val density = resources.displayMetrics.density
        val btnSize = (44 * density).toInt()
        val btnMargin = (14 * density).toInt()
        visibilityToggle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_visibility)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(160, 14, 15, 20))
            }
            setOnClickListener { toggleTouchControls() }
        }
        rootContainer.addView(
            visibilityToggle,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = btnMargin
                marginEnd = btnMargin
            }
        )

        setContentView(rootContainer)
        surfaceView.holder.addCallback(this)
        pushControllerState()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (rootPath.isEmpty() || romPath.isEmpty()) {
            finish()
            return
        }
        Log.i(logTag, "surfaceCreated ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()} rom=$romPath")
        NativeBridge.setSurface(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
        applyEmulatorSettings(holder.surfaceFrame.height())
        startEmulation()
    }

    private fun applyEmulatorSettings(surfaceHeight: Int) {
        val prefs = getSharedPreferences("nin64_prefs", MODE_PRIVATE)
        prefs.getString("mupen64plus-aspect", null)?.let {
            NativeBridge.setOption("mupen64plus-aspect", it)
        }

        // For "Auto" (or unset) we pick the Nx native-resolution multiplier closest
        // to the device's surface height. N64 native is 320x240, and GlideN64 only
        // honours integer factors 1..8 — handing it an arbitrary WxH string causes
        // games like Conker to render wrong on first boot.
        val resPref = prefs.getString("mupen64plus-EnableNativeResFactor", null)
        val factor = if (resPref.isNullOrEmpty() || resPref == "0") {
            ((surfaceHeight + 120) / 240).coerceIn(1, 8)
        } else {
            resPref.toIntOrNull()?.coerceIn(1, 8) ?: 1
        }
        NativeBridge.setOption("mupen64plus-EnableNativeResFactor", factor.toString())
        applyPerGameEmulatorSettings()
        Log.i(logTag, "applyEmulatorSettings surfaceHeight=$surfaceHeight resFactor=$factor")
    }

    private fun applyPerGameEmulatorSettings() {
        NativeBridge.setOption(
            "mupen64plus-txHiresEnable",
            if (useTexturePack) "True" else "False"
        )
        NativeBridge.setOption(
            "mupen64plus-txHiresFullAlphaChannel",
            if (useTexturePack) "True" else "False"
        )
        NativeBridge.setOption(
            "mupen64plus-EnableEnhancedHighResStorage",
            if (useTexturePack) "True" else "False"
        )
        NativeBridge.setOption(
            "mupen64plus-CorrectTexrectCoords",
            if (useTexturePack) "Auto" else "Off"
        )
        NativeBridge.setOption(
            "mupen64plus-GLideN64IniBehaviour",
            if (useTexturePack) "early" else "late"
        )
        NativeBridge.setOption(
            "mupen64plus-ForceDisableExtraMem",
            if (disableExpansionPak) "True" else "False"
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(logTag, "surfaceChanged ${width}x${height} format=$format")
        NativeBridge.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(logTag, "surfaceDestroyed")
        stopEmulation()
        NativeBridge.clearSurface()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.requestFocus()
        updateSurfaceLayoutForCurrentFrame()
    }

    private fun startEmulation() {
        running = true
        emulationThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            try {
                Log.i(logTag, "emulation thread boot start")
                val bootResult = NativeBridge.bootRomForPlay(rootPath, romPath)
                Log.i(logTag, "boot result=$bootResult")
                if (bootResult != "booted") {
                    running = false
                    runOnUiThread {
                        Toast.makeText(this, bootResult, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@Thread
                }

                updateSurfaceLayoutForCurrentFrame()
                emulationLoop()
            } finally {
                Log.i(logTag, "emulation thread shutting down")
                NativeBridge.shutdownSession()
            }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val button = mapGamepadKeyToN64Button(event.keyCode)
        if (button == 0 || !isGamepadSource(event.source)) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyButtonMask = keyButtonMask or button
            KeyEvent.ACTION_UP -> keyButtonMask = keyButtonMask and button.inv()
            else -> return super.dispatchKeyEvent(event)
        }

        pushControllerState()
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE || !isGamepadSource(event.source)) {
            return super.dispatchGenericMotionEvent(event)
        }

        val device = event.device ?: return super.dispatchGenericMotionEvent(event)

        analogStickX = scaleStickAxes(
            event = event,
            device = device,
            axes = controlsConfig.gamepadMapping.analogXAxes,
            positiveUp = false
        )
        analogStickY = scaleStickAxes(
            event = event,
            device = device,
            axes = controlsConfig.gamepadMapping.analogYAxes,
            positiveUp = true
        )
        axisButtonMask = mapGamepadAxesToN64ButtonMask(event, device)

        pushControllerState()
        return true
    }

    private fun emulationLoop() {
        while (running) {
            NativeBridge.runFrame(OPS_PER_CHUNK)
        }
    }

    private fun updateSurfaceLayoutForCurrentFrame() {
        val frameWidth = NativeBridge.getFrameWidth()
        val frameHeight = NativeBridge.getFrameHeight()
        if (frameWidth <= 0 || frameHeight <= 0) {
            return
        }

        runOnUiThread {
            val parentWidth = rootContainer.width
            val parentHeight = rootContainer.height
            if (parentWidth <= 0 || parentHeight <= 0) {
                rootContainer.post { updateSurfaceLayoutForCurrentFrame() }
                return@runOnUiThread
            }

            val scale = min(
                parentWidth.toFloat() / frameWidth.toFloat(),
                parentHeight.toFloat() / frameHeight.toFloat()
            )
            val targetWidth = (frameWidth * scale).roundToInt().coerceAtLeast(2)
            val targetHeight = (frameHeight * scale).roundToInt().coerceAtLeast(2)
            val currentParams = surfaceView.layoutParams as FrameLayout.LayoutParams

            if (currentParams.width == targetWidth &&
                currentParams.height == targetHeight &&
                currentParams.gravity == Gravity.CENTER) {
                return@runOnUiThread
            }

            Log.i(
                logTag,
                "updateSurfaceLayout frame=${frameWidth}x${frameHeight} target=${targetWidth}x${targetHeight} parent=${parentWidth}x${parentHeight}"
            )
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                targetWidth,
                targetHeight,
                Gravity.CENTER
            )
        }
    }

    private fun pushControllerState() {
        val gamepadMagnitude = (analogStickX * analogStickX) + (analogStickY * analogStickY)
        val touchMagnitude = (touchAnalogStickX * touchAnalogStickX) + (touchAnalogStickY * touchAnalogStickY)
        val stickX = if (touchMagnitude > gamepadMagnitude) touchAnalogStickX else analogStickX
        val stickY = if (touchMagnitude > gamepadMagnitude) touchAnalogStickY else analogStickY

        NativeBridge.setControllerState(
            keyButtonMask or axisButtonMask or touchButtonMask,
            stickX,
            stickY
        )
    }

    private fun mapGamepadKeyToN64Button(keyCode: Int): Int {
        var mask = 0
        controlsConfig.gamepadMapping.targetBindings.forEach { (target, bindings) ->
            if (bindings.any { it.type == BindingType.KEY && it.code == keyCode }) {
                mask = mask or target.buttonMask
            }
        }
        return mask
    }

    private fun mapGamepadAxesToN64ButtonMask(event: MotionEvent, device: InputDevice): Int {
        var mask = 0
        controlsConfig.gamepadMapping.targetBindings.forEach { (target, bindings) ->
            for (binding in bindings) {
                if (binding.type != BindingType.AXIS) continue
                val value = getAxisValue(event, device, binding.code)
                val pressed = if (binding.direction < 0) {
                    value <= -AXIS_BUTTON_THRESHOLD
                } else {
                    value >= AXIS_BUTTON_THRESHOLD
                }
                if (pressed) {
                    mask = mask or target.buttonMask
                    break
                }
            }
        }
        return mask
    }

    private fun isGamepadSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun scaleStickAxes(
        event: MotionEvent,
        device: InputDevice,
        axes: List<Int>,
        positiveUp: Boolean
    ): Int {
        val value = getAxisValue(event, device, axes)
        val scaled = (if (positiveUp) -value else value) * STICK_MAX
        return scaled.roundToInt().coerceIn(-STICK_MAX, STICK_MAX)
    }

    private fun getAxisValue(event: MotionEvent, device: InputDevice, axes: List<Int>): Float {
        var bestValue = 0f
        var bestMagnitude = 0f

        for (axis in axes) {
            val value = getAxisValue(event, device, axis)
            val magnitude = abs(value)
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestValue = value
            }
        }

        return bestValue
    }

    private fun getAxisValue(event: MotionEvent, device: InputDevice, vararg axes: Int): Float {
        var bestValue = 0f
        var bestMagnitude = 0f

        for (axis in axes) {
            val range = getMotionRange(device, axis, event.source) ?: continue
            val value = event.getAxisValue(axis)
            val centeredValue = if (abs(value) > range.flat) value else 0f
            val magnitude = abs(centeredValue)
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestValue = centeredValue
            }
        }

        return bestValue
    }

    private fun getMotionRange(device: InputDevice, axis: Int, source: Int): InputDevice.MotionRange? {
        return device.getMotionRange(axis, source)
            ?: device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
            ?: device.getMotionRange(axis)
    }

    private fun toggleTouchControls() {
        touchControlsVisible = !touchControlsVisible
        touchControlsView.visibility = if (touchControlsVisible) View.VISIBLE else View.GONE
        visibilityToggle.setImageResource(
            if (touchControlsVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
        )
        if (!touchControlsVisible) {
            touchButtonMask = 0
            touchAnalogStickX = 0
            touchAnalogStickY = 0
            pushControllerState()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        stopEmulation()
        super.onBackPressed()
    }

    override fun onDestroy() {
        keyButtonMask = 0
        axisButtonMask = 0
        touchButtonMask = 0
        analogStickX = 0
        analogStickY = 0
        touchAnalogStickX = 0
        touchAnalogStickY = 0
        pushControllerState()
        stopEmulation()
        NativeBridge.clearSurface()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROOT_PATH = "extra_root_path"
        private const val EXTRA_ROM_PATH = "extra_rom_path"
        private const val EXTRA_USE_TEXTURE_PACK = "extra_use_texture_pack"
        private const val EXTRA_DISABLE_EXPANSION_PAK = "extra_disable_expansion_pak"
        private const val EXTRA_ROM_PREFERENCE_KEY = "extra_rom_preference_key"
        private const val OPS_PER_CHUNK = 2_000_000
        private const val STICK_MAX = 80
        private const val AXIS_BUTTON_THRESHOLD = 0.45f

        fun launch(
            context: Context,
            rootPath: String,
            romPath: String,
            useTexturePack: Boolean = false,
            disableExpansionPak: Boolean = false,
            romPreferenceKey: String? = null
        ) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_ROOT_PATH, rootPath)
                    .putExtra(EXTRA_ROM_PATH, romPath)
                    .putExtra(EXTRA_USE_TEXTURE_PACK, useTexturePack)
                    .putExtra(EXTRA_DISABLE_EXPANSION_PAK, disableExpansionPak)
                    .putExtra(EXTRA_ROM_PREFERENCE_KEY, romPreferenceKey)
            )
        }
    }
}
