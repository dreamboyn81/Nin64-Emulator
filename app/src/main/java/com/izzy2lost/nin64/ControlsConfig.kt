package com.izzy2lost.nin64

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject

enum class N64Target(val label: String, val buttonMask: Int) {
    ANALOG_STICK("Analog Stick", 0x0000),
    DPAD_UP("D-Pad Up", 0x0008),
    DPAD_DOWN("D-Pad Down", 0x0004),
    DPAD_LEFT("D-Pad Left", 0x0002),
    DPAD_RIGHT("D-Pad Right", 0x0001),
    START("Start", 0x0010),
    A_BUTTON("A", 0x0080),
    B_BUTTON("B", 0x0040),
    L_TRIGGER("L", 0x2000),
    R_TRIGGER("R", 0x1000),
    Z_TRIGGER("Z", 0x0020),
    C_UP("C-Up", 0x0800),
    C_DOWN("C-Down", 0x0400),
    C_LEFT("C-Left", 0x0200),
    C_RIGHT("C-Right", 0x0100);

    companion object {
        fun fromName(name: String?): N64Target? =
            enumValues<N64Target>().firstOrNull { it.name == name }
    }
}

data class TouchControl(
    val id: String,
    val target: N64Target,
    val x: Float,
    val y: Float,
    val size: Float,
    val opacity: Float,
    val visible: Boolean,
)

data class TouchLayout(val controls: List<TouchControl>) {
    fun toJson(): String {
        val controlsJson = JSONArray()
        controls.forEach { control ->
            controlsJson.put(
                JSONObject()
                    .put("id", control.id)
                    .put("target", control.target.name)
                    .put("x", control.x.toDouble())
                    .put("y", control.y.toDouble())
                    .put("size", control.size.toDouble())
                    .put("opacity", control.opacity.toDouble())
                    .put("visible", control.visible)
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("controls", controlsJson)
            .toString()
    }

    companion object {
        fun default(): TouchLayout = TouchLayout(
            listOf(
                TouchControl("l_trigger",  N64Target.L_TRIGGER,    0.12f, 0.12f, 0.13f, 0.65f, true),
                TouchControl("r_trigger",  N64Target.R_TRIGGER,    0.88f, 0.12f, 0.13f, 0.65f, true),
                TouchControl("dpad_up",    N64Target.DPAD_UP,      0.10f, 0.41f, 0.09f, 0.65f, true),
                TouchControl("dpad_left",  N64Target.DPAD_LEFT,    0.05f, 0.50f, 0.09f, 0.65f, true),
                TouchControl("dpad_right", N64Target.DPAD_RIGHT,   0.15f, 0.50f, 0.09f, 0.65f, true),
                TouchControl("dpad_down",  N64Target.DPAD_DOWN,    0.10f, 0.59f, 0.09f, 0.65f, true),
                TouchControl("analog",     N64Target.ANALOG_STICK, 0.19f, 0.74f, 0.22f, 0.65f, true),
                TouchControl("start",      N64Target.START,        0.50f, 0.88f, 0.08f, 0.68f, true),
                TouchControl("z_trigger",  N64Target.Z_TRIGGER,    0.76f, 0.85f, 0.10f, 0.65f, true),
                TouchControl("b_button",   N64Target.B_BUTTON,     0.77f, 0.63f, 0.10f, 0.68f, true),
                TouchControl("a_button",   N64Target.A_BUTTON,     0.82f, 0.74f, 0.12f, 0.68f, true),
                TouchControl("c_up",       N64Target.C_UP,         0.89f, 0.43f, 0.08f, 0.65f, true),
                TouchControl("c_left",     N64Target.C_LEFT,       0.84f, 0.52f, 0.08f, 0.65f, true),
                TouchControl("c_right",    N64Target.C_RIGHT,      0.94f, 0.52f, 0.08f, 0.65f, true),
                TouchControl("c_down",     N64Target.C_DOWN,       0.89f, 0.61f, 0.08f, 0.65f, true),
            )
        )

        fun fromJson(json: String?): TouchLayout {
            if (json.isNullOrBlank()) return default()
            return runCatching {
                val root = JSONObject(json)
                val controlsJson = root.optJSONArray("controls") ?: return@runCatching default()
                val defaultById = default().controls.associateBy { it.id }
                val controls = mutableListOf<TouchControl>()

                for (index in 0 until controlsJson.length()) {
                    val item = controlsJson.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val fallback = defaultById[id]
                    val target = N64Target.fromName(item.optString("target")) ?: fallback?.target ?: continue
                    controls += TouchControl(
                        id = id,
                        target = target,
                        x = item.optDouble("x", fallback?.x?.toDouble() ?: 0.5).toFloat().coerceIn(0f, 1f),
                        y = item.optDouble("y", fallback?.y?.toDouble() ?: 0.5).toFloat().coerceIn(0f, 1f),
                        size = item.optDouble("size", fallback?.size?.toDouble() ?: 0.1).toFloat().coerceIn(0.04f, 0.35f),
                        opacity = item.optDouble("opacity", fallback?.opacity?.toDouble() ?: 0.6).toFloat().coerceIn(0.15f, 1f),
                        visible = item.optBoolean("visible", fallback?.visible ?: true),
                    )
                }

                val loadedById = controls.associateBy { it.id }
                val merged = default().controls.map { loadedById[it.id] ?: it }
                TouchLayout(merged)
            }.getOrElse { default() }
        }
    }
}

enum class BindingType {
    KEY,
    AXIS,
}

data class GamepadBinding(
    val type: BindingType,
    val code: Int,
    val direction: Int = 0,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("type", type.name)
            .put("code", code)
            .put("direction", direction)

    companion object {
        fun key(keyCode: Int): GamepadBinding = GamepadBinding(BindingType.KEY, keyCode)

        fun axis(axisCode: Int, direction: Int): GamepadBinding =
            GamepadBinding(BindingType.AXIS, axisCode, if (direction < 0) -1 else 1)

        fun fromJson(json: JSONObject): GamepadBinding? {
            val type = runCatching { BindingType.valueOf(json.optString("type")) }.getOrNull()
                ?: return null
            val code = json.optInt("code", Int.MIN_VALUE)
            if (code == Int.MIN_VALUE) return null
            val direction = json.optInt("direction", 0).coerceIn(-1, 1)
            return GamepadBinding(type, code, direction)
        }
    }
}

data class GamepadMapping(
    val analogXAxes: List<Int>,
    val analogYAxes: List<Int>,
    val targetBindings: Map<N64Target, List<GamepadBinding>>,
) {
    fun toJson(): String {
        val bindingsJson = JSONObject()
        targetBindings.forEach { (target, bindings) ->
            val values = JSONArray()
            bindings.forEach { values.put(it.toJson()) }
            bindingsJson.put(target.name, values)
        }

        return JSONObject()
            .put("version", 1)
            .put("analogXAxes", analogXAxes.toJsonArray())
            .put("analogYAxes", analogYAxes.toJsonArray())
            .put("bindings", bindingsJson)
            .toString()
    }

    fun withBinding(target: N64Target, binding: GamepadBinding): GamepadMapping {
        if (target == N64Target.ANALOG_STICK) {
            return this
        }
        val updated = targetBindings.toMutableMap()
        updated[target] = listOf(binding)
        return copy(targetBindings = updated)
    }

    fun withAnalogAxes(xAxis: Int, yAxis: Int): GamepadMapping =
        copy(analogXAxes = listOf(xAxis), analogYAxes = listOf(yAxis))

    companion object {
        fun default(): GamepadMapping = GamepadMapping(
            analogXAxes = listOf(MotionEvent.AXIS_X),
            analogYAxes = listOf(MotionEvent.AXIS_Y),
            targetBindings = mapOf(
                N64Target.DPAD_UP to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_DPAD_UP),
                    GamepadBinding.axis(MotionEvent.AXIS_HAT_Y, -1),
                ),
                N64Target.DPAD_DOWN to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_DPAD_DOWN),
                    GamepadBinding.axis(MotionEvent.AXIS_HAT_Y, 1),
                ),
                N64Target.DPAD_LEFT to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_DPAD_LEFT),
                    GamepadBinding.axis(MotionEvent.AXIS_HAT_X, -1),
                ),
                N64Target.DPAD_RIGHT to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_DPAD_RIGHT),
                    GamepadBinding.axis(MotionEvent.AXIS_HAT_X, 1),
                ),
                N64Target.START to listOf(GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_START)),
                N64Target.A_BUTTON to listOf(GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_A)),
                N64Target.B_BUTTON to listOf(GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_B)),
                N64Target.L_TRIGGER to listOf(GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_L1)),
                N64Target.R_TRIGGER to listOf(GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_R1)),
                N64Target.Z_TRIGGER to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_L2),
                    GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_Z),
                    GamepadBinding.axis(MotionEvent.AXIS_LTRIGGER, 1),
                    GamepadBinding.axis(MotionEvent.AXIS_BRAKE, 1),
                ),
                N64Target.C_UP to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_X),
                    GamepadBinding.axis(MotionEvent.AXIS_RZ, -1),
                    GamepadBinding.axis(MotionEvent.AXIS_RY, -1),
                ),
                N64Target.C_DOWN to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_THUMBR),
                    GamepadBinding.axis(MotionEvent.AXIS_RZ, 1),
                    GamepadBinding.axis(MotionEvent.AXIS_RY, 1),
                ),
                N64Target.C_LEFT to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_Y),
                    GamepadBinding.axis(MotionEvent.AXIS_Z, -1),
                    GamepadBinding.axis(MotionEvent.AXIS_RX, -1),
                ),
                N64Target.C_RIGHT to listOf(
                    GamepadBinding.key(KeyEvent.KEYCODE_BUTTON_R2),
                    GamepadBinding.axis(MotionEvent.AXIS_Z, 1),
                    GamepadBinding.axis(MotionEvent.AXIS_RX, 1),
                ),
            )
        )

        fun fromJson(json: String?): GamepadMapping {
            if (json.isNullOrBlank()) return default()
            return runCatching {
                val root = JSONObject(json)
                val default = default()
                val bindingsJson = root.optJSONObject("bindings") ?: JSONObject()
                val bindings = mutableMapOf<N64Target, List<GamepadBinding>>()

                enumValues<N64Target>()
                    .filter { it != N64Target.ANALOG_STICK }
                    .forEach { target ->
                        val items = bindingsJson.optJSONArray(target.name)
                        if (items == null) {
                            bindings[target] = default.targetBindings[target].orEmpty()
                        } else {
                            val parsed = mutableListOf<GamepadBinding>()
                            for (index in 0 until items.length()) {
                                items.optJSONObject(index)?.let(GamepadBinding.Companion::fromJson)?.let(parsed::add)
                            }
                            bindings[target] = parsed
                        }
                    }

                GamepadMapping(
                    analogXAxes = root.optJSONArray("analogXAxes").toIntList(default.analogXAxes),
                    analogYAxes = root.optJSONArray("analogYAxes").toIntList(default.analogYAxes),
                    targetBindings = bindings,
                )
            }.getOrElse { default() }
        }
    }
}

data class ControlsConfig(
    val touchLayout: TouchLayout,
    val gamepadMapping: GamepadMapping,
)

object ControlsRepository {
    private const val PREFS_NAME = "nin64_prefs"
    private const val GLOBAL_TOUCH_LAYOUT = "controls.global.touch_layout"
    private const val GLOBAL_GAMEPAD_MAPPING = "controls.global.gamepad_mapping"

    fun load(context: Context, romKey: String?): ControlsConfig =
        resolve(
            globalTouchJson = prefs(context).getString(GLOBAL_TOUCH_LAYOUT, null),
            globalGamepadJson = prefs(context).getString(GLOBAL_GAMEPAD_MAPPING, null),
            perGameTouchJson = romKey?.let { prefs(context).getString(perGameTouchLayoutKey(it), null) },
            perGameGamepadJson = romKey?.let { prefs(context).getString(perGameGamepadMappingKey(it), null) },
        )

    fun resolve(
        globalTouchJson: String?,
        globalGamepadJson: String?,
        perGameTouchJson: String?,
        perGameGamepadJson: String?,
    ): ControlsConfig =
        ControlsConfig(
            touchLayout = TouchLayout.fromJson(perGameTouchJson ?: globalTouchJson),
            gamepadMapping = GamepadMapping.fromJson(perGameGamepadJson ?: globalGamepadJson),
        )

    fun loadTouchLayout(context: Context, romKey: String?): TouchLayout =
        load(context, romKey).touchLayout

    fun saveTouchLayout(context: Context, romKey: String?, layout: TouchLayout) {
        prefs(context).edit()
            .putString(if (romKey == null) GLOBAL_TOUCH_LAYOUT else perGameTouchLayoutKey(romKey), layout.toJson())
            .apply()
    }

    fun loadGamepadMapping(context: Context, romKey: String?): GamepadMapping =
        load(context, romKey).gamepadMapping

    fun saveGamepadMapping(context: Context, romKey: String?, mapping: GamepadMapping) {
        prefs(context).edit()
            .putString(if (romKey == null) GLOBAL_GAMEPAD_MAPPING else perGameGamepadMappingKey(romKey), mapping.toJson())
            .apply()
    }

    fun resetGlobal(context: Context) {
        prefs(context).edit()
            .remove(GLOBAL_TOUCH_LAYOUT)
            .remove(GLOBAL_GAMEPAD_MAPPING)
            .apply()
    }

    fun resetPerGame(context: Context, romKey: String) {
        prefs(context).edit()
            .remove(perGameTouchLayoutKey(romKey))
            .remove(perGameGamepadMappingKey(romKey))
            .apply()
    }

    fun perGameTouchLayoutKey(romKey: String): String =
        "per_game.$romKey.touch_layout"

    fun perGameGamepadMappingKey(romKey: String): String =
        "per_game.$romKey.gamepad_mapping"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

private fun List<Int>.toJsonArray(): JSONArray {
    val json = JSONArray()
    forEach(json::put)
    return json
}

private fun JSONArray?.toIntList(default: List<Int>): List<Int> {
    if (this == null) return default
    val values = mutableListOf<Int>()
    for (index in 0 until length()) {
        values += optInt(index)
    }
    return values.ifEmpty { default }
}
