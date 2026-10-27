# ZoomArt-Android — UI/UX Responsiveness & Branding Plan (v0.7.0 Phase 5)

## Source of truth / brief

User wants the v0.7.0 UI evolved to:

1. **Responsive design** — scale to different device screen sizes (Note 8: 1440x2960 portrait @560dpi override; Tab S9 FE: 2304x1440 landscape @420dpi). Density-aware sizing, percentage/capped dp dimensions, no hardcoded px.
2. **Icons on panels and toolbars** — replace text-only buttons with vector drawable icons (icon + optional label).
3. **Title page uses branding** — `brand/zoomart_icon_512.png` (dark navy, white circle, blue inner ring, cross motif) as the large app icon; brand color palette.
4. **Address existing gaps**:
   - Gap 1: Calligraphy direction-varying width (angled nib only → width varies with stroke direction)
   - Gap 2: Long-press drag layer reorder (Up/Dn buttons MVP only)
   - Gap 3: FLIP does not mirror images (pinch rotate/scale used instead)
   - Gap 5: docs/USER_GUIDE.md v0.5.0 refresh in flight
5. **Test on connected Note 8** (ce081718958624350c7e) via adb.

## Device context

| Device | Size | Orientation | Density | Notes |
|--------|------|-------------|---------|-------|
| Galaxy Note 8 (SM_N950U) | 1440x2960 | Portrait | 560dpi (override) | Connected now — adb serial ce081718958624350c7e |
| Galaxy Tab S9 FE (SM-X510) | 2304x1440 | Landscape | 420dpi | Previously verified |

Responsive design must look good on both: narrow-tall portrait vs wide-short landscape.

## Execution plan

### Phase 5a — Brand palette + vector icons
- Add brand colors to `app/src/main/res/values/colors.xml` (navy bg, blue accent, glass tones)
- Create vector drawable icons: brush, erase, shapes, blend, layers, image, undo, redo, select, import, png, video, demo, pins, clear, flip, rot90, lazy, pick, zoom±, settings, resume, new

### Phase 5b — Branded title page
- TitleActivity: large brand icon (zoomart_icon_512.png), brand colors, "ZoomArt" wordmark, responsive card sizing

### Phase 5c — Icon toolbars + responsive sizing
- Replace text buttons with icon buttons (icon + small label) on grouped toolbars
- All dimensions density-aware (dp/sp), panel widths capped as % of screen or fixed max dp
- Floating panels clamp to screen bounds on any density/orientation

### Phase 5d — Gap fixes
- Calligraphy: direction-varying nib width (angle-based width modulation)
- Layers: long-press drag reorder (in addition to Up/Dn)
- Images: FLIP horizontal mirror
- USER_GUIDE.md: refresh to v0.7.0

### Phase 5e — Verify on Note 8 + commit
- `./gradlew testDebugUnitTest assembleDebug` → 200/200 green
- `adb -s ce081718958624350c7e install -r` → install on Note 8
- Launch, navigate title → canvas → settings, verify responsive layout
- Commit + push to GitHub

## Success criteria

- App builds, 200/200 tests green
- APK installs and launches on Note 8
- Title page shows brand icon + brand colors
- Toolbars show icons (not just text)
- Layout responsive on Note 8 portrait + Tab S9 FE landscape
- Calligraphy width varies with stroke direction
- Long-press drag reorders layers
- FLIP mirrors images horizontally
- progress.md checkpoint 5 added
