package com.example.zoomart

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zoomart.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Informational frame profile on a real window; functional tests do not assert timing thresholds. */
@RunWith(AndroidJUnit4::class)
class DrawingFrameProfileTest {
    @Test fun hardwareCanvasPanZoomProfile() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,DrawingProfileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DrawingProfileActivity
        val thread=HandlerThread("frame-profile").apply { start() }
        try {
            for(count in listOf(1000,10000)) {
                val samples=Collections.synchronizedList(mutableListOf<Double>())
                val dropped=java.util.concurrent.atomic.AtomicInteger()
                val done=CountDownLatch(1)
                val listener=Window.OnFrameMetricsAvailableListener { _,metrics,lost ->
                    samples.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1_000_000.0);dropped.addAndGet(lost)
                }
                instrumentation.runOnMainSync {
                    val view=activity.canvas;val doc=view.viewModel.document
                    doc.layers.forEach { it.strokes.clear() }
                    repeat(count) { n ->
                        val stroke=StrokeData(BrushSpec(baseWidthWorld=2f))
                        repeat(40) { p -> stroke.addPoint(StrokePoint((n%100)*12f+p*0.2f,(n/100)*6f+p*0.12f,0.6f)) }
                        doc.activeLayer.strokes.add(stroke)
                    }
                    doc.reindex()
                    activity.window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))
                    ValueAnimator.ofFloat(0f,1f).apply {
                        duration=6000
                        addUpdateListener { animation ->
                            val t=animation.animatedValue as Float
                            view.viewModel.camera.scale=1f+t
                            view.viewModel.camera.transX=-t*300f
                            view.invalidate()
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) { done.countDown() }
                        })
                        start()
                    }
                }
                assertTrue(done.await(30,TimeUnit.SECONDS))
                instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
                val drain=CountDownLatch(1);Handler(thread.looper).post { drain.countDown() };assertTrue(drain.await(5,TimeUnit.SECONDS))
                val sorted=synchronized(samples) { samples.sorted() }
                assertTrue("No frame metrics collected",sorted.isNotEmpty())
                val report="$count strokes/${count*40} points: frames=${sorted.size}, p50=${sorted[sorted.size/2]} ms, p95=${sorted[(sorted.size*0.95).toInt().coerceAtMost(sorted.lastIndex)]} ms, over16.7=${sorted.count { it>16.7 }}, missedMetrics=${dropped.get()}"
                android.util.Log.i("ZoomArtFrames",report)
                java.io.File(instrumentation.targetContext.cacheDir,"frame_profile_$count.txt").writeText(report)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            thread.quitSafely()
        }
    }
}
