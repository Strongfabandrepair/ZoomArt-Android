package com.example.zoomart.model

import java.io.File

/**
 * In-flight crash handling: when ANY thread dies on an uncaught exception,
 * snapshot the session so the next launch recovers the drawing, then chain
 * to the previous handler so the system still logs/ANRs normally. Never
 * lets persistence failures mask the original crash.
 */
object CrashGuard {

    fun install(sessionSerializer: () -> String?, crashFile: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val snapshot = sessionSerializer()
                if (snapshot != null) {
                    val tmp = File(crashFile.parentFile, crashFile.name + ".tmp")
                    tmp.writeText(snapshot)
                    crashFile.delete()
                    if (!tmp.renameTo(crashFile)) crashFile.writeText(snapshot)
                }
            } catch (ignored: Throwable) {
                // never mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
