package com.example.zoomart.model

/** Ready-made parametric brushes; the brush panel lets users tweak on top. */
object BrushPresets {
    val BALLPOINT = BrushSpec(
        baseWidthWorld = 3f, pressureResponse = 1.5f, minWidthRatio = 0.25f,
        color = 0xFF16181D.toInt(),
    )
    val MARKER = BrushSpec(
        baseWidthWorld = 14f, pressureResponse = 0.6f, minWidthRatio = 0.6f,
        opacity = 0.85f, color = 0xFF2E66F6.toInt(),
    )
    val SOFT_BRUSH = BrushSpec(
        baseWidthWorld = 22f, pressureResponse = 2.2f, minWidthRatio = 0.08f,
        opacity = 0.6f, color = 0xFF16181D.toInt(),
    )
    val FINE_INK = BrushSpec(
        baseWidthWorld = 1.5f, pressureResponse = 1f, minWidthRatio = 0.5f,
        color = 0xFF101014.toInt(),
    )
    val HIGHLIGHTER = BrushSpec(
        baseWidthWorld = 30f, pressureResponse = 0.2f, minWidthRatio = 0.85f,
        opacity = 0.35f, color = 0xFFF5D90A.toInt(),
    )

    // Infinite-Design-style additions (brush settings: nib angle, dash, spray)
    val CALLIGRAPHY = BrushSpec(
        baseWidthWorld = 10f, pressureResponse = 1.2f, minWidthRatio = 0.35f,
        angleDeg = 40f, color = 0xFF16181D.toInt(),
    )
    val DASHED_PEN = BrushSpec(
        baseWidthWorld = 5f, dashOn = 12f, dashOff = 8f, color = 0xFF16181D.toInt(),
    )
    val SPRAY = BrushSpec(
        baseWidthWorld = 2f, scatterWorld = 14f, spacingWorld = 4f,
        opacity = 0.5f, color = 0xFF16181D.toInt(),
    )
    val PENCIL = BrushSpec(
        // P1.2: thin, slightly transparent; pressure controls OPACITY while
        // the width response stays low (spec BRUSHES, was CONFLICT).
        baseWidthWorld = 3f, pressureResponse = 0.25f, minWidthRatio = 0.8f,
        pressureOpacity = true, minOpacity = 0.15f,
        scatterWorld = 1.5f, spacingWorld = 2f, opacity = 0.75f, color = 0xFF2B2B2B.toInt(),
    )

    val all: List<Pair<String, BrushSpec>> = listOf(
        "Ballpoint" to BALLPOINT,
        "Marker" to MARKER,
        "Soft Brush" to SOFT_BRUSH,
        "Fine Ink" to FINE_INK,
        "Highlighter" to HIGHLIGHTER,
        "Calligraphy" to CALLIGRAPHY,
        "Dashed" to DASHED_PEN,
        "Spray" to SPRAY,
        "Pencil" to PENCIL,
    )
}
