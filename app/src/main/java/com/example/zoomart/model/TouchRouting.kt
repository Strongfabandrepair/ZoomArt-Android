package com.example.zoomart.model

/**
 * P1.1 (spec CORE#1): pen-vs-finger input routing.
 *
 * Pure, JVM-testable classification of MotionEvent tool types. The constants
 * mirror `android.view.MotionEvent.TOOL_TYPE_*` by value (UNKNOWN=0, FINGER=1,
 * STYLUS=2, MOUSE=3, ERASER=4) so the model stays free of Android imports;
 * AppSmokeTest asserts the parity under Robolectric.
 *
 * Routing rules (ui/ZoomableCanvasView):
 *  - STYLUS (and ERASER) run the active tool: draw / erase / select;
 *    TOOL_TYPE_ERASER — the S Pen's eraser end — forces the erase tool.
 *  - FINGER never draws: one finger pans the camera, two fingers pinch
 *    pan+zoom, finger flings carry inertia; double-tap pins come from the
 *    pen only.
 */
object TouchRouting {
    const val TOOL_TYPE_UNKNOWN = 0
    const val TOOL_TYPE_FINGER = 1
    const val TOOL_TYPE_STYLUS = 2
    const val TOOL_TYPE_MOUSE = 3
    const val TOOL_TYPE_ERASER = 4

    /** Pen family: anything that runs the active tool (draw/erase/select). */
    fun isPen(toolType: Int): Boolean =
        toolType == TOOL_TYPE_STYLUS || toolType == TOOL_TYPE_ERASER

    /** The S Pen eraser end maps to the erase tool regardless of selection. */
    fun forcesErase(toolType: Int): Boolean = toolType == TOOL_TYPE_ERASER
}
