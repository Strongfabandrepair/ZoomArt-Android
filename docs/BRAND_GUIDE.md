# ZoomArt brand and icon system

## Identity

**Name:** ZoomArt. **Descriptor:** an Android drawing studio for layered ink and deep-zoom exploration. **Tagline:** “A little mark. An endless world.” The tagline is expressive brand copy, not a technical guarantee of unlimited precision or storage.

The orbital mark combines nested rings with a drawing nib: navigation and making a mark in one symbol. Lavender and teal rings express depth; a warm copper nib identifies the act of drawing. Keep the mark on a quiet background with at least one nib-width of clear space. Do not distort it or add shadows inside its linework.

[Vector mark](brand/zoomart-mark.svg) · [Icon catalog](brand/icon-catalog.svg)

## Palette

| Role | Color |
|---|---|
| Deep background | `#111929` |
| Violet atmosphere | `#29233B` |
| Teal atmosphere | `#173039` |
| Lavender orbit / dark-surface controls | `#C7A1FF` |
| Teal orbit | `#88DFD0` |
| Copper nib / warm emphasis | `#FFC38A` |
| Primary text / icon strokes | `#F4F1FA` |
| Supporting text | `#C7C4D5` |

Amber remains a distinct selected-tool indicator. On pale Atelier surfaces, use darker control tints rather than pale lavender to preserve contrast. Avoid placing text directly over bright particles. Legacy blue resource names remain for compatibility; the orbital palette is the current brand identity.

## Toolbar icons

The original vector family uses a **24×24 viewport**, **1.65-unit rounded strokes**, round joins and optical padding. Paths describe recognizable actions rather than decorative silhouettes: a nib for Brush, an eraser with a cut edge, a pipette, stacked layers, a map pin, a file-plus for New, adjustment rails for Settings and three dots for More.

Icons inherit the control tint and remain legible in selected/disabled states. Do not encode meaning through color alone. Keep a visible label where space permits and a complete accessibility description. Preserve minimum 48 dp interactive targets; the artwork itself is 24 dp. Avoid adding details that disappear at normal toolbar size.

The catalog is generated from the same path definitions used by Android resources. These paths were authored for this project; no external icon pack or attribution dependency was added. Future changes should update both the Android resources and catalog.

## Launcher

`ic_launcher_foreground.xml` uses the same nib-and-orbit mark, padded within the adaptive-icon viewport. `launcher_background.xml` provides the dark atmospheric gradient. Round and normal launcher resources use the same adaptive layers. API 33+ resources include a monochrome layer for launchers that support themed icons; its final color and masking are controlled by the launcher.

Keep the application ID and display name stable. Updating these assets does not migrate or rename saved projects. The older raster logo files are retained as historical assets and are not the current title or launcher identity.

## Typography, surfaces and motion

Use the system sans-serif family: bold for the product name, medium for section labels, regular for explanatory text. Use sentence case in controls. The title, Settings and library share the animated orbital backdrop. Dock and panel shells use quieter variants, with a pale treatment for the Atelier; dialogs share the static gradient.

Decorative motion pauses during drawing, while hidden/unfocused, when system animations are disabled and in Battery Saver. It must not become part of exported artwork. The current ambient animation is deliberately capped below full display refresh; do not advertise it as a 90/120 fps renderer. See [theme implementation](STUDIO_THEME.md) for lifecycle and performance details.
