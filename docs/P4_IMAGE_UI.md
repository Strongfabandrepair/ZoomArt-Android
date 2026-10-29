# P4 — Image v2 + UI (implementation notes)

> Historical planning/engineering record. For the current product behavior, brush collection and verification status, use the [documentation index](README.md) and [user guide](USER_GUIDE.md). Earlier test counts and release targets below are preserved as historical evidence.

The FINAL phase of the refactor program: implements the IMAGE IMPORT &
TRACING and UI sections of `docs/REVIEW_2026-10-18.md` plus the
three-times-deferred cross-level eraser carryover. All work test-first;
the suite grew 166 → 185 (0 failures, no pre-existing test modified).

Everything rides the established invariants: level-local storage (P0),
screen-relative sizes (P1), stacked-sheet layers with locks and blends
(P2), one-undo-step-per-gesture (P3), stylus-only tools (P1).

## P4.1 — Image import v2: own layer

`DrawingDocument.importImageOnNewLayer(path, aspect, camera)` replaces
the old "place on the active layer as underlay" flow for the UI path:

- creates a NEW `LayerData` named **"Image \<n\>"** directly above the
  active one, places the `PlacedImage` there (levelId = the camera's
  current level, centered on the view — the same 60%-of-viewport math as
  before), and makes the new layer active;
- the whole import is **ONE undoable command** (`ImportImageCommand`):
  undo removes the layer and restores the previous active layer, redo
  re-inserts it — so the user never strands an unwanted underlay;
- layer v2 ops (rename / duplicate / lock / reorder / blend / opacity)
  apply to it like any layer;
- the legacy `DrawingViewModel.placeImage` (active-layer placement) stays
  — its locked-layer rejection and placement math are pinned by the
  pre-P4 tests.

## P4.2 — Image transforms (Select tool)

`SelectionModel` gains `selectedImages` beside strokes/shapes with the
same invariants (active-layer only, pruned on layer switches, lock
rejects, ONE undo step per gesture):

- **TAP** hit-tests the active layer's images by projected bbox with
  24 px screen slack (topmost/last-placed first, after shapes); a LOCKED
  image is never selected — draw over it freely;
- **drag inside** moves (per-level frame conversion, like shapes);
- **corner handles** resize UNIFORMLY about the opposite corner
  (`scaleAboutCameraPoint` extended to images; rotation angle preserved);
- **two-finger pinch** on a selection containing images rotates + scales
  it: the View tracks the inter-pointer angle and feeds `selection
  .rotate(delta)` live on top of the existing pinch-scale — same ONE
  undo step (`onScaleBegin` snapshots, `onScaleEnd` records);
- `PlacedImage` grows `rotation` (degrees about the image center) and a
  rotation-aware `bbox()` (rotated corners' AABB) so culling, marquee
  and handles stay correct at any angle;
- rendering: `drawImage` saves/rotates about the center when
  `rotation != 0`;
- the marquee picks active-layer images by projected bbox (locked ones
  never); delete-selection removes selected images in the same single
  undo step; the transform command carries image `[x, y, w, h, rotation]`
  before/after records.

**Documented deviation:** FLIP mirrors strokes/shapes only — mirroring a
rotated bitmap is visually ambiguous, so images keep their placement
(pinch rotate/scale is the intended path).

## P4.3 — Image panel + tracing mode (`ui/ImagePanel.kt`)

The panel acts on the **current image** — the SELECTED image when the
Select tool holds one, else the most recently placed image of the active
layer (`DrawingViewModel.currentImage()`):

| Control | Behavior |
|---|---|
| Transparency 0-100% | per-image `alpha` applied via `Paint.alpha` at draw (x the layer's opacity). Live preview; ONE undo step per slider drag (`SetImageAlphaCommand`, the layer-opacity contract) |
| Lock image | `locked` images ignore select/move (tap and marquee) — draw over them freely. ONE undoable toggle (`SetImageLockCommand`) |
| Trace | ONE composite undoable command (`TracingModeCommand`): alpha → 40%, image locked, a new empty layer created directly above the image's layer and made active. Undo restores alpha + lock + layer + active index in a single step |
| Display cycle Off → Gray → Contrast | render-time `ColorMatrixColorFilter` on the image paint ONLY (saturation 0 / contrast 1.8 about the 128 midpoint). The bitmap DATA is never touched |
| Note (persistent TextView) | "Imported images are bitmaps — they blur when zoomed far past their resolution. Drawn strokes stay sharp at any zoom." |

**Serialization decisions (documented):** per-image `alpha`, `locked`
and `rot` serialize as ADDITIVE codec fields (emitted only when
non-default, so pre-P4 files round-trip byte-identically; absent fields
default to 1.0 / false / 0). The **display filter does NOT serialize** —
it is UI-state, matching the audit's "display filter, not data" note; a
restored session shows the untouched image.

## P4.4 — Toolbar sections + CLEAR

The bottom strip keeps the programmatic MVP approach but is now grouped
behind lightweight gray divider labels, mapping every button into the
audit's eight sections:

| Section | Buttons |
|---|---|
| Brushes | Brush (panel), Erase |
| Blend | Blend (arms smudge + panel) |
| Shapes | Shapes (arms shape tool + panel) |
| Style | color swatch (HSV picker), Pick (eyedropper) |
| Lazy | Lazy ✓/✗ (quick stabilizer toggle) |
| Layers | Layers |
| Image | Import (photo picker), Image (the P4.3 panel) |
| Edit | Select, Undo, Redo, Del, FlipH, Rot90, **Clear** |
| *(trailing, beyond the audit's eight)* | Demo, Pins, −, +, PNG, Video |

- one panel open at a time as before (the Image panel joins the toggle
  group); every section's panel opens/closes cleanly;
- **CLEAR** empties the ACTIVE layer (strokes, shapes, smudges, images)
  behind a confirm dialog, as ONE undoable `ClearLayerCommand`
  (`docs`-style before/after lists; undo restores the same objects);
  clearing an already-empty layer pushes nothing;
- touch targets stay the Material buttons (≥ 48 dp); the dividers are
  non-interactive 30 px labels.

## P4.5 — Demo button + zoom/depth readout

- **Demo** (`ZoomableCanvasView.runDemo`): `viewModel.seedDemoContent()`
  builds a chain of 4 zoom levels rooted at the origin (`childAt` origin
  reuse — repeated runs SHARE levels instead of forking duplicates) and
  a FRESH layer per level carrying concentric star/rect/ellipse shapes
  at ±300 level-local units — zooming in 100x per step reveals the next
  ring filling the screen exactly like the previous one. The camera then
  flies depth by depth (~2 s per leg) riding the P1 smooth cross-level
  flight (log-space magnification, root-space position, legal [1,100)
  band per frame). **ANY touch cancels the flight** (`ACTION_DOWN` →
  `cancelDemo`); logcat narrates every leg for the device pass.
- **UNDO decision (documented):** demo scenery is seeded WITHOUT
  commands — it is not a user action, so the undo stack stays clean
  (test-pinned: `canUndo == false` after seeding). Demo layers are
  ordinary layers: delete/reorder/lock them in the Layers panel.
- **Readout:** `cameraReadout()` now ALWAYS surfaces both values —
  `"12.3x · level 2"` — total magnification (`scale * 100^depth`) plus
  the depth level (at root: `"1.0x · level 0"`).

## P4.6 — Cross-level eraser (the carryover, finally)

`DrawingViewModel.eraseTo` no longer skips non-camera-level strokes:

- candidate strokes of the active layer in every VISIBLE level (camera
  chain + children — the same set the renderer composes) are matched by
  projecting their bboxes into the camera frame (P1.3b math) and
  intersecting with the erase path's camera-frame bbox padded by the
  radius;
- for each surviving stroke the erase centers are converted into the
  stroke's OWN frame (via `Camera.transformTo`) and the radius through
  the scale chain: `radius_local = radius_cam * camScale / k_level`;
- only centers that can possibly reach the stroke are converted, so a
  long gesture stays cheap;
- `StrokeEraser` splits as before and **fragments inherit the erased
  stroke's levelId** — a cut child-level stroke stays child-level
  content;
- still ONE undo step per gesture (`ReplaceStrokeCommand` unchanged);
  locked layers still reject the eraser entirely; strokes of the
  camera's own level reduce to the exact pre-P4 math (the pre-existing
  eraser tests stayed green untouched);
- siblings of the camera level (and grandchildren) stay unreachable —
  the eraser matches exactly what's VISIBLE on screen (test-pinned).

## Tests (suite 166 → 185, 0 failures)

- `P4ImageUiTest` (14, JVM): own-layer import flow (naming, active
  switch, placement math, single-step undo/redo, codec round trip);
  tap-select/move/corner-resize single-undo semantics; rotation pivot
  math + rotated bbox; marquee and lock rejections; alpha/lock/rotation
  codec round trip + pre-P4 migration + byte-stable defaults; display
  filter NOT serialized + enum cycle; alpha/lock undoability incl. no-op
  re-sets; tracing mode as one fully-restoring command; clear-layer
  command (exact restore, empty no-op); demo seeding sanity (level chain
  at origin, per-level shapes at ±300 local, level reuse across runs,
  no undo pollution); cross-level eraser (radius conversion, fragment
  levelId, visibility scoping, camera-level equivalence).
- `P4SmokeTest` (5, Robolectric, real MotionEvents + NATIVE graphics):
  stylus tap-selects an image, drag MOVES it, corner-handle drag
  RESIZES it (pivot fixed) — each ONE undo step; ImagePanel controls
  driving the real document (transparency mapping, lock + Unlock label,
  display cycle, tracing-mode composite, the bitmap note); display
  filters proven at the PIXEL level through a real decoded bitmap
  (grayscale equalizes channels, contrast boosts spread); demo seeding +
  any-touch cancel + readout format ("1.0x · level 0" →
  "128.0x · level 1" after promote).

## Device pass (Galaxy Tab S9 FE, SM-X510, One UI; 2026-10-19, P4 build)

- The user's live session was backed up FIRST (34 strokes / 15 levels /
  2 layers / 1 pin, sha256-pinned) and restored EXACTLY at the end
  (same file, drawing verified rendering again — 9,018 non-white
  screencap samples); the user's own gallery exports were untouched and
  the eight seeded test exports were deleted by exact name.
- Seeded session (codec with image v2 fields): an "Image 3" layer
  holding a striped bitmap at ROOT level (analysis target) + a second
  copy inside a CHILD zoom level (depth proof), camera booting at root.
- **Image panel via uiautomator** (ALL-CAPS): IMAGE opens the panel
  (info line "imports/seed_img.png", OPACITY slider, LOCK, TRACE,
  DISPLAY: OFF, the bitmap note). LOCK flips to UNLOCK; the display
  button cycles OFF → GRAY → CONTRAST → OFF.
- **Display filters + transparency proven by PIL pixel analysis of the
  app's own PNG exports** (4096-px, root-space frame, image-region
  samples): baseline colored (|g-b| = 10.1) → GRAY equalizes channels
  (|g-b| = 0.0, means 142.8/142.7/142.7) → CONTRAST boosts the spread
  (|r-g| 5.8 → 15.1) → transparency slider lightens the image (mean
  145 → 242 toward white). The per-image `alpha`/`locked` values and
  the "Image 3 trace" layer round-tripped through the app's own
  autosave in the new codec fields; `displayFilter` correctly absent
  from the session file (UI-state decision).
- **Depth image**: camera set into the child level — the child-level
  bitmap renders big and COLORED at 100x depth (1,989/2,601 strongly
  colored samples; red/green/blue stripe families all present).
- **Demo tour**: DEMO seeded 4 nested rings (levels reused across two
  runs — ids 0,2,3,4) and flew the full tour: leg 0→3 at magnifications
  1.0 → 100 → 10,000 → 1,000,000, ~2 s per leg, "tour complete at
  depth 3"; a second run was cancelled MID-TOUR by a touch ("demo: tour
  cancelled at depth 1"). Toolbar sections (Brushes/Blend/Shapes/Style/
  Lazy/Layers/Image/Edit labels + CLEAR + DEMO buttons) all verified in
  the uiautomator dumps.
- **0 `FATAL EXCEPTION`** across the whole pass (AndroidRuntime tag).
- Stylus-dependent paths (image tap-select/move/resize, pinch rotate,
  the cross-level eraser) remain the Robolectric executable spec — adb
  cannot inject stylus tool types (unchanged P1-P3 limitation); real-S-Pen
  feel is left for hand testing.

## Honest gaps / deferred

- **Flip on images**: not implemented (mirroring rotated bitmaps is
  visually ambiguous; documented deviation — pinch rotates+scales).
- **Display filters are session-volatile**: deliberately not serialized
  (audit: "display filter, not data"); a relaunch shows the original
  image.
- **Image panel has no multi-image picker**: it acts on the selected or
  latest image of the active layer; selecting a different image
  requires the Select tool.
- **Cross-level eraser + image transforms under real stylus**: verified
  via Robolectric real-tool-type dispatch + JVM math; physical S Pen
  hand-test items (list in README).
- **Shape selection pruning**: pre-existing P3 latent gap (shape
  selections are not pruned on layer switch, unlike strokes/images) —
  out of P4 scope, unchanged.
- Calligraphy direction-varying width (audit BRUSHES) and long-press
  drag reorder (P2) remain open from earlier phases; the eraser carry-
  over is now CLOSED.
