package com.example.zoomart.model

/** Resource and graph limits apply before constructing/indexing any imported document. */
internal object DocumentValidation {
    fun check(root: Map<*, *>) {
        fun bad(message: String): Nothing = throw DocumentCodec.JsonError(message)
        fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: bad("Expected object")
        fun list(parent: Map<*, *>, key: String): List<*> = when(val v=parent[key]) {
            null -> emptyList<Any>()
            is List<*> -> v
            else -> bad("Expected array: $key")
        }
        fun range(m: Map<*, *>, key: String, low: Double, high: Double) {
            if (!m.containsKey(key)) return
            val value=m[key] as? Double ?: bad("Expected number: $key")
            if (!value.isFinite() || value !in low..high) bad("Out of range: $key")
        }
        fun id(m: Map<*, *>, key: String, fallback: Long=0): Long {
            if (!m.containsKey(key)) return fallback
            val n=m[key] as? Double ?: bad("Invalid ID")
            if(n < 0 || n > 9007199254740991.0 || n % 1.0 != 0.0) bad("Invalid ID")
            return n.toLong()
        }
        fun numbers(value: Any?) {
            when(value) {
                is Double -> if (!value.isFinite() || !value.toFloat().isFinite()) bad("Non-finite geometry")
                is Map<*, *> -> value.values.forEach(::numbers)
                is List<*> -> value.forEach(::numbers)
                is String -> if(value.length>8192) bad("Text exceeds limit")
            }
        }
        numbers(root)
        val levels=list(root,"levels")
        if(levels.size>4096) bad("Too many zoom levels")
        val depths=mutableMapOf(0L to 0)
        val seen=mutableSetOf<Long>()
        for(v in levels) {
            val m=map(v); val key=id(m,"id")
            if(!seen.add(key)) bad("Duplicate level ID")
            val parent=(m["parentId"] as? Double ?: -1.0)
            if(key==0L) { if(parent != -1.0) bad("Invalid root parent"); continue }
            if(parent < 0 || parent % 1.0 != 0.0 || parent.toLong() !in depths) bad("Missing or cyclic parent")
            val depth=depths.getValue(parent.toLong())+1
            if(depth>512) bad("Zoom depth exceeds 512")
            depths[key]=depth
        }
        fun reference(m: Map<*, *>, key: String) { if(id(m,key) !in depths) bad("Unknown zoom level") }
        val camera=map(root["camera"]); reference(camera,"levelId")
        range(camera,"scale",1e-30,1e30)
        val layers=list(root,"layers")
        if(layers.size>512) bad("Too many layers")
        var points=0L; var objects=0
        for(v in layers) {
            val layer=map(v); range(layer,"opacity",0.0,1.0)
            for(kind in listOf("strokes","images","shapes","smudges")) for(item in list(layer,kind)) {
                if(++objects>200000) bad("Too many objects")
                val m=map(item); reference(m,"level")
                if(kind=="strokes") {
                    val p=list(m,"points")
                    if(p.size%3 != 0 || p.any { it !is Double }) bad("Invalid stroke points")
                    points+=p.size/3
                    for(i in 2 until p.size step 3) if((p[i] as Double) !in 0.0..1.0) bad("Invalid pressure")
                    range(m,"taper",0.0,1.0)
                    if(m["brush"] != null) {
                        val b=map(m["brush"])
                        range(b,"width",1e-30,1e12); range(b,"response",0.0,16.0)
                        for(k in listOf("opacity","minRatio","minOp")) range(b,k,0.0,1.0)
                        for(k in listOf("dashOn","dashOff","scatter","spacing")) range(b,k,0.0,1e12)
                    }
                }
                if(kind=="images") {
                    val path=m["path"] as? String ?: bad("Missing image path")
                    if(path.startsWith("/") || path.contains('\\') || path.contains(':') || path.split('/').any { it==".." || it.isEmpty() }) bad("Unsafe image path")
                    range(m,"w",1e-30,1e12); range(m,"h",1e-30,1e12); range(m,"alpha",0.0,1.0)
                }
                if(kind=="shapes") { range(m,"count",3.0,512.0); range(m,"outlineWidth",0.0,1e12); range(m,"round",0.0,1.0) }
                if(kind=="smudges") {
                    val p=list(m,"stamps")
                    if(p.size%5 != 0 || p.any { it !is Double }) bad("Invalid smudge stamps")
                    points+=p.size/5
                    for(i in 0 until p.size step 5) if((p[i+2] as Double)<0 || (p[i+4] as Double) !in 0.0..1.0) bad("Invalid stamp")
                }
                if(points>1000000) bad("Too many sampled points")
            }
        }
        if(list(root,"bookmarks").size>10000) bad("Too many bookmarks")
        for(v in list(root,"bookmarks")) { val b=map(v); reference(b,"levelId"); range(b,"scale",1e-30,1e30) }
    }
}
