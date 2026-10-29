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
        internal set

    /** Last two-finger angle for rotation delta tracking. */
    private var lastRotateAngle: Float? = null

    val viewModel = DrawingViewModel()

    var tool: Tool = Tool.DRAW
    /** Opt-in phone workflow: one finger draws; a second finger cancels the mark and navigates. */
    var fingerDrawingEnabled = false
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
    private data class CachedOutline(val revision: Long, val taper: Float, val path: Path, val bytes: Int, val firstRadius: Float, val lastRadius: Float)
    private val outlineCache = object : android.util.LruCache<StrokeData, CachedOutline>(
        (Runtime.getRuntime().maxMemory() / 12).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: StrokeData, value: CachedOutline): Int = value.bytes
    }

    private val imageCache = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

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
    private var bookmarkAnimator: ValueAnimator? = null

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
                val focus = unrotateScreen(detector.focusX, detector.focusY)
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (selectionGesture) {
                    viewModel.document.selection.scaleBy(detector.scaleFactor)
                    invalidate()
                    return true
                }
                val cam = viewModel.camera
                val focus = unrotateScreen(detector.focusX, detector.focusY)
                cam.panBy(focus[0] - lastFocusX, focus[1] - lastFocusY)
                lastFocusX = focus[0]
                lastFocusY = focus[1]
                cam.zoomAt(focus[0], focus[1], cam.scale * detector.scaleFactor)
                if (cam.applyLevelTransitions(focus[0], focus[1])) {
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
    private var activePenId = -1
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
                lastPenScreenX=x;lastPenScreenY=y
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

    fun cancelActiveGesture() {
        viewModel.cancelGesture()
        smudgeSnapshot?.recycle();smudgeSnapshot=null
        isDrawing=false;penGesture=false;activePenId=-1;fingerPanning=false;pinPending=false
        isTransformingSelection=false;resizingCorner=-1;marqueeWorld=null;lastRotateAngle=null
        ignoredPalmIds.clear();scroller.forceFinished(true)
        onCanvasActivityChanged?.invoke(false);invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cam = viewModel.camera
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // P4.5: ANY touch cancels the in-flight demo tour
                if (demoActive) cancelDemo()
                bookmarkAnimator?.cancel()
                scroller.forceFinished(true)
                penGesture = isPenEvent(event) || fingerDrawingEnabled
                if (penGesture) {
                    activePenId = event.getPointerId(0)
                    fingerPanning = false
                    val uv = unrotateScreen(event.x, event.y)
                    startPenGesture(
                        event.getToolType(0), uv[0], uv[1],
                        pressure01(event.pressure),
                    )
                    // Long-press to drop a pin (replaces the too-sensitive double-tap).
                    // Armed only when a pen gesture started; cancelled on move/lift.
                    if (penGesture && isPenEvent(event) && gestureTool == Tool.DRAW) {
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
                    activePenId = event.getPointerId(idx)
                    fingerPanning = false
                    val uv = unrotateScreen(event.getX(idx), event.getY(idx))
                    startPenGesture(
                        event.getToolType(idx), uv[0], uv[1],
                        pressure01(event.getPressure(idx)),
                    )
                    return true // pen pointers stay out of the pinch detector
                }
                val activeIndex = event.findPointerIndex(activePenId)
                if (isDrawing && penGesture && activeIndex >= 0 && isPenEvent(event, activeIndex)) {
                    // palm rejection: a finger while the pen works is ignored
                    ignoredPalmIds.add(event.getPointerId(idx))
                    return true
                }
                if (isDrawing) {
                    // Erasing and transforms modify existing objects live: preserve their undo step.
                    if (gestureTool == Tool.ERASE) viewModel.endErase()
                    if (gestureTool == Tool.SELECT) viewModel.document.selection.endTransform()
                    isTransformingSelection = false
                    marqueeWorld = null
                    smudgeSnapshot?.recycle()
                    smudgeSnapshot = null
                }
                viewModel.inProgress = null
                viewModel.inProgressShape = null
                viewModel.inProgressSmudge = null
                isDrawing = false
                penGesture = false
                onCanvasActivityChanged?.invoke(false)
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
                val penIndex = event.findPointerIndex(activePenId)
                // a moving pen is drawing, not holding for a pin — cancel pending pin
                if (pinPending && penIndex >= 0) {
                    val dx = abs(event.getX(penIndex) - lastPinScreenX)
                    val dy = abs(event.getY(penIndex) - lastPinScreenY)
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat() > pinMoveThresholdDp * resources.displayMetrics.density) {
                        pinPending = false
                    }
                }
                if (isDrawing && penGesture && penIndex >= 0) {
                    when (gestureTool) {
                        Tool.DRAW -> {
                            for (i in 0 until event.historySize) {
                                val uv = unrotateScreen(
                                    event.getHistoricalX(penIndex, i), event.getHistoricalY(penIndex, i))
                                viewModel.extendStrokeAtScreen(
                                    uv[0], uv[1],
                                    pressure01(event.getHistoricalPressure(penIndex, i)),
                                )
                            }
                            val uv = unrotateScreen(event.getX(penIndex), event.getY(penIndex))
                            viewModel.extendStrokeAtScreen(
                                uv[0], uv[1], pressure01(event.getPressure(penIndex)))
                            lastPenScreenX = uv[0]
                            lastPenScreenY = uv[1]
                        }
                        Tool.ERASE -> {
                            val uv = unrotateScreen(event.getX(penIndex), event.getY(penIndex))
                            lastEraserScreenX = uv[0]
                            lastEraserScreenY = uv[1]
                            viewModel.eraseTo(
                                cam.screenToLocalX(uv[0]),
                                cam.screenToLocalY(uv[1]),
                            )
                        }
                        Tool.SHAPE -> {
                            // P3.1: rubber-band the preview to the pen
                            val uv = unrotateScreen(event.getX(penIndex), event.getY(penIndex))
                            viewModel.extendShapeGesture(
                                cam.screenToLocalX(uv[0]),
                                cam.screenToLocalY(uv[1]),
                            )
                        }
                        Tool.SMUDGE -> {
                            // P3.3: sample + carry + deposit along the drag
                            val uv = unrotateScreen(event.getX(penIndex), event.getY(penIndex))
                            viewModel.extendSmudge(
                                uv[0], uv[1], pressure01(event.getPressure(penIndex)), smudgeSampler())
                        }
                        Tool.SELECT -> {
                            val selectionPoint = unrotateScreen(event.getX(penIndex), event.getY(penIndex))
                            if (resizingCorner >= 0 && isTransformingSelection) {
                                // P3.1: corner resize — incremental uniform
                                // scale about the opposite corner
                                val newDist = dist(
                                    selectionPoint[0], selectionPoint[1], resizePivotScreenX, resizePivotScreenY)
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
                                val dx = cam.screenToLocalX(selectionPoint[0]) -
                                    cam.screenToLocalX(lastSelScreenX)
                                val dy = cam.screenToLocalY(selectionPoint[1]) -
                                    cam.screenToLocalY(lastSelScreenY)
                                viewModel.document.selection.translateBy(dx, dy)
                            } else {
                                val m = marqueeWorld
                                if (m != null) {
                                    m[2] = cam.screenToLocalX(selectionPoint[0])
                                    m[3] = cam.screenToLocalY(selectionPoint[1])
                                }
                            }
                            lastSelScreenX = selectionPoint[0]
                            lastSelScreenY = selectionPoint[1]
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
                        val from = unrotateScreen(event.x - dx, event.y - dy)
                        val to = unrotateScreen(event.x, event.y)
                        cam.panBy(to[0] - from[0], to[1] - from[1])
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
                        ).let { Math.toDegrees(it).toFloat() }
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
                if (event.pointerCount <= 2) lastRotateAngle = null
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
                if (event.actionMasked == MotionEvent.ACTION_UP && pinPending &&
                    SystemClock.uptimeMillis() - pinDownTime >= pinLongPressDelay) {
                    placePinAtLastPin()
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelActiveGesture()
                else if (isDrawing && penGesture) {
                    val index=event.findPointerIndex(activePenId)
                    if(index>=0 && gestureTool==Tool.DRAW) {
                        val uv=unrotateScreen(event.getX(index),event.getY(index))
                        if (uv[0] != lastPenScreenX || uv[1] != lastPenScreenY)
                            viewModel.extendStrokeAtScreen(uv[0],uv[1],pressure01(event.getPressure(index)))
                    }
                    finishPenGesture()
                }
                onCanvasActivityChanged?.invoke(false)
                activePenId = -1
                lastRotateAngle = null
                fingerPanning = false
                penGesture = false
                ignoredPalmIds.clear()
                // Long-press pin: if the pen was held long enough, drop a pin on release.
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
            val from = unrotateScreen(0f, 0f)
            val to = unrotateScreen(-dx.toFloat(), -dy.toFloat())
            viewModel.camera.panBy(to[0] - from[0], to[1] - from[1])
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

    /** Composes complete layers in stack order; translucent/blended layers use one offscreen sheet.
     * The index culls candidates while document order controls overlapping strokes.
     * Export callers use a detached snapshot and omit live gestures and bookmark chrome.
     */
    fun drawScene(
        canvas: Canvas,
        camera: Camera = viewModel.camera,
        includeInProgress: Boolean = true,
        allDescendants: Boolean = false,
    ) {
        val doc = viewModel.document
        val tree = camera.level.tree
        // clipBounds includes the inverse canvas rotation, avoiding clipped corners.
        val clip = canvas.clipBounds
        data class Pass(val level: ZoomLevel, val k: Float, val tx: Float, val ty: Float,
                        val rect: FloatArray, val visible: Set<StrokeData>)
        val descendants = if (allDescendants) tree.allLevels().filter { candidate ->
            candidate !== camera.level && generateSequence(candidate.parent) { it.parent }.any { it === camera.level }
        } else tree.childrenOf(camera.level)
        val levels = generateSequence(camera.level) { it.parent }.toList().asReversed() + descendants
        val passes = levels.mapNotNull { level ->
            val t = camera.transformTo(level)
            val k = t[0]
            if (!k.isFinite() || k !in minRenderableScale..maxRenderableScale ||
                !t[1].isFinite() || !t[2].isFinite()) return@mapNotNull null
            val rect = floatArrayOf((clip.left - t[1]) / k, (clip.top - t[2]) / k,
                (clip.right - t[1]) / k, (clip.bottom - t[2]) / k)
            val visible = doc.strokeIndex.queryVisibleLevel(level.id,
                rect[0], rect[1], rect[2], rect[3]).map { it.first }.toHashSet()
            Pass(level, k, t[1], t[2], rect, visible)
        }
        fun intersects(b: FloatArray, r: FloatArray, pad: Float = 0f) =
            b[2] + pad >= r[0] && b[0] - pad <= r[2] &&
                b[3] + pad >= r[1] && b[1] - pad <= r[3]

        // Always paint whole sheets in layer order, including the live gesture.
        // Opacity belongs to the composed sheet, not each overlapping mark.
        for (layer in doc.layers) {
            if (!layer.isVisible || layer.opacity <= 0f) continue
            val grouped = layer.blend != LayerBlend.NORMAL || layer.opacity < 1f
            if (grouped) {
                applyBlendTo(layerBlendPaint, layer.blend)
                layerBlendPaint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                canvas.saveLayer(null, layerBlendPaint)
            }
            for (pass in passes) {
                canvas.save()
                canvas.translate(pass.tx, pass.ty)
                canvas.scale(pass.k, pass.k)
                for (img in layer.images) {
                    if (img.levelId == pass.level.id && intersects(img.bbox(), pass.rect))
                        drawImage(canvas, img, 1f)
                }
                // Grid traversal order is spatial, not paint order.
                for (stroke in layer.strokes) {
                    if (stroke in pass.visible) drawStroke(canvas, stroke, 1f)
                }
                for (shape in layer.shapes) {
                    if (shape.levelId == pass.level.id &&
                        intersects(shape.bbox(), pass.rect, shape.outlineWidthWorld))
                        drawShape(canvas, shape, 1f)
                }
                drawSmudgesOfLevel(canvas, layer, pass.level.id, 1f)
                if (includeInProgress && layer === doc.activeLayer) {
                    viewModel.inProgress?.takeIf { it.levelId == pass.level.id }?.let { stroke ->
                        val pts = if (viewModel.lazyActive && stroke.points.size >= 2)
                            LazyBrush.smoothPoints(stroke.points.toList()) else null
                        drawStroke(canvas, stroke, 1f, pts)
                    }
                    viewModel.inProgressShape?.takeIf { it.levelId == pass.level.id }?.let {
                        drawShape(canvas, it, 1f)
                    }
                    viewModel.inProgressSmudge?.takeIf { it.levelId == pass.level.id }?.let {
                        for (stamp in it.stamps) drawStamp(canvas, stamp, 1f)
                    }
                }
                canvas.restore()
            }
            if (grouped) canvas.restore()
        }
        // Navigation annotations are editor chrome, never exported artwork.
        if (includeInProgress) for (bookmark in doc.bookmarks) {
            val level = tree.byId(bookmark.levelId) ?: continue
            if (level !in levels) continue
            val t = camera.transformTo(level)
            if (!t[0].isFinite() || t[0] !in minRenderableScale..maxRenderableScale) continue
            val x = bookmark.worldX * t[0] + t[1]
            val y = bookmark.worldY * t[0] + t[2]
            if (x >= clip.left - 20 && x <= clip.right + 20 &&
                y >= clip.top - 20 && y <= clip.bottom + 20)
                canvas.drawCircle(x, y, 10f, bookmarkPaint)
        }
    }

    private fun drawImage(canvas: Canvas, img: PlacedImage, layerOpacity: Float) {
        val bmp = imageCache.get(img.path) ?: run {
            val f = File(context.filesDir, img.path)
            if (!f.exists()) return
            val decoded = BitmapFactory.decodeFile(f.absolutePath) ?: return
            imageCache.put(img.path, decoded)
            decoded
        }
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

    internal fun drawStroke(
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
        val pts = ptsOverride ?: stroke.points
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
            val rnd = java.util.Random(0x5EEDL)
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
            var nextStamp = brush.spacingWorld.coerceAtLeast(0.01f)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len < 1e-4f) continue
                var t = nextStamp
                while (t <= len) {
                    val f = t / len
                    val mid = a.pressure01 + (b.pressure01 - a.pressure01) * f
                    stamp(a.x + dx * f, a.y + dy * f,
                        brush.widthForPressure(mid) * 0.5f, mid)
                    t += brush.spacingWorld.coerceAtLeast(0.01f)
                }
                nextStamp = t - len
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
        val cacheable = ptsOverride == null && stroke !== viewModel.inProgress
        val cached = if (cacheable) outlineCache.get(stroke)?.takeIf {
            it.revision == stroke.geometryRevision && it.taper == stroke.taper
        } else null
        if (cached != null) {
            canvas.drawPath(cached.path, strokeFillPaint)
            return
        }
        val outline = if (cacheable) Path() else scratchPath.apply { reset() }
        if (StrokeOutline.buildPath(pts, brush, AndroidPathSink(outline), stroke.taper)) {
            val capF = if (stroke.taper > 0f) LazyBrush.taperFactors(pts, stroke.taper) else null
            val first = pts.first(); val last = pts.last()
            val rFirst = brush.widthForPressure(first.pressure01) * 0.5f * (capF?.get(0) ?: 1f)
            val rLast = brush.widthForPressure(last.pressure01) * 0.5f * (capF?.last() ?: 1f)
            // Merge caps into the outline so translucent ink is composited only once.
            fun cap(point: StrokePoint, radius: Float) {
                if (radius <= 0.05f) return
                val circle = Path().apply { addCircle(point.x, point.y, radius, Path.Direction.CW) }
                outline.op(circle, Path.Op.UNION)
            }
            cap(first, rFirst); cap(last, rLast)
            if (cacheable) outlineCache.put(stroke, CachedOutline(stroke.geometryRevision,
                stroke.taper, outline, 128 + pts.size.coerceAtMost(100_000) * 32, rFirst, rLast))
            canvas.drawPath(outline, strokeFillPaint)
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
        bookmarkAnimator?.cancel()
        val tree = viewModel.document.levelTree
        val target = pinCamera(bookmark, tree, width.toFloat(), height.toFloat())
        val from = Camera(
            viewModel.camera.scale, viewModel.camera.transX, viewModel.camera.transY,
            viewModel.camera.level,
        )
        bookmarkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                viewModel.camera.setTo(from.interpolatedTo(target, animator.animatedValue as Float))
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        if (isDrawing) cancelActiveGesture()
        cancelDemo()
        bookmarkAnimator?.cancel()
        scroller.forceFinished(true)
        smudgeSnapshot?.recycle()
        smudgeSnapshot = null
        imageCache.evictAll()
        outlineCache.evictAll()
        super.onDetachedFromWindow()
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
