# P0 Architecture — Nested Zoom Levels (implementation spec)

Goal: TRUE infinite zoom. 30+ levels, no float jitter, zoom-out shows earlier
levels intact. Replaces the single-world-space Camera model.

## Data model (model/ZoomLevel.kt)

    class ZoomLevel(
        val id: Long,                    // stable, serialized
        var originX: Float, var originY: Float,  // this level's (0,0) in PARENT units
        val scaleToParent: Float = 100f, // fixed promotion threshold
    ) {
        var parent: ZoomLevel? = null
        val children = mutableListOf<ZoomLevel>()
        // content lives in LAYER objects; level assignment is per-stroke:
        // StrokeData gains `var levelId: Long` (its owning level).
        // Strokes keep LOCAL coordinates in their level's frame.
    }

    object ZoomTree {
        val root = ZoomLevel(ROOT_ID, 0f, 0f)   // legacy "world" = root level
        fun byId(id: Long): ZoomLevel?          // map for lookup
        fun childAt(parent: ZoomLevel, x: Float, y: Float): ZoomLevel
            // find existing child whose origin is within tolerance, else create
    }

Invariant: every stroke's local coordinates stay within ~[0, 100 x threshold]
magnitudes in its own level -> floats keep ~7 significant digits at any depth.

## Camera v2 (model/Camera.kt rewrite)

    class Camera(
        var level: ZoomLevel = ZoomTree.root,
        var localScale: Float = 1f,        // 1 .. threshold(100)
        var localTransX: Float, var localTransY: Float,
    )

- screen <-> local: existing formulas, applied to the CURRENT level only.
- local <-> root: recursive multiply through the parent chain.
- Pinch: zoomAt keeps working in local units; when localScale crosses 100,
  `promote(focusScreen)`: focus point becomes the child's origin
  (childAt), level = child, localScale = 1f, localTrans recomputed. Below
  1f (and level != root): `demote()` to parent, localScale = 100f.
- Bookmarks/pins store (levelId, localX, localY, localScale).
- `interpolatedTo` operates within a level; cross-level flights hop at the
  boundary (snap promote/demote at t where the threshold is crossed).

## Input (commit-time conversion)

- Touch sampling converts screen -> CURRENT level local coords (unchanged math).
- A stroke begun in level L is committed with levelId = L.id — no cross-level
  splitting needed for P0 (a stroke lives in the level it started in; at
  extreme zoom-out it renders via the parent chain at correct scale).
- Screen-relative brush width (P1 lands with P0 since both touch commit):
  store width in level-local units = screenPx / localScale at commit time.

## Rendering (ui/ZoomableCanvasView.drawScene)

Visible level set = current level L, plus the parent chain up to root,
plus ALL children of L (one level deep) — clipped by each level's local
viewport. Per level, the canvas matrix is composed by walking from the
camera's level outward:

    levelToScreen(level): if level == camera.level -> camera local transform
      else compose parent-chain transforms (origin, 1/scaleToParent) then
      camera transform; children: inverse chain (scaleToParent about origin).

Draw order: root first (deepest ancestor), descending to the current level,
then children. Layers stay global (layer stack applies across levels); within
one level pass, iterate layers as today via the StrokeIndex (index gains a
level dimension: cells keyed by (levelId, cx, cy)).

## Culling index v2 (StrokeIndex)

Cell key becomes (levelId, cellX, cellY). Query takes the camera + visible
level set; each level's viewport rect queries its own bucket set. Same
oversize/dedup/owner logic, plus level filter.

## Codec v3 (DocumentCodec)

JSON gains: "levels":[{id,parentId,originX,originY,scaleToParent}],
strokes/shape/stamps/images gain "level":<id>. fromJson rebuilds the tree
by id (parent before child order), reindexes. Old v1/v2 session files
migrate: all content assigned to ROOT_ID (one root level) — lossless.

## Tests (rewrite CanvasTransformTest -> CameraLevelTest, all JVM)

1. Round-trip screen<->local<->root across a 30-deep chain: coordinates
   reconstructed to <0.01 local units at every depth.
2. Promote at 100x: the world point under the focus keeps its screen pos
   across the boundary (no jump).
3. Demote back: exact inverse of promote.
4. Zoom-out integrity: stroke created in level 12 renders (bbox-projected)
   at the right root-space extent after demoting to root.
5. childAt reuse: repeated promote at the same focus reuses the same child.
6. Codec: 30-level tree + strokes in mixed levels round-trips byte-stable;
   v1 session file migrates to root-level content.
7. Jitter probe: render-bbox width of a fixed stroke at depth 30 vs depth 0
   differs < 1e-3 relative (float precision held).

## Execution order (next sessions)

P0.1 model: ZoomLevel/ZoomTree/Camera v2 + tests (JVM-harness first).
P0.2 StrokeIndex level buckets + codec v3 + migration test.
P0.3 View: level-composed rendering + promote/demote on pinch + readout.
P0.4 Device pass on the Tab S9 FE (deep-zoom jitter + zoom-out integrity),
     then P1 (pen-vs-finger, pencil pressure->opacity) rides the same commit
     path already built in P0.
