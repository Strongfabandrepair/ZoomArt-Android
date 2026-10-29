package com.example.zoomart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ReleaseIntegrityTest {
    private fun files()=File(ApplicationProvider.getApplicationContext<Context>().filesDir,"integrity-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    @Test fun saveSnapshotIsDetachedAndNewestQueuedRevisionWins() {
        val vm=DrawingViewModel();val destination=File(files(),"session.json")
        vm.beginStroke(1f,2f,1f);vm.extendStroke(3f,4f,1f);vm.endStroke()
        val snapshot=SessionWriter.capture(vm.document,vm.camera)
        vm.document.activeLayer.strokes.clear()
        SessionWriter.enqueue(destination,snapshot);SessionWriter.awaitIdle()
        assertEquals(1,DocumentCodec.fromJson(destination.readText()).document.activeLayer.strokes.size)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val completed=java.util.concurrent.CopyOnWriteArrayList<Float>()
        repeat(20) {
            vm.camera.transX=it.toFloat()
            val next=SessionWriter.capture(vm.document,vm.camera)
            assertEquals(it.toFloat(),next.camera.transX,0f)
            SessionWriter.enqueue(destination,next) { e -> if(e!=null) failure.set(e);completed.add(next.camera.transX) }
        }
        SessionWriter.awaitIdle()
        assertNull(failure.get()?.stackTraceToString(),failure.get())
        assertEquals(19f,DocumentCodec.fromJson(destination.readText()).camera.transX,0f)
    }
    @Test fun failedTransactionRetainsPreviousDocument() {
        val root=files();val destination=File(root,"session.json")
        val vm=DrawingViewModel();SessionWriter.write(destination,SessionWriter.capture(vm.document,vm.camera))
        val before=destination.readBytes()
        // AtomicFile's new-file path cannot be opened when it is a directory.
        File(destination.path+".new").mkdir()
        assertThrows(Exception::class.java) { SessionWriter.write(destination,SessionWriter.capture(vm.document,vm.camera)) }
        assertArrayEquals(before,destination.readBytes())
    }
    @Test fun portableArchiveRoundTripAndTrashRestoreKeepAssets() {
        val root=files();val library=ProjectLibrary(root);val vm=DrawingViewModel()
        val asset=File(root,"imports/test.png");asset.parentFile!!.mkdirs();asset.writeBytes(byteArrayOf(1,2,3,4))
        vm.document.activeLayer.images.add(PlacedImage("imports/test.png",1f,2f,30f,40f).apply { flipH=true })
        val entry=library.save("Original",SessionWriter.capture(vm.document,vm.camera))
        val bytes=ByteArrayOutputStream();library.archive(entry,bytes)
        val imported=library.importArchive(ByteArrayInputStream(bytes.toByteArray()),"Imported")
        val image=library.read(imported).document.activeLayer.images.single()
        assertTrue(image.flipH);assertNotEquals("imports/test.png",image.path)
        assertArrayEquals(asset.readBytes(),File(root,image.path).readBytes())
        library.moveToTrash(imported)
        val trashed=library.entries(true).single();library.moveToTrash(trashed,true)
        assertEquals(2,library.entries().size)
    }
    @Test fun archiveTraversalAndMissingAssetsAreRejected() {
        val root=files();val library=ProjectLibrary(root);val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("assets/../../escape"));it.write(1);it.closeEntry() }
        assertThrows(IllegalArgumentException::class.java) { library.importArchive(ByteArrayInputStream(bytes.toByteArray()),"Bad") }
        assertTrue(library.entries().isEmpty());assertFalse(File(root,"escape").exists())
    }
    @Test fun malformedDocumentsCannotCreateInvalidGeometryOrGraphs() {
        val valid=DrawingViewModel().serialize()
        for(text in listOf(valid.replace("\"scale\":1.0","\"scale\":-1"),
            valid.replace("\"levelId\":0","\"levelId\":999"),
            "{\"camera\":{},\"camera\":{},\"layers\":[]}",
            "[".repeat(80)+"0"+"]".repeat(80))) {
            assertThrows(DocumentCodec.JsonError::class.java) { DocumentCodec.fromJson(text) }
        }
    }
    @Test fun cancellationRestoresEraserAndSelectionWithoutHistoryEntry() {
        val vm=DrawingViewModel();vm.beginStroke(0f,0f,1f);vm.extendStroke(100f,0f,1f);vm.endStroke()
        val original=vm.document.activeLayer.strokes.single()
        vm.beginErase(50f,0f);vm.eraseTo(50f,1f);vm.cancelGesture()
        assertSame(original,vm.document.activeLayer.strokes.single())
        vm.document.selection.selected.add(original);vm.document.selection.beginTransform();vm.document.selection.translateBy(30f,40f)
        vm.cancelGesture();assertEquals(0f,original.points.first().x,0f)
        vm.undo();assertTrue(vm.document.activeLayer.strokes.isEmpty())
    }
    @Test fun bookmarkFlightRemainsFiniteBelowDoubleRootPrecision() {
        val tree=LevelTree();var common=tree.root
        repeat(180) { common=tree.childAt(common,1000f,1000f) }
        val child=tree.childAt(common,20f,30f)
        val start=Camera(2f,10f,20f,common);val end=Camera(3f,40f,50f,child)
        for(i in 1..99) {
            val camera=start.interpolatedTo(end,i/100f)
            assertTrue(camera.scale.isFinite() && camera.scale>0)
            assertTrue(camera.transX.isFinite() && camera.transY.isFinite())
        }
        assertEquals(end.transX,start.interpolatedTo(end,1f).transX,0f)
    }
    @Test fun viewportRejectsOffscreenNeighboursInSameBucket() {
        val vm=DrawingViewModel()
        vm.beginStroke(400f,400f,1f);vm.extendStroke(410f,410f,1f);vm.endStroke()
        assertTrue(vm.document.strokeIndex.queryVisible(0f,0f,10f,10f).isEmpty())
        assertEquals(1,vm.document.strokeIndex.queryVisible(398f,398f,412f,412f).size)
    }
    @Test fun projectRenameAtomicallyReplacesExistingName() {
        val library=ProjectLibrary(files());val vm=DrawingViewModel()
        val entry=library.save("First",SessionWriter.capture(vm.document,vm.camera))
        library.rename(entry,"Second")
        assertEquals("Second",library.entries().single().name)
    }

}
