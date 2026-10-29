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
        // Append to preserve saved default-brush indices from earlier releases.
        "Technical pen" to BrushSpec(baseWidthWorld=1.2f,pressureResponse=0f,minWidthRatio=1f),
        "Expressive ink" to BrushSpec(baseWidthWorld=18f,pressureResponse=2.6f,minWidthRatio=0.03f),
        "Broad nib" to CALLIGRAPHY.copy(baseWidthWorld=28f,angleDeg=65f,minWidthRatio=0.15f),
        "Charcoal grain" to PENCIL.copy(baseWidthWorld=4f,spacingWorld=0.8f,scatterWorld=7f,opacity=0.45f),
        "Graphite shade" to PENCIL.copy(baseWidthWorld=8f,spacingWorld=1.4f,scatterWorld=3f,opacity=0.2f),
        "Pastel dust" to SPRAY.copy(baseWidthWorld=5f,spacingWorld=1.2f,scatterWorld=8f,opacity=0.35f,pressureOpacity=true,minOpacity=0.08f),
        "Ink spatter" to SPRAY.copy(baseWidthWorld=7f,spacingWorld=9f,scatterWorld=24f,opacity=0.85f),
        "Mist" to SPRAY.copy(baseWidthWorld=2f,spacingWorld=0.8f,scatterWorld=22f,opacity=0.12f,pressureOpacity=true),
        "Stipple" to BrushSpec(baseWidthWorld=3f,spacingWorld=7f,scatterWorld=2f,pressureResponse=0.5f),
        "Stitch" to DASHED_PEN.copy(baseWidthWorld=2f,dashOn=3f,dashOff=7f),
        "Stardust" to BrushSpec(baseWidthWorld=18f,spacingWorld=18f,scatterWorld=12f,stampShape=StampShape.STAR,pressureOpacity=true),
        "Star chain" to BrushSpec(baseWidthWorld=16f,spacingWorld=22f,pressureResponse=0f,stampShape=StampShape.STAR),
        "Foliage" to BrushSpec(baseWidthWorld=22f,spacingWorld=12f,scatterWorld=14f,angleDeg=35f,stampShape=StampShape.LEAF,opacity=0.75f),
        "Confetti" to BrushSpec(baseWidthWorld=12f,spacingWorld=14f,scatterWorld=22f,angleDeg=45f,stampShape=StampShape.CHISEL),
        "Dry chisel" to BrushSpec(baseWidthWorld=14f,spacingWorld=3f,scatterWorld=3f,angleDeg=35f,stampShape=StampShape.CHISEL,opacity=0.3f,pressureOpacity=true),
    )
}
