package com.example.zoomart

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistenceRegressionTest {
    @Test fun disabledAutosaveWaitsForPauseAndPauseStillSaves() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.putAutosaveMs(context, 0L)
        val file = File(context.filesDir, "zoomart_session.json")
        file.delete()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertFalse(file.exists())
        controller.pause()
        com.example.zoomart.model.SessionWriter.awaitIdle()
        assertNotNull(DocumentCodec.fromJson(file.readText()))
        controller.stop().destroy()
    }

    @Test fun newProjectIntentDoesNotEraseArtworkOnActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).putExtra("zoomart.mode", "new")
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val vm = controller.get().canvasView.viewModel
        vm.beginStroke(10f, 10f, 1f); vm.extendStroke(30f, 30f, 1f); vm.endStroke()
        controller.recreate()
        assertEquals(1, controller.get().canvasView.viewModel.document.activeLayer.strokes.size)
        controller.pause().stop().destroy()
    }

    @Test fun recoveredCrashIsDurablySavedBeforeRecoveryFileIsRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = DrawingViewModel()
        vm.beginStroke(1f, 2f, 1f); vm.extendStroke(3f, 4f, 1f); vm.endStroke()
        val crash = File(context.filesDir, "zoomart_crash_session.json")
        crash.writeText(vm.serialize())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(crash.exists())
        val saved = DocumentCodec.fromJson(File(context.filesDir, "zoomart_session.json").readText())
        assertEquals(1, saved.document.activeLayer.strokes.size)
        controller.pause().stop().destroy()
    }
}
