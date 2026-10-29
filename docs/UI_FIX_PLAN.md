# ZoomArt-Android — UI Fix Plan (Post-Device Testing)

> Historical planning/engineering record. For the current product behavior, brush collection and verification status, use the [documentation index](README.md) and [user guide](USER_GUIDE.md). Earlier test counts and release targets below are preserved as historical evidence.

## Issues Found on Device (Note 8, 1440x2960 portrait)

### 1. Docks overlap in screen middle
- Edit dock (BOTTOM|START) and View/Export dock (TOP|END) overlap vertically
- Labels truncated: "DE", "PI", "IM", "PN", "VI", "UN", "RE", "DE", "FLI", "RO", "CL"

### 2. Zoom readout pill rotated with canvas
- Fixed in code (draw outside rotation transform) — needs device verification

### 3. Missing easily-accessible options
- Color picker exists but only as small swatch button
- Layers panel exists but needs clear access
- Brushes panel exists but needs clear access

### 4. Labels unreadable
- 9f text too small; truncated by narrow panel width

## Execution Plan

### Fix 1: Separate docks vertically
- Edit dock: BOTTOM|START with margin
- View/Export dock: TOP|END with margin
- Ensure they don't overlap on Note 8 portrait

### Fix 2: Fix label readability
- iconChip: 13f text (already done), ensure panel width accommodates labels
- groupedToolbar header: 14f (already done)

### Fix 3: Add clearly visible floating panels for brushes/layers/color
- Ensure BrushPanel, LayersPanel, ColorPicker are all accessible via clear floating shells

### Fix 4: Verify zoom readout doesn't rotate
- Already fixed in onDraw — verify on device
