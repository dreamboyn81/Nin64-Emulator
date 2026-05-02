package com.izzy2lost.nin64

import android.content.Context

data class DisplayOptions(
    val aspect: String,
    val resolutionFactor: String,
)

object DisplayOptionsRepository {
    const val PREFS_NAME = "nin64_prefs"
    const val PREF_ASPECT = "mupen64plus-aspect"
    const val PREF_RES_FACTOR = "mupen64plus-EnableNativeResFactor"
    const val DEFAULT_ASPECT = "4:3"
    const val DEFAULT_RES_FACTOR = "2"

    fun load(context: Context, romKey: String?): DisplayOptions {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return resolve(
            globalAspect = prefs.getString(PREF_ASPECT, null),
            globalResolutionFactor = prefs.getString(PREF_RES_FACTOR, null),
            perGameAspect = romKey?.let { prefs.getString(perGameAspectKey(it), null) },
            perGameResolutionFactor = romKey?.let { prefs.getString(perGameResolutionFactorKey(it), null) },
        )
    }

    fun resolve(
        globalAspect: String?,
        globalResolutionFactor: String?,
        perGameAspect: String?,
        perGameResolutionFactor: String?,
    ): DisplayOptions =
        DisplayOptions(
            aspect = perGameAspect ?: globalAspect ?: DEFAULT_ASPECT,
            resolutionFactor = perGameResolutionFactor ?: globalResolutionFactor ?: DEFAULT_RES_FACTOR,
        )

    fun nativeResolutionFactorForPreference(preference: String, surfaceHeight: Int): Int {
        val defaultFactor = DEFAULT_RES_FACTOR.toInt()
        return preference
            .takeUnless { it.isEmpty() || it == "0" }
            ?.toIntOrNull()
            ?.coerceIn(1, 9)
            ?: defaultFactor
    }

    fun resetPerGame(context: Context, romKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(perGameAspectKey(romKey))
            .remove(perGameResolutionFactorKey(romKey))
            .apply()
    }

    fun perGameAspectKey(romKey: String): String =
        "per_game.$romKey.$PREF_ASPECT"

    fun perGameResolutionFactorKey(romKey: String): String =
        "per_game.$romKey.$PREF_RES_FACTOR"
}
