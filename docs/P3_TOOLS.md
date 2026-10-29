# P3 — New Tools: Shapes, Lazy Brush, Blend/Smudge (implementation notes)

> Historical planning/engineering record. For the current product behavior, brush collection and verification status, use the [documentation index](README.md) and [user guide](USER_GUIDE.md). Earlier test counts and release targets below are preserved as historical evidence.

Implements the three MISSING tool families of
`docs/REVIEW_2026-10-18.md` (SHAPES / LAZY BRUSH / BLEND+SMUDGE sections)
on top of P0 (nested zoom levels), P1 (pen routing + screen-relative
sizes), and P2 (layers v2). All work is test-first; the suite grew
109 → 150 (0 failures).

Everything lives in the established invariants:

- **Level-local storage** — every shape/stroke/stamp stores `levelId` and
  coordinates in that level's frame (P0), so geometry stays float-safe at
  any zoom depth.
- **Screen-relative sizes** — shape outline width and stamp radius are
  defined as screen px at 1x and divided by `camera.scale` at commit
  (the `BrushSpec.scaledForLocalScale` contract), so on-screen weight is
  constant across zoom levels.
- **One undo step per gesture** — shape commit, shape move, corner
  resize, marquee select+transform, and a whole smudge gesture each
  record exactly one reversible command.
- **Layer lock** — locked layers reject shape gestures and smudges at
  BOTH the begin and the commit path (the mid-gesture lock tap is
  covered), while still rendering.
- **Stylus-only tools** — SHAPE/SMUDGE run on pen events; fingers keep
  panning/pinching (CORE#1), verified by Robolectric tool-type dispatch.

## P3.1 Shapes (`model/ShapeData.kt`)

`ShapeType`: LINE, RECTANGLE, ROUNDED_RECT, ELLIPSE, TRIANGLE, POLYGON,
STAR, ARROW. A `ShapeData` is two anchor corners in the owning level's
frame + style (`fillOn`, `fillColor`, `outlineColor`, `outlineWidthWorld`
level-local, `cornerRoundness`, `pointCount`) — pure data; all geometry
lives in the JVM-testable `ShapeGeometry` object:

| Type | Geometry (anchors' box = normalized rect) |
|---|---|
| LINE | open segment A→B (fill toggle ignored) |
| RECTANGLE | the box |
| ROUNDED_RECT | the box, corner radius `roundness * min(w,h)/2` (rendered with `drawRoundRect` — crisp arcs at any zoom) |
| ELLIPSE | inscribed in the box (`drawOval`) |
| TRIANGLE | apex top-center, base at the bottom corners |
| POLYGON | regular N-gon (N = points slider, ≥3) inscribed in the box's ellipse, first vertex at the top |
| STAR | N points, outer radius = box half-extents, inner = `STAR_INNER_RATIO` (0.5), 2N vertices alternating from the top |
| ARROW | shaft A→B + filled head triangle at B (head length 0.3·|AB|, half-width 0.5·head length) |

**Deviation, documented:** the audit's "POLYGON (points list)" becomes a
regular N-gon under drag creation — a two-anchor drag cannot produce a
freeform vertex list, and the N-gon keeps the shape parametric + level-
projectable. The points slider covers the "N" degree of freedom.

- **Drag = live rubber-band preview.** `DrawingViewModel.beginShapeGesture
  / extendShapeGesture / endShapeGesture` keep an `inProgressShape`
  rendered by `drawScene` exactly like an in-progress stroke (same level
  transform, same saveLayer blend wrapping on blended active sheets).
  Constrain / From-Center are applied to the LIVE anchors each frame via
  `ShapeGeometry.resolveAnchors` (from-center FIRST, then constrain, so
  constrained centered boxes stay centered).
  - **Constrain** (shift-like): rect-family → square/circle (equal sides,
    drag-direction signs preserved); LINE/ARROW → 15° direction snapping
    with length preserved.
  - **From-center**: the drag start becomes the shape's center; the box
    grows symmetrically about it (for LINE/ARROW the segment mirrors
    about the start point).
  - Degenerate taps commit nothing (no undo entry).
- **Commit** → `DrawingDocument.commitShape` = `AddShapeCommand`
  (undoable; redo re-inserts the same object).
- **Select tool integration** (`model/Selection.kt`): the selection
  gains `selectedShapes` beside strokes, with the SAME invariants
  (active-layer only, pruned on layer switches, lock rejects, ONE undo
  step per gesture via the shared Transform command):
  - **TAP** a shape (pen down inside its projected bbox + 24 px slack,
    topmost last-drawn first) selects just that shape; dragging the tap
    moves it. Empty space still starts a marquee, and the marquee picks
    shapes across every VISIBLE level by projected bbox (P1.3b math).
  - **Corner handles**: the selection rectangle draws 4 small squares
    (24 px visual, 40 px+ square touch target). Dragging one scales the
    whole selection UNIFORMLY about the OPPOSITE corner —
    `SelectionModel.scaleAboutCameraPoint` projects the pivot into each
    level group's own frame (the same honest per-level-group semantics
    as rotate/flip).
  - **Del** removes selected shapes in the same single undo step as
    strokes.
- **Rendering order** (per layer, per level): images → strokes → shapes →
  smudges, in BOTH the all-normal fast path and the P2 layer-major
  blended path (new content paints over the layer's older strokes;
  smudges over shapes, per the deposit-order rule below).
- **UI**: `ui/ShapesPanel.kt` — type row (8 buttons, armed one
  highlighted), Fill toggle, Fill/Outline color wells (reusing the HSV
  `ColorPickerDialog`), Width (1-60 px) / Round (0-100%) / Points (3-12)
  sliders, Constrain + Center toggles. Arming a type switches the canvas
  to the SHAPE tool; the toolbar "Shapes" button toggles the tool+panel.
  All controls carry `contentDescription`s for uiautomator.

## P3.2 Lazy brush / stabilizer (`model/LazyBrush.kt`)

Pure rope model: the BRUSH POINT (screen px) trails the PEN by at most
`radiusScreen` (0-200). `feed(penX, penY)`:

- radius ≤ 0 → brush = raw pen (test-pinned degenerate mode);
- pen inside the radius → brush point returned UNCHANGED (static);
- pen beyond → the brush moves along the pen direction to EXACTLY r.

Integration: `DrawingViewModel.beginStrokeAtScreen / extendStrokeAtScreen
/ endStrokeLazy` (the View always routes DRAW through them). While the
stabilizer is on (`lazyEnabled` + radius > 0):

- the committed stroke points come from the ROPE's brush point converted
  to level-local via the camera each sample, so density is honest at any
  zoom/level (test at scale 4);
- at commit the points are replaced by their **midpoint quadratic
  smoothing** — linear half-segments at the ends, and for each interior
  point p_i a quadratic Bezier from mid(p_{i-1}, p_i) with control p_i to
  mid(p_i, p_{i+1}), sampled 4x per segment (the exact quad midpoint
  0.25·M1 + 0.5·C + 0.25·M2 is test-asserted);
- the stroke is flagged `taper = 0.1`: `StrokeOutline` (which gained an
  optional taper parameter — existing call sites unchanged) smoothsteps
  the effective width down over the first/last 10% of the stroke's
  ARCLENGTH, reaching ~0 at the very ends; the end cap circles scale
  with the same factors. Eraser/select act on geometry only and are
  untouched.
- `taper` serializes as an additive per-stroke codec field (absent → 0 →
  pre-P3 rendering, so old files render byte-identically).

**Overlay** (`drawLazyOverlay`, screen space, after the scene like the
selection overlay): while a lazy DRAW gesture runs — a thin line from the
pen tip to the brush point, a small filled circle at the brush point, and
a faint ring of the rope radius (our addition; the audit only demands
line + circle, the ring makes the stabilizer's reach legible).

**Controls**: in `BrushPanel` — "Lazy ✓/✗" toggle + "Lazy px" 0-200
slider, wired to `viewModel.lazyEnabled` / `lazyBrush.radiusScreen`.

## P3.3 Blend / smudge (`model/StampData.kt`)

- `StampData(x, y, radius, color, alpha)` — one soft VECTOR stamp:
  position + radius in level-local units (radius = screen size / 2 ÷
  camera scale at commit), opaque ARGB carried color, deposit alpha.
  Pure float circles, NO bitmaps — "stamps stay smooth at any zoom".
- `SmudgeData(levelId)` = one gesture's ordered stamp trail, stored on
  the layer alongside strokes/images/shapes. Layer lock rules apply; NOT
  stroke-erasable (the vector eraser only fragments strokes — test-
  pinned). Commit = `AddSmudgeCommand`, ONE undo step per gesture.
- **Carry chain**: at gesture start the View renders the composed scene
  ONCE to a snapshot bitmap (`smudgeSampler`, the readback the spec
  explicitly allows) and the initial carried color is sampled under the
  pen. At every deposit step: sample the snapshot under the pen, then
  `carried = 0.8·carried + 0.2·sampled` per channel (pure
  `SmudgeMath.mixCarried`; geometric convergence test-pinned), then
  deposit a stamp of the carried color. Stamps deposit every ~4 screen
  px of pen travel (`SmudgeMath.shouldDeposit`, interval converted to
  level-local units), each at `alpha = strength × pressure`.
- **Rendering** (`drawStamp`): one `drawCircle` per stamp with a
  `RadialGradient` alpha falloff — the softness slider interpolates the
  gradient's hard-stop position (0 = hard edge, 1 = linear falloff from
  the center); layer opacity multiplies the deposit alpha. Stamps draw
  per level/layer UNDER the saveLayer blend path, AFTER images and AFTER
  that layer's strokes (deposit order; the audit's "stamps paint over
  strokes within the same layer").
- **Controls**: `ui/BlendPanel.kt` — Strength (0-100%), Size (5-150
  screen px), Softness (0-100%) sliders; the toolbar "Blend" button arms
  the SMUDGE tool and opens the panel.

## Codec v3 extension (`model/DocumentCodec.kt`)

Additive, version stays 3 (the pre-P3 migration test pins a byte-exact
pre-P3 snippet):

- per layer: `"shapes":[{type, level, x0, y0, x1, y1, fillOn, fillColor,
  outlineColor, outlineWidth, round, count}]` and
  `"smudges":[{level, stamps:[x,y,radius,color,alpha,...]}]` — emitted
  only when non-empty so pre-P3 files round-trip byte-identically;
- per stroke: `"taper"` when nonzero;
- unknown shape `type` strings are SKIPPED on load (forward-compat
  degrades gracefully instead of failing the session); malformed stamp
  rows are dropped; absent fields default.

## Tests (suite 109 → 166, 0 failures)

- `P3ShapesTest` (27, JVM): per-type geometry (ellipse equation, triangle,
  N-gon regularity, star radii alternation, arrow head corners,
  rounded-rect arcs on the corner circle), constrain (square box /
  15° snap with length preserved), from-center (symmetric growth,
  stays centered under constrain — the center-squaring rule), commit/
  undo/redo (exactly one shape per gesture, outline width screen→local
  at zoom), tap/move/resize/delete single-undo semantics, cross-level
  marquee of shape bboxes, lock rejection at both paths, deep-copy
  independence, codec round trips + pre-P3 migration + unknown-type
  skip + unsigned-ARGB tolerance.
- `P3LazyTest` (10, JVM): rope math (static inside r, exact-r at 3r and
  along (3,4,5), radius-0 raw pen), quad midpoint property, in-polygon
  bounds, taper factors (0 at ends, 1 mid, smoothstep(0.5), fraction-0
  off), outline half-widths tapering at the ends, VM commit path (rope
  output → level-local at scale 1 and 4, smoothing densifies, endpoints
  exact, taper flag), lazy-off keeps the legacy path byte-for-byte.
- `P3SmudgeTest` (10, JVM): 80/20 channel math + 20-step convergence,
  deposit interval in local units, strength×pressure alpha, radius
  conversion, gesture commit/undo/redo as one step, sub-interval moves
  deposit nothing, carry chain across deposits (scripted sampler), empty
  gesture, lock rejection, not-stroke-erasable, codec round trip.
- `P3SmokeTest` (6, Robolectric, real MotionEvents): stylus drag commits
  exactly one shape + fingers pan only; SELECT tap→move + corner resize
  with single-step undos through real touch dispatch; lazy stroke
  commits the rope output smoothed+tapered; smudge drag commits the stamp
  trail; pixel-level NATIVE-graphics checks (filled vs outline rect dark
  pixel counts, soft-stamp intermediate grays + hard/soft rim
  comparison); BlendPanel slider mapping.

Two model fixes the tests forced out: `SmudgeMath.mixCarried` now
half-up ROUNDS per channel (float truncation stalled the carry chain at
250/255), and `ShapeGeometry.bboxOf` returns EXACT geometry extents
(padded bboxes made corner-resize handles drift) — culling adds the
outline slack instead.

## Exporter + codec hardening found on-device

- `PngExporter` now includes shapes and smudges in the export bbox union
  (stamps via min/max ± radius), so exports frame P3 content — before,
  a shapes-only document framed the empty viewport.
- `DocumentCodec` ARGB reads tolerate UNSIGNED color encodings
  (0..4294967295): JVM double→int SATURATES above Int.MAX_VALUE to
  0x7FFFFFFF, which silently whites out content written by non-app
  tools (found via the seeded-device session rendering blank; test-
  pinned by `unsignedArgbColorEncodingsDoNotSaturateToWhite`).

## Device pass (Galaxy Tab S9 FE, SM-X510, One UI; 2026-10-19, P3 build)

- User's live session backed up FIRST and restored EXACTLY afterwards
  (34 strokes, 15 levels, 2 layers — verified loading + rendering again,
  35k non-white screencap samples; the user's own gallery exports were
  left untouched, the three seeded-test PNGs were deleted).
- **Seed 1 (root-level P3 content)** — 5 shapes (filled rect, outline
  rect, 5-point star, black rect, red arrow) + a 126-stamp smudge
  trail, written in the new codec format: restored on-device, exported
  via the app's own PNG button (4096×805 — the P3 bbox union framing
  works), analyzed with PIL: **filled rect 254,770 dark px vs the
  outline rect's 5,362-px edge strip** (filled dwarfs outline); the
  star's ray-cast radial profile shows **exactly 5 outer bulges**
  (max ray radius 272 px, inner profile 114); the arrow measures
  **27,529 red px**; the smudge trail beyond the black rect holds
  **6,548 mid-gray px across 255 distinct gray levels** (soft
  RadialGradient falloff), with stamp cores stacking to black at the
  seeded 0.85 alpha as expected.
- **Seed 2 (deep level)** — a red 5-point star + white→red smudge in a
  CHILD zoom level (origin (1050,1050), scaleToParent 100) + a root
  blue rect; camera boots AT the child level: the child star renders
  ~43k red px at depth; after four toolbar "-" taps the camera demotes
  to root (×50) and the SAME star renders at its correct smaller size
  (~6.4k px — 2 root units at ×50 ≈ 100 px, matches prediction); three
  more "-" shrink it further; seven "+" taps promote back into the
  child with the star big again (~186k px). The app's own autosave
  round-trips the deep session (2 levels, child-levelId shape + smudge
  preserved) — shapes created at depth render at the correct scale
  through the whole promote/demote cycle. 0 `FATAL EXCEPTION`s.
- **Panels via uiautomator** (ALL-CAPS dump labels): SHAPES opens the
  panel with all 8 type buttons (LINE/RECT/RRECT/OVAL/TRI/POLY/STAR/
  ARROW); FILL ✓ flips to ✗, CONSTRAIN ✗→✓, CENTER ✗→✓; the Width/
  Round/Points SeekBars tap-drag; BLEND opens the Blend/Smudge panel
  with the Strength/Size/Softness sliders driven at 90%/50%/20%.
- Stylus-dependent paths (shape drag commit, tap-select, resize, lazy
  rope, smudge sampling) are the Robolectric executable spec — adb
  cannot inject tool types (unchanged P1/P2 limitation); the real-S-Pen
  feel (rope lag, taper) is left for hand testing.

## Honest gaps / deferred

- **Cross-level eraser (P3.4 carryover)**: skipped again — P3.1-P3.3
  plus the device pass consumed the phase; it stays on the honest-gaps
  list (radius conversion per level via the scale chain, fragments
  inherit the erased stroke's level).
- Long-press drag reorder (P2 carryover), calligraphy direction-varying
  width (audit BRUSHES), image v2 + toolbar sections + demo button (P4).
- Shape hit-testing is bbox-based (the audit asks "bbox/outline");
  thin LINE shapes near a busy region can over-select. Tap tolerance
  is 24 px screen.
- The lazy overlay's rope-radius ring is an addition beyond the spec's
  line + circle (documented above).
- Smudge sampling uses ONE scene snapshot per gesture (allowed), so
  deposits do not feed on each other within the same gesture — colors
  come from the gesture-start composition.
- Shader-per-stamp rendering allocates one RadialGradient per stamp per
  frame; fine at current stamp counts, a batching/cache item if
  profiling ever demands.
