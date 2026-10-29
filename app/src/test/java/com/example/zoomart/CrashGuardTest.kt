package com.example.zoomart

import com.example.zoomart.model.CrashGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CrashGuardTest {

    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    @Test
    fun crashWritesSnapshotAndChainsToPreviousHandler() {
        val dir = File(System.getProperty("java.io.tmpdir"), "crash_test_" + System.nanoTime())
        dir.mkdirs()
        val crashFile = File(dir, "crash.json")
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }

        CrashGuard.install({ "{\"ok\":true}" }, crashFile)
        val t = Thread { throw RuntimeException("boom") }
        t.start()
        t.join(10_000)

        assertTrue(crashFile.exists())
        assertEquals("{\"ok\":true}", crashFile.readText())
        assertTrue(chained)
        dir.deleteRecursively()
    }

    @Test
    fun serializerOrDiskFailureNeverMasksTheOriginalCrash() {
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }
        val impossible = File(System.getProperty("java.io.tmpdir"), "no_such_dir_zzz/crash.json")

        CrashGuard.install({ throw IllegalStateException("cannot serialize") }, impossible)
        val t = Thread { throw RuntimeException("boom") }
        t.start()
        t.join(10_000)
        assertTrue(chained) // original handler still ran; guard swallowed its own failure
    }

    @Test
    fun reinstallDoesNotOverwriteLatestSnapshotWithOldActivity() {
        val dir = java.nio.file.Files.createTempDirectory("guard_reinstall").toFile()
        val file = File(dir, "session.json")
        var chains = 0
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chains++ }
        CrashGuard.install({ "old" }, file)
        val latest = CrashGuard.install({ "latest" }, file)
        latest.uncaughtException(Thread.currentThread(), RuntimeException("test"))
        assertEquals("latest", file.readText())
        assertEquals(1, chains)
        CrashGuard.uninstall(latest)
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() !== latest)
        dir.deleteRecursively()
    }
}
