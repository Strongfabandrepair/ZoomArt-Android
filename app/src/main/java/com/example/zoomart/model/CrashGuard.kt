package com.example.zoomart.model

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Best-effort crash recovery without chaining obsolete activity snapshots. */
object CrashGuard {
    private class Guard(
        val previous: Thread.UncaughtExceptionHandler?,
        val serializer: () -> String?,
        val file: File,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                serializer()?.let { snapshot ->
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    FileOutputStream(tmp).use {
                        it.write(snapshot.toByteArray(Charsets.UTF_8))
                        it.fd.sync()
                    }
                    Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: Throwable) { /* Never mask the original exception. */ }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun install(sessionSerializer: () -> String?, crashFile: File): Thread.UncaughtExceptionHandler {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        val previous = if (current is Guard) current.previous else current
        return Guard(previous, sessionSerializer, crashFile).also {
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
    }

    fun uninstall(handler: Thread.UncaughtExceptionHandler?) {
        if (handler is Guard && Thread.getDefaultUncaughtExceptionHandler() === handler)
            Thread.setDefaultUncaughtExceptionHandler(handler.previous)
    }
}
