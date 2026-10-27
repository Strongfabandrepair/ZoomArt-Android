package com.example.zoomart.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Saves renders into the PUBLIC Pictures/ZoomArt collection on API 29+
 * (no permission required, visible in gallery/Photos apps). Returns null on
 * older Android versions so callers can fall back to the app-specific dir.
 */
object MediaSaver {

    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ZoomArt")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: return null
            "Pictures/ZoomArt/$displayName"
        } catch (t: Throwable) {
            null
        }
    }
}
