# P1 — Spec-Correctness Fixes (implementation notes)

Implements the CONFLICT/PARTIAL items of `docs/REVIEW_2026-10-18.md` on
top of the P0 nested-zoom-level architecture (`docs/P0_ZOOM_LEVELS.md`).
All work is test-first; the suite grew 63 → 78 (0 failures).

## P1.1 Pen-vs-finger input routing (spec CORE#1)

`model/TouchRouting.kt` holds the pure classification (constants mirror
`MotionEvent.TOOL_TYPE_*` by value; parity is asserted in `AppSmokeTest`
under Robolectric). `ui/ZoomableCanvasView.onTouchEvent` routes:

| Tool type | Behavior |
|---|---|
| STYLUS | runs the active tool: draw (pressure-sensitive) / erase / select |
| ERASER (S Pen eraser end) | forces the erase tool regardless of selection |
| FINGER (and anything else) | never draws: 1 finger pans (fling inertia on release), 2 fingers pinch pan+zoom (unchanged) |

- Double-tap bookmark pins and eyedropper sampling are pen-only (spec:
  "finger = pan/zoom only"). Single-finger flings pan; pen flings never
  do (they're strokes).
- **Palm rejection:** a finger landing while a pen gesture is active is
  ignored until it lifts (`ignoredPalmIds`); pen pointers are never fed
  to the `ScaleGestureDetector`. A pen landing mid-pinch ends the pinch
  with a synthetic `ACTION_CANCEL` and starts the tool gesture.
- Known corner (documented, accepted): after the pen finishes with a
  palm finger still on glass, that stale finger stays inert until
  re-touched — the pinch detector never saw its DOWN, so adopting it
  could desync its pointer tracking.
- `AppSmokeTest` constructs real MotionEvents with
  `PointerProperties(TOOL_TYPE_STYLUS)` vs `(TOOL_TYPE_FINGER)` and
  asserts: stylus commits strokes + pins; a finger drag pans the camera
  (transX/transY move exactly by the drag vector) and commits nothing;
  a finger double-tap never pins; a finger flick pans without stroking.
  This is the executable spec for the on-device behavior because
  `adb shell input` cannot inject stylus tool types.

## P1.2 Pencil pressure → opacity (spec BRUSHES)

`BrushSpec` gains `pressureOpacity: Boolean` and `minOpacity` (floor) with

    alphaForPressure(p) = pressureOpacity ? opacity * lerp(minOpacity, 1, p) : opacity

- PENCIL preset: thin (3 px at 1x), `pressureOpacity = true`,
  `minOpacity = 0.15`, base opacity 0.75; width response kept LOW
  (response 0.25, minRatio 0.8 → width varies < 1.25x across pressure).
  Rendered alpha spans 0.11 (feather touch) → 0.75 (hard press).
- The stamp renderer (`spacingWorld > 0` — spray/pencil) varies paint
  alpha per stamp from the interpolated pressure; the single-dot path
  uses the point pressure. Outline and dash strokes draw one path per
  stroke, so they approximate with the first point's alpha (documented
  limitation; pencil is a stamp brush and gets exact per-point alpha).
- Codec v3 serializes `pOpacity`/`minOp` per stroke (absent fields in
  older files default to plain-opacity brushes; round trips remain
  byte-stable). `BrushPanel` carries the fields through slider tweaks
  so editing a pencil's size doesn't silently strip its pressure-alpha.

## P1.3a Smooth cross-level camera flights

`Camera.interpolatedTo` no longer half-holds and mid-flight snaps.
Cross-level flights are computed in root Double space:

- interpolate the on-screen magnification of root units
  (`scale * 100^depth`) in LOG space, and the root point pinned under
  the screen origin linearly;
- decompose each frame onto the level of the source→target path whose
  implied `localScale = mag / 100^depthLevel` lands in `[1, 100)` —
  adjacent path levels differ by exactly `scaleToParent`, so exactly one
  level can match at any magnification (nearest-band fallback for
  degenerate endpoints);
- `t = 0` / `t = 1` return the source/target cameras exactly.

Same-level flights keep the legacy exact log-scale + linear-translate
formula. The zoom readout was also fixed to display true total
magnification (`Camera.rootMagnification() = scale * 100^depth`); the
old display used `effectiveScaleToRoot()` (scale / 100^depth), which
falls toward 0.0x as you zoom IN (kept for the P0 zoom-out-integrity
test, no longer shown to users).

Tests (`P1SpecTest`): no hold at any t, every intermediate frame in the
legal band, screen-position continuity of a fixed root point across all
frames, exact endpoints in both directions.

## P1.3b Cross-level marquee selection

`SelectionModel` becomes camera-aware (`camera` wired by
`DrawingViewModel`, re-wired on `restore`):

- `selectInRectAcrossLevels(camera, visibleLevels, rect)` hit-tests every
  visible level's strokes (camera chain + children — the same set the
  renderer composes) by projecting stroke bboxes into the camera's frame
  via `Camera.transformTo`. Siblings of the camera level and
  grandchildren stay invisible, matching what's on screen. This also
  fixes a P0 gap: the View used to commit marquees in ROOT coordinates,
  so selecting at depth > 0 selected nothing.
- `translateBy` converts the camera-frame delta into each stroke's OWN
  frame (`dx * camScale / k_level`), so a mixed selection drags as one
  visual unit. `scaleBy`/`rotate`/`flip` pivot at each LEVEL GROUP's own
  bbox (single-level selections reduce to the pre-P1 pivots exactly);
  a rigid group rotation across frames is impossible without re-leveling
  content, so each level's strokes pivot about their own group center.
- `bbox()` returns the selection bbox projected into the camera frame
  (what the overlay rectangle draws).
- Undo/redo remains ONE command per gesture, restoring every stroke's
  points exactly in its own frame.

**Eraser scope (honest decision):** the vector eraser stays scoped to
the camera's level, as in P0. Extending stroke-splitting across frames
would require fragmenting into the erased stroke's level with radius
conversion per level — deferred to P2+ as it proved hairy for the value;
marquee select + translate covers the cross-level editing use case.

## Verification

- `testDebugUnitTest`: 78/78 green (63 pre-existing untouched or
  behavior-compatible + 15 new/expanded; `AppSmokeTest` edits were
  authorized by the P1 brief).
- Device pass on the Galaxy Tab S9 FE (SM-X510): see README
  "Verification status" for the on-device evidence (finger-pan
  no-draw, deep-cycle promote/demote, pencil alpha pixel analysis,
  zero FATALs, user session content restored afterwards).

## Out of scope (unchanged, per the P1 brief)

Shapes, lazy brush, blend/smudge, layers v2, image v2, demo button, GPU
jitter instrumentation — these remain P2-P4 items.
