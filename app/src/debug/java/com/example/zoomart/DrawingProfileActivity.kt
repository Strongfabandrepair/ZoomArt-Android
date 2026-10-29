package com.example.zoomart

import android.app.Activity
import android.os.Bundle
import com.example.zoomart.ui.ZoomableCanvasView

/** Debug-only isolated hardware-rendering host. Never opens the user's session. */
class DrawingProfileActivity : Activity() {
    lateinit var canvas: ZoomableCanvasView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);canvas=ZoomableCanvasView(this);setContentView(canvas)
    }
}
