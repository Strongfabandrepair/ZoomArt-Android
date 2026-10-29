package com.example.zoomart.export

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.os.SystemClock
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.zoomart.model.Bookmark
import com.example.zoomart.model.Camera
import com.example.zoomart.ui.ZoomableCanvasView
import java.io.File

/** Offline H.264 tour encoding with explicit timestamps and continuous output draining.
 * A reusable bitmap feeds stride-aware YUV input; no UI renderer state is shared.
 */
object ZoomVideoExporter {

    fun export(
        view: ZoomableCanvasView,
        pins: List<Bookmark>,
        outFile: File,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        secondsPerHop: Float = 2.5f,
        onProgress: (Float) -> Unit = {},
    ): File {
        require(pins.isNotEmpty()) { "need at least one pin" }
        val vm = view.viewModel

        // Keyframes: current camera, then each pin centered at its saved zoom
        // and level (P0: pins remember their levelId).
        val tree = vm.document.levelTree
        val cams = ArrayList<Camera>()
        cams.add(Camera(vm.camera.scale, vm.camera.transX, vm.camera.transY, vm.camera.level))
        for (b in pins) {
            val s = b.scale.coerceIn(Camera.MIN_SCALE, 100f)
            val level = tree.byId(b.levelId) ?: tree.root
            cams.add(Camera(s, width / 2f - b.worldX * s, height / 2f - b.worldY * s, level))
        }

        val totalFrames = ((cams.size - 1) * secondsPerHop * fps).toInt().coerceAtLeast(fps)

        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(fps in 1..60 && secondsPerHop.isFinite() && secondsPerHop > 0)
        val encoder = MediaCodec.createEncoderByType("video/avc")
        var bitmap: Bitmap? = null
        var muxer: MediaMuxer? = null
        var started = false
        var muxerStarted = false
        var completed = false
        var trackIndex = -1
        val info = MediaCodec.BufferInfo()
        var sawEOS = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            started = true
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pixels = IntArray(width * height)

            // Drain continuously so the encoder never blocks on a full output queue.
            fun drain(waitUs: Long = 0) {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, waitUs)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed twice" }
                            trackIndex = muxer!!.addTrack(encoder.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            try {
                                val encoded = encoder.getOutputBuffer(index)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                    check(muxerStarted && encoded != null) { "Missing encoder output format" }
                                    encoded.position(info.offset)
                                    encoded.limit(info.offset + info.size)
                                    muxer!!.writeSampleData(trackIndex, encoded, info)
                                }
                                sawEOS = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { encoder.releaseOutputBuffer(index, false) }
                            if (sawEOS) return
                        }
                    }
                }
            }
            fun inputSlot(): Int {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    drain()
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) return index
                }
                error("Video encoder timed out")
            }
            for (f in 0 until totalFrames) {
                val t = f.toFloat() / (totalFrames - 1).coerceAtLeast(1)
                val seg = t * (cams.size - 1)
                val idx = seg.toInt().coerceAtMost(cams.size - 2)
                val eased = (0.5 - 0.5 * Math.cos(Math.PI * (seg - idx))).toFloat()
                canvas.drawColor(Color.WHITE)
                view.drawScene(canvas, cams[idx].interpolatedTo(cams[idx + 1], eased), false)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val slot = inputSlot()
                val image = checkNotNull(encoder.getInputImage(slot)) { "Encoder lacks YUV image input" }
                writeYuv(pixels, width, height, image)
                encoder.queueInputBuffer(slot, 0, width * height * 3 / 2,
                    f * 1_000_000L / fps, 0)
                drain()
                onProgress((f + 1f) / totalFrames)
            }
            encoder.queueInputBuffer(inputSlot(), 0, 0,
                totalFrames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!sawEOS && SystemClock.elapsedRealtime() < deadline) drain(10_000)
            check(sawEOS && muxerStarted) { "Video encoder did not finish" }
            muxer.stop()
            muxerStarted = false
            completed = true
        } finally {
            bitmap?.recycle()
            if (started) runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!completed) outFile.delete()
        }
        return outFile
    }
    /** Respect vendor row/pixel strides (planar and interleaved YUV input). */
    private fun writeYuv(pixels: IntArray, width: Int, height: Int, image: Image) {
        val planes = image.planes
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels[y * width + x]
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            val luma = ((66 * r + 129 * g + 25 * b + 128 shr 8) + 16).coerceIn(0, 255)
            planes[0].buffer.put(y * planes[0].rowStride + x * planes[0].pixelStride, luma.toByte())
            if (x % 2 == 0 && y % 2 == 0) {
                val u = ((-38 * r - 74 * g + 112 * b + 128 shr 8) + 128).coerceIn(0, 255)
                val v = ((112 * r - 94 * g - 18 * b + 128 shr 8) + 128).coerceIn(0, 255)
                planes[1].buffer.put(y / 2 * planes[1].rowStride + x / 2 * planes[1].pixelStride, u.toByte())
                planes[2].buffer.put(y / 2 * planes[2].rowStride + x / 2 * planes[2].pixelStride, v.toByte())
            }
        }
    }

}
