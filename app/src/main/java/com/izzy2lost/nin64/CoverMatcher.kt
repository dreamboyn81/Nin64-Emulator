package com.izzy2lost.nin64

import android.content.Context

/**
 * Caches the cover-art index from the GitHub repo (one .png filename per line) and
 * fuzzy-matches a ROM filename to a cover. The index is downloaded once per day.
 *
 * Match order:
 *   1. Exact normalized match (strip region/version tags, lowercase, alphanumeric only)
 *   2. Prefix/substring (handles subtitles added or removed)
 *   3. Greedy LCS similarity ≥ 0.75 (catches minor spelling differences)
 */
internal object CoverMatcher {

    private const val PREFS = "cover_index"
    private const val KEY_DATA = "data"
    private const val KEY_TS = "ts"
    private const val TTL_MS = 24L * 3600_000

    @Volatile private var normToFile: Map<String, String> = emptyMap()
    @Volatile private var ready = false

    fun init(context: Context, indexUrl: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DATA, null)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_TS, 0)

        if (cached != null) {
            normToFile = buildIndex(cached)
            ready = true
        }

        if (cached == null || age > TTL_MS) {
            Thread {
                try {
                    val text = java.net.URL(indexUrl).readText()
                    if (text.isNotBlank()) {
                        prefs.edit()
                            .putString(KEY_DATA, text)
                            .putLong(KEY_TS, System.currentTimeMillis())
                            .apply()
                        normToFile = buildIndex(text)
                        ready = true
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    /** Best-matching cover filename for the ROM, or null if none. */
    fun resolve(romFileName: String): String? {
        if (!ready) return null
        val normRom = normalize(romFileName.substringBeforeLast('.'))
        if (normRom.isEmpty()) return null

        normToFile[normRom]?.let { return it }

        val prefixHits = normToFile.entries.filter { (k, _) ->
            k.startsWith(normRom) || normRom.startsWith(k)
        }
        if (prefixHits.isNotEmpty()) {
            return prefixHits.minBy { (k, _) -> kotlin.math.abs(k.length - normRom.length) }.value
        }

        return normToFile.entries
            .map { (k, v) -> v to similarity(normRom, k) }
            .filter { (_, s) -> s >= 0.75f }
            .maxByOrNull { (_, s) -> s }
            ?.first
    }

    private fun buildIndex(text: String): Map<String, String> =
        text.lines()
            .filter { it.endsWith(".png") }
            .associateBy { normalize(it.substringBeforeLast('.')) }

    private fun normalize(name: String): String =
        name
            .replace(Regex("""\s*\([^)]*\)"""), "")
            .replace(Regex("""\s*\[[^\]]*\]"""), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        var i = 0; var j = 0; var common = 0
        while (i < a.length && j < b.length) {
            when {
                a[i] == b[j] -> { common++; i++; j++ }
                a.length - i > b.length - j -> i++
                else -> j++
            }
        }
        return 2f * common / (a.length + b.length)
    }
}
