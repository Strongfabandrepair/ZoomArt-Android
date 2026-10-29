# P2 — Layers v2 (implementation notes)

> Historical planning/engineering record. For the current product behavior, brush collection and verification status, use the [documentation index](README.md) and [user guide](USER_GUIDE.md). Earlier test counts and release targets below are preserved as historical evidence.

Implements the LAYERS section of `docs/REVIEW_2026-10-18.md` on top of the
P0 nested-zoom-level architecture and P1 spec fixes. All work test-first;
the suite grew 78 → 109 (0 failures).

Model: layers are **stacked sheets of paper, separate from zoom levels**.
A layer's content spans ALL zoom levels (every sheet holds root-level and
deep-level strokes alike, each in its own level's local frame) — this was
already true after P0 (levels are content placement, layers are
stacking); P2 makes it the enforced render order and documents it.

## P2.1 Model (model/LayerData.kt)

- `LayerData` gains `locked: Boolean = false` and
  `blend: LayerBlend = NORMAL` (`enum class LayerBlend { NORMAL,
  MULTIPLY, SCREEN, OVERLAY }`, `cycled()` for the panel button,
  `fromName()` migration-safe codec read).
- New undoable commands mirroring AddLayer/RemoveLayer:
  - `DuplicateLayerCommand` — inserts a pre-built DEEP copy above the
    source and makes it active. `LayerData.deepCopy()` builds NEW
    `StrokeData` objects (points + levelId copied, bbox recomputed; the
    immutable `BrushSpec` is shared — it has no mutable fields) and NEW
    `PlacedImage` objects, so mutating either layer afterwards leaves the
    other untouched (tested both directions). Undo removes the copy and
    restores the previous active index; redo re-inserts the SAME copy
    object. Name: `"<source> copy"`.
  - `RenameLayerCommand` (old/new name; blank input is a no-op).
  - `ReorderLayerCommand` (from/to final index) — captures the ACTIVE
    `LayerData` at execute time and re-points `activeLayerIndex` at it
    after every move, so the active layer keeps its identity (the
    selection, which references strokes by identity, stays scoped to the
    same sheet). No spatial-index rebuild: owners are keyed by object
    identity and never change.
  - `SetLockCommand`, `SetBlendCommand` (before/after records).
  - `SetLayerOpacityCommand` — the panel applies opacity LIVE during a
    slider drag and records exactly ONE undo step on release
    (pre-drag value → released value).
- Public API: `duplicateLayer(index)`, `renameLayer(index, name)`,
  `moveLayer(from, to)`, `setLayerLocked(index, locked)`,
  `setLayerBlend(index, blend)`,
  `setLayerOpacity(index, opacity, before = current)`. No-op calls
  (re-locking a locked layer, moving onto itself, unchanged opacity)
  push nothing onto the undo stack.
- **Selection saneness:** `pruneSelectionToActiveLayer()` runs after
  every layer-structure command (add/remove/duplicate/reorder) and on
  `setActiveLayer` changes — selected strokes always belong to the
  active layer, so a stale selection can never transform or delete
  strokes of a sheet the user is no longer editing (this also fixes the
  pre-P2 hazard where Del after switching active layers bound the wrong
  layer). Locking the active layer clears its selection outright.

## P2.2 Lock semantics

A locked layer rejects every mutation path while its VISIBILITY is
untouched (it still renders normally):

| Path | Guard |
|---|---|
| New strokes | `DrawingViewModel.beginStroke` (no in-progress stroke opens) AND `DrawingDocument.commitStroke` (re-checks at commit — a second finger can tap LOCK mid-gesture) |
| Eraser | `DrawingViewModel.eraseTo` early-return |
| Marquee selection | `SelectionModel.selectInRectAcrossLevels` |
| Transforms | `SelectionModel.beginTransform` (scale/rotate/flip/translate all no-op without a pending snapshot) |
| Delete-selection | `SelectionModel.deleteSelection` |
| Image placement | `DrawingViewModel.placeImage` |
| View feedback | `ZoomableCanvasView.startPenGesture` refuses to start a draw/erase/select gesture and toasts "Layer is locked" |

## P2.3 Blend rendering (ui/ZoomableCanvasView.drawScene)

Two render paths:

- **All visible layers NORMAL (the common case):** the pre-P2
  LEVEL-MAJOR fast path, unchanged rendering with zero `saveLayer` cost.
- **Any visible layer non-NORMAL:** LAYER-MAJOR — sheets composite in
  stack order; each sheet's content is drawn across ALL visible levels
  (parent chain root→camera plus camera's children; ancestors first,
  children on top inside a sheet); each non-NORMAL sheet is wrapped in
  `canvas.saveLayer(null, paint-with-mode)` + `restore()` so it
  composites with its blend mode. Only blended sheets pay the offscreen
  buffer. Within one sheet its images draw under its strokes (the
  pre-P2 per-layer convention).

API mapping: API 29+ `android.graphics.BlendMode.MULTIPLY/SCREEN/OVERLAY`
via `Paint.setBlendMode`; API 26-28 fallback `PorterDuff.Mode` via
`Paint.setXfermode` (all three exist in PorterDuff).

PNG (`PngExporter`) and MP4 (`ZoomVideoExporter`) render through
`drawScene`, so exports inherit blending. The in-progress stroke on a
blended sheet is wrapped in its own saveLayer so the live stroke
composites with the sheet's mode (exact when the active sheet is the
topmost one). Known corner, documented: in the layer-major path a higher
sheet's image covers a lower sheet's strokes (stacked-sheet semantics),
whereas the all-normal fast path keeps all images beneath all strokes —
the paths only differ when blends are in play AND images live on
multiple layers.

## P2.4 Layers panel v2 (ui/LayersPanel.kt)

Per row: active radio, tap-the-name → rename AlertDialog (prefilled
EditText), visibility checkbox, LOCK toggle (locked rows show a 🔒
padlock), DUPLICATE, blend-cycle button (Normal→Multiply→Screen→Overlay,
label shows the current mode), Up/Dn reorder buttons, delete, opacity
slider (live preview + one undo step on release). Header carries a
collapse/expand chevron (▲/▼) toggling the rows section. Active row is
highlighted. All buttons carry `contentDescription`s for uiautomator.

## P2.5 Codec v3 extension (model/DocumentCodec.kt)

Layer objects serialize `"locked":<bool>` and `"blend":"<NAME>"`.
The session `version` stays 3 — the fields are additive: absent fields
default to `locked=false`/`NORMAL` on read (pre-P2 v3 files migrate
losslessly, verified with a byte-exact pre-P2 snippet), and unknown blend
strings fall back to NORMAL instead of failing the load. Round trips
remain byte-stable.

## Verification

- `testDebugUnitTest`: 109/109 green (78 pre-P2 kept green + 31 new:
  26 JVM in `P2LayersTest` — duplicate deep-copy independence both
  directions, undo/redo of duplicate/rename/reorder/lock/blend/opacity,
  reorder active-identity, lock rejections through every commit path,
  selection pruning, codec round-trip + pre-P2 migration + unknown-mode
  fallback; 5 Robolectric in `P2SmokeTest` — locked-layer stylus
  rejection through real touch dispatch, panel v2 controls driving the
  real document (lock/dup/blend cycle/reorder/collapse/rename dialog),
  and pixel-level blend verification (Robolectric native graphics
  rasterizes for real: NORMAL=blue over red, MULTIPLY=black, SCREEN=
  magenta, OVERLAY distinguished from NORMAL).
- Device pass on the Galaxy Tab S9 FE (SM-X510): seeded-session +
  exported-PNG pixel analysis (the red×sky-blue overlap measures
  (39,0,0) under MULTIPLY vs (39,162,216) under NORMAL, and a
  child-level stroke composites black vs sky-blue — blended sheets span
  ALL zoom levels), uiautomator drive of the panel v2 controls (blend
  cycle ring, duplicate, Up/Dn reorder, rename dialog, lock/unlock +
  padlock, opacity drag persisted as 0.23, collapse/expand), locked
  layer kept its strokes and still rendered, zero FATALs; the user's
  live session was backed up first and restored exactly afterwards
  (verified semantically identical). See README "Verification status".

## Honest gaps / deferred

- Long-press drag reorder: Up/Dn buttons are the MVP; true drag handles
  are a P3+ polish item.
- Cross-level eraser (radius converted per level, fragments inherit the
  erased stroke's level) — deferred since P1 (see
  `docs/P1_SPEC_FIXES.md`); still scoped to the camera's level.
- Blend path re-queries the spatial index per (sheet × level); fine for
  correctness-first P2, can be batched later if profiling demands.
- The blend live-preview of the in-progress stroke is exact only when the
  active sheet is the topmost; otherwise it draws unblended-on-top until
  commit.
