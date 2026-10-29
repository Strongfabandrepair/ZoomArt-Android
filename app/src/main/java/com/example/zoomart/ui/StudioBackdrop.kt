package com.example.zoomart.ui

import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import com.example.zoomart.ui.glass.Springs
import kotlin.math.*

/** Projected orbital particles, drawn locally; never snapshots or invalidates the canvas. */
class StudioBackdrop(context: Context, private val subdued: Boolean = false,
    private val light: Boolean = false) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbitSegments=FloatArray(96*4)
    private val particleBatches=Array(12) { FloatArray(84*2) }
    private val particleCounts=IntArray(12)
    private var sky: Bitmap?=null
    private val skyBounds=RectF()
    private val colors=intArrayOf(0xFFC7A1FF.toInt(),0xFF88DFD0.toInt(),0xFFFFC38A.toInt())
    private var backdrop: Shader?=null
    private var glow: Shader?=null
    private val glowMatrix=Matrix()
    private var ready=false
    private var paused=false
    private var energySaving=false
    private val powerReceiver=object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context,intent: android.content.Intent) {
            energySaving=(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
            syncMotion()
        }
    }
    private var lastTick=0L
    private var phase=0.0
    internal var motionScheduled=false
        private set
    internal var motionFrames=0L
        private set
    private val frame=object : Runnable {
        override fun run() {
            motionScheduled=false
            if (!canAnimate()) return
            val now=SystemClock.uptimeMillis()
            phase+=(now-lastTick).coerceIn(0,100)/1000.0
            lastTick=now;motionFrames++
            invalidate()
            motionScheduled=true;postOnAnimationDelayed(this,if(subdued) 66L else 33L)
        }
    }
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable=false;isFocusable=false
        ready=true
    }
    private fun canAnimate() = isAttachedToWindow && isShown && windowVisibility==VISIBLE &&
        hasWindowFocus() && !paused && !energySaving && Springs.enabled()
    private fun syncMotion() {
        if(!ready) return
        removeCallbacks(frame);motionScheduled=false
        if(canAnimate()) { lastTick=SystemClock.uptimeMillis();motionScheduled=true;postOnAnimationDelayed(frame,33) }
    }
    fun setMotionPaused(value: Boolean) { paused=value;syncMotion() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        energySaving=(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        androidx.core.content.ContextCompat.registerReceiver(context,powerReceiver,
            android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        syncMotion()
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(frame);motionScheduled=false
        context.unregisterReceiver(powerReceiver)
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus);syncMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility);syncMotion() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible);syncMotion() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        backdrop=LinearGradient(0f,0f,w.toFloat(),h.toFloat(),
            if(light) intArrayOf(0xFFF4EEF9.toInt(),0xFFF8F4F0.toInt(),0xFFEAF5F2.toInt())
            else intArrayOf(0xFF111929.toInt(),0xFF29233B.toInt(),0xFF173039.toInt()),null,Shader.TileMode.CLAMP)
        glow=RadialGradient(0f,0f,(max(w,h)*0.65f).coerceAtLeast(1f),
            intArrayOf(if(light) 0x166F4B9E else 0x356F4B9E,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        if(w>0 && h>0) {
            // Cache atmospheric shading; only geometry changes each frame.
            val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            val buffer=Canvas(bitmap)
            buffer.scale(256f/w,256f/h)
            paint.alpha=255;paint.shader=backdrop
            buffer.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
            glowMatrix.setTranslate(w*0.3f,h*0.35f);glow?.setLocalMatrix(glowMatrix)
            paint.shader=glow;buffer.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
            paint.shader=null;sky=bitmap;skyBounds.set(0f,0f,w.toFloat(),h.toFloat())
        }
    }
    override fun onDraw(canvas: Canvas) {
        val w=width.toFloat();val h=height.toFloat()
        if(w<=0 || h<=0) return
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.shader=null;paint.isFilterBitmap=true
        sky?.let { canvas.drawBitmap(it,null,skyBounds,paint) }
        val density=resources.displayMetrics.density
        val rotation=phase*(if(subdued) 0.025 else 0.055)
        // Three tilted orbital planes. Perspective size/opacity supplies depth without a GL surface.
        for(band in 0..2) {
            var previousX=0f;var previousY=0f
            for(i in 0..96) {
                val angle=i*PI*2/96+rotation+band*0.7
                val z=sin(angle)*0.42
                val perspective=(1/(1.6-z)).toFloat()
                val x=w*0.5f+(cos(angle)*w*(0.79+band*0.13)*perspective).toFloat()
                val y=h*0.5f+(sin(angle+band*0.45+sin(phase*0.08+band)*0.18)*h*0.62*perspective).toFloat()
                if(i>0) {
                    val offset=(i-1)*4
                    orbitSegments[offset]=previousX;orbitSegments[offset+1]=previousY
                    orbitSegments[offset+2]=x;orbitSegments[offset+3]=y
                }
                previousX=x;previousY=y
            }
            paint.color=colors[band];paint.alpha=if(subdued) 22 else 48
            paint.style=Paint.Style.STROKE;paint.strokeWidth=density*(if(subdued) 0.6f else 0.8f)
            canvas.drawLines(orbitSegments,paint)
        }
        paint.style=Paint.Style.FILL
        val count=if(subdued) 22 else 84
        particleCounts.fill(0)
        for(i in 0 until count) {
            val angle=i*2.399963+rotation*(0.6+(i%5)*0.17)
            val orbit=0.42+(i%11)/17.0
            val z=sin(angle+i*0.37)*0.65
            val perspective=1/(1.7-z)
            val x=(w*0.5+cos(angle)*w*orbit*perspective).toFloat()
            val y=(h*0.5+sin(angle*0.79+i*0.31)*h*orbit*perspective).toFloat()
            val depth=((perspective-0.4)*5).toInt().coerceIn(0,3)
            val batch=(i%3)*4+depth
            val offset=particleCounts[batch]
            particleBatches[batch][offset]=x;particleBatches[batch][offset+1]=y
            particleCounts[batch]+=2
        }
        // Batch by tint and projected depth instead of issuing a draw for each mote.
        paint.strokeCap=Paint.Cap.ROUND
        for(batch in 0 until 12) {
            if(particleCounts[batch]==0) continue
            val depth=batch%4
            val radius=density*(0.7f+depth*0.45f)
            paint.color=if(light) 0xFF796587.toInt() else colors[batch/4]
            if(!subdued) {
                paint.alpha=12;paint.strokeWidth=radius*7f
                canvas.drawPoints(particleBatches[batch],0,particleCounts[batch],paint)
            }
            paint.alpha=if(subdued) 28 else 70+depth*30
            paint.strokeWidth=radius*2f
            canvas.drawPoints(particleBatches[batch],0,particleCounts[batch],paint)
        }
        paint.strokeCap=Paint.Cap.BUTT
        paint.alpha=255
    }
}
