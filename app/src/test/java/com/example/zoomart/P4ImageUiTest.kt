package com.example.zoomart

import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.ImageDisplayFilter
import com.example.zoomart.model.PlacedImage
import com.example.zoomart.model.ShapeType
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokePoint
import com.example.zoomart.model.ZoomLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 JVM tests — image v2 + UI model behaviors (docs/P4_IMAGE_UI.md):
 *
 *  - P4.1 IMAGE IMPORT V2: the bitmap lands on a NEW layer above the active
 *    one (named "Image <n>"), which becomes active; ONE undo step removes
 *    the whole layer, redo re-inserts it;
 *  - P4.2 IMAGE TRANSFORMS: tap-select (bbox hit-test), drag-move,
 *    corner-handle uniform resize about the opposite corner — ONE undo
 *    step per gesture; locked images never select;
 *  - P4.3 transparency/lock/rotation serialize additively (pre-P4 files
 *    migrate to defaults); the DISPLAY filter is UI-state and NOT
 *    serialized (documented decision); TRACING MODE is one composite
 *    undoable command (alpha 40% + lock + fresh layer above + active);
 *  - P4.4 CLEAR LAYER removes all content as ONE undoable step;
 *  - P4.5 DEMO seeding sanity: nested rings at level-local coords, level
 *    reuse across runs, NO undo pollution;
 *  - P4.6 CROSS-LEVEL ERASER: radius/center conversion per level via the
 *    scale chain, fragments inherit the erased stroke's levelId, only
 *    VISIBLE levels are reachable.
 */
class P4ImageUiTest {

    // ---------------- P4.1: own-layer import ----------------

    @Test
    fun importPlacesImageOnOwnNewLayerAndUndoRestoresIt() {
        val vm = DrawingViewModel()
        val doc = vm.document
        assertEquals(2, doc.layers.size)
        doc.setActiveLayer(1)

        val img = vm.importImageOnNewLayer("imports/imp_1.png", 2f)!!

        // a NEW layer directly above the active one, named "Image <n>"...
        assertEquals(3, doc.layers.size)
        val layer = doc.layers[2]
        assertEquals("Image 3", layer.name)
        assertSame("the new layer is ACTIVE", layer, doc.activeLayer)
        // ...carrying exactly the placed image
        assertEquals(1, layer.images.size)
        assertSame(img, layer.images[0])
        // centered on the view at the camera's current level
        assertEquals(vm.camera.level.id, img.levelId)
        // deterministic placement math (fallback viewport 1080x2340 @ scale 1):
        // w = 1080 * 0.6 = 648, h = 648/2 = 324, centered on (540, 1170)
        assertEquals(648f, img.w, 0.001f)
        assertEquals(324f, img.h, 0.001f)
        assertEquals(540f - 324f, img.x, 0.001f)
        assertEquals(1170f - 162f, img.y, 0.001f)
        // the ORIGINAL active layer keeps nothing
        assertTrue(doc.layers[1].images.isEmpty())

        // ONE undo step removes the whole layer and restores the active one
        assertTrue(doc.undo())
        assertEquals(2, doc.layers.size)
        assertSame(doc.layers[1], doc.activeLayer)
        assertTrue(doc.canRedo)
        assertTrue(doc.redo())
        assertEquals(3, doc.layers.size)
        assertSame(layer, doc.activeLayer)
        assertSame(img, doc.activeLayer.images[0])
    }

    @Test
    fun importOwnLayerRoundTripsThroughTheCodec() {
        val vm = DrawingViewModel()
        vm.importImageOnNewLayer("imports/imp_2.png", 1f)
        val session = DocumentCodec.fromJson(vm.serialize())
        val restored = session.document
        assertEquals("image layer name survives the codec", "Image 3",
            restored.layers[restored.activeLayerIndex].name)
        assertEquals(1, restored.layers[restored.activeLayerIndex].images.size)
        assertEquals("imports/imp_2.png",
            restored.layers[restored.activeLayerIndex].images[0].path)
    }

    // ---------------- P4.2: select / move / resize ----------------

    private fun seedImage(vm: DrawingViewModel): PlacedImage {
        val img = PlacedImage("imports/x.png", 100f, 100f, 200f, 100f, ZoomLevels.ROOT_ID)
        vm.document.activeLayer.images.add(img)
        return img
    }

    @Test
    fun imageTapSelectMoveAndCornerResizeAreOneUndoStepEach() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val img = seedImage(vm)

        // TAP-select: the image's bbox contains its center with 24 px slack
        val hit = doc.selection.selectImageAt(vm.camera, vm.visibleLevels(), 150f, 130f)!!
        assertSame(img, hit)
        assertTrue(doc.selection.selectedImages.contains(img))

        // drag inside MOVES (one undo step per gesture)
        doc.selection.beginTransform()
        doc.selection.translateBy(30f, 20f)
        doc.selection.endTransform()
        assertEquals(130f, img.x, 0.001f)
        assertEquals(120f, img.y, 0.001f)
        assertTrue(doc.undo())
        assertEquals(100f, img.x, 0.001f)
        assertEquals(100f, img.y, 0.001f)
        assertTrue(doc.redo())
        assertEquals(130f, img.x, 0.001f)

        // corner-handle RESIZE: uniform scale about the OPPOSITE corner.
        // The image sits at (130, 120) after the move; scaling about the
        // original TL corner (100, 100) by 2 moves it to (160, 140).
        doc.selection.selectImageAt(vm.camera, vm.visibleLevels(), 150f, 130f)
        doc.selection.beginTransform()
        doc.selection.scaleAboutCameraPoint(100f, 100f, 2f) // pivot = TL corner
        doc.selection.endTransform()
        assertEquals(160f, img.x, 0.001f)
        assertEquals(140f, img.y, 0.001f)
        assertEquals(400f, img.w, 0.001f)
        assertEquals(200f, img.h, 0.001f)
        assertTrue(doc.undo())
        assertEquals(200f, img.w, 0.001f)
        assertEquals(100f, img.h, 0.001f)
        assertEquals("undo restores the pre-resize position too", 130f, img.x, 0.001f)
    }

    @Test
    fun imageMarqueePicksUnlockedImagesOnly() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val free = seedImage(vm)
        val lockedImg = PlacedImage("imports/y.png", 500f, 500f, 50f, 50f, ZoomLevels.ROOT_ID)
        lockedImg.locked = true
        doc.activeLayer.images.add(lockedImg)

        assertTrue(vm.selectInWorldRect(0f, 0f, 1000f, 1000f))
        assertTrue(doc.selection.selectedImages.contains(free))
        assertFalse("locked images never marquee-select", doc.selection.selectedImages.contains(lockedImg))

        // a LOCKED image ignores tap-select too
        assertNull(doc.selection.selectImageAt(vm.camera, vm.visibleLevels(), 525f, 525f))
        assertFalse(doc.selection.selectedImages.contains(lockedImg))
    }

    @Test
    fun imageRotationPivotsAboutItsLevelGroupCenter() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val img = seedImage(vm)
        doc.selection.selectImageAt(vm.camera, vm.visibleLevels(), 150f, 150f)
        doc.selection.beginTransform()
        doc.selection.rotate(90f)
        doc.selection.endTransform()

        assertEquals(90f, img.rotation, 0.001f)
        // center (200, 150) rotated 90 about the group center (200, 150):
        // the image pivots about its own center -> position unchanged
        assertEquals(100f, img.x, 0.01f)
        assertEquals(100f, img.y, 0.01f)
        // the rotated bbox grows to the rotated extents
        val b = img.bbox()
        assertEquals(100f, b[2] - b[0], 0.01f)
        assertEquals(200f, b[3] - b[1], 0.01f)
        assertTrue(doc.undo())
        assertEquals(0f, img.rotation, 0.001f)
    }

    // ---------------- P4.3: transparency / lock / codec / tracing ----------------

    @Test
    fun imageAlphaLockRotationRoundTripAndPreP4FilesMigrate() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val img = seedImage(vm)
        img.alpha = 0.55f
        img.locked = true
        img.rotation = 33f

        val restored = DocumentCodec.fromJson(vm.serialize()).document
        val back = restored.layers[restored.activeLayerIndex].images[0]
        assertEquals(0.55f, back.alpha, 0.0001f)
        assertTrue(back.locked)
        assertEquals(33f, back.rotation, 0.0001f)

        // a DEFAULT image round-trips byte-stable (no new tokens emitted)
        img.alpha = 1f
        img.locked = false
        img.rotation = 0f
        val payload = vm.serialize()
        assertFalse("default images keep the pre-P4 byte format",
            payload.contains("\"alpha\"") || payload.contains("\"locked\":true") ||
            payload.contains("\"rot\""))

        // pre-P4 session files (no alpha/locked/rot fields) load at defaults
        val legacy = DocumentCodec.fromJson(
            "{\"version\":3,\"camera\":{\"levelId\":0,\"scale\":1.0,\"transX\":0.0,\"transY\":0.0}," +
                "\"activeLayer\":1,\"levels\":[{\"id\":0,\"parentId\":-1,\"originX\":0.0,\"originY\":0.0,\"scaleToParent\":100.0}]," +
                "\"layers\":[{\"name\":\"Background\",\"visible\":true,\"opacity\":1.0,\"locked\":false,\"blend\":\"NORMAL\"," +
                "\"images\":[],\"strokes\":[]},{\"name\":\"Layer 1\",\"visible\":true,\"opacity\":1.0,\"locked\":false,\"blend\":\"NORMAL\"," +
                "\"images\":[{\"path\":\"imports/old.png\",\"x\":10.0,\"y\":20.0,\"w\":100.0,\"h\":50.0,\"level\":0}],\"strokes\":[]}]," +
                "\"bookmarks\":[]}"
        ).document
        val migrated = legacy.layers[1].images[0]
        assertEquals(1f, migrated.alpha, 0.0001f)
        assertFalse(migrated.locked)
        assertEquals(0f, migrated.rotation, 0.0001f)
    }

    @Test
    fun displayFilterIsUiStateOnlyAndNeverSerialized() {
        val vm = DrawingViewModel()
        val img = seedImage(vm)
        img.displayFilter = ImageDisplayFilter.GRAYSCALE

        val payload = vm.serialize()
        assertFalse("display filter must NOT serialize",
            payload.contains("displayFilter") || payload.contains("GRAYSCALE"))
        // ...so a restored session shows the untouched image
        val back = DocumentCodec.fromJson(payload).document.layers[1].images[0]
        assertEquals(ImageDisplayFilter.NONE, back.displayFilter)

        // the enum cycles None -> Gray -> Contrast -> None
        assertEquals(ImageDisplayFilter.GRAYSCALE, ImageDisplayFilter.NONE.cycled())
        assertEquals(ImageDisplayFilter.HIGH_CONTRAST, ImageDisplayFilter.GRAYSCALE.cycled())
        assertEquals(ImageDisplayFilter.NONE, ImageDisplayFilter.HIGH_CONTRAST.cycled())
        assertEquals(ImageDisplayFilter.HIGH_CONTRAST, ImageDisplayFilter.fromName("HIGH_CONTRAST"))
        assertEquals(ImageDisplayFilter.NONE, ImageDisplayFilter.fromName("SOMETHING_NEW"))
        assertEquals(ImageDisplayFilter.NONE, ImageDisplayFilter.fromName(null))
    }

    @Test
    fun imageAlphaAndLockChangesAreUndoable() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val img = seedImage(vm)

        // transparency: live drag then ONE recorded step (panel contract)
        img.alpha = 0.4f
        doc.setLayerImageAlpha(img, 0.4f, before = 1f)
        assertTrue(doc.undo())
        assertEquals(1f, img.alpha, 0.0001f)
        assertTrue(doc.redo())
        assertEquals(0.4f, img.alpha, 0.0001f)

        // lock toggle: one undoable step; no-op re-sets push nothing
        doc.setImageLock(img, true)
        assertTrue(img.locked)
        doc.setImageLock(img, true)                            // no-op re-lock
        doc.setLayerImageAlpha(img, 0.4f, before = 0.4f)      // no-op re-set
        assertTrue(doc.undo())
        assertFalse(img.locked)
        assertEquals("the alpha step still sits below on the stack", 0.4f, img.alpha, 0.0001f)
        assertTrue(doc.undo())
        assertEquals(1f, img.alpha, 0.0001f)
        assertFalse("only real changes push undo entries (2 total)", doc.canUndo)
    }

    @Test
    fun tracingModeIsOneUndoStepAndFullyRestores() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val img = seedImage(vm)
        val activeBefore = doc.activeLayer

        assertTrue(vm.beginTracingMode(img))
        assertEquals(0.4f, img.alpha, 0.0001f)
        assertTrue(img.locked)
        assertEquals("a fresh layer appears above the image layer", 3, doc.layers.size)
        val traceLayer = doc.layers[2]
        assertEquals("Layer 1 trace", traceLayer.name)
        assertSame(traceLayer, doc.activeLayer)

        // ONE undo restores alpha, lock, the layer AND the active layer
        assertTrue(doc.undo())
        assertEquals(1f, img.alpha, 0.0001f)
        assertFalse(img.locked)
        assertEquals(2, doc.layers.size)
        assertSame(activeBefore, doc.activeLayer)
        // redo re-applies the whole composite
        assertTrue(doc.redo())
        assertEquals(0.4f, img.alpha, 0.0001f)
        assertTrue(img.locked)
        assertSame(traceLayer, doc.activeLayer)
    }

    // ---------------- P4.4: clear layer ----------------

    @Test
    fun clearLayerRemovesAllContentAndOneUndoRestoresItExactly() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val layer = doc.activeLayer

        // an already-empty layer clears to a NO-op (no undo entry)
        assertFalse(doc.clearActiveLayer())
        assertFalse(doc.canUndo)

        vm.beginStroke(0f, 0f, 1f); vm.extendStroke(10f, 0f, 1f); vm.endStroke()
        val stroke = layer.strokes[0]
        val img = seedImage(vm)
        val shape = com.example.zoomart.model.ShapeData(ShapeType.RECTANGLE, 0L)
        shape.setAnchors(0f, 0f, 10f, 10f)
        layer.shapes.add(shape)
        val smudge = com.example.zoomart.model.SmudgeData(0L)
        smudge.stamps.add(com.example.zoomart.model.StampData(5f, 5f, 3f, 0xFF000000.toInt(), 0.5f))
        layer.smudges.add(smudge)
        // content on ANOTHER layer must survive
        doc.setActiveLayer(0)
        vm.beginStroke(0f, 0f, 1f); vm.extendStroke(9f, 0f, 1f); vm.endStroke()
        val keep = doc.activeLayer.strokes[0]
        doc.setActiveLayer(1)

        assertTrue(doc.clearActiveLayer())
        assertTrue(layer.strokes.isEmpty())
        assertTrue(layer.images.isEmpty())
        assertTrue(layer.shapes.isEmpty())
        assertTrue(layer.smudges.isEmpty())
        assertEquals("other layers keep their content", 1, doc.layers[0].strokes.size)

        assertTrue(doc.undo())
        assertEquals(1, layer.strokes.size)
        assertSame("undo restores the SAME objects", stroke, layer.strokes[0])
        assertSame(img, layer.images[0])
        assertSame(shape, layer.shapes[0])
        assertSame(smudge, layer.smudges[0])
        assertSame(keep, doc.layers[0].strokes[0])
        assertTrue(doc.redo())
        assertTrue(layer.strokes.isEmpty())
        assertTrue(doc.undo())
        assertEquals("back to the filled layer", 1, layer.strokes.size)
    }

    // ---------------- P4.5: demo content ----------------

    @Test
    fun demoSeedsNestedRingsReusingLevelsWithoutUndoPollution() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val activeBefore = doc.activeLayer

        val plan = vm.seedDemoContent()
        assertEquals(DrawingViewModel.DEMO_DEPTHS, plan.levels.size)
        assertEquals(DrawingViewModel.DEMO_DEPTHS, plan.layers.size)

        // the levels chain root -> depth 3, all centered at the ORIGIN
        assertEquals(0, plan.levels[0].depth)
        for (d in 1 until plan.levels.size) {
            assertEquals(d, plan.levels[d].depth)
            assertSame("each demo level is a child of the previous",
                plan.levels[d - 1], plan.levels[d].parent)
            assertEquals(0f, plan.levels[d].originX, 0.001f)
            assertEquals(0f, plan.levels[d].originY, 0.001f)
        }

        // each demo layer carries 3 shapes (star/rect/ellipse) at the
        // level-local concentric coords, in its OWN level's frame
        for ((d, layer) in plan.layers.withIndex()) {
            assertEquals(3, layer.shapes.size)
            assertEquals(plan.levels[d].id, layer.shapes[0].levelId)
            val s = layer.shapes[0]
            assertEquals(ShapeType.STAR, s.type)
            assertTrue(s.fillOn)
            assertEquals(-DrawingViewModel.DEMO_HALF_LOCAL, s.x0, 0.001f)
            assertEquals(-DrawingViewModel.DEMO_HALF_LOCAL, s.y0, 0.001f)
            assertEquals(DrawingViewModel.DEMO_HALF_LOCAL, s.x1, 0.001f)
            assertEquals(DrawingViewModel.DEMO_HALF_LOCAL, s.y1, 0.001f)
            assertEquals(ShapeType.RECTANGLE, layer.shapes[1].type)
            assertFalse(layer.shapes[1].fillOn)
            assertEquals(ShapeType.ELLIPSE, layer.shapes[2].type)
        }

        // seeded WITHOUT commands: no undo entries, active layer untouched
        assertFalse(doc.canUndo)
        assertSame(activeBefore, doc.activeLayer)
        assertEquals(DrawingViewModel.DEMO_DEPTHS, doc.layers.size - 2)

        // a SECOND run reuses the same levels (childAt origin tolerance)
        val levelsBefore = doc.levelTree.allLevels().map { it.id }
        val plan2 = vm.seedDemoContent()
        assertEquals("levels are reused, not forked",
            levelsBefore, doc.levelTree.allLevels().map { it.id })
        assertSame(plan.levels[1], plan2.levels[1])
        assertEquals(DrawingViewModel.DEMO_DEPTHS * 2, doc.layers.size - 2)
        assertFalse(doc.canUndo)
    }

    // ---------------- P4.6: cross-level eraser ----------------

    private fun childLevel(vm: DrawingViewModel, ox: Float = 0f, oy: Float = 0f) =
        vm.camera.level.tree.childAt(vm.camera.level.tree.root, ox, oy)

    private fun addStroke(vm: DrawingViewModel, levelId: Long, vararg xs: Float): StrokeData {
        val s = StrokeData()
        s.levelId = levelId
        for (x in xs) s.addPoint(StrokePoint(x, 0f, 1f))
        vm.document.activeLayer.strokes.add(s)
        vm.document.reindex()
        return s
    }

    @Test
    fun crossLevelEraserCutsChildLevelStrokesWithConvertedRadius() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val child = childLevel(vm) // origin (0,0), scaleToParent 100
        val stroke = addStroke(vm, child.id, 0f, 2f, 4f, 7.5f, 20f, 22f)

        // camera at root, scale 1. Eraser radius 0.04 ROOT units -> the
        // child's frame sees radius 0.04 * 100 = 4 local units; the center
        // (0.03, 0) root -> (3, 0) child. Points within 4 of x=3: 0, 2, 4
        // (7.5 is 4.5 away and must SURVIVE).
        vm.eraserRadiusWorld = 0.04f
        vm.beginErase(0.03f, 0f)
        assertTrue(vm.eraseTo(0.031f, 0f))
        vm.endErase()

        assertEquals("the stroke is split into the surviving tail", 1, doc.activeLayer.strokes.size)
        val frag = doc.activeLayer.strokes[0]
        assertNotSame(stroke, frag)
        assertEquals("fragments inherit the erased stroke's levelId", child.id, frag.levelId)
        assertEquals(3, frag.points.size)
        assertEquals(7.5f, frag.points[0].x, 0.001f)
        assertEquals(20f, frag.points[1].x, 0.001f)
        assertEquals(22f, frag.points[2].x, 0.001f)

        // ONE undo step restores the original stroke exactly
        assertTrue(doc.undo())
        assertEquals(1, doc.activeLayer.strokes.size)
        assertSame(stroke, doc.activeLayer.strokes[0])
        assertEquals(6, stroke.points.size)
        assertEquals(child.id, stroke.levelId)
    }

    @Test
    fun eraserOnlyReachesVisibleLevels() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val tree = vm.camera.level.tree
        val child = tree.childAt(tree.root, 0f, 0f)
        val sibling = tree.childAt(tree.root, 500f, 0f) // NOT visible from `child`

        val visibleStroke = addStroke(vm, child.id, 0f, 10f)
        val hiddenStroke = addStroke(vm, sibling.id, 500f, 510f)

        // camera descends into `child`; `sibling` is outside the visible set
        vm.camera.level = child
        doc.reindex()

        vm.eraserRadiusWorld = 4f // child-local 4 (camera level == stroke level)
        vm.beginErase(5f, 0f)
        assertTrue(vm.eraseTo(6f, 0f))
        vm.endErase()

        // the visible child stroke was erased away...
        assertTrue(doc.activeLayer.strokes.none { it === visibleStroke })
        // ...while the sibling-level stroke stayed untouched
        assertTrue(doc.activeLayer.strokes.contains(hiddenStroke))
        assertEquals(1, doc.activeLayer.strokes.size)
    }

    @Test
    fun cameraLevelEraserBehavesExactlyAsBefore() {
        // strokes of the camera's own level erase with the pre-P4 math:
        // radius in camera-local units, centers unconverted
        val vm = DrawingViewModel()
        val doc = vm.document
        addStroke(vm, ZoomLevels.ROOT_ID, 0f, 2f, 4f, 7.5f, 20f, 22f)

        vm.eraserRadiusWorld = 4f
        vm.beginErase(3f, 0f)
        assertTrue(vm.eraseTo(3.1f, 0f))
        vm.endErase()

        val frag = doc.activeLayer.strokes[0]
        assertEquals(3, frag.points.size)
        assertEquals(7.5f, frag.points[0].x, 0.001f)
        assertEquals(ZoomLevels.ROOT_ID, frag.levelId)
    }
}
