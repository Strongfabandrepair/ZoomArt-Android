package com.example.zoomart

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.model.*
import com.example.zoomart.ui.ProjectThumbnails
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceFeaturesTest {
    @Test fun symmetryCommitsAndUndoesAsOneGestureAndSurvivesReopen() {
        val vm=DrawingViewModel()
        vm.symmetry=DrawingViewModel.Symmetry.FOUR_WAY
        vm.symmetryCenterX=100f;vm.symmetryCenterY=100f
        vm.beginStroke(120f,130f,0.5f);vm.extendStroke(140f,150f,0.8f);vm.endStroke()
        val strokes=vm.document.activeLayer.strokes
        assertEquals(4,strokes.size)
        assertEquals(setOf(140f to 150f,60f to 150f,140f to 50f,60f to 50f),
            strokes.map { it.points.last().let { p -> p.x to p.y } }.toSet())
        assertTrue(strokes.all { it.points.last().pressure01==0.8f })
        val restored=DocumentCodec.fromJson(vm.serialize())
        assertEquals(4,restored.document.activeLayer.strokes.size)
        vm.undo();assertTrue(vm.document.activeLayer.strokes.isEmpty())
        vm.redo();assertEquals(4,vm.document.activeLayer.strokes.size)
    }
    @Test fun mirroredStampOrientationSurvivesReopening() {
        val vm=DrawingViewModel();vm.symmetry=DrawingViewModel.Symmetry.HORIZONTAL
        vm.currentBrush=BrushSpec(spacingWorld=20f,stampShape=StampShape.STAR)
        vm.beginStroke(20f,30f,1f);vm.extendStroke(60f,50f,1f);vm.endStroke()
        val restored=DocumentCodec.fromJson(vm.serialize()).document.activeLayer.strokes
        assertEquals(2,restored.size)
        assertFalse(restored[0].brush.stampFlipY)
        assertTrue(restored[1].brush.stampFlipY)
        assertFalse(restored[1].brush.stampFlipX)
    }
    @Test fun strokeOnTheMirrorAxisDoesNotDoubleDepositInk() {
        val vm=DrawingViewModel();vm.symmetry=DrawingViewModel.Symmetry.VERTICAL;vm.symmetryCenterX=20f
        vm.beginStroke(20f,0f,1f);vm.extendStroke(20f,50f,1f);vm.endStroke()
        assertEquals(1,vm.document.activeLayer.strokes.size)
    }
    @Test fun cancelledAndLockedSymmetryGesturesDoNotCommitCopies() {
        val vm=DrawingViewModel();vm.symmetry=DrawingViewModel.Symmetry.VERTICAL
        vm.beginStroke(10f,10f,1f);vm.extendStroke(30f,30f,1f);vm.cancelGesture()
        assertNull(vm.endStroke());assertFalse(vm.document.canUndo)
        vm.beginStroke(10f,10f,1f);vm.extendStroke(30f,30f,1f)
        vm.document.activeLayer.locked=true
        assertNull(vm.endStroke());assertTrue(vm.document.activeLayer.strokes.isEmpty())
    }
    @Test fun weightedAndFinishModesHaveDifferentLiveResponses() {
        val weighted=LazyBrush(60f).apply { mode=LazyBrush.Mode.WEIGHTED;begin(0f,0f) }
        val p=weighted.feed(30f,0f)
        assertTrue(p[0]>0f && p[0]<30f)
        val finish=LazyBrush(60f).apply { mode=LazyBrush.Mode.FINISH;begin(0f,0f) }
        assertEquals(30f,finish.feed(30f,0f)[0],0f)
        weighted.radiusScreen=0f
        assertEquals(100f,weighted.feed(100f,0f)[0],0f)
    }
    @Test fun finishSmoothingKeepsRawInputUntilCommit() {
        val vm=DrawingViewModel();vm.lazyEnabled=true;vm.lazyBrush.radiusScreen=60f
        vm.lazyBrush.mode=LazyBrush.Mode.FINISH
        vm.beginStrokeAtScreen(0f,0f,1f);vm.extendStrokeAtScreen(30f,30f,1f);vm.extendStrokeAtScreen(60f,0f,1f)
        assertEquals(3,vm.inProgress!!.points.size)
        val stroke=vm.endStrokeLazy()!!
        assertTrue(stroke.points.size>3)
        assertEquals(60f,stroke.points.last().x,0f)
        assertEquals(LazyBrush.TAPER_FRACTION,stroke.taper,0f)
    }
    @Test fun galleryThumbnailCacheRefreshesWhenCheckpointChanges() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.filesDir,"thumb-test").apply { mkdirs() }
        val library=ProjectLibrary(root)
        fun snapshot(color: Int): DocumentCodec.SessionData {
            val model=DrawingViewModel()
            model.currentBrush=BrushSpec(baseWidthWorld=20f,color=color)
            model.beginStroke(0f,20f,1f);model.extendStroke(100f,20f,1f);model.endStroke()
            return SessionWriter.capture(model.document,model.camera)
        }
        val entry=library.save("Thumbnail test",snapshot(Color.RED))
        val thumbnails=ProjectThumbnails(context)
        val a=thumbnails.load(library,entry)!!
        assertEquals(Color.RED,a.getPixel(a.width/2,a.height/2))
        val cached=thumbnails.load(library,entry)!!
        assertTrue(a.sameAs(cached))
        val previous=entry.file.lastModified()
        SessionWriter.write(entry.file,snapshot(Color.BLUE));entry.file.setLastModified(previous+2000)
        val b=thumbnails.load(library,entry)!!
        assertEquals(Color.BLUE,b.getPixel(b.width/2,b.height/2))
        library.moveToTrash(entry)
        assertNotNull(thumbnails.load(library,library.entries(true).first()))
        a.recycle();b.recycle();cached.recycle()
    }
    private fun descendants(v: View): List<View> = listOf(v)+
        if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    @Test fun toolbarCollapsePersistsAndCanBeReopened() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("workspace_ui",Context.MODE_PRIVATE).edit().clear().commit()
        var controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        fun button(label: Int)=descendants(controller.get().window.decorView).filterIsInstance<Button>().first { it.text==context.getString(label) }
        button(R.string.hide_tools).performClick()
        assertTrue(button(R.string.show_tools).isShown)
        controller.pause().stop().destroy()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        button(R.string.show_tools).performClick()
        assertTrue(button(R.string.hide_tools).isShown)
        controller.pause().stop().destroy()
        context.getSharedPreferences("workspace_ui",Context.MODE_PRIVATE).edit().clear().commit()
    }
}
