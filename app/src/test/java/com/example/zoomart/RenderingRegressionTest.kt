package com.example.zoomart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.model.*
import com.example.zoomart.ui.ZoomableCanvasView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderingRegressionTest {
    private fun view() = ZoomableCanvasView(ApplicationProvider.getApplicationContext()).apply { layout(0, 0, 128, 128) }
    private fun line(color: Int) = StrokeData(BrushSpec(baseWidthWorld = 30f, color = color)).apply {
        addPoint(StrokePoint(20f, 64f, 1f)); addPoint(StrokePoint(108f, 64f, 1f))
    }
    private fun pixel(v: ZoomableCanvasView, live: Boolean = false): Int {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp); canvas.drawColor(Color.WHITE); v.drawScene(canvas, includeInProgress = live)
        val result = bmp.getPixel(64, 64); bmp.recycle(); return result
    }
    @Test fun upperLayerStrokeCoversLowerLayerShape() {
        val v = view(); val vm = v.viewModel
        vm.document.setActiveLayer(0)
        vm.beginShapeGesture(20f, 20f); vm.extendShapeGesture(108f, 108f)
        val shape = vm.endShapeGesture()!!
        shape.fillOn = true
        shape.fillColor = Color.RED
        vm.document.setActiveLayer(1); vm.document.commitStroke(line(Color.BLUE))
        assertEquals(Color.BLUE, pixel(v))
    }
    @Test fun layerOpacityCompositesOverlappingMarksOnce() {
        val v = view(); val doc = v.viewModel.document
        doc.activeLayer.opacity = 0.5f
        doc.commitStroke(line(Color.BLACK)); doc.commitStroke(line(Color.BLACK))
        val value = Color.red(pixel(v))
        assertTrue("Expected half-opacity gray, got $value", value in 126..129)
    }
    @Test fun liveStrokeRemainsBelowUpperLayer() {
        val v = view(); val vm = v.viewModel
        vm.document.commitStroke(line(Color.BLUE))
        vm.document.setActiveLayer(0)
        vm.inProgress = line(Color.RED)
        assertEquals(Color.BLUE, pixel(v, true))
    }

    @Test fun cachedOutlineTracksTransformAndUndo() {
        val v = view(); val doc = v.viewModel.document
        val stroke = line(Color.BLUE)
        doc.commitStroke(stroke)
        assertEquals(Color.BLUE, pixel(v)) // warms outline cache
        doc.selection.selected.add(stroke)
        doc.selection.beginTransform(); doc.selection.translateBy(0f, 60f); doc.selection.endTransform()
        assertEquals(Color.WHITE, pixel(v))
        doc.undo()
        assertEquals(Color.BLUE, pixel(v))
    }

    @Test fun wholeDrawingExportFitsDeepContentWithoutRootPrecisionLoss() {
        val v = view()
        repeat(180) { v.zoomBy(100f) }
        val stroke = line(Color.BLUE).apply { levelId = v.viewModel.camera.level.id }
        v.viewModel.document.commitStroke(stroke)
        val bitmap = com.example.zoomart.export.PngExporter.renderBitmap(v, 256)
        assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        bitmap.recycle()
    }

    @Test fun stampSpacingDoesNotDependOnInputSamplingDensity() {
        val v = view()
        fun render(step: Int): Bitmap {
            val stroke = StrokeData(BrushSpec(baseWidthWorld = 6f, spacingWorld = 8f))
            for (x in 8..120 step step) stroke.addPoint(StrokePoint(x.toFloat(),64f,1f))
            return Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).also {
                v.drawStroke(Canvas(it),stroke,1f)
            }
        }
        val sparse=render(8); val dense=render(1)
        assertTrue(sparse.sameAs(dense))
        sparse.recycle(); dense.recycle()
    }

    @Test fun translucentStrokeCapsDoNotDarkenAtTheirOverlap() {
        val v=view()
        val stroke=StrokeData(BrushSpec(baseWidthWorld=20f,opacity=0.5f)).apply {
            addPoint(StrokePoint(24f,64f,1f)); addPoint(StrokePoint(104f,64f,1f))
        }
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
        v.drawStroke(canvas,stroke,1f)
        assertEquals(bitmap.getPixel(64,64),bitmap.getPixel(26,64))
        bitmap.recycle()
    }
    @Test fun calligraphyEndsDoNotHaveRoundBulbs() {
        val v=view()
        val stroke=StrokeData(BrushSpec(baseWidthWorld=20f,angleDeg=45f)).apply {
            addPoint(StrokePoint(24f,64f,1f)); addPoint(StrokePoint(104f,64f,1f))
        }
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        v.drawStroke(Canvas(bitmap),stroke,1f)
        assertEquals(0, Color.alpha(bitmap.getPixel(15,64)))
        assertEquals(0, Color.alpha(bitmap.getPixel(113,64)))
        assertTrue(Color.alpha(bitmap.getPixel(64,64)) > 0)
        bitmap.recycle()
    }
}
