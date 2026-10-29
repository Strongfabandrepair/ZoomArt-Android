package com.example.zoomart

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted user preferences for ZoomArt (v0.7.0 title/settings flow).
 * All reads/writes go through a single SharedPreferences file so the title
 * page, settings screen, and canvas activity stay in sync.
 */
object AppSettings {
    private const val PREFS = "zoomart_settings"

    // ----- keys -----
    private const val KEY_BRUSH_PRESET = "brush_preset"          // index into BrushPresets.all
    private const val KEY_AUTO_HIDE = "auto_hide"                // panels auto-hide while drawing
    private const val KEY_FINGER_DRAWING = "finger_drawing"
    private const val KEY_AUTOSAVE_MS = "autosave_ms"            // autosave interval in ms
    private const val KEY_LAST_SESSION_TS = "last_session_ts"    // millis of last save (resume eligibility)

    /** Defaults. */
    private const val DEFAULT_BRUSH_PRESET = 0   // Ballpoint
    private const val DEFAULT_AUTO_HIDE = true
    private const val DEFAULT_AUTOSAVE_MS = 15_000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ----- brush default -----
    fun getBrushPresetIndex(context: Context): Int =
        prefs(context).getInt(KEY_BRUSH_PRESET, DEFAULT_BRUSH_PRESET)
            .coerceIn(0, com.example.zoomart.model.BrushPresets.all.lastIndex)

    fun putBrushPresetIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_BRUSH_PRESET, index).apply()
    }

    // ----- auto-hide -----
    fun getAutoHide(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_HIDE, DEFAULT_AUTO_HIDE)

    fun putAutoHide(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_HIDE, value).apply()
    }

    fun getFingerDrawing(context: Context): Boolean = prefs(context).getBoolean(KEY_FINGER_DRAWING, false)

    fun putFingerDrawing(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FINGER_DRAWING, value).apply()
    }

    // ----- autosave interval -----
    fun getAutosaveMs(context: Context): Long =
        prefs(context).getLong(KEY_AUTOSAVE_MS, DEFAULT_AUTOSAVE_MS).let {
            if (it == 0L) 0L else it.coerceIn(5_000L, 300_000L)
        }

    fun putAutosaveMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_AUTOSAVE_MS, ms).apply()
    }

    // ----- dock orientation -----
    private const val KEY_DOCK_ORIENTATION = "dock_orientation" // 0=horizontal, 1=vertical
    private const val DEFAULT_DOCK_ORIENTATION = 0  // preserve the familiar bottom dock

    fun getDockOrientation(context: Context): Int =
        prefs(context).getInt(KEY_DOCK_ORIENTATION, DEFAULT_DOCK_ORIENTATION)

    fun putDockOrientation(context: Context, orientation: Int) {
        prefs(context).edit().putInt(KEY_DOCK_ORIENTATION, orientation).apply()
    }

    // ----- resume eligibility -----
    /** Called by MainActivity on every successful save (quiet or not). */
    fun noteSessionSaved(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SESSION_TS, System.currentTimeMillis()).apply()
    }

    /** True when a recoverable session exists (a session file has been written at least once). */
    fun hasResumeableSession(context: Context): Boolean {
        val f = java.io.File(context.filesDir, "zoomart_session.json")
        val crash = java.io.File(context.filesDir, "zoomart_crash_session.json")
        val backup = java.io.File(context.filesDir, "zoomart_session.json.bak")
        return listOf(f, crash, backup).any { it.exists() && it.length() > 0 }
    }
}
