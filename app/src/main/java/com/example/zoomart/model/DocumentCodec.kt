package com.example.zoomart.model

/**
 * Session codec v3 (P0): hand-rolled JSON writer + parser for
 * autosave/restore. Deliberately dependency-free so the whole round trip is
 * verifiable on a plain JVM, and cross-checkable by any other JSON parser.
 *
 * v3 adds nested zoom levels:
 *  - "levels":[{id,parentId,originX,originY,scaleToParent}] (root first,
 *    parentId -1; parents always precede children)
 *  - strokes and images carry "level":<id>
 *  - bookmarks carry "levelId":<id>; the camera carries "levelId"
 *
 * Old v1/v2 session files migrate losslessly: absent "levels" means just the
 * root level, and absent per-item level fields default to ROOT_ID — every
 * legacy stroke/image/pin becomes root-level content, byte-for-byte the
 * same geometry it always was.
 */
object DocumentCodec {

    class JsonError(message: String) : RuntimeException(message)

    data class SessionData(val document: DrawingDocument, val camera: Camera)

    private val DEFAULT_COLOR = (0xFF shl 24) or 0x16181D

    /**
     * ARGB read that tolerates UNSIGNED 0..4294967295 encodings (e.g. from
     * non-app tools): JVM double->int conversion saturates out-of-range
     * values to 0x7FFFFFFF, which would silently white out colors, so
     * values above Int.MAX_VALUE wrap into the signed ARGB range first.
     */
    private fun argbOf(d: Double?, fallback: Int): Int {
        if (d == null) return fallback
        return if (d >= 2147483648.0 && d < 4294967296.0) {
            (d - 4294967296.0).toInt()
        } else {
            d.toInt()
        }
    }

    fun toJson(document: DrawingDocument, camera: Camera): String = buildString {
        append("{\"version\":3")
        append(",\"camera\":{\"levelId\":").append(camera.level.id)
        append(",\"scale\":").append(camera.scale)
        append(",\"transX\":").append(camera.transX)
        append(",\"transY\":").append(camera.transY).append("}")
        append(",\"activeLayer\":").append(document.activeLayerIndex)
        append(",\"levels\":[")
        document.levelTree.allLevels().forEachIndexed { li, lvl ->
            if (li > 0) append(",")
            append("{\"id\":").append(lvl.id)
            append(",\"parentId\":").append(lvl.parent?.id ?: -1L)
            append(",\"originX\":").append(lvl.originX)
            append(",\"originY\":").append(lvl.originY)
            append(",\"scaleToParent\":").append(lvl.scaleToParent).append("}")
        }
        append("]")
        append(",\"layers\":[")
        document.layers.forEachIndexed { li, layer ->
            if (li > 0) append(",")
            append("{\"name\":\"").append(escape(layer.name))
            append("\",\"visible\":").append(layer.isVisible)
            append(",\"opacity\":").append(layer.opacity)
            // P2.5: layer lock + blend (absent in pre-P2 v3 files ->
            // locked=false, blend=NORMAL on read; lossless migration)
            append(",\"locked\":").append(layer.locked)
            append(",\"blend\":\"").append(layer.blend.name).append("\"")
            append(",\"images\":[")
            layer.images.forEachIndexed { ii, img ->
                if (ii > 0) append(",")
                append("{\"path\":\"").append(escape(img.path))
                append("\",\"x\":").append(img.x)
                append(",\"y\":").append(img.y)
                append(",\"w\":").append(img.w)
                append(",\"h\":").append(img.h)
                append(",\"level\":").append(img.levelId)
                // P4.2/P4.3: image v2 fields — emitted only when non-default
                // so pre-P4 files round-trip byte-identically. The DISPLAY
                // filter is UI-state and deliberately NOT serialized.
                if (img.alpha != 1f) append(",\"alpha\":").append(img.alpha)
                if (img.locked) append(",\"locked\":").append(img.locked)
                if (img.rotation != 0f) append(",\"rot\":").append(img.rotation)
                if (img.flipH) append(",\"flipH\":true")
                append("}")
            }
            append("],\"strokes\":[")
            layer.strokes.forEachIndexed { si, stroke ->
                if (si > 0) append(",")
                append("{\"brush\":{\"width\":").append(stroke.brush.baseWidthWorld)
                append(",\"response\":").append(stroke.brush.pressureResponse)
                append(",\"minRatio\":").append(stroke.brush.minWidthRatio)
                append(",\"color\":").append(stroke.brush.color)
                append(",\"opacity\":").append(stroke.brush.opacity)
                append(",\"pOpacity\":").append(stroke.brush.pressureOpacity)
                append(",\"minOp\":").append(stroke.brush.minOpacity)
                append(",\"stampShape\":\"").append(stroke.brush.stampShape.name).append("\"")
                append(",\"stampFlipX\":").append(stroke.brush.stampFlipX)
                append(",\"stampFlipY\":").append(stroke.brush.stampFlipY)
                if (stroke.brush.angleDeg != 0f) append(",\"angle\":").append(stroke.brush.angleDeg)
                if (stroke.brush.dashOn != 0f) append(",\"dashOn\":").append(stroke.brush.dashOn)
                if (stroke.brush.dashOff != 0f) append(",\"dashOff\":").append(stroke.brush.dashOff)
                if (stroke.brush.scatterWorld != 0f) append(",\"scatter\":").append(stroke.brush.scatterWorld)
                if (stroke.brush.spacingWorld != 0f) append(",\"spacing\":").append(stroke.brush.spacingWorld)
                if (stroke.brush.erase) append(",\"erase\":true")
                append("}")
                append(",\"level\":").append(stroke.levelId)
                // P3.2: lazy-brush taper (absent in pre-P3 files -> 0)
                if (stroke.taper != 0f) append(",\"taper\":").append(stroke.taper)
                append(",\"points\":[")
                stroke.points.forEachIndexed { pi, p ->
                    if (pi > 0) append(",")
                    append(p.x).append(",").append(p.y).append(",").append(p.pressure01)
                }
                append("]}")
            }
            append("]")
            // P3.1: parametric shapes (absent in pre-P3 files -> none)
            if (layer.shapes.isNotEmpty()) {
                append(",\"shapes\":[")
                layer.shapes.forEachIndexed { si, sh ->
                    if (si > 0) append(",")
                    append("{\"type\":\"").append(sh.type.name)
                    append("\",\"level\":").append(sh.levelId)
                    append(",\"x0\":").append(sh.x0)
                    append(",\"y0\":").append(sh.y0)
                    append(",\"x1\":").append(sh.x1)
                    append(",\"y1\":").append(sh.y1)
                    append(",\"fillOn\":").append(sh.fillOn)
                    append(",\"fillColor\":").append(sh.fillColor)
                    append(",\"outlineColor\":").append(sh.outlineColor)
                    append(",\"outlineWidth\":").append(sh.outlineWidthWorld)
                    append(",\"round\":").append(sh.cornerRoundness)
                    append(",\"count\":").append(sh.pointCount).append("}")
                }
                append("]")
            }
            // P3.3: smudge gestures (absent in pre-P3 files -> none)
            if (layer.smudges.isNotEmpty()) {
                append(",\"smudges\":[")
                layer.smudges.forEachIndexed { si, sm ->
                    if (si > 0) append(",")
                    append("{\"level\":").append(sm.levelId)
                    append(",\"stamps\":[")
                    sm.stamps.forEachIndexed { ti, st ->
                        if (ti > 0) append(",")
                        append(st.x).append(",").append(st.y).append(",")
                        append(st.radius).append(",").append(st.color).append(",")
                        append(st.alpha)
                    }
                    append("]}")
                }
                append("]")
            }
            append("}")
        }
        append("]")
        append(",\"bookmarks\":[")
        document.bookmarks.forEachIndexed { bi, b ->
            if (bi > 0) append(",")
            append("{\"worldX\":").append(b.worldX)
            append(",\"worldY\":").append(b.worldY)
            append(",\"scale\":").append(b.scale)
            append(",\"label\":\"").append(escape(b.label))
            append("\",\"levelId\":").append(b.levelId).append("}")
        }
        append("]}")
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }

    fun fromJson(text: String): SessionData {
        if (text.length > 64 * 1024 * 1024) throw JsonError("Document exceeds 64 MiB limit")
        val parsed = try {
            Parser(text).document()
        } catch (e: JsonError) {
            throw e
        }
        val root = parsed as? Map<*, *> ?: throw JsonError("root is not a JSON object")
        if ((root["version"] as? Double ?: 1.0) > 3.0) throw JsonError("newer document version")

        DocumentValidation.check(root)
        val doc = DrawingDocument()

        // ----- levels (absent in v1/v2 => single root level: migration) -----
        doc.levelTree.clear()
        (root["levels"] as? List<*>)?.forEach { lvlAny ->
            val lm = lvlAny as? Map<*, *> ?: return@forEach
            val id = (lm["id"] as? Double ?: return@forEach).toLong()
            val parentId = (lm["parentId"] as? Double)?.toLong() ?: -1L
            val levelScale = (lm["scaleToParent"] as? Double ?: ZoomLevels.THRESHOLD.toDouble()).toFloat()
            if (!levelScale.isFinite() || (id != ZoomLevels.ROOT_ID && levelScale <= 1f))
                throw JsonError("invalid zoom level scale")
            doc.levelTree.attach(
                id,
                parentId,
                (lm["originX"] as? Double ?: 0.0).toFloat(),
                (lm["originY"] as? Double ?: 0.0).toFloat(),
                levelScale,
            )
        }

        // ----- camera (levelId absent in v1/v2 => root) -----
        val camMap = root["camera"] as? Map<*, *> ?: throw JsonError("missing camera object")
        val camLevelId = (camMap["levelId"] as? Double ?: ZoomLevels.ROOT_ID.toDouble()).toLong()
        val camera = Camera(
            scale = (camMap["scale"] as? Double ?: 1.0).toFloat(),
            transX = (camMap["transX"] as? Double ?: 0.0).toFloat(),
            transY = (camMap["transY"] as? Double ?: 0.0).toFloat(),
            level = doc.levelTree.byId(camLevelId) ?: doc.levelTree.root,
        )
        if (!camera.scale.isFinite() || camera.scale <= 0f ||
            !camera.transX.isFinite() || !camera.transY.isFinite()) throw JsonError("invalid camera")

        doc.layers.clear()
        (root["layers"] as? List<*>)?.forEach { layerAny ->
            val lm = layerAny as? Map<*, *> ?: return@forEach
            val layer = LayerData(name = lm["name"] as? String ?: "Layer")
            layer.isVisible = lm["visible"] as? Boolean ?: true
            layer.opacity = (lm["opacity"] as? Double ?: 1.0).toFloat()
            // P2.5: lock + blend; absent fields default (pre-P2 v3 files)
            layer.locked = lm["locked"] as? Boolean ?: false
            layer.blend = LayerBlend.fromName(lm["blend"] as? String)
            (lm["images"] as? List<*>)?.forEach { imgAny ->
                val im = imgAny as? Map<*, *> ?: return@forEach
                val path = im["path"] as? String ?: return@forEach
                layer.images.add(
                    PlacedImage(
                        path,
                        (im["x"] as? Double ?: 0.0).toFloat(),
                        (im["y"] as? Double ?: 0.0).toFloat(),
                        (im["w"] as? Double ?: 100.0).toFloat(),
                        (im["h"] as? Double ?: 100.0).toFloat(),
                        (im["level"] as? Double ?: ZoomLevels.ROOT_ID.toDouble()).toLong(),
                        // P4.2/P4.3: absent in pre-P4 files -> defaults
                        // (alpha 1, unlocked, unrotated) — lossless migration
                        (im["alpha"] as? Double ?: 1.0).toFloat().coerceIn(0f, 1f),
                        im["locked"] as? Boolean ?: false,
                        (im["rot"] as? Double ?: 0.0).toFloat(),
                    ).also { it.flipH = im["flipH"] as? Boolean ?: false }
                )
            }
            (lm["strokes"] as? List<*>)?.forEach { strokeAny ->
                val sm = strokeAny as? Map<*, *> ?: return@forEach
                val bm = sm["brush"] as? Map<*, *>
                val brush = BrushSpec(
                    baseWidthWorld = (bm?.get("width") as? Double ?: 6.0).toFloat(),
                    pressureResponse = (bm?.get("response") as? Double ?: 2.0).toFloat(),
                    minWidthRatio = (bm?.get("minRatio") as? Double ?: 0.15).toFloat(),
                    color = argbOf(bm?.get("color") as? Double, DEFAULT_COLOR),
                    opacity = (bm?.get("opacity") as? Double ?: 1.0).toFloat(),
                    angleDeg = (bm?.get("angle") as? Double ?: 0.0).toFloat(),
                    dashOn = (bm?.get("dashOn") as? Double ?: 0.0).toFloat(),
                    dashOff = (bm?.get("dashOff") as? Double ?: 0.0).toFloat(),
                    scatterWorld = (bm?.get("scatter") as? Double ?: 0.0).toFloat(),
                    spacingWorld = (bm?.get("spacing") as? Double ?: 0.0).toFloat(),
                    erase = bm?.get("erase") as? Boolean ?: false,
                    // P1.2 fields; absent in older files -> plain opacity brushes
                    pressureOpacity = bm?.get("pOpacity") as? Boolean ?: false,
                    stampFlipX = bm?.get("stampFlipX") as? Boolean ?: false,
                    stampFlipY = bm?.get("stampFlipY") as? Boolean ?: false,
                    stampShape = StampShape.values().firstOrNull { it.name == bm?.get("stampShape") } ?: StampShape.ROUND,
                    minOpacity = (bm?.get("minOp") as? Double ?: BrushSpec.DEFAULT_MIN_OPACITY.toDouble()).toFloat(),
                )
                val stroke = StrokeData(brush = brush)
                stroke.levelId =
                    (sm["level"] as? Double ?: ZoomLevels.ROOT_ID.toDouble()).toLong()
                // P3.2: lazy-brush taper (absent in pre-P3 files -> 0)
                stroke.taper = (sm["taper"] as? Double ?: 0.0).toFloat()
                val pts = sm["points"] as? List<*> ?: emptyList<Any?>()
                var k = 0
                while (k + 3 <= pts.size) {
                    stroke.addPoint(
                        StrokePoint(
                            (pts[k] as? Double ?: 0.0).toFloat(),
                            (pts[k + 1] as? Double ?: 0.0).toFloat(),
                            (pts[k + 2] as? Double ?: 0.0).toFloat(),
                        )
                    )
                    k += 3
                }
                if (stroke.points.isNotEmpty()) layer.strokes.add(stroke)
            }
            // P3.1: parametric shapes; unknown type names are SKIPPED so a
            // newer session degrades gracefully instead of failing to load
            (lm["shapes"] as? List<*>)?.forEach { shAny ->
                val shm = shAny as? Map<*, *> ?: return@forEach
                val type = ShapeType.fromName(shm["type"] as? String) ?: return@forEach
                val shape = ShapeData(
                    type,
                    (shm["level"] as? Double ?: ZoomLevels.ROOT_ID.toDouble()).toLong(),
                )
                shape.setAnchors(
                    (shm["x0"] as? Double ?: 0.0).toFloat(),
                    (shm["y0"] as? Double ?: 0.0).toFloat(),
                    (shm["x1"] as? Double ?: 0.0).toFloat(),
                    (shm["y1"] as? Double ?: 0.0).toFloat(),
                )
                shape.fillOn = shm["fillOn"] as? Boolean ?: true
                shape.fillColor = argbOf(shm["fillColor"] as? Double, 0xFF2E66F6.toInt())
                shape.outlineColor = argbOf(shm["outlineColor"] as? Double, 0xFF16181D.toInt())
                shape.outlineWidthWorld = (shm["outlineWidth"] as? Double ?: 4.0).toFloat()
                shape.cornerRoundness = (shm["round"] as? Double ?: 0.25).toFloat()
                shape.pointCount = (shm["count"] as? Double ?: 5.0).toInt().coerceAtLeast(3)
                layer.shapes.add(shape)
            }
            // P3.3: smudge gestures; malformed stamp rows are dropped
            (lm["smudges"] as? List<*>)?.forEach { smAny ->
                val smm = smAny as? Map<*, *> ?: return@forEach
                val smudge = SmudgeData(
                    (smm["level"] as? Double ?: ZoomLevels.ROOT_ID.toDouble()).toLong()
                )
                val stamps = smm["stamps"] as? List<*> ?: emptyList<Any?>()
                var k = 0
                while (k + 5 <= stamps.size) {
                    smudge.stamps.add(
                        StampData(
                            (stamps[k] as? Double ?: 0.0).toFloat(),
                            (stamps[k + 1] as? Double ?: 0.0).toFloat(),
                            (stamps[k + 2] as? Double ?: 0.0).toFloat(),
                            argbOf(stamps[k + 3] as? Double, 0),
                            (stamps[k + 4] as? Double ?: 1.0).toFloat().coerceIn(0f, 1f),
                        )
                    )
                    k += 5
                }
                if (smudge.stamps.isNotEmpty()) layer.smudges.add(smudge)
            }
            doc.layers.add(layer)
        }
        (root["bookmarks"] as? List<*>)?.forEach { bAny ->
            val bm = bAny as? Map<*, *> ?: return@forEach
            doc.bookmarks.add(
                Bookmark(
                    worldX = (bm["worldX"] as? Double ?: 0.0).toFloat(),
                    worldY = (bm["worldY"] as? Double ?: 0.0).toFloat(),
                    scale = (bm["scale"] as? Double ?: 1.0).toFloat(),
                    label = bm["label"] as? String ?: "",
                    levelId = (bm["levelId"] as? Double ?: ZoomLevels.ROOT_ID.toDouble()).toLong(),
                )
            )
        }
        val maxIndex = (doc.layers.size - 1).coerceAtLeast(0)
        doc.activeLayerIndex =
            ((root["activeLayer"] as? Double)?.toInt() ?: maxIndex).coerceIn(0, maxIndex)
        doc.reindex()
        return SessionData(doc, camera)
    }

    // ---------------- minimal recursive-descent JSON parser ----------------
    private class Parser(private val s: String) {
        private var i = 0
        private var depth = 0
        private var tokens = 0

        fun document(): Any? {
            val result = value()
            ws()
            if (i != s.length) fail("trailing data")
            return result
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end of input")
            if (++tokens > 4000000 || ++depth > 64) fail("Document complexity limit")
            return try { when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '\"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            } } finally { depth-- }
        }

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun fail(msg: String): Nothing = throw JsonError(msg + " (at " + i + ")")

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) fail("expected '" + word + "'")
            i += word.length
        }

        private fun obj(): LinkedHashMap<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val key = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                if (m.containsKey(key)) fail("Duplicate key")
                m[key] = value()
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                fail("expected ',' or '}'")
            }
        }

        private fun arr(): MutableList<Any?> {
            i++
            val list = mutableListOf<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(value())
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return list }
                fail("expected ',' or ']'")
            }
        }

        private fun str(): String {
            if (i >= s.length || s[i] != '\"') fail("expected string")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\"') { i++; return sb.toString() }
                if (c == '\\') {
                    i++
                    if (i >= s.length) fail("unterminated escape")
                    when (s[i]) {
                        '\"' -> sb.append('\"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'u' -> {
                            if (i + 4 >= s.length) fail("short unicode escape")
                            val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: fail("bad unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        'f' -> sb.append('')
                        else -> fail("bad escape")
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            fail("unterminated string")
        }

        private fun num(): Double {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (!(c.isDigit() || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E')) break
                i++
            }
            if (i == start) fail("bad number")
            return try {
                s.substring(start, i).toDouble().also { if (!it.isFinite()) fail("non-finite number") }
            } catch (e: NumberFormatException) {
                fail("bad number")
            }
        }
    }
}
