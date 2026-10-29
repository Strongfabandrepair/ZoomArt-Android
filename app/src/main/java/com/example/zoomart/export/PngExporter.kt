package com.example.zoomart.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.zoomart.model.Camera
import com.example.zoomart.model.LevelTree
import com.example.zoomart.model.StrokeData
import com.example.zoomart.ui.ZoomableCanvasView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object PngExporter {

    /** Export the visible framing, including canvas rotation, without losing deep-zoom detail. */
    fun renderViewport(view: ZoomableCanvasView, maxEdgePx: Int = 4096): Bitmap {
        require(maxEdgePx > 0 && view.width > 0 && view.height > 0)
        val factor = maxEdgePx.toFloat() / max(view.width, view.height)
        val width = (view.width * factor).toInt().coerceAtLeast(1)
        val height = (view.height * factor).toInt().coerceAtLeast(1)
        val source = view.viewModel.camera
        val camera = Camera(source.scale * factor, source.transX * factor, source.transY * factor, source.level)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.rotate(view.canvasRotationDegrees, width / 2f, height / 2f)
        view.drawScene(canvas, camera, includeInProgress = false)
        return bitmap
    }

    /** Fit content in its nearest shared zoom frame, avoiding root-space precision loss. */
    fun renderBitmap(view: ZoomableCanvasView, maxEdgePx: Int = 4096): Bitmap {
        require(maxEdgePx > 0)
        val vm = view.viewModel
        data class Box(val level: com.example.zoomart.model.ZoomLevel, val bounds: FloatArray)
        val boxes = mutableListOf<Box>()
        fun add(levelId: Long, bounds: FloatArray, pad: Float = 0f) {
            val level = vm.document.levelTree.byId(levelId) ?: return
            boxes.add(Box(level, floatArrayOf(bounds[0] - pad, bounds[1] - pad,
                bounds[2] + pad, bounds[3] + pad)))
        }
        for (layer in vm.document.layers) {
            if (!layer.isVisible || layer.opacity <= 0f) continue
            for (img in layer.images) if (img.alpha > 0f) add(img.levelId, img.bbox())
            for (stroke in layer.strokes) if (stroke.points.isNotEmpty()) add(stroke.levelId,
                floatArrayOf(stroke.minX, stroke.minY, stroke.maxX, stroke.maxY),
                stroke.brush.baseWidthWorld + stroke.brush.scatterWorld)
            for (shape in layer.shapes) add(shape.levelId, shape.bbox(), shape.outlineWidthWorld)
            for (smudge in layer.smudges) for (stamp in smudge.stamps) add(smudge.levelId,
                floatArrayOf(stamp.x, stamp.y, stamp.x, stamp.y), stamp.radius)
        }
        if (boxes.isEmpty()) return renderViewport(view, maxEdgePx)
        var frame = boxes.first().level
        for (box in boxes) {
            val ancestors = generateSequence(box.level) { it.parent }.toSet()
            while (frame !in ancestors) frame = checkNotNull(frame.parent)
        }
        fun coordinate(box: Box, value: Float, x: Boolean): Double {
            var level = box.level
            var result = value.toDouble()
            while (level !== frame) {
                result = (if (x) level.originX else level.originY) + result / level.scaleToParent
                level = checkNotNull(level.parent)
            }
            return result
        }
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (box in boxes) {
            minX = min(minX, coordinate(box, box.bounds[0], true))
            minY = min(minY, coordinate(box, box.bounds[1], false))
            maxX = max(maxX, coordinate(box, box.bounds[2], true))
            maxY = max(maxY, coordinate(box, box.bounds[3], false))
        }
        val worldW = max(maxX - minX, 1e-6)
        val worldH = max(maxY - minY, 1e-6)
        val scale = fitScale(worldW, worldH, maxEdgePx)
        check(scale.toFloat().isFinite() && scale > 0) { "Drawing bounds cannot be exported" }
        val width = ceil(worldW * scale).toInt().coerceIn(1, maxEdgePx)
        val height = ceil(worldH * scale).toInt().coerceIn(1, maxEdgePx)
        val camera = Camera(scale.toFloat(), (-minX * scale).toFloat(), (-minY * scale).toFloat(), frame)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        view.drawScene(canvas, camera, includeInProgress = false, allDescendants = true)
        return bitmap
    }

    private fun fitScale(worldW: Double, worldH: Double, maxEdgePx: Int): Double =
        min(maxEdgePx / worldW, maxEdgePx / worldH)

    /** Legacy file-based export (app-specific dir); MediaSaver supersedes it on API 29+. */
    fun export(view: ZoomableCanvasView, outDir: File, maxEdgePx: Int = 4096): File {
        val bmp = renderBitmap(view, maxEdgePx)
        outDir.mkdirs()
        val file = File(outDir, "zoomart_" + System.currentTimeMillis() + ".png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }
}
