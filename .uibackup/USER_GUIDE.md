# ZoomArt — User Guide

*An infinite-canvas vector drawing app for Android with **true endless
zoom**. Everything you draw is vector data in a tree of nested zoom levels,
so it stays razor-crisp at any magnification — zoom in a hundredfold, a
millionfold, and beyond. Draw with a pressure stylus, sketch parametric
shapes, smudge color, trace photos, layer your work, and share it as a
high-res PNG or an animated zoom-tour video.*

**App version:** 0.5.0 · Requires Android 8.0+ (API 26) · PNG exports land
in your gallery on Android 10+

## 1. What's new since 0.2.0

ZoomArt 0.5.0 is the result of a five-phase rebuild (P0–P4):

- **Truly infinite zoom** — a tree of **nested zoom levels**: zoom past
  100× and the camera seamlessly descends — no jump, no precision loss, no
  ceiling. The readout shows where you are, e.g. `12.3x · level 2`.
- **Pen draws, fingers navigate** — the stylus runs the tool; the S Pen
  eraser end always erases; fingers pan/pinch/fling only (palm rejection
  built in).
- **Screen-relative sizes** — strokes keep a constant on-screen width at
  any zoom; the pencil maps pressure to **opacity**; nine presets
  (Calligraphy, Dashed, Spray, Pencil are new).
- **Layers v2** — duplicate, rename, reorder, lock, per-layer blend modes
  (Normal/Multiply/Screen/Overlay), collapsible panel.
- **New tools** — eight parametric **Shapes**, the rope-style **Lazy**
  stabilizer, and a **Blend/smudge** tool.
- **Image v2** — own-layer imports, transforms, transparency, lock, one-tap
  **tracing mode**, Gray/Contrast display filters.
- **Better editing** — cross-level selection + eraser, a demo tour button,
  a grouped toolbar, and a per-layer Clear behind a confirm dialog.

A few deliberate limitations remain — see §17.

## 2. Getting started

Install the APK and tap the **ZoomArt** icon: a blank white canvas and a
toolbar along the bottom. Work autosaves every 15 seconds and whenever you
leave the app; the next launch restores the whole session — drawing,
layers, pins and the exact camera position. (Toolbar buttons render in
**ALL CAPS** on screen, e.g. `UNDO`; this guide uses friendly names.)

## 3. The infinite canvas: zoom levels

A boundless sheet of paper you fly over — no edges, no zoom ceiling:

- **Pinch in past 100×** and the camera *promotes* into a deeper level
  whose origin sits exactly at your pinch focus — nothing shifts on
  screen. **Pinch out below 1×** and it *demotes* to the parent.
- The top-center readout always shows total magnification **and** depth:
  `1.0x · level 0` at the root, `12.3x · level 2` two levels deep. Each
  level is 100×, so level 2 starts around 10,000×. **`+` / `−`** zoom
  2× / 0.5× around the screen center.
- Everything you draw is stored in the level it was drawn in, as pure
  vector data — crisp at any depth, forever.
- **Sizes are screen-relative:** brush width, eraser and smudge size, and
  shape outline width keep their on-screen size as you zoom — perfect for
  working deep inside a drawing.

## 4. Gestures

| Gesture | Action |
|---|---|
| **Stylus drag** | Runs the active tool: draw / erase / shape / smudge / select. Pressure affects width (or opacity with the Pencil). |
| **S Pen eraser end** | Always erases, regardless of the armed tool. |
| **Stylus double-tap** | Drops a bookmark **pin** (§13). |
| **Stylus tap while Pick is armed** | Samples the color under the tip into the current brush, then returns to drawing. |
| **One finger drag** | Pans (never draws). A fast release glides with inertia. |
| **Two fingers — pinch** | Zoom + pan anchored under your fingers; crossing 100× promotes/demotes seamlessly. |
| **Palm on glass while drawing** | Ignored until it lifts (palm rejection). The pen landing mid-pinch cancels the pinch. |
| **Pinch while a selection is active** | Scales it; twisting the fingers rotates it (one undo step per gesture). |
| **Any touch during the Demo tour** | Cancels the tour. |

On a stylus-less device, fingers still pan and zoom — drawing is
stylus-only by design.

## 5. The toolbar

Grouped into eight labeled sections plus trailing utilities. One panel is
open at a time; tap its button again to close it.

| Section | Button | What it does |
|---|---|---|
| **Brushes** | **Brush** / **Erase** | Brush panel (§6) / arm the eraser (§7). |
| **Blend** | **Blend** | Arm the smudge tool + panel (§9). |
| **Shapes** | **Shapes** | Arm the shape tool + panel (§8). |
| **Style** | **Color swatch** / **Pick** | HSV color picker / pen-tap eyedropper. |
| **Lazy** | **Lazy ✓/✗** | Quick stabilizer toggle (radius in the Brush panel, §6). |
| **Layers** | **Layers** | Layers panel (§10). |
| **Image** | **Import** / **Image** | Photo picker (§12) / image panel (§12). |
| **Edit** | **Select** | Marquee/tap/move/resize tool (§11). |
| | **Undo** / **Redo** | Steps through strokes, erases, shapes, smudges, image moves, layer changes. |
| | **Del** / **FlipH** / **Rot90** | Delete / mirror / rotate the selection (one undo step). |
| | **Clear** | Empty the **active layer** behind a confirm dialog (one undo step). |
| *(utilities)* | **Demo** / **Pins** | Nested-rings tour (§14) / pins panel (§13). |
| | **− / +** | Zoom out / in around the screen center. |
| | **PNG** / **Video** | Exports (§14). |

## 6. Brush panel — presets, sliders, Lazy

**Presets** (top row; tap one, then tweak freely):

| Preset | Character |
|---|---|
| **Ballpoint** | Thin everyday ink; moderate pressure response. |
| **Marker** | Broad semi-transparent blue line. |
| **Soft Brush** | Very wide; strong pressure response — light = thin, firm = fat. |
| **Fine Ink** | Ultra-fine technical line. |
| **Highlighter** | Huge, pale yellow, nearly ignores pressure. |
| **Calligraphy** | Angled nib — slanted strokes run thin/thick. |
| **Dashed** | Broken/dashed line. |
| **Spray** | Airbrush speckle with soft build-up. |
| **Pencil** | Thin graphite; **pressure controls opacity** — feather touch faint, hard press dark. |

**Sliders** (live): **Size** 1–50 (screen px at 1×, constant on-screen at
any zoom) · **Pressure** 0–4 (0 ignores pressure, 4 = lightest touches
thinnest) · **Opacity** 0–100% · **Erase size** 5–150 (§7) · **Lazy px**
0–200 screen px, default 60.

**Lazy stabilizer.** Toggle here or with the toolbar's **Lazy** button. The
brush trails the pen like a rope: inside the radius it holds still; beyond
it follows at exactly the radius — shaky hands make smooth curves. While
drawing you see a guide line from pen to brush, a dot at the brush, and a
faint circle of the rope's reach. Lazy strokes get quadratic smoothing and
**tapered ends** (first/last ~10% thins to a point). Radius 0 = raw pen;
erase/shapes/select unaffected. *Try it:* slow circles at ~100 px feel like
ink flowing downstream.

**Color.** The swatch opens the HSV picker (hue slider + saturation/value
pad + preview strip, **OK** applies). **Pick** samples any on-screen color
with a pen tap — only the brush color changes; the tool disarms itself.

## 7. Eraser — vector, cross-level

1. Tap **Erase** (or flip the S Pen to its eraser end).
2. Drag across ink: touched strokes are **cut where you passed** and
   survive as clean fragments. Tap **Erase** again to draw.

- **Size:** *Erase size*, 5–150 — screen-relative like brush size.
- **Cross-level:** carves strokes of the active layer in **every visible
  zoom level** — root strokes and deep-detail strokes in one pass;
  fragments stay in their own level, crisp at any zoom.
- Fragments under 2 points are dropped. One gesture = one undo step.
  Active layer only; locked layers reject the eraser (they still render).

*Try it:* at depth 2–3, carve across strokes of several levels — one Undo
restores them all.

## 8. Shapes panel

Open with **Shapes**. Arming a type also arms the shape tool: the next
**stylus drag** draws it with a live rubber-band preview (fingers keep
panning).

**Eight types** (button labels): **Line**, **Rect**, **RRect** (rounded
rectangle), **Oval**, **Tri**, **Poly**, **Star**, **Arrow**.

- **Fill ✓/✗** — filled, outlined, or both; the two swatches beside it set
  fill and outline colors (HSV picker). Line ignores fill; Arrow fills
  its head.
- **Constrain ✓/✗** — boxes become squares/circles; Line and Arrow snap
  to 15° steps.
- **Center ✓/✗** — grows symmetrically about your drag start.
- **Width** 1–60 px · **Round** 0–100% (RRect corners) · **Points** 3–12
  (Poly's side count, Star's point count).

Shapes are level-tagged parametric vector data — crisp and editable at any
zoom. **Poly** is a regular N-gon set by Points, not a freeform vertex
list (a documented limitation). Move/resize with Select (§11); one undo
step per shape drawn, moved or resized.

## 9. Blend / smudge

Tap **Blend** to arm the smudge tool and its panel: **Strength** 0–100%,
**Size** 5–150 px, **Softness** 0–100%.

Drag with the stylus to smear color. The tool samples the composed canvas
color under the pen at gesture start, then **carries it along the path**,
re-mixing 80% carried / 20% freshly picked-up color — like a finger
through wet paint. Deposits are **soft vector stamps** (not pixels), smooth
at any zoom; harder pressure deposits more strongly. Smudges live on the
active layer but are **not** erasable — use **Undo** or **Clear**. One
gesture = one undo step; locked layers reject smudges.

## 10. Layers

Open with **Layers** (docks on the right). Layers are stacked sheets of
paper, separate from zoom levels: every layer holds content from **all**
zoom levels and sheets composite top-to-bottom. The header chevron (▲/▼)
collapses the list. Each row:

- **Radio button** — make it the active layer (new content lands here).
- **Name + stroke count** — e.g. `Background (12)`; **tap the name** to
  rename. Locked layers show a 🔒 padlock.
- **Visibility checkbox** — hide/show without deleting.
- **Lock / Unlock** — locked layers reject strokes, erasing, selection,
  transforms, deletion and image placement, but keep rendering.
- **Dup** — deep copy inserted above the source, made active.
- **Blend button** — cycles Normal → Multiply → Screen → Overlay; honored
  across all zoom levels and by PNG/MP4 exports.
- **Up / Dn** — reorder (drag-reorder is not implemented; these are the
  way).
- **X** — delete (the last remaining layer is protected).
- **Opacity slider** — 0–100%, live while dragging, one undo step on
  release.

**+ Add layer** inserts a `Layer N` above the active one and activates it.
Every layer operation is **undoable** — delete a layer, tap Undo, and its
strokes, stack position and blend all come back. Your selection always
stays on the active layer, so switching sheets can never leave you
editing the wrong one.

## 11. Selecting & transforming

Arm **Select**, then:

- **Drag a rectangle** to grab strokes, shapes and images of the active
  layer — across **all visible zoom levels** at once.
- **Tap** a shape or image to select just it (~24 px of slack around its
  bounding box).
- **Drag inside** to move · **drag a corner handle** (small white squares)
  to resize uniformly about the opposite corner · **two-finger pinch** to
  scale, twisting to rotate.
- **FlipH / Rot90 / Del** (Edit) mirror, rotate or delete the selection.
  FlipH mirrors strokes and shapes only — images keep their orientation;
  pinch-rotate them instead.

Every gesture is one undo step. Tap **Select** again to drop the selection
and return to drawing.

## 12. Images — import, transform, trace

**Import** opens the system photo picker. The picture lands on a
**brand-new `Image N` layer** directly above the active one (which becomes
active) — never buried under your ink — centered on the current view, in
the **current zoom level**, at about 60% of the view width. The whole
import is **one undoable step**. Transform it with Select (§11): tap to
select, drag to move, corner-handle to resize, two-finger pinch to rotate
+ scale.

**Image panel** (toolbar **Image**) — acts on the selected image when
Select holds one, else the newest image on the active layer:

- **Opacity** — transparency, 0–100% (live preview, one undo step per
  drag).
- **Lock** — locked images can't be selected or moved; draw over them
  freely.
- **Trace** — one tap for tracing mode: the image dims to 40%, locks, and
  a fresh empty layer is created **above** it and activated. Ink over
  it, then hide or delete the image layer. One Undo restores everything.
- **Display: Off / Gray / Contrast** — a view-only filter cycle
  (grayscale or high-contrast). It changes nothing in the file and resets
  on relaunch — a seeing aid while you trace or study values.
- The panel's standing note: *imported images are bitmaps — they blur
  when zoomed far past their resolution. Drawn strokes stay sharp at any
  zoom.* Need crisp deep-zoom detail? Draw it.

## 13. Pins — bookmark your places

- **Create:** double-tap the canvas **with the stylus**. Pins are numbered
  `Pin 1`, `Pin 2`, … and store position **and** zoom, level included.
- **Fly there:** open **Pins** (top-left panel) and tap a pin's button.
  The camera glides ~0.7 s to it — **across zoom levels**, no seam. Deep
  pins show their level, e.g. `Pin 2 (L1·50.0x)`.
- **Delete:** the ✕ next to a pin. Pins are the script for the Video
  export (§14).

## 14. Workflows

**Trace a photo.** Import → **Trace** (dims to 40%, locks, adds a clean
layer above) → optionally **Display: Gray** → sketch with any brush,
zooming freely (your ink stays sharp) → hide or delete the image layer.

**Record an endless-zoom video.** Zoom out to the full composition,
double-tap to pin it. Zoom into a tiny detail, pin that. Repeat. Tap
**Video**: the camera records an H.264 MP4 (720p, 30 fps, ~2.5 s per hop)
flying from where you are through every pin into the app's Movies
folder — the toast shows the path. The classic social-media "endless
zoom".

**Export a PNG.** Tap **PNG**: visible layers are fitted to their bounding
box and rendered at up to **4096 px** on the long edge — pure vector,
crisp at any size, blend modes honored. Android 10+: public
**Pictures/ZoomArt** collection, no permission prompt. Android 8–9: the
app's own folder, path in the toast.

**Take the demo tour.** Tap **Demo**: four nested rings (star, rectangle,
ellipse) are auto-drawn on fresh `Demo N` layers and the camera flies from
1.0× to 1,000,000×, ~2 s per stop. Any touch cancels. The scenery carries
no undo entries — delete the Demo layers in the Layers panel when done.
It's also the fastest way to *feel* the nested-level zoom.

## 15. Autosave & crash recovery

- **Autosave every 15 seconds** plus on every pause; saves are atomic
  (write-to-temp + rename), so a crash mid-save can never corrupt you.
- The session holds **everything**: the full multi-level tree, all layers
  (lock/blend/opacity), every stroke with its pressure history, shapes,
  smudges, images (transparency/lock/rotation), pins, and the exact
  camera. Relaunching shows *"Session restored"*.
- **Crash recovery:** a crash writes a snapshot as the app dies, and that
  file **wins** on the next launch — *"Recovered session from last
  crash"*. An unreadable session starts you fresh with a notice, never a
  crash.

## 16. Tips & troubleshooting

**Eraser isn't cutting?** Check the *Eraser ON* toast; raise *Erase size*;
make sure the ink is on the **active** layer and that layer isn't locked.
Very short fragments vanish entirely (too small to see).

**Select taps grab the wrong thing?** Tap hit-testing uses bounding boxes
with ~24 px of slack — near crowded thin shapes (especially lines) it can
grab a neighbor. Zoom in and tap again, or marquee instead.

**A finger stopped responding after drawing?** If a palm finger was still
down when the pen lifted, it stays inert (palm rejection) — lift it and
touch again.

**Smudge won't come off.** Smudges aren't erasable; Undo the gesture or
**Clear** the layer (undoable).

**"Session restored" toast on every launch?** Normal — autosave reloading
your last session.

**Exports failing?** The toast carries the error. PNG renders can be
several MB; give it a second. Video needs at least one pin.

**No pressure (finger, or a stylus that doesn't report it)?** Pressure
reads as constant — use the Size slider. The Pencil's pressure→opacity
needs a real pressure stylus.

**Want a fresh start?** **Clear** the active layer (undoable), or clear
the app's storage in system settings.

**Performance.** Rendering is culled by a spatial index; huge canvases pan
and zoom smoothly. If a very dense drawing ever feels heavy, hide the
layers you're not working on.

## 17. Known limitations (honest list)

- **No drag-reorder of layers** — use the **Up/Dn** buttons.
- **Calligraphy** has an angled nib, but its width does not vary with
  stroke direction.
- **Display filters (Gray/Contrast) are view-only** and reset on
  relaunch.
- **The Image panel acts on one image** — the selected one, or the newest
  on the active layer; pick another with Select first.
- **FlipH does not mirror images** — pinch-rotate/scale them instead.
- **Poly is a regular N-gon** (Points slider), not a freeform polygon.
- Stylus-gesture behaviors (pin-dropping mid-draw, pinch-rotating an
  image, the cross-level eraser at depth, the Lazy rope's feel and taper)
  are covered by the automated test suite; if anything feels off in the
  hand, one Undo has you covered.

*Generated for ZoomArt 0.5.0 — see `README.md` for the engineering record,
`docs/REVIEW_2026-10-18.md` for the feature audit, and the per-phase notes
(`docs/P0_ZOOM_LEVELS.md` … `docs/P4_IMAGE_UI.md`) for implementation
details.*
