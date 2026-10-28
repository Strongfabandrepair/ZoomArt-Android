package com.example.zoomart.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import android.widget.Toast
import com.example.zoomart.model.Bookmark
import com.example.zoomart.model.Camera
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.ImageDisplayFilter
import com.example.zoomart.model.LazyBrush
import com.example.zoomart.model.LayerBlend
import com.example.zoomart.model.LayerData
import com.example.zoomart.model.LevelTree
import com.example.zoomart.model.PathSink
import com.example.zoomart.model.PlacedImage
import com.example.zoomart.model.ShapeData
import com.example.zoomart.model.ShapeGeometry
import com.example.zoomart.model.SmudgeData
import com.example.zoomart.model.StampData
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokeOutline
import com.example.zoomart.model.StrokePoint
import com.example.zoomart.model.TouchRouting
import com.example.zoomart.model.ZoomLevel
import java.io.File
import kotlin.math.abs

/**
 * The infinite zoom canvas (P0: nested zoom levels; P1: pen/finger routing).
 *
 * Touch model (spec CORE#1):
 *   - PEN (STYLUS, and TOOL_TYPE_ERASER -> the erase tool) runs the active
 *     tool: DRAW draws a pressure-sensitive stroke in the CURRENT level;
 *     ERASE carves strokes of the current level; SELECT drags a marquee
 *     across every VISIBLE level (P1.3b) then moves/scales the selection.
 *   - FINGER never draws: one finger pans the camera (with fling inertia);
 *     two fingers pan + pinch-zoom as before. Double-tap bookmark pins and
 *     eyedropper sampling respond to the pen only.
 *   - Palm rejection: a finger landing while the pen works is ignored
 *     until it lifts; the pen landing mid-pinch cancels the pinch.
 *   - Crossing the 100x threshold promotes the camera into a child level
 *     at the pinch focus; below 1x it demotes to the parent.
 *
 * Rendering (drawScene) is level-composed: the visible level set is the
 * parent chain root->camera plus the camera's children; each level pass
 * applies that level's composed matrix (Camera.transformTo), culls through
 * the per-level index buckets, and draws images then strokes layer by layer.
 * Deeper ancestors whose on-screen scale degenerates are skipped — their
 * content is sub-pixel at that zoom by construction.
 */
class ZoomableCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Tool { DRAW, EYEDROPPER, ERASE, SELECT, SHAPE, SMUDGE }

    /** v0.7.0: effective tool — erase-as-modifier means DRAW with erase=true acts as ERASE. */
    val effectiveTool: Tool get() =
        if (tool == Tool.DRAW && viewModel.currentBrush.erase) Tool.ERASE else tool

    /** v0.7.0: canvas rotation in degrees, applied in onDraw. Two-finger rotate gesture. */
    var canvasRotationDegrees: Float = 0f
        private set

    /** Last two-finger angle for rotation delta tracking. */
    private var lastRotateAngle: Float? = null

    val viewModel = DrawingViewModel()

    var tool: Tool = Tool.DRAW
    var onStateChanged: (() -> Unit)? = null
    var onEyedrop: ((Int) -> Unit)? = null
    var onCameraLevelChanged: ((Int) -> Unit)? = null

    /**
     * U-overhaul (auto-hide pipeline): fired with TRUE when a stylus tool
     * gesture or a pinch begins, FALSE when it ends. The OverlayLayer
     * listens and spring-slides its floating panels out of the way while
     * the user is drawing, restoring them ~600 ms after the last gesture.
     * Finger pans never fire this (panels stay put while navigating).
     */
    var onCanvasActivityChanged: ((Boolean) -> Unit)? = null

    /**
     * U-overhaul (decoupling rule): panels REQUEST scene repaints through
     * this semantic entry point after document mutations — panel code never
     * calls invalidate() itself, so the canvas stays the only owner of
     * scene invalidation and panel UI/animation work never redraws it.
     */
    fun requestSceneRepaint() { invalidate() }

    private val strokeFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val dashPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val imagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }

    private val bookmarkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0xFFE5484D.toInt()
    }

    private val selectionPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF2E66F6.toInt()
    }

    private val marqueePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF2E66F6.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val readoutPaint = Paint().apply {
        isAntiAlias = true
        // v0.7.0: neutral text on the crisp dark chip (measured chrome)
        color = 0xFFECEDEF.toInt()
        textSize = 14f * resources.displayMetrics.density
    }

    /** v0.7.0: the LEVEL NUMBER renders in the amber accent (spec 6). */
    private val readoutAccentPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFAD01.toInt()
        textSize = 14f * resources.displayMetrics.density
    }

    /** v0.7.0: the HUD chip's near-opaque neutral fill (spec 6). */
    private val hudChipPaint = Paint().apply {
        isAntiAlias = true
        color = 0xE616171B.toInt()
    }
    private val hudBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x1FFFFFFF.toInt()
    }

    private val scratchPath = Path()
    private val imageCache = HashMap<String, Bitmap?>()

    // ----- P3 render paints -----
    private val shapeFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val shapeOutlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val stampPaint = Paint().apply { isAntiAlias = true }
    private val lazyPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x9944494F.toInt()
    }
    private val lazyDotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0xE62E66F6.toInt()
    }
    /** P4.7: eraser size preview — a soft ink disc with a crisp ring. */
    private val eraserPreviewFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0x1F16181D
    }
    private val eraserPreviewRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xB316181D.toInt()
    }
    private val handleFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /**
     * P2.3: paint used ONLY as the saveLayer composite paint for non-NORMAL
     * layers (alpha 255; the blend mode does the work). Reused across
     * frames — applyBlendTo resets the mode each time.
     */
    private val layerBlendPaint = Paint()

    /** P2.3: maps a LayerBlend onto the platform blend API (29+ BlendMode,
     *  26-28 PorterDuff via xfermode — MULTIPLY/SCREEN/OVERLAY exist in both). */
    private fun applyBlendTo(paint: Paint, blend: LayerBlend) {
        if (Build.VERSION.SDK_INT >= 29) {
            paint.blendMode = when (blend) {
                LayerBlend.NORMAL -> BlendMode.SRC_OVER
                LayerBlend.MULTIPLY -> BlendMode.MULTIPLY
                LayerBlend.SCREEN -> BlendMode.SCREEN
                LayerBlend.OVERLAY -> BlendMode.OVERLAY
            }
        } else {
            paint.xfermode = when (blend) {
                LayerBlend.NORMAL -> null
                LayerBlend.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                LayerBlend.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                LayerBlend.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            }
        }
    }

    private class AndroidPathSink(private val path: Path) : PathSink {
        override fun moveTo(x: Float, y: Float) { path.moveTo(x, y) }
        override fun lineTo(x: Float, y: Float) { path.lineTo(x, y) }
        override fun close() { path.close() }
    }

    // ----- gesture state -----

    /** Levels whose on-screen scale degenerates are skipped (sub-pixel). */
    private val minRenderableScale = 1e-7f
    private val maxRenderableScale = 1e30f

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scroller = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0

    private var marqueeWorld: FloatArray? = null
    private var isTransformingSelection = false
    private var lastSelScreenX = 0f
    private var lastSelScreenY = 0f
    private var selectionGesture = false

    // ----- P3 gesture state -----
    /** SELECT corner-handle resize: which corner (0..3) is dragged, -1 none. */
    private var resizingCorner = -1
    /** The OPPOSITE corner (resize pivot) in SCREEN px. */
    private var resizePivotScreenX = 0f
    private var resizePivotScreenY = 0f
    /** Distance from the pivot to the pen at the last resize step. */
    private var lastResizePenDist = 0f

    /** Last pen position in SCREEN px (lazy overlay anchor). */
    private var lastPenScreenX = 0f
    private var lastPenScreenY = 0f

    /**
     * P4.7: last pen position for the ERASER size preview ring.
     * -1 = the pen has never touched the canvas, so nothing is drawn.
     */
    private var lastEraserScreenX = -1f
    private var lastEraserScreenY = -1f

    /** Corner-handle hit target half-extent in screen px (>= 40px square). */
    private val handleTouchHalfPx = 24f
    private val handleVisualHalfPx = 12f

    // ----- P4.5: demo tour state -----
    /** True while the nested demo flight is running; any touch cancels. */
    var demoActive = false
        private set
    private var demoLegIndex = 0
    private var demoTargets: List<ZoomLevel>? = null
    private var demoAnimator: ValueAnimator? = null

    // ----- P4.2: two-finger pinch rotation of the selection -----
    /** Inter-pointer angle of the current SELECT pinch, or null. */
    private var pinchAngle: Float? = null

    /**
     * P3.3: one full-scene snapshot rendered at smudge-gesture START (the
     * spec's allowed single readback); the gesture samples carried colors
     * from it, so a smudge never feeds on its own stamps. Recycled on end.
     */
    private var smudgeSnapshot: Bitmap? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                onCanvasActivityChanged?.invoke(true) // auto-hide while pinching
                if (tool == Tool.SELECT && !viewModel.document.selection.isEmpty) {
                    selectionGesture = true
                    viewModel.document.selection.beginTransform()
                    pinchAngle = null // P4.2: rotation starts fresh per pinch
                    return true
                }
                selectionGesture = false
                pinchAngle = null
                viewModel.inProgress = null       // second finger aborts strokes
                viewModel.inProgressShape = null   // ...and P3 shape/smudge gestures
                viewModel.inProgressSmudge = null
                scroller.forceFinished(true)      // gesture overrides inertia
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (selectionGesture) {
                    viewModel.document.selection.scaleBy(detector.scaleFactor)
                    invalidate()
                    return true
                }
                val cam = viewModel.camera
                cam.panBy(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                cam.zoomAt(detector.focusX, detector.focusY, cam.scale * detector.scaleFactor)
                if (cam.applyLevelTransitions(detector.focusX, detector.focusY)) {
                    Log.i(TAG, "camera level -> depth " + cam.level.depth +
                        " (scale " + cam.scale + ", zoom " + cam.rootMagnification() + ")")
                    onCameraLevelChanged?.invoke(cam.level.depth)
                }
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onCanvasActivityChanged?.invoke(false) // pinch ended
                if (selectionGesture) {
                    selectionGesture = false
                    pinchAngle = null
                    viewModel.document.selection.endTransform()
                }
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Pins now use long-press, not double-tap, which was too sensitive
                // and fired during normal drawing.
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (tool == Tool.EYEDROPPER && isPenEvent(e)) {
                    val color = samplePixel(e.x.toInt(), e.y.toInt())
                    tool = Tool.DRAW
                    onEyedrop?.invoke(color)
                    invalidate()
                    return true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // P1.1: pen gestures are strokes, never flings; multi-pointer
                // motion belongs to the pinch. FINGER pans keep their inertia.
                if (isPenEvent(e2) || e2.pointerCount >= 2) return false
                lastFlingX = 0
                lastFlingY = 0
                scroller.fling(
                    0, 0, -velocityX.toInt(), -velocityY.toInt(),
                    -1_000_000, 1_000_000, -1_000_000, 1_000_000,
                )
                postInvalidateOnAnimation()
                return true
            }
        },
    )

    private var isDrawing = false

    // ----- P1.1 input-routing state -----
    private var penGesture = false             // active tool gesture came from stylus/eraser
    private var gestureTool: Tool = Tool.DRAW  // tool captured at gesture start
    private var fingerPanning = false         // a single finger is panning the camera
    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private val ignoredPalmIds = HashSet<Int>() // fingers ignored while the pen works

    private fun isPenEvent(e: MotionEvent, index: Int = 0): Boolean =
        TouchRouting.isPen(e.getToolType(index))

    /** Begins a pen-driven tool gesture (draw/erase/select) at (x, y). */
    private fun startPenGesture(toolType: Int, x: Float, y: Float, pressure: Float) {
        if (scaleDetector.isInProgress) {
            // the pen interrupts an in-flight pinch: end it cleanly
            val cancel = MotionEvent.obtain(
                0L, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
                x, y, 0f, 0f, 0, 1f, 1f, 0, 0,
            )
            scaleDetector.onTouchEvent(cancel)
            cancel.recycle()
        }
        scroller.forceFinished(true)
        gestureTool = if (TouchRouting.forcesErase(toolType)) Tool.ERASE else effectiveTool
        if (gestureTool != Tool.EYEDROPPER && viewModel.document.activeLayer.locked) {
            // P2.2: locked layers reject new strokes, the eraser and
            // marquee selection; visible content stays untouched.
            Toast.makeText(context, "Layer is locked", Toast.LENGTH_SHORT).show()
            penGesture = false // no tool gesture runs
            return
        }
        val cam = viewModel.camera
        when (gestureTool) {
            Tool.DRAW -> {
                // P3.2: DRAW always enters through the SCREEN-space API so
                // the lazy rope (screen px) can feed the committed points.
                viewModel.beginStrokeAtScreen(x, y, pressure)
                isDrawing = true
            }
            Tool.ERASE -> {
                viewModel.beginErase(cam.screenToLocalX(x), cam.screenToLocalY(y))
                lastEraserScreenX = x
                lastEraserScreenY = y
                isDrawing = true
            }
            Tool.SHAPE -> {
                // P3.1: pen drags parametric shapes (fingers still pan).
                viewModel.beginShapeGesture(cam.screenToLocalX(x), cam.screenToLocalY(y))
                isDrawing = true
            }
            Tool.SMUDGE -> {
                // P3.3: one full-scene snapshot at gesture start feeds the
                // carried-color samples (sampler builds it lazily, once).
                viewModel.beginSmudge(x, y, pressure, smudgeSampler())
                isDrawing = true
            }
            Tool.SELECT -> {
                val wx = cam.screenToLocalX(x)
                val wy = cam.screenToLocalY(y)
                lastSelScreenX = x
                lastSelScreenY = y
                val b = viewModel.document.selection.bbox()
                val pad = 20f / cam.scale
                if (b != null && hitResizeHandle(x, y, b)) {
                    // P3.1: corner-handle resize — uniform scale about the
                    // OPPOSITE corner, one undo step for the whole gesture.
                    viewModel.document.selection.beginTransform()
                    isTransformingSelection = true
                } else if (b != null && wx >= b[0] - pad && wx <= b[2] + pad &&
                    wy >= b[1] - pad && wy <= b[3] + pad
                ) {
                    viewModel.document.selection.beginTransform()
                    isTransformingSelection = true
                } else {
                    // P3.1: TAP a shape to select it (topmost active-layer
                    // shape under the pen, any visible level); dragging the
                    // tap then MOVES the shape. Empty space starts a marquee.
                    val hit = viewModel.document.selection.selectShapeAt(
                        cam, viewModel.visibleLevels(), wx, wy)
                    if (hit != null) {
                        viewModel.document.selection.beginTransform()
                        isTransformingSelection = true
                    } else {
                        // P4.2: then an IMAGE of the active layer (LOCKED
                        // images never select) — dragging the tap moves it,
                        // the corner handles resize it, a two-finger pinch
                        // rotates + scales it. Still nothing -> marquee.
                        val img = viewModel.document.selection.selectImageAt(
                            cam, viewModel.visibleLevels(), wx, wy)
                        if (img != null) {
                            viewModel.document.selection.beginTransform()
                            isTransformingSelection = true
                        } else {
                            isTransformingSelection = false
                            marqueeWorld = floatArrayOf(wx, wy, wx, wy)
                        }
                    }
                }
                isDrawing = true
            }
            Tool.EYEDROPPER -> isDrawing = false // sampled on tap-confirmed
        }
        penGesture = true
        if (isDrawing) onCanvasActivityChanged?.invoke(true) // stroke began
    }

    /**
     * True when (screenX, screenY) sits within a corner handle's touch
     * target of the selection bbox [b] (camera-frame coords); arms the
     * resize state (dragged corner + opposite-corner pivot).
     */
    private fun hitResizeHandle(screenX: Float, screenY: Float, b: FloatArray): Boolean {
        val cam = viewModel.camera
        val corners = selectionCornersScreen(b, cam)
        for (i in corners.indices) {
            if (abs(screenX - corners[i][0]) <= handleTouchHalfPx &&
                abs(screenY - corners[i][1]) <= handleTouchHalfPx
            ) {
                resizingCorner = i
                val opp = corners[(i + 2) % 4]
                resizePivotScreenX = opp[0]
                resizePivotScreenY = opp[1]
                lastResizePenDist = dist(screenX, screenY, opp[0], opp[1])
                return true
            }
        }
        resizingCorner = -1
        return false
    }

    /** The selection bbox's 4 corners in SCREEN px: TL, TR, BR, BL. */
    private fun selectionCornersScreen(b: FloatArray, cam: Camera): Array<FloatArray> =
        arrayOf(
            floatArrayOf(cam.localToScreenX(b[0]), cam.localToScreenY(b[1])),
            floatArrayOf(cam.localToScreenX(b[2]), cam.localToScreenY(b[1])),
            floatArrayOf(cam.localToScreenX(b[2]), cam.localToScreenY(b[3])),
            floatArrayOf(cam.localToScreenX(b[0]), cam.localToScreenY(b[3])),
        )

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float =
        kotlin.math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    /**
     * P3.3: sampler over the gesture-start scene snapshot. Built ONCE per
     * smudge gesture (the first call renders the composed scene — blends
     * included — to [smudgeSnapshot]); zero-sized views get a plain white
     * sampler so the pipeline stays testable headless.
     */
    private fun smudgeSampler(): (Int, Int) -> Int {
        if (width <= 0 || height <= 0) return { _, _ -> Color.WHITE }
        val bmp = smudgeSnapshot ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also {
                val c = Canvas(it)
                c.drawColor(Color.WHITE)
                drawScene(c, includeInProgress = false)
                smudgeSnapshot = it
            }
        return { sx, sy ->
            bmp.getPixel(sx.coerceIn(0, bmp.width - 1), sy.coerceIn(0, bmp.height - 1))
        }
    }

    /** Finishes the pen gesture, recording exactly one undoable step. */
    private fun finishPenGesture() {
        when (gestureTool) {
            Tool.DRAW -> viewModel.endStrokeLazy()
            Tool.ERASE -> viewModel.endErase()
            Tool.SHAPE -> viewModel.endShapeGesture()
            Tool.SMUDGE -> {
                viewModel.endSmudge()
                smudgeSnapshot?.recycle()
                smudgeSnapshot = null
            }
            Tool.SELECT -> {
                if (isTransformingSelection) {
                    viewModel.document.selection.endTransform()
                } else {
                    val m = marqueeWorld
                    if (m != null) {
                        // P1.3b: marquee commit reaches every VISIBLE level
                        viewModel.selectInWorldRect(
                            minOf(m[0], m[2]), minOf(m[1], m[3]),
                            maxOf(m[0], m[2]), maxOf(m[1], m[3]),
                        )
                    }
                }
                marqueeWorld = null
                isTransformingSelection = false
                resizingCorner = -1
            }
            Tool.EYEDROPPER -> { }
        }
        isDrawing = false
        onCanvasActivityChanged?.invoke(false) // stroke/erase/shape/smudge/select gesture ended
        onStateChanged?.invoke()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cam = viewModel.camera
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // P4.5: ANY touch cancels the in-flight demo tour
                if (demoActive) cancelDemo()
                penGesture = isPenEvent(event)
                if (penGesture) {
                    fingerPanning = false
                    val uv = unrotateScreen(event.x, event.y)
                    startPenGesture(
                        event.getToolType(0), uv[0], uv[1],
                        pressure01(event.pressure),
                    )
                    // Long-press to drop a pin (replaces the too-sensitive double-tap).
                    // Armed only when a pen gesture started; cancelled on move/lift.
                    if (penGesture && gestureTool != Tool.EYEDROPPER) {
                        lastPinScreenX = event.x
                        lastPinScreenY = event.y
                        pinDownTime = SystemClock.uptimeMillis()
                        pinPending = true
                    }
                } else {
                    fingerPanning = true
                    lastFingerX = event.x
                    lastFingerY = event.y
                }
                tapDetector.onTouchEvent(event)
                scaleDetector.onTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                if (isPenEvent(event, idx)) {
                    if (isDrawing) return true // a second pen never joins a gesture
                    fingerPanning = false
                    val uv = unrotateScreen(event.getX(idx), event.getY(idx))
                    startPenGesture(
                        event.getToolType(idx), uv[0], uv[1],
                        pressure01(event.getPressure(idx)),
                    )
                    return true // pen pointers stay out of the pinch detector
                }
                if (isDrawing && penGesture) {
                    // palm rejection: a finger while the pen works is ignored
                    ignoredPalmIds.add(event.getPointerId(idx))
                    return true
                }
                viewModel.inProgress = null
                viewModel.inProgressShape = null
                viewModel.inProgressSmudge = null
                isDrawing = false
                fingerPanning = false
                tapDetector.onTouchEvent(event)
                scaleDetector.onTouchEvent(event)
                // v0.7.0: capture initial two-finger angle for rotation
                if (event.pointerCount == 2) {
                    lastRotateAngle = rotationAngle(event)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // a moving pen is drawing, not holding for a pin — cancel pending pin
                if (pinPending && isPenEvent(event)) {
                    val dx = abs(event.x - lastPinScreenX)
                    val dy = abs(event.y - lastPinScreenY)
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat() > pinMoveThresholdDp * resources.displayMetrics.density) {
                        pinPending = false
                    }
                }
                if (isDrawing && penGesture) {
                    when (gestureTool) {
                        Tool.DRAW -> {
                            for (i in 0 until event.historySize) {
                                val uv = unrotateScreen(
                                    event.getHistoricalX(i), event.getHistoricalY(i))
                                viewModel.extendStrokeAtScreen(
                                    uv[0], uv[1],
                                    pressure01(event.getHistoricalPressure(i)),
                                )
                            }
                            val uv = unrotateScreen(event.x, event.y)
                            viewModel.extendStrokeAtScreen(
                                uv[0], uv[1], pressure01(event.pressure))
                            lastPenScreenX = uv[0]
                            lastPenScreenY = uv[1]
                        }
                        Tool.ERASE -> {
                            val uv = unrotateScreen(event.x, event.y)
                            lastEraserScreenX = uv[0]
                            lastEraserScreenY = uv[1]
                            viewModel.eraseTo(
                                cam.screenToLocalX(uv[0]),
                                cam.screenToLocalY(uv[1]),
                            )
                        }
                        Tool.SHAPE -> {
                            // P3.1: rubber-band the preview to the pen
                            val uv = unrotateScreen(event.x, event.y)
                            viewModel.extendShapeGesture(
                                cam.screenToLocalX(uv[0]),
                                cam.screenToLocalY(uv[1]),
                            )
                        }
                        Tool.SMUDGE -> {
                            // P3.3: sample + carry + deposit along the drag
                            val uv = unrotateScreen(event.x, event.y)
                            viewModel.extendSmudge(
                                uv[0], uv[1], pressure01(event.pressure), smudgeSampler())
                        }
                        Tool.SELECT -> {
                            if (resizingCorner >= 0 && isTransformingSelection) {
                                // P3.1: corner resize — incremental uniform
                                // scale about the opposite corner
                                val newDist = dist(
                                    event.x, event.y, resizePivotScreenX, resizePivotScreenY)
                                if (lastResizePenDist > 1f && newDist > 1f) {
                                    val factor = newDist / lastResizePenDist
                                    viewModel.document.selection.scaleAboutCameraPoint(
                                        cam.screenToLocalX(resizePivotScreenX),
                                        cam.screenToLocalY(resizePivotScreenY),
                                        factor,
                                    )
                                    lastResizePenDist = newDist
                                }
                            } else if (isTransformingSelection) {
                                val dx = cam.screenToLocalX(event.x) -
                                    cam.screenToLocalX(lastSelScreenX)
                                val dy = cam.screenToLocalY(event.y) -
                                    cam.screenToLocalY(lastSelScreenY)
                                viewModel.document.selection.translateBy(dx, dy)
                            } else {
                                val m = marqueeWorld
                                if (m != null) {
                                    m[2] = cam.screenToLocalX(event.x)
                                    m[3] = cam.screenToLocalY(event.y)
                                }
                            }
                            lastSelScreenX = event.x
                            lastSelScreenY = event.y
                        }
                        Tool.EYEDROPPER -> { }
                    }
                    invalidate()
                } else if (fingerPanning && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    // P1.1: one finger pans the camera; never draws
                    val dx = event.x - lastFingerX
                    val dy = event.y - lastFingerY
                    lastFingerX = event.x
                    lastFingerY = event.y
                    if (dx != 0f || dy != 0f) {
                        cam.panBy(dx, dy)
                        invalidate()
                    }
                    tapDetector.onTouchEvent(event)
                } else {
                    tapDetector.onTouchEvent(event)
                    scaleDetector.onTouchEvent(event)
                    // v0.7.0: two-finger canvas rotation (when not selecting)
                    if (!selectionGesture && event.pointerCount == 2 && lastRotateAngle != null) {
                        val newAngle = rotationAngle(event)
                        var delta = newAngle - lastRotateAngle!!
                        while (delta > 180f) delta -= 360f
                        while (delta < -180f) delta += 360f
                        if (delta != 0f) {
                            canvasRotationDegrees += delta
                            lastRotateAngle = newAngle
                            invalidate()
                        }
                    }
                    // P4.2: while a SELECT pinch runs over a selection, the
                    // inter-pointer angle ROTATES it (onScale already scales
                    // it) — live, part of the same ONE undo step per gesture.
                    if (selectionGesture && event.pointerCount >= 2) {
                        val ang = kotlin.math.atan2(
                            (event.getY(1) - event.getY(0)).toDouble(),
                            (event.getX(1) - event.getX(0)).toDouble(),
                        ).toFloat()
                        val last = pinchAngle
                        if (last == null) {
                            pinchAngle = ang
                        } else {
                            var delta = ang - last
                            // wrap into (-180, 180] so crossing +/-pi is continuous
                            while (delta > 180f) delta -= 360f
                            while (delta < -180f) delta += 360f
                            pinchAngle = ang
                            if (delta != 0f) {
                                viewModel.document.selection.rotate(delta)
                                invalidate()
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (ignoredPalmIds.remove(event.getPointerId(idx))) {
                    return true // ignored palm finger lifted; the pen keeps working
                }
                // a lifted pen cancels any pending pin drop / drops a pin if held long enough
                if (pinPending && isPenEvent(event, idx)) {
                    if (SystemClock.uptimeMillis() - pinDownTime >= pinLongPressDelay) {
                        placePinAtLastPin()
                    }
                    pinPending = false
                }
                if (isDrawing && penGesture && isPenEvent(event, idx)) {
                    // pen lifted while fingers remain: finish, fingers go inert
                    finishPenGesture()
                    ignoredPalmIds.clear()
                    fingerPanning = false
                    tapDetector.onTouchEvent(event)
                    return true
                }
                tapDetector.onTouchEvent(event)
                scaleDetector.onTouchEvent(event)
                // v0.7.0: reset rotation tracking when fingers drop below 2
                if (event.pointerCount < 2) lastRotateAngle = null
                // a pinch survivor continues as the pan finger
                if (!penGesture && !isDrawing && !scaleDetector.isInProgress) {
                    var remaining = -1
                    for (i in 0 until event.pointerCount) {
                        if (i != idx) {
                            remaining = i
                            break
                        }
                    }
                    if (remaining >= 0 && !isPenEvent(event, remaining)) {
                        fingerPanning = true
                        lastFingerX = event.getX(remaining)
                        lastFingerY = event.getY(remaining)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing && penGesture) finishPenGesture()
                fingerPanning = false
                penGesture = false
                ignoredPalmIds.clear()
                // Long-press pin: if the pen was held long enough, drop a pin on release.
                if (pinPending) placePinAtLastPin()
                pinPending = false
                tapDetector.onTouchEvent(event)
                scaleDetector.onTouchEvent(event)
            }
        }
        return true
    }

    /** v0.7.0: create a copy of [event] with all pointer coordinates un-rotated
     *  by [canvasRotationDegrees] around the canvas center, so gesture math
     *  works in un-rotated canvas space. Returns the original event unchanged
     *  when rotation is zero. */
    /** v0.7.0: angle in degrees between the first two pointers. */
    private fun rotationAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val dx = scroller.currX - lastFlingX
            val dy = scroller.currY - lastFlingY
            lastFlingX = scroller.currX
            lastFlingY = scroller.currY
            viewModel.camera.panBy(-dx.toFloat(), -dy.toFloat())
            postInvalidateOnAnimation()
        }
    }

    private fun pressure01(raw: Float): Float = raw.coerceIn(0f, 1f)

    /** Delay before a held pen becomes a pin instead of drawing (standard long-press timeout). */
    private val pinLongPressDelay: Long = 500L
    /** Movement threshold (dp) beyond which a held pen is treated as a drag, not a hold. */
    private val pinMoveThresholdDp: Float = 8f
    /** Set while a pen is held without moving, awaiting the pin-drop timeout. */
    private var pinPending = false
    /** Time (system uptime ms) when the pen went down, for long-press pin detection. */
    private var pinDownTime: Long = 0
    /** Raw screen coords of the pen down that may become a pin. */
    private var lastPinScreenX = 0f
    private var lastPinScreenY = 0f

    /** Undoes the canvas's on-screen rotation ([canvasRotationDegrees]) for a
     *  raw SCREEN point, about the same center pivot [onDraw] rotates
     *  around, so the stylus tip and the drawn mark land on top of each
     *  other at any rotation angle. Rotation 0 (and zero-size views) pass
     *  the point through unchanged.
     *
     *  Finger panning/pinching intentionally stay in raw screen space:
     *  they track the finger on screen — the camera shifts content by the
     *  same screen delta the finger travels, which is correct under
     *  rotation too. Only pen-driven GEOMETRY (strokes, erase, shapes,
     *  smudge, select, marquee, bookmarks, eyedropper, lazy overlay) is
     *  un-rotated before the screen -> local conversion. */
    private fun unrotateScreen(x: Float, y: Float): FloatArray {
        val r = canvasRotationDegrees
        if (r == 0f || width <= 0f || height <= 0f) return floatArrayOf(x, y)
        val a = Math.toRadians((-r).toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        return floatArrayOf(cx + dx * c - dy * s, cy + dx * s + dy * c)
    }

    override fun onDraw(canvas: Canvas) {
        // Draw scene + overlays WITH canvas rotation applied
        canvas.save()
        canvas.rotate(canvasRotationDegrees, width / 2f, height / 2f)
        canvas.drawColor(Color.WHITE)
        drawScene(canvas)
        drawSelectionOverlay(canvas)
        drawLazyOverlay(canvas)
        drawEraserPreview(canvas)
        canvas.restore()

        // Zoom readout is drawn in SCREEN space (NOT rotated with canvas),
        // so it stays upright and readable at any canvas rotation.
        drawZoomReadout(canvas)
    }

    /**
     * U-overhaul: the zoom readout is now a small dark glass chip drawn
     * top-center in SCREEN space (drawSelectionOverlay-style), always
     * visible while panning/zooming so users see zoom depth. Drawn by the
     * canvas itself: it is camera state, always in sync, and never a
     * separate touchable view (see docs/UI_OVERHAUL.md).
     */
    private fun drawZoomReadout(canvas: Canvas) {
        val text = cameraReadout()
        val d = resources.displayMetrics.density
        val padH = 14f * d
        val padV = 6f * d
        // v0.7.0 (spec 6): monochrome label + AMBER level number
        val split = text.lastIndexOf(' ')
        val label = text.substring(0, split + 1)
        val number = text.substring(split + 1)
        val labelW = readoutPaint.measureText(label)
        val numberW = readoutAccentPaint.measureText(number)
        val chipW = labelW + numberW + padH * 2f
        val chipH = readoutPaint.textSize + padV * 2f
        val cx = width / 2f
        val top = 14f * d
        val left = cx - chipW / 2f
        val rect = RectF(left, top, left + chipW, top + chipH)
        val radius = 8f * d
        canvas.drawRoundRect(rect, radius, radius, hudChipPaint)
        canvas.drawRoundRect(rect, radius, radius, hudBorderPaint)
        val baseline = top + padV + readoutPaint.textSize * 0.8f
        canvas.drawText(label, cx - (labelW + numberW) / 2f, baseline, readoutPaint)
        canvas.drawText(number, cx - (labelW + numberW) / 2f + labelW, baseline,
            readoutAccentPaint)
    }

    fun cameraReadout(): String {
        val cam = viewModel.camera
        // P4.5: total magnification + depth level, ALWAYS surfaced
        return String.format("%.1fx · level %d", cam.rootMagnification(), cam.level.depth)
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        if (tool != Tool.SELECT) return
        val cam = viewModel.camera
        viewModel.document.selection.bbox()?.let { b ->
            canvas.drawRect(
                cam.localToScreenX(b[0]), cam.localToScreenY(b[1]),
                cam.localToScreenX(b[2]), cam.localToScreenY(b[3]),
                selectionPaint,
            )
            // P3.1: corner resize handles — small squares, 40px+ touch target
            for (c in selectionCornersScreen(b, cam)) {
                canvas.drawRect(
                    c[0] - handleVisualHalfPx, c[1] - handleVisualHalfPx,
                    c[0] + handleVisualHalfPx, c[1] + handleVisualHalfPx,
                    handleFillPaint,
                )
                canvas.drawRect(
                    c[0] - handleVisualHalfPx, c[1] - handleVisualHalfPx,
                    c[0] + handleVisualHalfPx, c[1] + handleVisualHalfPx,
                    selectionPaint,
                )
            }
        }
        marqueeWorld?.let { m ->
            canvas.drawRect(
                cam.localToScreenX(minOf(m[0], m[2])), cam.localToScreenY(minOf(m[1], m[3])),
                cam.localToScreenX(maxOf(m[0], m[2])), cam.localToScreenY(maxOf(m[1], m[3])),
                marqueePaint,
            )
        }
    }

    /**
     * P3.2: lazy-brush overlay, drawn while a lazy DRAW gesture is active:
     * a thin line from the pen tip to the trailing brush point, a small
     * filled circle AT the brush point, and a faint ring showing the rope
     * radius (all SCREEN space, after the scene — like the selection
     * overlay). Hidden when the stabilizer is off.
     */
    private fun drawLazyOverlay(canvas: Canvas) {
        if (!viewModel.lazyActive) return
        if (!isDrawing || !penGesture || gestureTool != Tool.DRAW) return
        val brushX = viewModel.lazyBrush.brushX
        val brushY = viewModel.lazyBrush.brushY
        canvas.drawLine(lastPenScreenX, lastPenScreenY, brushX, brushY, lazyPaint)
        canvas.drawCircle(brushX, brushY, 6f, lazyDotPaint)
        val r = viewModel.lazyBrush.radiusScreen
        if (r > 2f) canvas.drawCircle(brushX, brushY, r, lazyPaint)
    }

    /**
     * Drops a bookmark pin at the held pen position, un-rotating for canvas
     * rotation so the pin lands under the stylus tip. A long press is a pin
     * drop, not a draw — any in-progress stroke is discarded.
     */
    private fun placePinAtLastPin() {
        pinPending = false
        // Only place a pin if the pen was held long enough; a quick tap is not a pin.
        if (SystemClock.uptimeMillis() - pinDownTime < pinLongPressDelay) return
        // A long press is a pin drop, not a draw — discard any in-progress stroke.
        if (isDrawing && penGesture) {
            viewModel.inProgress = null
            isDrawing = false
        }
        penGesture = false
        fingerPanning = false
        val cam = viewModel.camera
        val uv = unrotateScreen(lastPinScreenX, lastPinScreenY)
        val label = "Pin " + (viewModel.document.bookmarks.size + 1)
        viewModel.addBookmark(cam.screenToLocalX(uv[0]), cam.screenToLocalY(uv[1]), label)
        onStateChanged?.invoke()
        invalidate()
    }

    /**
     * P4.7: eraser size preview — draws the eraser's on-screen circle at
     * the pen so the user sees exactly how big "Erase size" is from the
     * Brush panel's slider. The eraser's screen radius equals
     * eraserRadiusWorld px at any zoom, so this ring is 1:1 with the carve
     * area. Drawn whenever the erase tool is armed and the pen has ever
     * touched the canvas, including while adjusting the slider.
     */
    private fun drawEraserPreview(canvas: Canvas) {
        if (lastEraserScreenX < 0f) return
        val effective = effectiveTool
        if (effective != Tool.ERASE) return
        val r = viewModel.eraserRadiusWorld.coerceAtLeast(1f)
        canvas.drawCircle(lastEraserScreenX, lastEraserScreenY, r, eraserPreviewFillPaint)
        canvas.drawCircle(lastEraserScreenX, lastEraserScreenY, r, eraserPreviewRingPaint)
    }

    /**
     * Level-composed scene draw, used by the screen, the eyedropper, PNG
     * export and the MP4 encoder. [camera] must reference a level of the
     * same tree as the document content (the viewModel camera always does).
     *
     * P2.3: layers are "stacked sheets of paper" — a layer's content spans
     * ALL visible levels, and sheets composite in layer order. Two paths:
     *  - All layers NORMAL (the common case): the pre-P2 LEVEL-MAJOR fast
     *    path, byte-identical rendering with zero saveLayer overhead.
     *  - Any visible layer non-NORMAL: the LAYER-MAJOR path — sheets in
     *    stack order, each sheet's content drawn across every visible
     *    level (ancestors first, children on top), and each non-NORMAL
     *    sheet wrapped in canvas.saveLayer(null, paint-with-mode) so it
     *    composites with its blend mode. Only blended sheets pay the
     *    offscreen cost. Within one sheet its images draw under its
     *    strokes (the pre-P2 per-layer convention).
     */
    fun drawScene(
        canvas: Canvas,
        camera: Camera = viewModel.camera,
        includeInProgress: Boolean = true,
    ) {
        val tree = camera.level.tree
        val layers = viewModel.document.layers.toList()

        // Visible level set: root down to the camera's level, then its children.
        val chain = ArrayList<ZoomLevel>()
        var l: ZoomLevel? = camera.level
        while (l != null) {
            chain.add(l)
            l = l.parent
        }
        val levels = ArrayList<ZoomLevel>(chain.size + 4)
        for (i in chain.indices.reversed()) levels.add(chain[i]) // root first
        levels.addAll(tree.childrenOf(camera.level))
        val visibleIds = HashSet<Long>(levels.size * 2)
        for (lvl in levels) visibleIds.add(lvl.id)

        // Renderable passes: transform + viewport rect per level.
        class Pass(val lvl: ZoomLevel, val k: Float, val tx: Float, val ty: Float,
                   val rect: FloatArray)
        val passes = ArrayList<Pass>(levels.size)
        for (lvl in levels) {
            val t = camera.transformTo(lvl)
            val k = t[0]
            if (!k.isFinite() || k < minRenderableScale || k > maxRenderableScale) continue
            passes.add(Pass(lvl, k, t[1], t[2], floatArrayOf(
                (0f - t[1]) / k, (0f - t[2]) / k,
                (canvas.width - t[1]) / k, (canvas.height - t[2]) / k,
            )))
        }

        val anyBlended = layers.any { it.isVisible && it.blend != LayerBlend.NORMAL }

        if (!anyBlended) {
            // ---- fast path: level-major (pre-P2 rendering, unchanged) ----
            for (pass in passes) {
                canvas.save()
                val m = Matrix()
                m.setScale(pass.k, pass.k)
                m.postTranslate(pass.tx, pass.ty)
                canvas.concat(m)

                // images of this level first (underlay), across layers in order
                for (layer in layers) {
                    if (!layer.isVisible) continue
                    for (img in layer.images.toList()) {
                        if (img.levelId != pass.lvl.id) continue
                        val ib = img.bbox() // rotation-aware extents
                        if (ib[2] < pass.rect[0] || ib[0] > pass.rect[2] ||
                            ib[3] < pass.rect[1] || ib[1] > pass.rect[3]
                        ) continue
                        drawImage(canvas, img, layer.opacity)
                    }
                }

                // strokes of this level, per layer via the level's index buckets
                val visible = viewModel.document.strokeIndex
                    .queryVisibleLevel(pass.lvl.id, pass.rect[0], pass.rect[1], pass.rect[2], pass.rect[3])
                if (visible.isNotEmpty()) {
                    val byLayer = LinkedHashMap<LayerData, MutableList<StrokeData>>()
                    for ((stroke, layer) in visible) byLayer.getOrPut(layer) { mutableListOf() }.add(stroke)
                    for (layer in layers) {
                        if (!layer.isVisible) continue
                        byLayer[layer]?.forEach { drawStroke(canvas, it, layer.opacity) }
                    }
                }
                // P3: per-layer SHAPES then SMUDGES of this level (stamps
                // paint over strokes within the same layer — deposit order;
                // both after that layer's strokes, images stay under all)
                for (layer in layers) {
                    if (!layer.isVisible) continue
                    for (sh in layer.shapes.toList()) {
                        if (sh.levelId != pass.lvl.id) continue
                        val b = sh.bbox()
                        val w = sh.outlineWidthWorld * 0.5f + 0.01f // cull slack
                        if (b[2] + w < pass.rect[0] || b[0] - w > pass.rect[2] ||
                            b[3] + w < pass.rect[1] || b[1] - w > pass.rect[3]
                        ) continue
                        drawShape(canvas, sh, layer.opacity)
                    }
                    drawSmudgesOfLevel(canvas, layer, pass.lvl.id, layer.opacity)
                }
                canvas.restore()
            }
        } else {
            // ---- P2.3 layer-major path: stacked sheets, blended via saveLayer ----
            for (layer in layers) {
                if (!layer.isVisible) continue
                val blended = layer.blend != LayerBlend.NORMAL
                if (blended) {
                    applyBlendTo(layerBlendPaint, layer.blend)
                    canvas.saveLayer(null, layerBlendPaint)
                }
                for (pass in passes) {
                    canvas.save()
                    val m = Matrix()
                    m.setScale(pass.k, pass.k)
                    m.postTranslate(pass.tx, pass.ty)
                    canvas.concat(m)

                    // this sheet's images of the level (under its strokes)
                    for (img in layer.images.toList()) {
                        if (img.levelId != pass.lvl.id) continue
                        val ib = img.bbox() // rotation-aware extents
                        if (ib[2] < pass.rect[0] || ib[0] > pass.rect[2] ||
                            ib[3] < pass.rect[1] || ib[1] > pass.rect[3]
                        ) continue
                        drawImage(canvas, img, layer.opacity)
                    }

                    // this sheet's strokes of the level (identity-filtered)
                    val visible = viewModel.document.strokeIndex
                        .queryVisibleLevel(pass.lvl.id, pass.rect[0], pass.rect[1], pass.rect[2], pass.rect[3])
                    for ((stroke, owner) in visible) {
                        if (owner === layer) drawStroke(canvas, stroke, layer.opacity)
                    }
                    // P3: this sheet's shapes then smudges of the level
                    for (sh in layer.shapes.toList()) {
                        if (sh.levelId != pass.lvl.id) continue
                        val b = sh.bbox()
                        val w = sh.outlineWidthWorld * 0.5f + 0.01f // cull slack
                        if (b[2] + w < pass.rect[0] || b[0] - w > pass.rect[2] ||
                            b[3] + w < pass.rect[1] || b[1] - w > pass.rect[3]
                        ) continue
                        drawShape(canvas, sh, layer.opacity)
                    }
                    drawSmudgesOfLevel(canvas, layer, pass.lvl.id, layer.opacity)
                    canvas.restore()
                }
                if (blended) canvas.restore()
            }
        }

        if (includeInProgress) {
            viewModel.inProgress?.let { s ->
                val lvl = tree.byId(s.levelId) ?: return@let
                val t = camera.transformTo(lvl)
                val k = t[0]
                if (k.isFinite() && k in minRenderableScale..maxRenderableScale) {
                    val active = viewModel.document.activeLayer
                    val blended = active.blend != LayerBlend.NORMAL
                    if (blended) {
                        // live stroke on a blended sheet composites against
                        // the flattened backdrop with the sheet's own mode
                        applyBlendTo(layerBlendPaint, active.blend)
                        canvas.saveLayer(null, layerBlendPaint)
                    }
                    canvas.save()
                    val m = Matrix()
                    m.setScale(k, k)
                    m.postTranslate(t[1], t[2])
                    canvas.concat(m)
                    // P3.2: while a LAZY gesture runs, the live stroke renders
                    // through the midpoint-quadratic smoother so the mark is
                    // smooth for the WHOLE gesture (not only after commit).
                    // The stroke's own points stay RAW rope samples — only
                    // the drawn path is smoothed, once per frame, so the
                    // point count can never grow exponentially.
                    val livePts = if (viewModel.lazyActive && s.points.size >= 2) {
                        LazyBrush.smoothPoints(s.points.toList())
                    } else null
                    drawStroke(canvas, s, active.opacity, livePts)
                    canvas.restore()
                    if (blended) canvas.restore()
                }
            }
            // P3.1: live rubber-band shape (same level transform + active
            // sheet blend wrapping as the in-progress stroke)
            viewModel.inProgressShape?.let { s ->
                val lvl = tree.byId(s.levelId) ?: return@let
                val t = camera.transformTo(lvl)
                val k = t[0]
                if (k.isFinite() && k in minRenderableScale..maxRenderableScale) {
                    val active = viewModel.document.activeLayer
                    val blended = active.blend != LayerBlend.NORMAL
                    if (blended) {
                        applyBlendTo(layerBlendPaint, active.blend)
                        canvas.saveLayer(null, layerBlendPaint)
                    }
                    canvas.save()
                    val m = Matrix()
                    m.setScale(k, k)
                    m.postTranslate(t[1], t[2])
                    canvas.concat(m)
                    drawShape(canvas, s, active.opacity)
                    canvas.restore()
                    if (blended) canvas.restore()
                }
            }
            // P3.3: live smudge trail (stamps deposited so far)
            viewModel.inProgressSmudge?.let { sm ->
                val lvl = tree.byId(sm.levelId) ?: return@let
                val t = camera.transformTo(lvl)
                val k = t[0]
                if (k.isFinite() && k in minRenderableScale..maxRenderableScale) {
                    val active = viewModel.document.activeLayer
                    val blended = active.blend != LayerBlend.NORMAL
                    if (blended) {
                        applyBlendTo(layerBlendPaint, active.blend)
                        canvas.saveLayer(null, layerBlendPaint)
                    }
                    canvas.save()
                    val m = Matrix()
                    m.setScale(k, k)
                    m.postTranslate(t[1], t[2])
                    canvas.concat(m)
                    for (st in sm.stamps) drawStamp(canvas, st, active.opacity)
                    canvas.restore()
                    if (blended) canvas.restore()
                }
            }
        }

        // bookmark pins in SCREEN space; only pins of visible levels
        for (b in viewModel.document.bookmarks.toList()) {
            if (b.levelId !in visibleIds) continue
            val lvl = tree.byId(b.levelId) ?: continue
            val t = camera.transformTo(lvl)
            val k = t[0]
            if (!k.isFinite() || k < minRenderableScale) continue
            val sx = b.worldX * k + t[1]
            val sy = b.worldY * k + t[2]
            if (sx < -20f || sx > canvas.width + 20f || sy < -20f || sy > canvas.height + 20f) continue
            canvas.drawCircle(sx, sy, 10f, bookmarkPaint)
        }
    }

    private fun drawImage(canvas: Canvas, img: PlacedImage, layerOpacity: Float) {
        val bmp = imageCache.getOrPut(img.path) {
            val f = File(context.filesDir, img.path)
            if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
        } ?: return
        // P4.3: per-image transparency (x the layer's own opacity)
        imagePaint.alpha = (img.alpha * layerOpacity * 255f).toInt().coerceIn(0, 255)
        // P4.3: render-time DISPLAY filter — ColorMatrix on the image paint
        // only; the bitmap data is never touched (UI-state, not serialized)
        imagePaint.colorFilter = when (img.displayFilter) {
            ImageDisplayFilter.NONE -> null
            ImageDisplayFilter.GRAYSCALE ->
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            ImageDisplayFilter.HIGH_CONTRAST ->
                ColorMatrixColorFilter(ColorMatrix(HIGH_CONTRAST_MATRIX))
        }
        val left = img.x
        val top = img.y
        val right = img.x + img.w
        val bottom = img.y + img.h
        if (img.rotation == 0f && !img.flipH) {
            canvas.drawBitmap(bmp, null, RectF(left, top, right, bottom), imagePaint)
        } else {
            // P4.2/P5: rotate about the image center (two-finger pinch)
            // and/or horizontal mirror (FLIP tool)
            canvas.save()
            val cx = img.x + img.w / 2f
            val cy = img.y + img.h / 2f
            if (img.flipH) canvas.scale(-1f, 1f, cx, cy)
            if (img.rotation != 0f) canvas.rotate(img.rotation, cx, cy)
            canvas.drawBitmap(bmp, null, RectF(left, top, right, bottom), imagePaint)
            canvas.restore()
        }
        imagePaint.colorFilter = null
    }

    /** P4.3: high-contrast ColorMatrix (scale 1.8 about the 128 midpoint). */
    private val HIGH_CONTRAST_MATRIX = floatArrayOf(
        1.8f, 0f, 0f, 0f, -102f,
        0f, 1.8f, 0f, 0f, -102f,
        0f, 0f, 1.8f, 0f, -102f,
        0f, 0f, 0f, 1f, 0f,
    )

    /**
     * P3.3: all smudges of [levelId] on [layer] (committed gestures; the
     * live one draws via the in-progress block). Each stamp is one vector
     * circle with a soft RadialGradient alpha falloff — the softness
     * slider interpolates the gradient's hard-stop position (0 = hard
     * edge, 1 = linear falloff from the center), and deposit alpha =
     * stamp.alpha (strength x pressure) scaled by the layer opacity.
     */
    private fun drawSmudgesOfLevel(canvas: Canvas, layer: LayerData, levelId: Long, layerOpacity: Float) {
        for (sm in layer.smudges.toList()) {
            if (sm.levelId != levelId) continue
            for (st in sm.stamps) drawStamp(canvas, st, layerOpacity)
        }
    }

    private fun drawStamp(canvas: Canvas, st: StampData, layerOpacity: Float) {
        val a = (st.alpha * layerOpacity * 255f).toInt().coerceIn(0, 255)
        if (a <= 0 || st.radius <= 0f) return
        val softness = viewModel.smudgeSoftness.coerceIn(0f, 1f)
        val hardStop = (1f - softness).coerceIn(0.001f, 1f)
        val rgb = st.color and 0xFFFFFF
        val colors = intArrayOf((a shl 24) or rgb, (a shl 24) or rgb, rgb)
        val positions = floatArrayOf(0f, hardStop, 1f)
        stampPaint.shader = RadialGradient(
            st.x, st.y, st.radius, colors, positions, Shader.TileMode.CLAMP)
        canvas.drawCircle(st.x, st.y, st.radius, stampPaint)
        stampPaint.shader = null
    }

    /**
     * P3.1: renders one parametric shape in level-local coordinates (the
     * canvas already carries the level matrix; outlineWidthWorld is
     * level-local like brush widths, so on-screen weight is zoom-stable).
     *  - ELLIPSE / ROUNDED_RECT draw with native canvas primitives (crisp
     *    arcs at any zoom);
     *  - LINE ignores the fill toggle (open stroke, outline style only);
     *  - ARROW = shaft line (outline style) + filled head triangle when
     *    fill is on;
     *  - everything else = one closed path, fill first then outline.
     */
    private fun drawShape(canvas: Canvas, shape: ShapeData, layerOpacity: Float) {
        val fillAlpha = (255f * layerOpacity).toInt().coerceIn(0, 255)
        when (shape.type) {
            com.example.zoomart.model.ShapeType.ELLIPSE -> {
                val r = ShapeGeometry.rectOf(shape.x0, shape.y0, shape.x1, shape.y1)
                val rect = RectF(r[0], r[1], r[2], r[3])
                if (shape.fillOn) {
                    shapeFillPaint.color = shape.fillColor
                    shapeFillPaint.alpha = fillAlpha
                    canvas.drawOval(rect, shapeFillPaint)
                }
                if (shape.outlineWidthWorld > 0f) {
                    shapeOutlinePaint.color = shape.outlineColor
                    shapeOutlinePaint.alpha = fillAlpha
                    shapeOutlinePaint.strokeWidth = shape.outlineWidthWorld
                    canvas.drawOval(rect, shapeOutlinePaint)
                }
            }
            com.example.zoomart.model.ShapeType.ROUNDED_RECT -> {
                val r = ShapeGeometry.rectOf(shape.x0, shape.y0, shape.x1, shape.y1)
                val rect = RectF(r[0], r[1], r[2], r[3])
                val cr = ShapeGeometry.cornerRadius(
                    shape.x0, shape.y0, shape.x1, shape.y1, shape.cornerRoundness)
                if (shape.fillOn) {
                    shapeFillPaint.color = shape.fillColor
                    shapeFillPaint.alpha = fillAlpha
                    canvas.drawRoundRect(rect, cr, cr, shapeFillPaint)
                }
                if (shape.outlineWidthWorld > 0f) {
                    shapeOutlinePaint.color = shape.outlineColor
                    shapeOutlinePaint.alpha = fillAlpha
                    shapeOutlinePaint.strokeWidth = shape.outlineWidthWorld
                    canvas.drawRoundRect(rect, cr, cr, shapeOutlinePaint)
                }
            }
            com.example.zoomart.model.ShapeType.ARROW -> {
                val head = ShapeGeometry.arrowHead(shape.x0, shape.y0, shape.x1, shape.y1)
                shapeOutlinePaint.color = shape.outlineColor
                shapeOutlinePaint.alpha = fillAlpha
                shapeOutlinePaint.strokeWidth = shape.outlineWidthWorld
                canvas.drawLine(shape.x0, shape.y0, shape.x1, shape.y1, shapeOutlinePaint)
                scratchPath.reset()
                scratchPath.moveTo(head.first[0], head.first[1])
                scratchPath.lineTo(shape.x1, shape.y1)
                scratchPath.lineTo(head.second[0], head.second[1])
                scratchPath.close()
                if (shape.fillOn) {
                    shapeFillPaint.color = shape.fillColor
                    shapeFillPaint.alpha = fillAlpha
                    canvas.drawPath(scratchPath, shapeFillPaint)
                }
                if (shape.outlineWidthWorld > 0f) {
                    canvas.drawPath(scratchPath, shapeOutlinePaint)
                }
            }
            else -> {
                val polys = ShapeGeometry.subPaths(shape)
                for (poly in polys) {
                    if (poly.pts.size < 2) continue
                    scratchPath.reset()
                    scratchPath.moveTo(poly.pts[0][0], poly.pts[0][1])
                    for (i in 1 until poly.pts.size) {
                        scratchPath.lineTo(poly.pts[i][0], poly.pts[i][1])
                    }
                    if (poly.closed) scratchPath.close()
                    if (shape.fillOn && poly.closed) {
                        shapeFillPaint.color = shape.fillColor
                        shapeFillPaint.alpha = fillAlpha
                        canvas.drawPath(scratchPath, shapeFillPaint)
                    }
                    if (shape.outlineWidthWorld > 0f) {
                        shapeOutlinePaint.color = shape.outlineColor
                        shapeOutlinePaint.alpha = fillAlpha
                        shapeOutlinePaint.strokeWidth = shape.outlineWidthWorld
                        canvas.drawPath(scratchPath, shapeOutlinePaint)
                    }
                }
            }
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: StrokeData,
        layerOpacity: Float,
        ptsOverride: List<StrokePoint>? = null,
    ) {
        val brush = stroke.brush
        // Constant stroke-level alpha for the outline/dash paths (they draw
        // one path per stroke, so per-point alpha cannot apply there; pencil
        // is a stamp brush and gets per-stamp alpha above). alphaForPressure
        // collapses to plain opacity for non-pressure-opacity brushes.
        val alpha = (brush.alphaForPressure(stroke.points.firstOrNull()?.pressure01 ?: 1f) *
            layerOpacity * 255f).toInt().coerceIn(0, 255)
        // [ptsOverride] lets the LIVE lazy stroke render through the
        // smoother without mutating the committed points (see drawScene).
        val pts = ptsOverride ?: stroke.points.toList()
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            strokeFillPaint.pathEffect = null
            strokeFillPaint.color = brush.color
            strokeFillPaint.alpha =
                (brush.alphaForPressure(pts[0].pressure01) * layerOpacity * 255f)
                    .toInt().coerceIn(0, 255)
            canvas.drawCircle(
                pts[0].x, pts[0].y,
                brush.widthForPressure(pts[0].pressure01) * 0.5f, strokeFillPaint)
            return
        }

        if (brush.spacingWorld > 0f) {
            // Stamp-based rendering (spray/pencil): deterministic per-stroke
            // jitter. P1.2: pressure-opacity brushes vary alpha PER STAMP
            // (pencil: light touches faint, hard presses dark); ordinary
            // brushes keep their constant alpha (alphaForPressure == opacity).
            strokeFillPaint.pathEffect = null
            strokeFillPaint.color = brush.color
            val rnd = java.util.Random(pts.hashCode().toLong())
            fun stamp(x: Float, y: Float, r: Float, pressure: Float) {
                strokeFillPaint.alpha =
                    (brush.alphaForPressure(pressure) * layerOpacity * 255f)
                        .toInt().coerceIn(0, 255)
                // Spray paint: scatter in a disk (not a square grid), vary each
                // particle's size and opacity so it reads as aerosol particles
                // landing around the stroke path rather than a dotted line.
                val angle = rnd.nextFloat() * 2f * kotlin.math.PI.toFloat()
                val radius = rnd.nextFloat() * brush.scatterWorld
                val ox = kotlin.math.cos(angle.toDouble()).toFloat() * radius
                val oy = kotlin.math.sin(angle.toDouble()).toFloat() * radius
                val size = (0.6f + rnd.nextFloat() * 0.8f) * r
                val alpha = (0.5f + rnd.nextFloat() * 0.5f).coerceIn(0f, 1f)
                strokeFillPaint.alpha = ((strokeFillPaint.alpha / 255f) * alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(x + ox, y + oy, size.coerceAtLeast(1f), strokeFillPaint)
            }
            stamp(pts[0].x, pts[0].y,
                brush.widthForPressure(pts[0].pressure01) * 0.5f, pts[0].pressure01)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len < 1e-4f) continue
                var t = brush.spacingWorld
                while (t <= len) {
                    val f = t / len
                    val mid = (a.pressure01 + b.pressure01) * 0.5f
                    stamp(a.x + dx * f, a.y + dy * f,
                        brush.widthForPressure(mid) * 0.5f, mid)
                    t += brush.spacingWorld
                }
            }
            return
        }

        if (brush.dashOn > 0f) {
            // Dash brushes: dash effects apply to stroked geometry, so render
            // the pressure-averaged centerline instead of a filled outline.
            dashPaint.color = brush.color
            dashPaint.alpha = alpha
            dashPaint.strokeWidth = brush.baseWidthWorld *
                (0.5f * (pts[0].pressure01 + pts[pts.size - 1].pressure01))
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(brush.dashOn, brush.dashOff), 0f)
            scratchPath.reset()
            val sink = AndroidPathSink(scratchPath)
            sink.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) sink.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(scratchPath, dashPaint)
            return
        }

        // Default: variable-width filled outline + round caps.
        strokeFillPaint.pathEffect = null
        strokeFillPaint.color = brush.color
        strokeFillPaint.alpha = alpha
        scratchPath.reset()
        if (StrokeOutline.buildPath(pts, brush, AndroidPathSink(scratchPath), stroke.taper)) {
            canvas.drawPath(scratchPath, strokeFillPaint)
            val first = pts.first()
            val last = pts.last()
            // P3.2: tapered ends shrink the cap circles with them (factor
            // ~0 at the very ends); skip zero-radius draws.
            val capF = if (stroke.taper > 0f) LazyBrush.taperFactors(pts, stroke.taper) else null
            val rFirst = brush.widthForPressure(first.pressure01) * 0.5f *
                (capF?.get(0) ?: 1f)
            val rLast = brush.widthForPressure(last.pressure01) * 0.5f *
                (capF?.get(capF.size - 1) ?: 1f)
            if (rFirst > 0.05f) canvas.drawCircle(first.x, first.y, rFirst, strokeFillPaint)
            if (rLast > 0.05f) canvas.drawCircle(last.x, last.y, rLast, strokeFillPaint)
        }
    }

    fun zoomBy(factor: Float) {
        val cam = viewModel.camera
        cam.zoomAt(width / 2f, height / 2f, cam.scale * factor)
        if (cam.applyLevelTransitions(width / 2f, height / 2f)) {
            Log.i(TAG, "camera level -> depth " + cam.level.depth +
                " (scale " + cam.scale + ", zoom " + cam.rootMagnification() + ")")
            onCameraLevelChanged?.invoke(cam.level.depth)
        }
        invalidate()
    }

    // ----- P4.5: demo tour (nested rings + depth-by-depth camera flight) -----

    /**
     * P4.5: DEMO — seeds the nested rings (viewModel.seedDemoContent: fresh
     * layers carrying concentric star/rect/ellipse shapes at descending
     * zoom levels, seeded WITHOUT undo entries — documented) and flies the
     * camera through them depth by depth, ~2 s per level, riding the P1
     * smooth cross-level flight (log-space magnification, root-space
     * position, legal [1,100) band per frame). ANY touch cancels the
     * flight; the seeded scenery stays until the user deletes the layers.
     */
    fun runDemo() {
        cancelDemo()
        val plan = viewModel.seedDemoContent()
        demoTargets = plan.levels
        demoLegIndex = 0
        demoActive = true
        Log.i(TAG, "demo: seeded " + plan.layers.size + " nested rings on levels " +
            plan.levels.joinToString(",") { it.id.toString() } + "; tour starting")
        startDemoLeg()
    }

    private fun startDemoLeg() {
        val targets = demoTargets
        if (!demoActive || targets == null) return
        if (demoLegIndex >= targets.size) {
            Log.i(TAG, "demo: tour complete at depth " + viewModel.camera.level.depth)
            demoActive = false
            demoTargets = null
            demoAnimator = null
            invalidate()
            return
        }
        val lvl = targets[demoLegIndex]
        // camera framing the level's origin at local scale 1 (pin framing)
        val target = Camera(1f, width / 2f, height / 2f, lvl)
        val from = Camera(
            viewModel.camera.scale, viewModel.camera.transX, viewModel.camera.transY,
            viewModel.camera.level,
        )
        Log.i(TAG, "demo: leg " + demoLegIndex + " -> level depth " + lvl.depth +
            " (magnification " + target.rootMagnification() + ")")
        demoAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DEMO_LEG_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                if (!demoActive) {
                    cancel()
                    return@addUpdateListener
                }
                viewModel.camera.setTo(from.interpolatedTo(target, animator.animatedValue as Float))
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (demoActive) {
                        demoLegIndex++
                        startDemoLeg()
                    }
                }
                override fun onAnimationStart(animation: android.animation.Animator) { }
                override fun onAnimationCancel(animation: android.animation.Animator) { }
                override fun onAnimationRepeat(animation: android.animation.Animator) { }
            })
            start()
        }
    }

    /** Cancels the in-flight demo tour (any ACTION_DOWN calls this). */
    fun cancelDemo() {
        if (demoActive || demoAnimator != null) {
            Log.i(TAG, "demo: tour cancelled at depth " + viewModel.camera.level.depth)
        }
        demoActive = false
        demoAnimator?.cancel()
        demoAnimator = null
        demoTargets = null
        invalidate()
    }

    fun samplePixel(screenX: Int, screenY: Int): Int {
        if (width <= 0 || height <= 0) return Color.BLACK
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        drawScene(canvas)
        val pixel = bmp.getPixel(screenX.coerceIn(0, width - 1), screenY.coerceIn(0, height - 1))
        bmp.recycle()
        return pixel
    }

    fun animateToBookmark(bookmark: Bookmark) {
        val tree = viewModel.document.levelTree
        val target = pinCamera(bookmark, tree, width.toFloat(), height.toFloat())
        val from = Camera(
            viewModel.camera.scale, viewModel.camera.transX, viewModel.camera.transY,
            viewModel.camera.level,
        )
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                viewModel.camera.setTo(from.interpolatedTo(target, animator.animatedValue as Float))
                invalidate()
            }
            start()
        }
    }

    companion object {
        private const val TAG = "ZoomArt"

        /** P4.5: one demo tour leg per level, ~2 s each. */
        private const val DEMO_LEG_MS = 2000L

        /** Camera framing [b] at its saved level, pin centered on screen. */
        fun pinCamera(b: Bookmark, tree: LevelTree, screenWidth: Float, screenHeight: Float): Camera {
            val s = b.scale.coerceIn(Camera.MIN_SCALE, 100f)
            val level = tree.byId(b.levelId) ?: tree.root
            return Camera(s, screenWidth / 2f - b.worldX * s, screenHeight / 2f - b.worldY * s, level)
        }
    }
}
