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

    /**
     * Renders everything on visible layers to a Bitmap. Content bboxes are
     * per-level (P0), so each item is first projected into ROOT coordinates
     * (Double — 100^depth never loses precision there) and the union is fit
     * into [maxEdgePx] with a root-level camera. If all content lives in a
     * deep level the root-frame fit degenerates below float range; in that
     * case the frame falls back to the dominant content level, fit in its
     * own local units (the render is identical, the floats stay bounded).
     */
    fun renderBitmap(view: ZoomableCanvasView, maxEdgePx: Int = 4096): Bitmap {
        val vm = view.viewModel
        val doc = vm.document
        val tree: LevelTree = doc.levelTree

        // Root-frame bbox (Double) + per-level local bboxes for the fallback.
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val localBoxes = HashMap<Long, FloatArray>() // levelId -> [minX,minY,maxX,maxY]
        var dominantLevel = vm.camera.level.id

        fun expandRoot(levelId: Long, x0: Float, y0: Float, x1: Float, y1: Float) {
            val lvl = tree.byId(levelId) ?: return
            minX = min(minX, vm.camera.localToRootX(lvl, x0))
            minY = min(minY, vm.camera.localToRootY(lvl, y0))
            maxX = max(maxX, vm.camera.localToRootX(lvl, x1))
            maxY = max(maxY, vm.camera.localToRootY(lvl, y1))
            val b = localBoxes.getOrPut(levelId) {
                floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            }
            b[0] = min(b[0], x0); b[1] = min(b[1], y0)
            b[2] = max(b[2], x1); b[3] = max(b[3], y1)
        }

        fun expandStroke(stroke: StrokeData) {
            if (stroke.points.isEmpty()) return
            val pad = stroke.brush.baseWidthWorld
            expandRoot(
                stroke.levelId,
                stroke.minX - pad, stroke.minY - pad,
                stroke.maxX + pad, stroke.maxY + pad,
            )
            dominantLevel = stroke.levelId // deep content dominates exports
        }

        for (layer in doc.layers) {
            if (!layer.isVisible) continue
            for (img in layer.images) {
                expandRoot(img.levelId, img.x, img.y, img.x + img.w, img.y + img.h)
                dominantLevel = img.levelId
            }
            for (stroke in layer.strokes) expandStroke(stroke)
            // P3: shapes and smudges join the export bbox union (stamps via
            // their own min/max +/- radius), so exports frame ALL content
            for (shape in layer.shapes) {
                val b = shape.bbox()
                val pad = shape.outlineWidthWorld // generous: outline can straddle
                expandRoot(shape.levelId, b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
                dominantLevel = shape.levelId
            }
            for (smudge in layer.smudges) {
                if (smudge.stamps.isEmpty()) continue
                var sx0 = Float.MAX_VALUE; var sy0 = Float.MAX_VALUE
                var sx1 = -Float.MAX_VALUE; var sy1 = -Float.MAX_VALUE
                for (st in smudge.stamps) {
                    sx0 = min(sx0, st.x - st.radius); sy0 = min(sy0, st.y - st.radius)
                    sx1 = max(sx1, st.x + st.radius); sy1 = max(sy1, st.y + st.radius)
                }
                expandRoot(smudge.levelId, sx0, sy0, sx1, sy1)
                dominantLevel = smudge.levelId
            }
        }

        var frameLevel = tree.root
        var fx0: Double
        var fy0: Double
        var fx1: Double
        var fy1: Double

        val rootDegenerate = maxX < minX ||
            !fitScale(maxX - minX, maxY - minY, maxEdgePx).isFinite()
        if (rootDegenerate) {
            val box = localBoxes[dominantLevel]
                ?: vm.camera.visibleLocalRect(view.width.toFloat(), view.height.toFloat())
            frameLevel = tree.byId(dominantLevel) ?: tree.root
            fx0 = box[0].toDouble(); fy0 = box[1].toDouble()
            fx1 = box[2].toDouble(); fy1 = box[3].toDouble()
        } else {
            fx0 = minX; fy0 = minY; fx1 = maxX; fy1 = maxY
        }

        val worldW = max(fx1 - fx0, 1e-6)
        val worldH = max(fy1 - fy0, 1e-6)
        val scale = fitScale(worldW, worldH, maxEdgePx)
        val outW = max(1, ceil(worldW * scale).toInt().coerceAtMost(maxEdgePx))
        val outH = max(1, ceil(worldH * scale).toInt().coerceAtMost(maxEdgePx))
        val camera = Camera(
            scale.toFloat(),
            (-fx0 * scale).toFloat(),
            (-fy0 * scale).toFloat(),
            frameLevel,
        )
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        view.drawScene(canvas, camera, includeInProgress = false)
        return bmp
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
