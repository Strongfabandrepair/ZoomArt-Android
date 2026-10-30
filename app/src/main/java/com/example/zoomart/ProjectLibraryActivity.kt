package com.example.zoomart

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import com.example.zoomart.model.*
import java.io.File
import java.util.concurrent.Executors

class ProjectLibraryActivity : AppCompatActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var library: ProjectLibrary
    private lateinit var list: LinearLayout
    private var deleted=false
    private var page=0
    private var query=""
    @Volatile private var generation=0
    private lateinit var thumbnails: com.example.zoomart.ui.ProjectThumbnails
    private var exporting: ProjectLibrary.Entry?=null
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) work { contentResolver.openInputStream(uri)!!.use { library.importArchive(it,getString(R.string.imported_project)) } }
    }
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val entry=exporting
        if(uri!=null && entry!=null) work { contentResolver.openOutputStream(uri,"wt")!!.use { library.archive(entry,it) } }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("trash",deleted);out.putString("exportPath",exporting?.file?.path)
        out.putInt("page",page);out.putString("query",query)
        super.onSaveInstanceState(out)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); library=ProjectLibrary(filesDir)
        thumbnails=com.example.zoomart.ui.ProjectThumbnails(this)
        deleted=state?.getBoolean("trash") ?: false
        page=state?.getInt("page") ?: 0;query=state?.getString("query") ?: ""
        exporting=state?.getString("exportPath")?.let { ProjectLibrary.Entry(File(it),"") }
        val density=resources.displayMetrics.density
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding((20*density).toInt(),(20*density).toInt(),(20*density).toInt(),0)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(TextView(this).apply { text=getString(R.string.project_library);textSize=28f;setTextColor(-1) })
        val actions=LinearLayout(this)
        fun action(label: Int, run: () -> Unit) { actions.addView(Button(this).apply {
            setText(label);isAllCaps=false;setOnClickListener { run() }
        },LinearLayout.LayoutParams(0,-2,1f)) }
        action(R.string.save_checkpoint) { nameDialog(getString(R.string.untitled_project)) { name -> work {
            SessionWriter.awaitIdle()
            val file=File(filesDir,"zoomart_session.json")
            val session=SessionWriter.latest(file) ?: DocumentCodec.fromJson(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            library.save(name,session)
        } } }
        action(R.string.import_project) { importFile.launch(arrayOf("application/zip","application/octet-stream","*/*")) }
        action(R.string.trash_projects) { deleted=!deleted;refresh() }
        root.addView(actions)
        root.addView(EditText(this).apply {
            hint=getString(R.string.search_projects); setTextColor(-1); setHintTextColor(0xFFCCCCCC.toInt())
            isSingleLine=true;setText(query)
            addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                    query=s.toString(); page=0; if(::list.isInitialized) refresh()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        },LinearLayout.LayoutParams(-1,-2))
        list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },LinearLayout.LayoutParams(-1,0,1f))
        val scene=FrameLayout(this).apply {
            addView(com.example.zoomart.ui.StudioBackdrop(this@ProjectLibraryActivity),FrameLayout.LayoutParams(-1,-1))
            addView(root,FrameLayout.LayoutParams(-1,-1))
        }
        setContentView(scene); WindowInsetsSupport.apply(root)
        refresh()
    }
    private fun nameDialog(name: String, save: (String) -> Unit) {
        val input=EditText(this).apply { setText(name);isSingleLine=true;selectAll();filters=arrayOf(android.text.InputFilter.LengthFilter(80)) }
        AlertDialog.Builder(this).setTitle(R.string.project_name).setView(input).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.save_checkpoint) { _,_ -> save(input.text.toString()) }.show()
    }
    private fun work(action: () -> Unit) {
        worker.execute { val error=runCatching(action).exceptionOrNull()
            runOnUiThread { if(!isDestroyed) { if(error!=null) Toast.makeText(this,error.message ?: getString(R.string.project_failed),Toast.LENGTH_LONG).show();refresh() } }
        }
    }
    private fun refresh() {
        val ticket=++generation
        val showDeleted=deleted; val filter=query; val requestedPage=page
        list.removeAllViews()
        list.addView(TextView(this).apply { setText(R.string.loading_previews); setTextColor(-1) })
        worker.execute {
            if(ticket!=generation || isDestroyed) return@execute
            val result=runCatching { library.entries(showDeleted).filter { it.name.contains(filter,ignoreCase=true) } }
            val entries=result.getOrDefault(emptyList())
            val currentPage=requestedPage.coerceIn(0,((entries.size-1)/24).coerceAtLeast(0))
            val previews=entries.drop(currentPage*24).take(24).map { entry ->
                entry to if(Thread.currentThread().isInterrupted || ticket!=generation) null else thumbnails.load(library,entry)
            }
            runOnUiThread {
                if(isDestroyed || ticket!=generation) return@runOnUiThread
                page=currentPage; list.removeAllViews()
                val density=resources.displayMetrics.density
                previews.forEach { (entry,preview) ->
                    val card=LinearLayout(this).apply {
                        orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL
                        setPadding((8*density).toInt(),(8*density).toInt(),(8*density).toInt(),(8*density).toInt())
                        isClickable=true; isFocusable=true; contentDescription=entry.name
                        setOnClickListener { options(entry) }
                        background=android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFAF7F3.toInt());cornerRadius=16*density }
                    }
                    card.addView(ImageView(this).apply {
                        scaleType=ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(0xFFEEEEEE.toInt())
                        if(preview!=null) setImageBitmap(preview) else setImageResource(R.drawable.ic_image)
                        contentDescription=getString(R.string.project_preview,entry.name)
                    },LinearLayout.LayoutParams((112*density).toInt(),(84*density).toInt()))
                    card.addView(TextView(this).apply {
                        text=entry.name+"\n"+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(entry.file.lastModified()))
                        textSize=16f; setTextColor(0xFF242635.toInt()); setPadding((12*density).toInt(),0,0,0)
                    },LinearLayout.LayoutParams(0,-2,1f))
                    list.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=(8*density).toInt() })
                }
                if(previews.isEmpty()) list.addView(TextView(this).apply {
                    text=if(result.isFailure) getString(R.string.project_failed) else getString(R.string.no_projects)
                    setTextColor(-1); textSize=16f
                })
                if(entries.size>24) {
                    val paging=LinearLayout(this)
                    paging.addView(Button(this).apply { setText(R.string.previous_page); isEnabled=page>0; setOnClickListener { page--;refresh() } },LinearLayout.LayoutParams(0,-2,1f))
                    paging.addView(Button(this).apply { setText(R.string.next_page); isEnabled=(page+1)*24<entries.size; setOnClickListener { page++;refresh() } },LinearLayout.LayoutParams(0,-2,1f))
                    list.addView(paging)
                }
            }
        }
    }
    private fun options(entry: ProjectLibrary.Entry) {
        if(deleted) { AlertDialog.Builder(this).setTitle(entry.name).setPositiveButton(R.string.restore_project) { _,_ -> work { library.moveToTrash(entry,true) } }.setNegativeButton(android.R.string.cancel,null).show();return }
        val labels=intArrayOf(R.string.open_project,R.string.rename_project,R.string.duplicate_project,R.string.export_project,R.string.trash_projects)
        AlertDialog.Builder(this).setTitle(entry.name).setItems(labels.map { getString(it) }.toTypedArray()) { _,which ->
            when(which) {
                0 -> { startActivity(Intent(this,MainActivity::class.java).putExtra("zoomart.project",entry.file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish() }
                1 -> nameDialog(entry.name) { work { library.rename(entry,it) } }
                2 -> work { library.save(entry.name+" · copy",library.read(entry)) }
                3 -> { exporting=entry;exportFile.launch(entry.name.replace(Regex("[^a-zA-Z0-9 _-]"),"_")+".zoomart") }
                4 -> AlertDialog.Builder(this).setMessage(R.string.trash_confirmation).setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.trash_projects) { _,_ -> work { library.moveToTrash(entry) } }.show()
            }
        }.show()
    }
    override fun onDestroy() { generation++; worker.shutdownNow();super.onDestroy() }
}
