> Historical engineering record. For the current interface and identity, see [User guide](USER_GUIDE.md), [Brand guide](BRAND_GUIDE.md) and [Release readiness](RELEASE_HARDENING.md).

> Historical planning/engineering record. For the current product behavior, brush collection and verification status, use the [documentation index](README.md) and [user guide](USER_GUIDE.md). Earlier test counts and release targets below are preserved as historical evidence.

# ZoomArt UI Style Spec — v0.7.0 "Infinite Design / Google Earth" language

Derived 2026-10-27 by direct pixel measurement of the 4 user reference
screenshots with PIL (scripts archived in the Hermes scratch dir). This file
is the CONTRACT for the v0.7.0 chrome revision; every restyled surface must
match the tokens below. The 0.6.0 glass machinery (FloatingPanel drag +
persistence, OverlayLayer isolation, Springs, AutoHide pipeline, snapshot
perf strategy) is KEPT — only the visual language changes.

## 0. Measurement provenance (coarse → precise)

| Reference | Size | Measured facts |
|---|---|---|
| brushes2.jpg (brush picker w/ big preview) | 447x447 | light panel #F0F0F0/#FCFCFC (65% of quantized light mass), accent amber **#FFAD01** (top vivid pixel, 21,280 sampled hits; crop is ~45% amber = the selected brush's stroke preview rendered IN AMBER on a light panel), ink grays #A8A8A8 |
| icons.jpg (icon strip) | 472x423 | near-black bg **#222127** (49.6% of px; secondary #1A1A1C), icon-pixel coverage only 2% (thin line art), horizontal run-length histogram dominated by 1–3 px (thin strokes), toolbar icon row ~47 px tall in ~59 px pitch, dense sheet cells ~14 px in ~21 px pitch, inter-icon gaps 12–24 px |
| color-wheel.jpg | 353x566 | muted wheel: ring samples h=0..360 **s=1.00, v=0.65–0.71** (all 8 sampled angles), center swatch = current color (teal #017083, r≈23 px), neutral gap, hue ring r≈64..115 px on a 353 px-wide panel (outer Ø ≈ 65% panel width), chrome grays #242424..#606060, one bright marker (#1BC8E8) on the ring |
| brushes.jpg (brush list) | 353x566 | grayscale only (saturation 0%: 0 vivid pixels), panel chrome gray #666666/#646464, full-width WHITE preview strips (#F0F0F0+) ~52 px tall with 10–12 px gaps, ink #1E1E1E/#000000, 10 content bands (dense list) |

## 1. Color tokens (the whole palette is neutral + ONE accent)

| Token | Value | Source |
|---|---|---|
| ACCENT (amber) | `#FFAD01` | brushes2 dominant vivid pixel (exact) |
| ACCENT_DEEP (pressed/filled) | `#E0A000` | amber darkened 12% for filled active chips |
| CANVAS_BG | `#1A1C24` | existing Brand.BACKGROUND (unchanged) |
| CHROME (panel/dock fill) | `#16171B` @ 90% alpha (`0xE616171B`) | icons bg #222127 measured on near-black chrome; the reference chrome is CRISP, not frosted |
| CHROME_SOLID (sliders/rows on dark) | `#26272B` | wheel chrome grays #24–#60 family |
| BORDER (1 px hairline) | `#FFFFFF` @ 12% (`0x1FFFFFFF`) | neutral hairline, not blue |
| TEXT_PRIMARY | `#ECEDEF` | near-white neutral (was #E8EAEE) |
| TEXT_SECONDARY | `#98A0A8` | neutral gray-blue |
| LIGHT_PANEL (reading panels) | `#F2F2F2` | brushes/brushes2 white rows (#F0F0F0/#FCFCFC) |
| LIGHT_ROW (brush preview rows) | `#FAFAFA` | measured #FCFCFC row cards on #F0F0F0 panel |
| LIGHT_INK | `#1E1E1E` | brushes.jpg dark ink (exact) |
| LIGHT_INK_SECONDARY | `#6E6E6E` | brushes.jpg mid-gray labels |
| LIGHT_BORDER | `#1E1E1E` @ 10% (`0x1A1E1E1E`) | 1 px neutral rule on light panels |
| WHEEL_RING_VALUE | HSV V=0.65, S=1.0 | wheel.jpg 8-angle ring samples (muted wheel) |
| WHEEL_CHROME | dialog bg `#1C1D21`, borders `#3C3C3C` | wheel.jpg chrome grays |

NO other hue appears anywhere in the chrome. The old brand-blue `#2E66F6`
remains ONLY as: the MARKER/marquee selection color on canvas (content, not
chrome) and MARKER brush preset color (content).

## 2. Shape tokens

| Token | Value | Source |
|---|---|---|
| CORNER_PANEL | 12 dp (was 28 dp) | references show crisp, compact chrome; no measurable panel corner survives in the crops (all four crops are interior views — honest note), so radii are set small to match the compact/dense language |
| CORNER_CHIP / row card | 8 dp | same |
| CORNER_SWATCH | 4 dp | same |
| BORDER_WIDTH | 1 px | mission + reference hairlines |
| BLUR_RADIUS | 5 dp (was 12 dp) | references read as crisp opaque chrome; blur machinery KEPT (API 31+ tier) but demoted — panels are 90% opaque so blur is a subtle depth cue only |
| ELEVATION | 6 dp (was 12) | flat, quiet chrome |

## 3. Icon system (the toolbar strip)

- Hand-written Android vector drawables, 24x24 viewport, `strokeColor`
  (not fill), **strokeWidth = 2** (≈2 dp at render → matches the measured
  1–3 px runs on 14–47 px reference icons), round caps/joins, NO fill.
- Icon content size: 24 dp inside a **48 dp minimum touch target**.
- Neutral stroke `#ECEDEF`; ACTIVE tool = stroke tinted **amber #FFAD01**
  + a 4 dp amber underline dot centered under the icon.
- Strip: ONE slim horizontal dock (near-black #1E1D22 fill, 1 px hairline,
  12 dp corner), horizontally scrollable, 48 dp row height + 13 dp drag
  handle. Section grouping is expressed ONLY by a 14 dp spacing gap (no
  dividers, no headers, no labels) — maps the old 8-section dock to the
  reference's single strip:
  `[Brush, Erase, Pick, Select, Shapes, Blend] gap [Swatch, Lazy] gap
   [Layers, Image, Pins, Import] gap [Undo, Redo, Delete, Clear, Flip,
   Rotate] gap [Zoom−, Zoom+] gap [PNG, Video, Demo]`
- contentDescription = the previous text labels (Undo, Redo, Brush, …)
  so TalkBack + every existing dump-based test keeps matching.

## 4. Brush panel (light reading panel)

- Panel shell: LIGHT variant of FloatingPanel (light fill, dark grip) —
  machinery unchanged, style swapped.
- Preset list = dense ROWS (measured pitch: ~52 px rows + 10–12 px gaps on a
  353 px reference → 44 dp row height + 8 dp gap at panel scale):
  row = [72x28 dp stroke preview] [name + spec line], light row cards
  `#FAFAFA`, 8 dp corners.
- Each preset's preview draws its CHARACTERISTIC stroke (pure geometry in
  `BrushPreviewMath`, JVM-tested): tapered line (Ballpoint), uniform band
  (Marker/Fine Ink), soft wide band (Soft Brush), wide translucent square
  band (Highlighter), angled ribbon (Calligraphy), dashes (Dashed), seeded
  dot cloud (Spray), grainy low-alpha line (Pencil).
- INK = `#1E1E1E`; the SELECTED preset renders its preview stroke in
  **ACCENT #FFAD01** (exactly the brushes2 reference: the big amber preview
  IS the selection state) + a 2 dp amber left edge on the row card.
- Sliders: slim neutral SeekBars — amber thumb + amber progress tint,
  neutral track; labels LIGHT_INK on light rows.

## 5. Color picker (muted ring wheel)

- Ring wheel per color-wheel.jpg: hue ANNULUS (outer R = 45% of view min
  side, inner radius 0.56R), ring rendered at S=1.0, **V=0.65** (muted,
  measured); center swatch disc r=0.22R shows the CURRENT color; thin
  neutral gap between (measured r≈23 px swatch vs 64..115 px ring).
- Geometry as pure math (`WheelGeometry`, JVM-tested): angle→hue (0° at
  east/red, clockwise), radius→saturation (clamped to the annulus),
  value from a separate slim amber-thumb slider (the old view had V
  hard-wired to 1.0 — the ring UI adds it per the reference's muted
  output). Marker = small white dot with dark ring on the annulus.
- Dialog chrome: dark neutral `#1C1D21`, 1 px `#3C3C3C` hairline, no
  OK/Cancel clutter (live-apply retained).

## 6. HUD / readout / polish

- Zoom readout chip: dark `#16171B` @ 90%, 1 px hairline, **8 dp corner**
  (not a full pill), text `#ECEDEF` with the LEVEL NUMBER in amber
  `#FFAD01` (e.g. "2.4× · L" neutral + "3" amber).
- Typography: 13 sp medium labels, 11 sp secondary, NO all-caps chrome
  (button text renders mixed-case; contentDescriptions preserve the old
  label words). 8 dp grid throughout.
- Drag grips: 28x3 dp neutral pill `#FFFFFF` @ 26%, centered.

## 7. What deliberately does NOT change

Canvas rendering, model/, export/ logic, FloatingPanel drag+persist,
OverlayLayer gesture isolation, AutoHide pipeline (stylus/pinch hide →
600 ms debounce restore), Springs, snapshot-on-show/drag-end perf
strategy, every feature's reachability (all 0.6.0 actions remain one
tap away — several return to the strip: Pick, Select, Flip, Rotate,
Import, PNG, Video, Demo, Lazy, Pins panel).
