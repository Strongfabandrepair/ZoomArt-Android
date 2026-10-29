package com.example.zoomart.model

import java.io.*
import java.util.UUID
import java.util.zip.*

/** Local checkpoints, recoverable trash and bounded portable archives. All methods run on a worker. */
class ProjectLibrary(private val files: File) {
    private val home=File(files,"project_library").apply { mkdirs() }
    private val trash=File(home,"trash").apply { mkdirs() }
    data class Entry(val file: File,val name: String,val recovery: Boolean=false)
    fun entries(deleted: Boolean=false): List<Entry> {
        val owned=(if(deleted) trash else home).listFiles().orEmpty().filter { it.extension=="json" }
            .map { Entry(it,File(it.path+".name").takeIf { n -> n.isFile }?.readText()?.take(80) ?: "Untitled drawing") }
        val backups=if(deleted) emptyList() else File(files,"project_backups").listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".bak")) }
            .map { Entry(it,"Recovered · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified())),true) }
        return (owned+backups).sortedByDescending { it.file.lastModified() }
    }
    fun read(entry: Entry): DocumentCodec.SessionData {
        require(entry.file.canonicalFile.parentFile in listOf(home.canonicalFile,trash.canonicalFile,File(files,"project_backups").canonicalFile))
        require(entry.file.length() <= 64L*1024*1024) { "Project exceeds size limit" }
        return DocumentCodec.fromJson(android.util.AtomicFile(entry.file).openRead().bufferedReader().use { it.readText() })
    }
    fun save(name: String, snapshot: DocumentCodec.SessionData): Entry {
        val file=File(home,UUID.randomUUID().toString()+".json")
        SessionWriter.write(file,snapshot)
        val entry=Entry(file,name.trim().take(80).ifEmpty { "Untitled drawing" })
        try { rename(entry,entry.name); return entry }
        catch(e: Exception) { android.util.AtomicFile(file).delete(); throw e }
    }
    fun rename(entry: Entry, name: String) {
        read(entry)
        SessionWriter.writeBytes(File(entry.file.path+".name"),name.trim().take(80).ifEmpty { "Untitled drawing" }.toByteArray())
    }
    fun moveToTrash(entry: Entry, restore: Boolean=false) {
        read(entry)
        val destination=File(if(restore) home else trash,entry.file.name)
        check(!destination.exists() && entry.file.renameTo(destination)) { "Could not move project" }
        val name=File(entry.file.path+".name")
        if(name.exists()) check(name.renameTo(File(destination.path+".name"))) { "Project moved but name could not be moved" }
    }
    fun archive(entry: Entry, output: OutputStream) {
        val session=read(entry)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("document.json"))
            zip.write(DocumentCodec.toJson(session.document,session.camera).toByteArray()); zip.closeEntry()
            for(path in session.document.layers.flatMap { it.images }.map { it.path }.distinct()) {
                val asset=File(files,path).canonicalFile
                require(asset.toPath().startsWith(files.canonicalFile.toPath()) && asset.isFile) { "Missing project image: $path" }
                zip.putNextEntry(ZipEntry("assets/$path")); asset.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    fun importArchive(input: InputStream, name: String): Entry {
        val id=UUID.randomUUID().toString()
        val staging=File(home,".import-$id").apply { mkdirs() }
        val installed=File(files,"imports/$id")
        var committed=false
        try {
            var total=0L; var count=0
            val names=hashSetOf<String>()
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(++count<=10000 && names.add(entry.name)) { "Too many or duplicate archive entries" }
                    val path=entry.name
                    require(!entry.isDirectory && (path=="document.json" || path.startsWith("assets/")) &&
                        !path.contains('\\') && !path.contains(':') && path.split('/').none { it==".." || it.isEmpty() }) { "Unsafe archive path" }
                    val destination=File(staging,path).canonicalFile
                    require(destination.toPath().startsWith(staging.canonicalFile.toPath()))
                    destination.parentFile!!.mkdirs()
                    destination.outputStream().use { out ->
                        val buffer=ByteArray(32768); var size=0L
                        while(true) { val n=zip.read(buffer); if(n<0) break
                            size+=n; total+=n
                            require(size<=64L*1024*1024 && total<=256L*1024*1024) { "Archive exceeds size limit" }
                            out.write(buffer,0,n)
                        }
                    }
                }
            }
            val session=DocumentCodec.fromJson(File(staging,"document.json").readText())
            val assets=File(staging,"assets").apply { mkdirs() }
            for(layer in session.document.layers) for(i in layer.images.indices) {
                val old=layer.images[i]
                require(File(assets,old.path).isFile) { "Missing archived image" }
                layer.images[i]=PlacedImage("imports/$id/"+old.path,old.x,old.y,old.w,old.h,old.levelId,old.alpha,old.locked,old.rotation).also { it.flipH=old.flipH }
            }
            installed.parentFile!!.mkdirs()
            check(assets.renameTo(installed)) { "Could not install image assets" }
            val entry=save(name,session); committed=true; return entry
        } finally {
            // Both roots are generated children of app-private storage; never user-supplied paths.
            check(staging.canonicalFile.parentFile == home.canonicalFile)
            check(installed.canonicalFile.parentFile == File(files,"imports").canonicalFile)
            staging.deleteRecursively()
            if(!committed) installed.deleteRecursively()
        }
    }
}
