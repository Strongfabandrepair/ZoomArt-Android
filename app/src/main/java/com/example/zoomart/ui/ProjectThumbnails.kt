package com.example.zoomart.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.zoomart.export.PngExporter
import com.example.zoomart.model.ProjectLibrary
import com.example.zoomart.model.SessionWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Construct on the UI thread, then use exclusively on the library's serial worker. */
class ProjectThumbnails(context: Context) {
    private val renderer = ZoomableCanvasView(context).apply { layout(0,0,320,240) }
    private val directory = File(context.cacheDir,"project_thumbnails").apply { mkdirs() }
    fun load(library: ProjectLibrary, entry: ProjectLibrary.Entry): Bitmap? = runCatching {
        val identity="v1:${entry.file.canonicalPath}:${entry.file.lastModified()}:${entry.file.length()}"
        val key=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file=File(directory,"$key.png")
        (if(file.isFile) BitmapFactory.decodeFile(file.path) else null)?.let { return@runCatching it }
        renderer.viewModel.restore(library.read(entry))
        val bitmap=PngExporter.renderBitmap(renderer,320)
        try {
            val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            SessionWriter.writeBytes(file,bytes)
            directory.listFiles().orEmpty().filter { it.extension=="png" }
                .sortedByDescending { it.lastModified() }.drop(64).forEach { it.delete() }
        } catch (_: Exception) { /* A full cache must not prevent viewing the project. */ }
        bitmap
    }.getOrNull()
}
