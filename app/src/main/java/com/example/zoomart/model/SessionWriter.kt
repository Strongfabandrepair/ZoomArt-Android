package com.example.zoomart.model

import java.io.File
import java.util.concurrent.Executors

/** Process-scoped ordered writer; latest pending snapshot replaces older pending work. */
object SessionWriter {
    private val executor=Executors.newSingleThreadExecutor { r -> Thread(r,"document-writer").apply { isDaemon=true } }
    private val pending=linkedMapOf<String, Pair<DocumentCodec.SessionData, (Throwable?) -> Unit>>()
    private val latest=mutableMapOf<String,DocumentCodec.SessionData>()
    private var running=false
    fun capture(document: DrawingDocument, camera: Camera): DocumentCodec.SessionData {
        val copy=DrawingDocument(); copy.layers.clear(); copy.levelTree.clear()
        for(l in document.levelTree.allLevels()) copy.levelTree.attach(l.id,l.parent?.id ?: -1,l.originX,l.originY,l.scaleToParent)
        document.layers.forEach { copy.layers.add(it.deepCopy()) }
        copy.bookmarks.addAll(document.bookmarks); copy.activeLayerIndex=document.activeLayerIndex
        return DocumentCodec.SessionData(copy,Camera(camera.scale,camera.transX,camera.transY,copy.levelTree.byId(camera.level.id)!!))
    }
    @Synchronized fun latest(file: File): DocumentCodec.SessionData? = latest[file.absolutePath]?.let { capture(it.document,it.camera) }
    @Synchronized fun enqueue(file: File, snapshot: DocumentCodec.SessionData, complete: (Throwable?) -> Unit = {}) {
        latest[file.absolutePath]=snapshot
        pending[file.absolutePath]=snapshot to complete
        if(running) return
        running=true
        executor.execute {
            while(true) {
                val work=synchronized(this) {
                    val entry=pending.entries.firstOrNull()
                    if(entry==null) { running=false; null }
                    else Triple(entry.key,entry.value.first,entry.value.second).also { pending.remove(entry.key) }
                } ?: break
                val failure=runCatching { write(File(work.first),work.second) }.exceptionOrNull()
                synchronized(this) { if(failure==null && latest[work.first] === work.second) latest.remove(work.first) }
                runCatching { work.third(failure) }
            }
        }
    }
    fun write(file: File, snapshot: DocumentCodec.SessionData) {
        val bytes=DocumentCodec.toJson(snapshot.document,snapshot.camera).toByteArray(Charsets.UTF_8)
        writeBytes(file,bytes)
    }
    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary=File(file.path+".new")
        try {
            java.io.FileOutputStream(temporary).use { stream -> stream.write(bytes);stream.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(),file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch(e: Exception) { if(temporary.isFile) temporary.delete();throw e }
    }
    /** Only use off the UI thread, or at explicit startup/recovery barriers. */
    fun awaitIdle() { executor.submit {}.get() }
}
