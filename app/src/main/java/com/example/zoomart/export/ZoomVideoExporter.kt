package com.example.zoomart.export

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.example.zoomart.model.Bookmark
import com.example.zoomart.model.Camera
import com.example.zoomart.ui.ZoomableCanvasView
import java.io.File

/**
 * Renders an animated zoom tour through the bookmark pins into an H.264 MP4.
 *
 * Frames are drawn with the same world-space drawScene() used on screen, with
 * the camera eased (cosine) between keyframes: current camera -> pin 1 ->
 * pin 2 -> ... The encoder receives frames through its input Surface (software
 * canvas lock/post), paced in real time so surface timestamps are correct.
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

        val encoder = MediaCodec.createEncoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = encoder.createInputSurface()
        encoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            // ---- render + submit frames (real-time pacing => correct pts) ----
            for (f in 0 until totalFrames) {
                val t = f.toFloat() / (totalFrames - 1).coerceAtLeast(1)
                val segF = t * (cams.size - 1)
                val idx = segF.toInt().coerceAtMost(cams.size - 2)
                val local = segF - idx
                // cosine ease-in-out per hop keeps the tour buttery
                val eased = (0.5 - 0.5 * Math.cos(Math.PI * local)).toFloat()
                val cam = cams[idx].interpolatedTo(cams[idx + 1], eased)

                val canvas = surface.lockCanvas(null)
                canvas.drawColor(Color.WHITE)
                view.drawScene(canvas, cam, includeInProgress = false)
                surface.unlockCanvasAndPost(canvas)

                onProgress(f.toFloat() / totalFrames)
                Thread.sleep((1000 / fps).toLong())
            }
            encoder.signalEndOfInputStream()

            // ---- drain the encoder into the muxer ----
            var sawEOS = false
            val drainDeadline = System.currentTimeMillis() + 60_000
            while (!sawEOS && System.currentTimeMillis() < drainDeadline) {
                val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep waiting for EOS */
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxer = MediaMuxer(outFile.absolutePath, 0 /* MediaMuxer.OutputFormat.MPEG_4 */).also {
                            trackIndex = it.addTrack(encoder.outputFormat)
                            it.start()
                            muxerStarted = true
                        }
                    }
                    index >= 0 -> {
                        val encoded = encoder.getOutputBuffer(index)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0 // config data already folded into format
                        }
                        if (bufferInfo.size > 0 && muxerStarted && encoded != null) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer!!.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawEOS = true
                        }
                    }
                }
            }
        } finally {
            try { encoder.stop() } catch (e: IllegalStateException) { }
            encoder.release()
            surface.release()
            if (muxerStarted) {
                try { muxer!!.stop() } catch (e: IllegalStateException) { }
            }
            muxer?.release()
        }
        return outFile
    }
}
