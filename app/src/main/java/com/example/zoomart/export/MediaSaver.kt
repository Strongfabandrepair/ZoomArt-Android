package com.example.zoomart.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** Publish complete media atomically; remove failed pending gallery entries. */
object MediaSaver {
    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): String? =
        publish(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, displayName,
            "image/png", Environment.DIRECTORY_PICTURES + "/ZoomArt") {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" }
        }

    fun saveVideoToGallery(context: Context, file: File): String? =
        publish(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, file.name,
            "video/mp4", Environment.DIRECTORY_MOVIES + "/ZoomArt") { output ->
            file.inputStream().use { it.copyTo(output) }
        }

    private fun publish(context: Context, collection: Uri, name: String, mime: String,
                        folder: String, write: (OutputStream) -> Unit): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destination = resolver.insert(collection, values) ?: return null
            uri = destination
            checkNotNull(resolver.openOutputStream(destination)).use(write)
            check(resolver.update(destination, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) > 0) { "Could not publish export" }
            destination.toString()
        } catch (_: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }
}
