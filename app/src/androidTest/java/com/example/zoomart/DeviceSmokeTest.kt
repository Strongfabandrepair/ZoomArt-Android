package com.example.zoomart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zoomart.export.PngExporter
import com.example.zoomart.export.MediaSaver
import com.example.zoomart.export.ZoomVideoExporter
import com.example.zoomart.model.*
import com.example.zoomart.ui.ZoomableCanvasView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated fixtures: never load, replace, or clear the user's saved artwork. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun fixture(): ZoomableCanvasView {
        lateinit var v: ZoomableCanvasView
        main {
            v = ZoomableCanvasView(instrumentation.targetContext)
            v.layout(0, 0, 1280, 720)
        }
        return v
    }
    private fun line(color: Int) = StrokeData(BrushSpec(baseWidthWorld = 50f, color = color)).apply {
        addPoint(StrokePoint(100f, 360f, 1f)); addPoint(StrokePoint(1180f, 360f, 1f))
    }
    private fun event(v: ZoomableCanvasView, action: Int, xy: List<Pair<Float, Float>>, tools: List<Int>) {
        val props = tools.mapIndexed { index, tool -> MotionEvent.PointerProperties().apply {
            id = index; toolType = tool
        } }.toTypedArray()
        val coords = xy.map { (px, py) -> MotionEvent.PointerCoords().apply {
            x = px; y = py; pressure = 1f; size = 1f
        } }.toTypedArray()
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, action, props.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    @Test fun stylusAfterFingerUsesStylusPointerAndUndoWorks() {
        val v = fixture()
        main {
            val f = MotionEvent.TOOL_TYPE_FINGER; val p = MotionEvent.TOOL_TYPE_STYLUS
            event(v, MotionEvent.ACTION_DOWN, listOf(800f to 500f), listOf(f))
            event(v, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(800f to 500f, 100f to 100f), listOf(f, p))
            event(v, MotionEvent.ACTION_MOVE, listOf(810f to 510f, 200f to 150f), listOf(f, p))
            event(v, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(810f to 510f, 200f to 150f), listOf(f, p))
            event(v, MotionEvent.ACTION_UP, listOf(810f to 510f), listOf(f))
            val stroke = v.viewModel.document.activeLayer.strokes.single()
            assertEquals(200f, stroke.points.last().x, 0.01f)
            assertEquals(150f, stroke.points.last().y, 0.01f)
            v.viewModel.undo(); assertTrue(v.viewModel.document.activeLayer.strokes.isEmpty())
            v.viewModel.redo(); assertEquals(1, v.viewModel.document.activeLayer.strokes.size)
        }
    }

    @Test fun nativeLayerOrderOpacityAndExportAreCorrect() {
        val v = fixture()
        main {
            val vm = v.viewModel
            vm.document.setActiveLayer(0)
            vm.beginShapeGesture(10f, 10f); vm.extendShapeGesture(1270f, 710f)
            vm.endShapeGesture()!!.fillColor = Color.RED
            vm.document.setActiveLayer(1)
            vm.document.commitStroke(line(Color.BLUE))
            val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp); v.drawScene(canvas, includeInProgress = false)
            assertEquals(Color.BLUE, bmp.getPixel(640, 360))
            bmp.recycle()
            val png = PngExporter.renderBitmap(v, 1024)
            val file = File(instrumentation.targetContext.cacheDir, "review_smoke.png")
            file.outputStream().use { assertTrue(png.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.length() > 100)
            png.recycle()
        }
    }

    @Test fun deepZoomSessionRoundTripKeepsGeometry() {
        val v = fixture()
        main {
            repeat(12) { v.zoomBy(100f) }
            assertEquals(12, v.viewModel.camera.level.depth)
            v.viewModel.beginStrokeAtScreen(100f, 100f, 1f)
            v.viewModel.extendStrokeAtScreen(200f, 200f, 1f)
            v.viewModel.endStrokeLazy()
            val payload = v.viewModel.serialize()
            v.viewModel.restore(DocumentCodec.fromJson(payload))
            assertEquals(payload, v.viewModel.serialize())
            val b = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            v.drawScene(Canvas(b), includeInProgress = false)
            b.recycle()
        }
    }

    @Test fun hardwareVideoExportCompletesAndDecodes() {
        val v = fixture()
        main { v.viewModel.document.commitStroke(line(Color.BLUE)) }
        val output = File(instrumentation.targetContext.cacheDir, "review_smoke.mp4")
        val pin = Bookmark(640f, 360f, 1f, "Center")
        ZoomVideoExporter.export(v, listOf(pin), output, fps = 12, secondsPerHop = 1f)
        assertTrue(output.length() > 1000)
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(output.absolutePath)
            assertEquals("1280", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("duration=$duration", duration in 800L..1500L)
            val frame = reader.getFrameAtTime(500_000)!!
            val color = frame.getPixel(frame.width / 2, frame.height / 2)
            assertTrue("Blue stroke must survive YUV encoding", Color.blue(color) > Color.red(color) + 80)
            frame.recycle()
        } finally { reader.release() }
    }

    @Test fun fingerDrawingIsOptionalAndSecondFingerCancelsUncommittedInk() {
        val v = fixture()
        main {
            val f = MotionEvent.TOOL_TYPE_FINGER
            v.fingerDrawingEnabled = true
            event(v, MotionEvent.ACTION_DOWN, listOf(100f to 100f), listOf(f))
            event(v, MotionEvent.ACTION_MOVE, listOf(200f to 150f), listOf(f))
            event(v, MotionEvent.ACTION_UP, listOf(200f to 150f), listOf(f))
            assertEquals(1, v.viewModel.document.activeLayer.strokes.size)
            event(v, MotionEvent.ACTION_DOWN, listOf(300f to 200f), listOf(f))
            event(v, MotionEvent.ACTION_MOVE, listOf(350f to 250f), listOf(f))
            event(v, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(350f to 250f, 500f to 400f), listOf(f, f))
            assertNull(v.viewModel.inProgress)
            event(v, MotionEvent.ACTION_CANCEL, listOf(350f to 250f, 500f to 400f), listOf(f, f))
            assertEquals(1, v.viewModel.document.activeLayer.strokes.size)
        }
    }

    @Test fun cachedRenderingProbe() {
        val v = fixture()
        main {
            repeat(1000) { n ->
                val s = StrokeData(BrushSpec(baseWidthWorld = 2f))
                repeat(40) { p -> s.addPoint(StrokePoint(
                    (n % 40) * 30f + p * 0.5f, (n / 40) * 26f + p * 0.2f, 1f)) }
                v.viewModel.document.commitStroke(s)
            }
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val coldStart = System.nanoTime()
            v.drawScene(canvas, includeInProgress = false)
            val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0
            val samples = (0 until 20).map {
                val start = System.nanoTime()
                canvas.drawColor(Color.WHITE)
                v.drawScene(canvas, includeInProgress = false)
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            android.util.Log.i("ZoomArtReview", "1000 strokes/40000 points software render: cold=$coldMs ms median=${samples[10]} ms p95=${samples[18]} ms")
            bitmap.recycle()
        }
    }

    @Test fun galleryExportPublishesReadablePng() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val context = instrumentation.targetContext
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val uri = android.net.Uri.parse(checkNotNull(MediaSaver.saveToGallery(
            context, bitmap, "zoomart_test_${System.currentTimeMillis()}.png")))
        try {
            val decoded = context.contentResolver.openInputStream(uri).use {
                android.graphics.BitmapFactory.decodeStream(it)
            }!!
            assertEquals(Color.BLUE, decoded.getPixel(16, 16))
            decoded.recycle()
        } finally {
            bitmap.recycle()
            context.contentResolver.delete(uri, null, null)
        }
    }
}
