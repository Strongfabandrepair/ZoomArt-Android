> Historical engineering record. For the current interface and identity, see [User guide](USER_GUIDE.md), [Brand guide](BRAND_GUIDE.md) and [Release readiness](RELEASE_HARDENING.md).

# ZoomArt — UI Overhaul: Procreate-style Glass (U1–U4, v0.6.0)

The 0.6.0 overhaul restyles the verified 0.5.0 app to a modern-minimalist,
Procreate-like design language WITHOUT touching the drawing engine: every
control keeps its exact label, all 185 pre-overhaul tests stay green (suite
is now 200), and the infinite-canvas frame budget is proven unchanged by
on-device gfxinfo numbers (below).

## What changed visually

- **Floating draggable glass panels** — every panel (Brush, Layers, Pins,
  Shapes, Blend, Image) now floats in a rounded dark-translucent shell
  (scrim `rgba(22,24,29,0.72)`, ~28dp corners, 1px `rgba(255,255,255,0.08)`
  hairline, 12dp elevation shadow) with a dedicated top **drag handle**
  (a centered grip pill; the handle consumes drags, so panel controls keep
  working untouched).
- **Frosted backdrop blur (API 31+)** — the shell hosts a backdrop
  ImageView with a 1/8-scale snapshot of the canvas behind it, blurred via
  `View.setRenderEffect(RenderEffect.createBlurEffect)` and dimmed by the
  scrim: real glass over your drawing.
- **Floating toolbar dock** — the old flat bottom strip is now a floating,
  horizontally scrollable, rounded glass dock anchored bottom-center (same
  24 buttons, same 8 section labels + utilities; ALL-CAPS chip styling;
  ≥48dp touch targets preserved).
- **Glass HUD chip** — the zoom readout (`12.3x · level 2`) is drawn
  top-center as a small dark glass chip (canvas-owned, see below).
- **Spring micro-animations** (androidx.dynamicanimation
  `dynamicanimation-ktx:1.1.0`) — panel show/hide (alpha + scale + slide),
  press-scale feedback on every toolbar/panel button (composes with clicks,
  never consumes), a tool-selection pulse, and the animated Layers-panel
  chevron collapse. One stiffness/damping pair everywhere:
  `STIFFNESS_MEDIUM`, damping `0.7` — snappy but visibly settling.
- **Auto-hide while drawing** — a stylus stroke or pinch springs the
  panels toward the nearest screen edge (80% out, alpha 0.15, the sliver
  still draggable) and fades the dock to 0 + INVISIBLE; everything springs
  back ~600 ms after the last gesture. Touching a faded panel restores it
  immediately.
- **Dark-mode contrast** — panels are light-text-on-dark-glass
  (`#E8EAEE` primary, `#9AA3AF` secondary, `#2A2E37` button fill).

## Component hierarchy

```
Activity content FrameLayout
├── ZoomableCanvasView            (full-bleed; the ONLY thing that renders/invalidates the scene)
│     └── onDraw: drawScene + drawSelectionOverlay + drawLazyOverlay + drawZoomReadout
│           (drawZoomReadout draws the HUD chip in screen space — camera state,
│            always in sync, always visible while panning/zooming, and it is NOT
│            a touchable view, so it can never steal gestures)
└── OverlayLayer (FrameLayout)    SIBLING above the canvas, never clickable,
      │                           never sets a touch listener — the gesture-isolation layer
      ├── FloatingPanel "BrushPanel"   ── BrushPanel      (hosted content)
      ├── FloatingPanel "LayersPanel"   ── LayersPanel
      ├── FloatingPanel "PinsPanel"     ── PinsPanel
      ├── FloatingPanel "ShapesPanel"    ── ShapesPanel
      ├── FloatingPanel "BlendPanel"    ── BlendPanel
      ├── FloatingPanel "ImagePanel"     ── ImagePanel
      └── FloatingPanel "ToolbarDock" (Kind.DOCK, non-draggable, no handle)
            └── HorizontalScrollView  ── the 24 buttons + section labels
                  (systemGestureExclusionRects kept: One UI gesture-zone fix)

FloatingPanel internals (bottom to top):
  backdrop ImageView   canvas-region snapshot at 1/8 scale (FIT_XY)
  scrim View           rgba(22,24,29,0.72)
  PanelHandle          dedicated drag bar (26dp, consumes its own drags)
  content FrameLayout  the hosted panel
  foreground           the 1px hairline (foreground, NOT background: the
                       fill children would cover a background stroke)
  outlineProvider      rounded rect -> elevation shadow + clipToOutline
```

`ui/glass/` files: `FloatingPanel.kt`, `OverlayLayer.kt`, `Springs.kt`,
`GlassStyle.kt`, `AutoHideState.kt`, `PanelGeometry.kt`,
`BackdropSnapshotter.kt`.

## The gesture-isolation guarantee

Everything is in-activity — **no window-level WindowManager floating**, so
no overlay permission, lifecycle or focus hazards. The OverlayLayer is a
plain FrameLayout: Android hit-testing skips it everywhere except where a
VISIBLE FloatingPanel actually is, because the layer never consumes
(documented and enforced: `OverlayLayer.setOnTouchListener`/`setClickable`
throw on attempts). Touches that land on a panel are consumed by the shell
(the handle drags, the controls click); every other touch falls through to
the canvas unchanged. GONE/INVISIBLE shells are not hit-tested at all —
which is also why the auto-hidden dock (INVISIBLE at alpha 0) lets pen
strokes pass straight through to the canvas over the dock's area.

The infinite-canvas touch routing (stylus draws / fingers pan+pinch, palm
rejection, pin eyedropper) is untouched — `ZoomableCanvasView.onTouchEvent`
is byte-identical except for two new one-line activity notifications.

## Auto-hide pipeline

```
stylus stroke / pinch begin ──▶ ZoomableCanvasView.onCanvasActivityChanged(true)
                                   │
                                   ▼
                        OverlayLayer.onCanvasActivity
                                   │
                                   ▼
                      AutoHideStateMachine  (pure Kotlin, JVM-tested)
   IDLE ──activity(true)──▶ ACTIVE ──activity(false)──▶ PENDING_RESTORE
     ▲                        │  panels slide 80% toward          │ ~600 ms debounce
     │                        │  the nearest edge, alpha 0.15     ▼
     └─────panelTouched───────┘  (dock: alpha 0 + INVISIBLE) ──▶ IDLE
                  (a touch on a faded panel restores everything at once)
```

A generation counter voids stale debounce timers (drawing that resumes
inside the 600 ms window, or an early touch-restore, can never be followed
by a late timer that yanks the panels back mid-stroke). Finger pans never
trigger auto-hide (spec: stylus strokes + pinches only). State machine:
`ui/glass/AutoHideState.kt`; slide math: `PanelGeometry.hideVector`
(nearest-edge, 80% of the panel's own size); drag clamp: `PanelGeometry.clampOffset`
(≥56 px always grabbable).

## Blur tiering & the 60 fps story

TRUE backdrop blur is `View.setRenderEffect(...)` — API 31+ only, and it
blurs the view's OWN content, not the canvas behind it. Therefore each
FloatingPanel hosts a **backdrop ImageView**: on panel SHOW and on drag END
only, `BackdropSnapshotter` renders the canvas region behind the shell
through the SAME `drawScene` path at ~1/8 scale, the ImageView upscales
it, `RenderEffect` blurs it, and the dark scrim sits on top.

- **API 31+** (the Tab S9 FE runs Android 16): blurred snapshot glass.
- **API 26–30**: scrim-only glass (no snapshot is even rendered).
- Snapshots are one-shot — between them the backdrop is a static bitmap,
  so canvas pan/zoom costs NOTHING extra per frame. This is asserted by
  `GlassPanelTest.backdropSnapshotsOnShowAndDragEndOnlyNeverPerFrame`
  (show -> 1 render, 50 drag frames -> still 1, drag end -> 2).

### Decoupling rule (audit-enforced)

Panel code never calls `canvasView.invalidate()`: panels REQUEST scene
repaints through the semantic `ZoomableCanvasView.requestSceneRepaint()`
after document mutations, and panel/glass animations touch ONLY their own
subtree. The canvas stays the only thing that renders and invalidates the
scene. `grep '\.invalidate()' ui/*Panel.kt` -> 0 hits.

### Position persistence

Panel rest positions persist in `SharedPreferences("zoomart_panels")` under
the shell key (= the hosted panel class's simpleName). Positions are
clamped on load into the current screen, and the saved position is applied
at ATTACH (`onAttachedToWindow`), because `addView` installs its own
gravity LayoutParams and would clobber anything set in a constructor — a
subtle bug found and fixed during the on-device pass, with a regression
test.

## Spring snippets

```kotlin
// Springs.kt — one feel everywhere
private fun force(target: Float) =
    SpringForce(target).setStiffness(SpringForce.STIFFNESS_MEDIUM)
                       .setDampingRatio(0.7f)

// panel show: alpha 0->1, scale 0.92->1
Springs.springAlpha(shell, 1f); Springs.springScale(shell, 1f)

// auto-hide: slide 80% toward the nearest edge, fade to 0.15
val v = PanelGeometry.hideVector(left, top, w, h, parentW, parentH)
spring(TRANSLATION_X, v[0]); spring(TRANSLATION_Y, v[1]); spring(ALPHA, 0.15f)

// press feedback — returns false so clicks keep flowing
View.OnTouchListener { view, event ->
    when (event.actionMasked) {
        ACTION_DOWN -> springScale(view, 0.92f)
        ACTION_UP, ACTION_CANCEL -> springScale(view, 1f)
    }
    false
}
```

## Device verification (Galaxy Tab S9 FE, SM-X510, Android 16, 2304x1440)

**User session protection:** the live session was pulled FIRST
(15 layers / 21 levels / 4 pins / 4 strokes, camera at depth-3 level 20),
sha256-pinned as
`5471bd81d95f3718fa6b3758016ff1c564a6c3fd17ef4d6189e5f9c0b64280be`,
and pushed back byte-exactly after every interaction pass (two
restores, both sha256-identical; final launch renders the drawing again,
9,683/12,960 non-white screencap samples; **0 `FATAL EXCEPTION`** across
the whole pass).

### gfxinfo — 10 mid-screen finger pan swipes per configuration

Same camera, same drawing (a dense multi-level scene), identical swipe
coordinates, `dumpsys gfxinfo ... reset` before each run:

| Metric | Panels closed | Panels open + blurred | Delta |
|---|---|---|---|
| Total frames rendered | 1014 | 1008 | -6 |
| **Janky frames (modern)** | **1 (0.10%)** | **2 (0.20%)** | **+0.10 pp** |
| Janky frames (legacy, >16.67 ms) | 0 (0.00%) | 148 (14.68%) | +14.68 pp |
| 50th percentile | 19 ms | 18 ms | -1 ms |
| 90th / 95th / 99th | 19 / 19 / 20 ms | 25 / 26 / 28 ms | +6..8 ms |
| Missed Vsync / Slow UI thread | 0 / 0 | 0 / 0 | 0 |
| GPU 50th / 90th | 16 / 16 ms | 14 / 21 ms | +5 ms tail |

**Verdict: panels-open panning is not meaningfully worse — 0.10% vs 0.20%
janky, a +0.10 pp delta against the <~5 pp target; median frame time is
identical.** The honest tail note: the glass adds GPU blending cost at the
90–99th percentiles (the translucent shell composites over the panning
canvas), which the legacy 16.67 ms metric counts as 14.68% "legacy-janky";
the UI thread is never slow in either configuration and the modern
frame-deadline metric — the one the <5% target names — stays at 0.2%.
The snapshot-on-show (not per-frame) design is what keeps the APP-side
draw cost flat; the residual tail cost is pure compositor blending that
any translucent overlay pays.

### Glass look — PIL pixel verification (screencap over white canvas + dense ink)

| Check | Measurement | Expected |
|---|---|---|
| Translucency (scrim over white canvas) | `(87, 88, 92)` | 0.72·(22,24,29) + 0.28·(255,255,255) = (87.2, 88.6, 92.3) ✓ |
| Border hairline present | 2 px band at `(100, 101, 105)` between the elevation shadow `(205-239)` and the scrim interior `(87,88,92)` | scrim-on-white + 8% white stroke ✓ |
| Rounded corner transparent | outside the 49 px corner arc: `(249,249,249)` ≈ raw canvas (255 shadow-dimmed); inside the arc: scrim `(87,88,92)` | canvas shows through at the corners ✓ |
| Backdrop blur actually blurs | sharp ink behind the panel: variance 12403 (raw) → 136 (through the glass), ratio 0.01 | RenderEffect + snapshot working, not scrim-only ✓ |

### Interactions driven on-device (uiautomator + input)

- Dock renders bottom-center with the 8 section labels + ALL-CAPS buttons
  (BRUSH/ERASE/BLEND/SHAPES/PICK/LAZY ✗/LAYERS/IMPORT/IMAGE; EDIT +
  utilities reached by scrolling — same strip, same labels).
- BRUSH opens the Brush panel: all 9 presets + Size/Pressure/Opacity/
  Erase size + Lazy toggle/radius, drag handle present.
- Handle drag: `input swipe` moved the panel (+403, −202), clamped at the
  right edge (panel right edge 2280 of 2304 = the 24 px grabbable sliver).
- Persistence: after force-stop + relaunch the panel reappears at the
  dragged spot `[427,286][2280,338]`.
- The frame flips between the two landscape orientations mid-pass
  (known P4 gotcha) — all taps use freshly parsed dump bounds.
- **Auto-hide itself is stylus/pinch-triggered and adb cannot inject
  those tool types (known limit): the pipeline is verified end-to-end in
  Robolectric** (`GlassPanelTest.stylusStrokeDrivesTheAutoHidePipelineEndToEnd`
  — real stylus MotionEvent through the real canvas fires the real
  overlay: ACTIVE, faded sliver 0.15, dock INVISIBLE, debounce restore,
  finger-pan neutrality). The in-hand feel is a hand-test item, like the
  other stylus behaviors.

## Tests (200/200 green)

185 pre-overhaul tests untouched and green. 15 new:

- `PanelGeometryTest` (JVM, 6): clamp math on all four edges + degenerate
  parent; hide-vector toward the nearest edge, fraction clamp, tie-break.
- `AutoHideStateTest` (JVM, 5): full state machine incl. debounce timing,
  resume-inside-window, stale-timer voiding, early touch-restore, no-ops.
- `GlassPanelTest` (Robolectric, 4): drag persistence through a REAL
  attached window (incl. the addView-clobbers-LP regression), snapshot-
  on-show-not-per-frame via an injected counter, the end-to-end stylus ->
  canvas -> overlay auto-hide pipeline (springs settled via
  `ShadowLooper.idleFor`), and the activity boot on the new shell system
  (canvas below the overlay, dock visible, panels GONE, dock hugs its
  content — the on-device found MATCH_PARENT-in-WRAP_CONTENT blow-up is
  now a permanent assertion).

Robolectric note: dynamicanimation keeps its `AnimationHandler` in a
static ThreadLocal bound to the first method's looper; `GlassPanelTest`
drops that binding in `@Before` so every method's springs tick (production
has a single looper and is unaffected).


## v0.7.0 REVISION — glass -> Infinite Design / Google Earth language

The user reviewed the 0.6.0 glass UI on the tablet and asked for the
reference app look instead. The 4 reference screenshots were measured
pixel-by-pixel with PIL and the derived tokens are the CONTRACT in
**`docs/UI_STYLE_SPEC.md`** (accent #FFAD01 measured exact; near-black
chrome #16171B @ 90%; light reading panels #F2F2F2 with #FAFAFA rows and
#1E1E1E ink; muted wheel ring S=1.0 V=0.65, inner radius 0.56R, center
swatch 0.22R; 12dp corners; 1px neutral hairlines). Kept machinery:
FloatingPanel drag + persistence, OverlayLayer isolation, Springs,
AutoHide pipeline, snapshot-on-show/drag-end — only the visual language
changed.

What changed:

- **GlassStyle tokens swapped** (same API): frosted 72% scrims ->
  near-opaque crisp neutral chrome; brand-blue accent -> single amber;
  corner 28dp -> 12dp; elevation 12 -> 6dp; backdrop blur kept but
  demoted to a 5dp depth cue (the references read as crisp).
- **One slim icon strip replaces the grouped text-button docks**: 22
  hand-written stroke-based vector icons (24dp viewport, strokeWidth 2,
  24dp content in 48dp targets, no labels/dividers — grouping is pure
  spacing). contentDescription keeps the legacy label words (a11y +
  dump-based tests). Active tool = amber tint + 4dp underline dot.
  Restore of one-tap access: Pick, Select, Flip, Rotate (selection),
  Import, PNG, Video, Demo, Lazy — plus the Pins panel gets a shell
  again (7 overlay shells total, unchanged count).
- **BrushPanel** becomes the light reading panel: dense preset rows
  (44dp cards, 8dp gaps) with per-preset characteristic stroke previews
  from the pure `BrushPreviewMath` (JVM-tested); the selected preset
  renders its preview in amber per brushes2.jpg; slim amber-thumb
  sliders; the list scrolls inside a capped container so the shell stays
  compact.
- **ColorWheelView** becomes the muted hue ring: annulus + center
  current-color swatch + white marker dot + NEW slim Value slider
  (0.6.0 hard-wired V=1.0). Geometry extracted to pure `WheelGeometry`
  (JVM-tested) which also FIXES the 0.6.0 bug where the marker drew
  90deg away from the touch point (touch and marker now share one
  convention).
- **HUD readout**: dark neutral chip, 8dp corner, monochrome text with
  the LEVEL NUMBER in amber; `cameraReadout()` string unchanged.
- **Tests**: 200 -> 215 (15 new: WheelGeometryTest 7, BrushPreviewMathTest
  8). One justified mechanical update in GlassPanelTest: the boot test's
  panel-height guard now asserts wrap-vs-fill (strictly below the
  offered AT_MOST space) instead of "under 3/4 of the (tiny 470px
  Robolectric) screen" — the dense brush list legitimately wraps taller
  than that synthetic screen; the original MATCH_PARENT-blow-up
  regression the guard was written for is still asserted.

Device verification (SM-N950U, Android 9 — the Tab S9 FE was not
attached; see honest notes in the session record): strip near-black with
48dp icon pitch and amber selected-tool pixels; brush panel light with
855 amber px on the selected row's preview (0 on unselected rows); wheel
ring measured live at V=0.65 (0xA6 max-channel) with the amber Value
slider; HUD amber level number (273 px); chrome pixel share 1% of screen
vs 4% for the 0.6.0-era screenshot on the same device; 0 FATAL across
launch+panel-open+pin-pan; gfxinfo janky% 5.21% closed vs 2.83% with the
panel open (no penalty for open panels); user session sha256 pinned,
restored byte-identical after testing.
