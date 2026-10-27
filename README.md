# ZoomArt-Android

An Android clone of Infinitefy OU's *Endless Zoom Canvas / ZoomArt*: an
infinite, endlessly zoomable vector drawing canvas with pressure-sensitive
brushes, layers, bookmark pins, autosave, crash recovery, and image/video
export.

## Architecture

Since P0, stroke geometry lives in a **tree of nested zoom levels**
(`model/ZoomLevel.kt`, `docs/P0_ZOOM_LEVELS.md`). Each level is one
float-safe coordinate frame: a child level's (0,0) sits at the zoom focus
in its parent and one child unit maps to 1/100 parent units, so local
coordinates stay small at ANY depth and floats never jitter. Every
stroke/pin/image stores the id of the level it was created in, with
coordinates in that level's local space.

The camera is a `(level, localScale, localTrans)` triple; pinching keeps
`localScale` within ~[1, 100] and crossing a boundary promotes/demotes the
camera into the child/parent level with exact screen-point continuity.
Rendering (`drawScene`) composes the visible set — the parent chain up to
root plus the camera's children — level by level through
`Camera.transformTo`, culling each level through its own buckets of the
synchronized `StrokeIndex` grid. The same draw path powers the screen, the
eyedropper, PNG export, and the MP4 zoom-tour encoder. Brush widths are
**screen-relative**: stored as level-local units (screen px / localScale)
at commit time, so a stroke keeps its on-screen width across all zoom
levels.

| Component | File(s) |
|---|---|
| Nested zoom levels + tree (P0) | `model/ZoomLevel.kt` |
| Camera v2: level-aware pan/zoom, promote/demote, root-space flights (P1) | `model/Camera.kt` |
| BrushSpec: pressure→width, pressure→opacity (P1 pencil), screen-relative sizing | `model/BrushSpec.kt`, `model/BrushPresets.kt` |
| Pen-vs-finger input routing constants (P1) | `model/TouchRouting.kt` |
| Variable-width stroke outline geometry (JVM-testable PathSink) | `model/StrokeOutline.kt` |
| Synchronized uniform-grid spatial index, keyed by (levelId, cell) | `model/StrokeIndex.kt` |
| Layers, bookmarks, undo/redo command stack — v2 (P2): duplicate/rename/reorder/lock, per-layer blend (LayerBlend) | `model/LayerData.kt` |
| Selection/transform (strokes + shapes + P4 images) — cross-level, per-frame | `model/Selection.kt` |
| Session codec v3 (levels, per-item levelIds, brush pOpacity, image alpha/lock/rot) | `model/DocumentCodec.kt` |
| In-flight crash guard (session snapshot on any uncaught exception) | `model/CrashGuard.kt` |
| App state (stroke lifecycle, restore, serialize, demo seeding, cross-level eraser) | `model/DrawingViewModel.kt` |
| Infinite-canvas View: pen/finger routing, level-composed rendering, image transforms, demo tour, flights | `ui/ZoomableCanvasView.kt` |
| Brush / Layers / Pins / Shapes / Blend / Image panels + HSV color picker (Layers v2: P2; Image: P4) | `ui/BrushPanel.kt`, `ui/LayersPanel.kt`, `ui/PinsPanel.kt`, `ui/ShapesPanel.kt`, `ui/BlendPanel.kt`, `ui/ImagePanel.kt`, `ui/ColorPickerDialog.kt` |
| PNG export (world bbox → up to 4096px) | `export/PngExporter.kt` |
| MP4 export (H.264 camera fly-through of pins) | `export/ZoomVideoExporter.kt` |
| Wiring: CrashGuard, atomic autosave, crash-file recovery, exports | `MainActivity.kt` |

## Input model (P1, spec CORE#1)

- **S Pen / stylus draws.** TOOL_TYPE_STYLUS runs the active tool
  (draw / erase / select); the S Pen's eraser end (TOOL_TYPE_ERASER)
  always erases. Pressure drives brush width and, for the pencil,
  opacity.
- **Fingers pan/zoom only.** One finger pans (with fling inertia); two
  fingers pinch pan+zoom as before. Fingers never commit strokes, never
  drop pins (stylus double-tap pins), and never sample the eyedropper.
- **Palm rejection:** a finger landing while the pen is working is
  ignored until it lifts; the pen landing mid-pinch cancels the pinch.
- Camera flights to pins (`animateToBookmark`, MP4 tours) interpolate
  smoothly ACROSS level boundaries — log-space magnification + root-space
  position, decomposed onto the legal [1, 100) level each frame (P1.3a).
- Marquee selection reaches every VISIBLE level (parent chain + children)
  by projecting stroke bboxes into the camera's frame; drags move each
  stroke in its own frame, one undoable step per gesture (P1.3b).

## Layers (P2, spec LAYERS)

Layers are **stacked sheets of paper, separate from zoom levels**: every
sheet holds content from ALL zoom levels (root-level and deep strokes
alike, each in its own level's frame) and sheets composite in stack
order.

- **Per-layer ops, all undoable:** duplicate (deep copy — new stroke and
  image objects, inserted above the source, made active), rename (tap
  the name in the panel), reorder (Up/Dn buttons; the active layer keeps
  its identity), opacity (live preview during the drag; ONE undo step on
  release).
- **Lock:** a locked layer rejects new strokes, the eraser, marquee
  selection, transforms, delete-selection and image placement — while
  still rendering normally. Locked rows show a 🔒 padlock; the pen gets
  a "Layer is locked" toast.
- **Blend modes:** Normal / Multiply / Screen / Overlay per layer. With
  any blended layer visible, `drawScene` renders LAYER-MAJOR (stacked
  sheets) and wraps each blended sheet in `canvas.saveLayer` so it
  composites with its mode across ALL visible levels (API 29+
  `BlendMode`; API 26-28 falls back to `PorterDuff.Mode`). PNG and MP4
  exports render through the same path, so they inherit blending.
- **Codec v3:** layers serialize `locked` + `blend`; pre-P2 session
  files migrate losslessly (defaults), unknown blend strings degrade to
  Normal, round trips stay byte-stable.
- Selection sanity: selected strokes always belong to the active layer
  (pruned after every layer op), so a stale selection can never edit a
  sheet you switched away from.

## New tools (P3, docs/P3_TOOLS.md)

- **Shapes** — 8 parametric vector types (line, rect, rounded rect,
  ellipse, triangle, polygon, star, arrow), each stored as two anchors +
  style in the OWNING level's local frame. Stylus drag draws with a live
  rubber-band preview; Constrain squares boxes / snaps lines to 15°,
  From-Center grows shapes about the drag start. Fill toggle + fill /
  outline colors (HSV picker) + width / roundness / points sliders in
  the Shapes panel; outline width converts screen px → level-local at
  commit so it keeps its on-screen weight at any zoom. The Select tool
  TAPS a shape to select it, drags to move, and the corner handles
  resize uniformly about the opposite corner — every gesture ONE undo
  step; the marquee picks shapes across visible levels by projected
  bbox.
- **Lazy brush** — a rope stabilizer (0-200 screen px, Brush panel):
  the committed points come from the trailing brush point (converted
  per-sample to level-local, so density holds at any zoom), the
  midpoint-quad smoothing densifies them at commit, and stroke ends
  taper over the first/last 10% of arclength (a per-stroke codec
  field; eraser/select unaffected). While drawing, a screen-space
  overlay shows the pen→brush line, the brush dot, and the rope ring.
- **Blend/smudge** — a stylus gesture samples the composed scene once
  (snapshot at gesture start), carries the color at 80% carried / 20%
  newly sampled per deposit step, and drops soft VECTOR stamps (float
  radius, RadialGradient alpha falloff — softness slider) at ~4 screen
  px intervals with alpha = strength × pressure. Stamps live on the
  layer (locked rules apply), are NOT stroke-erasable, and render after
  that layer's strokes in the same saveLayer blend path. One gesture =
  one undo step. Codec v3 carries shapes + smudges + taper additively;
  pre-P3 files migrate losslessly, unknown shape types degrade
  gracefully.

## Image v2 + UI (P4, docs/P4_IMAGE_UI.md)

- **Own-layer import** — the photo picker's bitmap lands on a NEW
  "Image \<n\>" layer above the active one (centered on the view in the
  camera's current level, which becomes active) — never burying the
  user's strokes. One undoable command; every layer v2 op applies.
- **Transforms** — the Select tool taps an image (bbox hit-test, 24 px
  slack), drag moves it, corner handles resize uniformly about the
  opposite corner, and a two-finger pinch rotates + scales it (the
  inter-pointer angle feeds the same live-rotation path as the Rot90
  button). All level-tagged, ONE undo step per gesture. Locked images
  are never selected — draw over them freely.
- **Image panel** (`ui/ImagePanel.kt`) — Transparency 0-100% (per-image
  alpha, one undo step per drag), Lock, **Trace** (one tap = one
  composite undoable command: alpha 40% + lock + a fresh layer above the
  image layer, active), and a display cycle Off → Gray → Contrast
  (render-time ColorMatrix on the image paint only — the bitmap data is
  untouched; UI-state, deliberately NOT serialized). Plus the audit's
  persistent note: imported bitmaps blur past their resolution, drawn
  strokes stay sharp at any zoom.
- **Toolbar sections** — the strip is grouped behind the audit's eight
  labeled sections (Brushes, Blend, Shapes, Style, Lazy, Layers, Image,
  Edit) with a trailing utility group (Demo, Pins, zoom ±, PNG, Video);
  one panel at a time. **Clear** (Edit) empties the active layer behind
  a confirm dialog as ONE undoable command.
- **Demo** — seeds 4 concentric star/rect/ellipse rings on fresh layers
  at descending zoom levels (a chain rooted at the origin, levels reused
  across runs) and flies the camera through them ~2 s per level on the
  P1 cross-level flight; ANY touch cancels. Seeded without undo entries
  (demo scenery is not a user action — documented).
- **Readout** — the on-canvas readout now always shows both total
  magnification and depth: `12.3x · level 2`.
- **Cross-level eraser** (the thrice-deferred carryover, now closed) —
  the eraser matches strokes of the active layer in ALL visible levels,
  converting its radius and path per level through the scale chain;
  fragments inherit the erased stroke's levelId; one undo step per
  gesture; camera-level strokes keep the exact pre-P4 math.

## Verification status

**JVM + Robolectric: 200/200 tests green** (`testDebugUnitTest`) — the
185 pre-overhaul suite untouched, plus 15 UI-overhaul tests
(`PanelGeometryTest`, `AutoHideStateTest`, `GlassPanelTest`; see
`docs/UI_OVERHAUL.md`).
- P0 suite (`CameraLevelTest`): 30-deep screen/local/root round trips,
  promote/demote exact inverses, zoom-out integrity from level 12,
  childAt reuse, codec v3 byte-stable round trips + v1 migration,
  depth-30 jitter probe, plus retained camera/brush/undo coverage.
- P1 suite (`P1SpecTest`): pen-family routing, eraser-end forcing,
  pencil pressure→opacity (alpha floor/monotonicity/width
  near-constancy), codec preservation of the new brush fields,
  cross-level flights (no hold, legal [1,100) bands at every frame,
  screen continuity, exact endpoints both directions), cross-level
  marquee (child levels from root, ancestors from deep cameras,
  visibility filtering), per-frame translate, one-step cross-level undo.
- `AppSmokeTest` (Robolectric): boots the real activity headlessly and
  dispatches real `TOOL_TYPE_STYLUS` / `TOOL_TYPE_FINGER` MotionEvents —
  stylus draws and double-tap-pins, finger drags PAN the camera and
  commit nothing (adb cannot inject tool types, so this is the
  executable spec for on-device pen-vs-finger behavior).
- P2 suite (`P2LayersTest` + `P2SmokeTest`): duplicate deep-copy
  independence both directions, undo/redo of duplicate/rename/reorder/
  lock/blend/opacity, reorder keeps the active-layer identity, lock
  rejections through every commit path (VM + document + selection +
  real stylus touch), panel v2 controls driving the real document
  (lock/dup/blend cycle/reorder/collapse/rename dialog), codec locked+
  blend round-trip + pre-P2 v3 migration + unknown-mode fallback, and
  pixel-level blend verification under Robolectric native graphics
  (NORMAL=blue over red, MULTIPLY=black, SCREEN=magenta, OVERLAY≠NORMAL).
- The retained Phase-5 coverage: undo/redo semantics, undoable layer
  ops, codec round trips cross-validated by Python `json.loads`,
  malformed-JSON rejection, crash-guard snapshot+chain, spatial index
  (dedup/oversize/owners), outline geometry.
- P3 suites (`P3ShapesTest` + `P3LazyTest` + `P3SmudgeTest`, JVM;
  `P3SmokeTest`, Robolectric): per-type shape geometry (star radii,
  arrow head, corner arcs, N-gon), constrain/from-center math, shape
  commit/move/resize/delete single-undo semantics, cross-level shape
  marquee, lazy rope math + quad smoothing + arclength taper, smudge
  80/20 carry chain + deposit cadence + strength×pressure, codec
  shapes/smudges/taper round trips + pre-P3 migration + unsigned-ARGB
  tolerance; real-stylus dispatch commits exactly one shape, tap-moves
  and corner-resizes shapes, lazy strokes match the rope output, smudge
  gestures commit stamp trails; pixel-level filled-vs-outline and
  soft-stamp gray checks under Robolectric native graphics.
- P4 suites (`P4ImageUiTest`, JVM; `P4SmokeTest`, Robolectric):
  own-layer import flow (naming, active switch, placement math,
  single-step undo/redo, codec round trip), image tap-select/move/
  corner-resize single-undo semantics, rotation pivot + rotated bbox,
  marquee/lock rejections, alpha/lock/rotation codec round trip +
  pre-P4 migration + byte-stable defaults, display-filter non-
  serialization (UI-state decision), tracing mode as one fully-
  restoring composite command, clear-layer command, demo seeding sanity
  (level chain, level-local coords, level reuse, no undo pollution),
  cross-level eraser (radius conversion, fragment levelId, visibility
  scoping, camera-level equivalence); real-stylus tap-selects/moves/
  resizes an image, ImagePanel controls driving the real document,
  display filters proven at the pixel level through a real decoded
  bitmap, demo any-touch cancel, readout format.

**Device pass (Galaxy Tab S9 FE, SM-X510, One UI; 2026-10-18, P1 build):**
- Fresh stylus-less launch: focused window, 0 `FATAL EXCEPTION` across
  the whole session.
- adb finger swipes (TOOL_TYPE_FINGER) pan the camera by the swipe
  vector (+300/+300 exact, plus fling glide; a second swipe -400/-300)
  and commit **zero** strokes (34 → 34) — the pre-P1 build drew on
  finger drags.
- Zoom ± deep-cycle via toolbar taps: root → depth 3 → root with clean
  promote/demote bands (scale 1.79 / 1.14 / 1.46 on the way down;
  73.1 / 57.1 / 89.3 on the way up), no crash.
- Pencil pressure→opacity rendered on-device: a seeded session with
  pencil strokes at pressures 0.15 / 0.50 / 0.95 screencap-analyzed at
  231 / 202 / 164 mean gray (light press faint, hard press dark), with
  an identical control brush (pressure-opacity off) staying dark (167)
  at the same light pressure — isolating pressure→opacity as the cause.
  The app's own autosave round-trips `pOpacity`/`minOp` on-device.

**Device pass (Galaxy Tab S9 FE, SM-X510, One UI; 2026-10-19, P2 build):**
- Fresh install + seeded session (strokes on 3 layers incl. a LOCKED
  layer and a child zoom level, written in the new locked+blend codec
  format): restored cleanly, 0 `FATAL EXCEPTION` across the whole pass
  (AndroidRuntime tag), no crash-recovery file.
- Layers panel v2 driven via uiautomator: blend cycle button walked the
  full ring (Multiply→Screen→Overlay→Normal→Multiply), DUPLICATE
  produced "Background copy (5)" directly above its source, Up/Dn moved
  it below/above TopMultiply, tap-name opened the rename dialog and the
  IME-typed rename ("TopMultiply_V2") survived an app relaunch, LOCK/
  UNLOCK toggled per row (locked rows show the 🔒 padlock), the opacity
  slider drag persisted as opacity 0.23 with the toolbar Undo covering
  it, and the ▲/▼ chevron collapsed/re-expanded the rows section.
- Locked-layer semantics on-device: the locked layer kept exactly its
  1 seeded stroke through the whole interaction pass (finger swipes pan
  only post-P1; the pen-rejection paths are the Robolectric executable
  spec since adb cannot inject stylus tool types) and its SCREEN stroke
  still rendered.
- Blend verified by exported-PNG pixel analysis (the app's own PNG
  button, MediaStore `Pictures/ZoomArt`, analyzed with PIL): with the
  same session rendered twice, the red×semi-transparent-sky-blue
  overlap measures **(39,0,0) under MULTIPLY vs (39,162,216) under
  NORMAL** — the exact PorterDuff/Skia math — and a stroke living in a
  CHILD zoom level composited as a black strip under MULTIPLY vs
  sky-blue under NORMAL, proving blended sheets span ALL zoom levels.
  The locked layer's SCREEN blend measured (255,0,130) in both exports.
- The live session on the tablet was backed up first and restored
  exactly afterwards (verified semantically identical: same 34-stroke
  drawing, 15-level tree, camera and pin; only the app's own autosave
  added the P2 `locked`/`blend` defaults). The seeded test PNGs were
  deleted; the user's own exports were left untouched.

**Device pass (Galaxy Tab S9 FE, SM-X510, One UI; 2026-10-19, P3 build):**
- The user's live session was backed up first and restored exactly at the
  end (34 strokes / 15 levels verified rendering again); the user's own
  gallery exports were untouched and the seeded test PNGs were deleted.
- Seeded sessions in the P3 codec format (shapes + smudges arrays):
  the root-level seed (filled/outline rects, 5-point star, red arrow, a
  126-stamp smudge trail) restored on-device and exported through the
  app's own PNG button (4096×805 — the P3 bbox union frames the new
  content types); PIL analysis: filled rect 254,770 dark px vs the
  outline rect's 5,362-px edge strip, the star's ray-cast radial profile
  shows exactly 5 outer bulges, the arrow 27,529 red px, and the smudge
  trail holds 6,548 mid-gray px across 255 distinct gray levels (soft
  vector-stamp falloff).
- Deep-level seed: a red star + smudge in a CHILD zoom level with the
  camera booting at depth — rendered big (~43k red px), survived the
  toolbar zoom-out demote to root at its correct smaller size (~6.4k px,
  matching the 2-root-unit prediction at ×50), and came back big after
  promote; the app's own autosave round-tripped the child-levelId shape
  and smudge. 0 `FATAL EXCEPTION`s across the whole pass.
- uiautomator drove the new panels: SHAPES (8 type buttons; FILL ✓→✗,
  CONSTRAIN ✗→✓, CENTER ✗→✓; Width/Round/Points SeekBars) and BLEND
  (Strength/Size/Softness SeekBars). Stylus-dependent paths remain the
  Robolectric executable spec (adb tool-type injection limit).

**Device pass (Galaxy Tab S9 FE, SM-X510, One UI; 2026-10-19, P4 build):**
- The user's live session was backed up FIRST (34 strokes / 15 levels /
  2 layers / 1 pin, sha256-pinned) and restored EXACTLY at the end (the
  drawing verified rendering again — 9,018 non-white screencap samples);
  the user's own gallery exports were untouched and the eight seeded
  test exports were deleted by exact name. 0 `FATAL EXCEPTION` across
  the whole pass.
- Seeded session in the P4 codec format: an "Image 3" layer holding a
  striped bitmap at ROOT level plus a second copy inside a CHILD zoom
  level, camera at root. The app's own autosave round-tripped the new
  per-image `alpha`/`locked` fields; `displayFilter` correctly never
  appears in the session file (UI-state decision).
- **Image panel via uiautomator**: IMAGE opens the panel (info line,
  OPACITY slider, LOCK, TRACE, DISPLAY: OFF, the bitmap note); LOCK
  flips to UNLOCK; the display cycle walks OFF → GRAY → CONTRAST → OFF.
- **Display filters + transparency proven by PIL analysis of the app's
  own PNG exports** (image-region samples): baseline colored (|g-b| =
  10.1) → GRAY equalizes the channels (|g-b| = 0.00, means
  142.8/142.7/142.7 — the saturation-0 ColorMatrix) → CONTRAST boosts
  the spread (|r-g| 5.8 → 15.1 — the 1.8x contrast matrix) → the
  transparency slider lightens the image (mean 145 → 242 toward white).
- **Depth image**: with the camera inside the child level, the
  child-level bitmap renders big and COLORED at 100x depth
  (1,989/2,601 strongly colored samples; red/green/blue stripe families
  all present).
- **Toolbar sections + demo**: the dumps show the eight section labels
  (BRUSHES/BLEND/SHAPES/STYLE/LAZY/LAYERS/IMAGE/EDIT) plus CLEAR and
  DEMO. DEMO seeded 4 nested rings (levels reused across two runs) and
  flew the full tour — legs at magnifications 1.0 → 100 → 10,000 →
  1,000,000, ~2 s per leg, "tour complete at depth 3" — and a second
  run was cancelled MID-TOUR by a touch ("demo: tour cancelled at
  depth 1"). Image select/move/resize + pinch-rotate + the cross-level
  eraser are stylus gestures: Robolectric/JVM-verified (adb tool-type
  injection limit, unchanged); the real-S-Pen feel is a hand-test item.

**Known limits (honest):** stylus double-tap pinning and eyedropping
cannot be adb-verified (no stylus injection) — Robolectric covers them;
real-S Pen feel is left for hand testing. Cross-level selections
transform per level group (a rigid group rotation across frames is
impossible without re-leveling content); the eraser became CROSS-LEVEL
in P4.6 (the old camera-level scoping note in `docs/P1_SPEC_FIXES.md`
is superseded). P2 adds: long-press drag reorder is not implemented
(Up/Dn buttons MVP), and locked-layer stroke rejection is
Robolectric-verified only (same stylus injection limit). P4 adds: FLIP
does not mirror images (pinch rotate/scale instead); image display
filters are session-volatile UI-state by design; the Image panel acts
on the selected/latest image of the active layer; shape selections are
not pruned on layer switches (pre-existing P3 latent gap).

## Build & run

```bash
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 185 tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and an Android SDK with platform 34 (full SDK installed at
`C:/Users/silo1/AndroidSDK`).

## Usage

- **User guide:** `docs/USER_GUIDE.md` — every tool, panel, gesture and
  export, written for users.
- **Stylus** draws with pressure (pencil: pressure controls opacity);
  double-tap the pen to drop a bookmark pin. The eraser end erases.
- **Fingers** pan (one) or pan+pinch-zoom (two) with fling inertia.
- Toolbar (grouped into the audit's eight sections): **Brushes** — Brush
  (presets + size/pressure/opacity sliders), Erase; **Blend** — arms the
  smudge tool + Strength/Size/Softness panel; **Shapes** — arms the
  SHAPE tool + panel; **Style** — color swatch (HSV picker), Pick
  (pen-tap to sample a color); **Lazy** — quick stabilizer toggle;
  **Layers**; **Image** — Import (photo picker; lands on its own new
  layer), Image (transparency / lock / Trace / display-filter panel);
  **Edit** — Select (taps/moves/resizes shapes AND images; pinch
  rotates+scales the selection), Undo/Redo (strokes, layer ops, shapes,
  smudges, image moves), Del, FlipH, Rot90, Clear (empties the active
  layer behind a confirm); trailing utilities — Demo (auto-draws nested
  rings and flies through them; touch cancels), Pins (tap to fly there
  — flights glide across zoom levels), zoom ± (readout shows total
  magnification AND level: `12.3x · level 2`), PNG, Video (H.264
  fly-through of all pins).
- Autosave every 15 s + on pause (atomic tmp+rename); sessions are
  restored on launch; any crash snapshots the session and recovery wins
  on relaunch.

## Phase status (see docs/SPEC.md, docs/REVIEW_2026-10-18.md)

- [x] Phase 1 — infinite pan/zoom canvas core
- [x] Phase 2 — pressure-sensitive brushes, undo/redo, bookmarks, serialization
- [x] Phase 3 — layers UI, brush presets/panel, color picker, eyedropper
- [x] Phase 4 — autosave/restore, high-res PNG export, MP4 zoom-tour export
- [x] Phase 5 — single-path variable-width outlines, spatial index culling,
      undoable layer ops, fling scrolling, crash guard + crash recovery
- [x] P0 — nested zoom levels (ZoomLevel tree, Camera v2 promote/demote,
      per-level index buckets, codec v3 + migration) — on-device deep-zoom
      validated to depth 4+ with lossless migration
- [x] P1 — spec-correctness: pen-vs-finger input routing, pencil
      pressure→opacity, screen-relative brush width, smooth cross-level
      camera flights, cross-level marquee selection, zoom readout fix
- [x] P2 — layers v2 (duplicate/rename/reorder/lock, blend modes with
      saveLayer compositing, opacity undo, collapsible panel, codec
      locked+blend) — see docs/P2_LAYERS_V2.md; drag-reorder remains an
      honest gap (Up/Dn buttons MVP)
- [x] P3 — tools: parametric shapes (8 types, live preview,
      constrain/from-center, tap-select + move + corner-handle resize),
      lazy brush stabilizer (rope + overlay + quad smoothing + end
      taper), blend/smudge (sampled-color carry 80/20, vector soft
      stamps, strength/size/softness) — see docs/P3_TOOLS.md
- [x] P4 — image v2 (own-layer import, Select-tool tap/move/resize,
      pinch rotate+scale, transparency, lock, tracing mode,
      grayscale/contrast display filters, bitmap-blur note), toolbar
      sections + Clear layer, Demo nested auto-tour (cancellable), zoom
      readout `mag · level`, cross-level eraser (the P1-P3 carryover,
      closed) — see docs/P4_IMAGE_UI.md and the FINAL COMPLIANCE TABLE
      in docs/REVIEW_2026-10-18.md
- [x] U — UI overhaul (0.6.0): Procreate-style floating draggable glass
      panels (FloatingPanel + OverlayLayer, in-activity only — no
      WindowManager), API-31-gated frosted backdrop blur via
      snapshot-on-show (BackdropSnapshotter reusing drawScene at 1/8
      scale + RenderEffect on the backdrop ImageView; API 26-30
      scrim-only), spring micro-animations (dynamicanimation-ktx 1.1.0,
      STIFFNESS_MEDIUM / damping 0.7: panel show/hide, press feedback,
      tool pulse, chevron collapse), auto-hide while drawing
      (AutoHideStateMachine: stylus/pinch -> slide-out + fade, ~600 ms
      debounce restore, touch-restores, finger pans neutral), floating
      glass dock (same 24 buttons + 8 section labels), canvas-drawn HUD
      chip, dark-glass panel restyle, position persistence per panel —
      185 pre-overhaul tests untouched + 15 new glass tests, 200/200
      green; on-device gfxinfo A/B (panels closed 0.10% janky vs open
      0.20%, median frame 19 vs 18 ms) and PIL glass pixel verification —
      see docs/UI_OVERHAUL.md

## Gotchas found by real tooling (worth remembering)

1. `enum class` — not `enum` — introduces an enum in Kotlin.
2. Kotlin does not resolve nested classes through subclass names:
   `HorizontalScrollView.LayoutParams` fails; use `FrameLayout.LayoutParams`.
3. `android.widget.OverScroller` — not `android.view.OverScroller`.
4. Against compileSdk 34's `android.jar`, `MediaMuxer.OutputFormat.MPEG_4`
   does not resolve in Kotlin 2.0.20 (verified by probe compile); the
   constant (documented as `0` since API 18) is used as a literal.
5. `MotionEvent.obtain(..., PointerProperties[], PointerCoords[], ...)` is
   the 14-arg overload (includes `buttonState`); Robolectric faithfully
   preserves `toolType` through it (probe-tested) — that's what makes the
   pen-vs-finger smoke test trustworthy.
6. On API 34, `run-as` cannot traverse the app's own external
   `Android/data` dir (scoped storage) — public `Pictures/ZoomArt`
   (MediaStore) stays adb-pullable.
7. `adb shell input` injects TOOL_TYPE_FINGER only; it can verify
   finger-panning on-device, but pen paths are Robolectric-verified.
8. adb-injected swipes near the bottom toolbar can hit the One UI
   gesture zone — use mid-screen swipes for camera tests.
9. `adb shell run-as <pkg> sh -c "cp a && rm b"` is parsed by the
   DEVICE shell: the `&&` binds OUTSIDE `sh -c`, so the cp runs with
   no arguments and silently "succeeds". Chain run-as commands one
   `adb shell run-as <pkg> <cmd>` invocation at a time.
10. JVM `Double.toInt()` SATURATES out-of-range values (≥2^31-1 →
    Int.MAX_VALUE) instead of wrapping — any JSON color field read as
    a double must wrap unsigned ARGB encodings itself (see
    `DocumentCodec.argbOf`; bit a seeded session to blank white).
11. `uiautomator dump` can FAIL silently and leave the PREVIOUS dump
    file on /sdcard — a driver that pulls without deleting the remote
    file first reads stale bounds (found in the P4 pass: taps kept
    "missing" nodes that had moved). Delete the remote file, pull, and
    verify the XML is fresh before trusting it.
12. The device can AUTO-ROTATE mid-drive (the P4 tablet flipped to
    landscape between commands) — uiautomator bounds are
    orientation-relative, so never hard-code a screen width: parse the
    dump's root-node bounds each time and recompute toolbar/canvas
    positions from the live dump.
13. A single-finger adb PAN has FLING inertia — a "precise" mid-screen
    swipe overshoots the target world point by a wide margin at high
    zoom. For deterministic camera placement, write the camera into the
    session file and relaunch instead of swiping.
14. A MATCH_PARENT child inside a WRAP_CONTENT FrameLayout measures to
    the FULL available space (AT_MOST spec -> plain Views take the whole
    spec) — the glass panel scrim blew the shells up to 2260x1260 px on
    the tablet. FloatingPanel.onMeasure now measures only handle+content
    and hands the fill children an EXACTLY-sized spec afterwards
    (regression-tested in GlassPanelTest).
15. `ViewGroup.addView(view, lp)` REPLACES any LayoutParams the view set
    on itself in a constructor — a FloatingPanel loading its persisted
    position in init silently lost it to addShell's gravity LP. Persisted
    positions must be applied at onAttachedToWindow (also
    regression-tested).
16. androidx.dynamicanimation's AnimationHandler lives in a static
    ThreadLocal bound to the FIRST Robolectric method's (recreated main)
    looper — later methods' springs never tick under `idleFor`.
    GlassPanelTest drops the ThreadLocal in @Before (production has one
    looper and is unaffected).
