package com.example.zoomart

import com.example.zoomart.model.BrushPresets
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingViewModel
import java.io.File

/**
 * Not a test: a main() that renders a representative document to JSON, parses
 * it back, re-serializes, and writes BOTH files so Python can compare them.
 */
fun main() {
    val vm = DrawingViewModel()
    vm.camera.scale = 2.5f
    vm.camera.transX = -100f
    vm.camera.transY = 40f
    vm.currentBrush = BrushPresets.MARKER

    vm.beginStroke(0f, 0f, 0.25f)
    vm.extendStroke(10f, 5f, 0.8f)
    vm.extendStroke(20f, 12f, 0.6f)
    vm.endStroke()
    vm.addBookmark(100f, 50f, "probe pin")

    File("build").mkdirs()
    val json1 = vm.serialize()
    File("build/serialize_sample.json").writeText(json1)

    val session = DocumentCodec.fromJson(json1)
    val json2 = DocumentCodec.toJson(session.document, session.camera)
    File("build/serialize_roundtrip.json").writeText(json2)

    println("sample=" + json1.length + "B roundtrip=" + json2.length + "B identical=" + (json1 == json2))
}
