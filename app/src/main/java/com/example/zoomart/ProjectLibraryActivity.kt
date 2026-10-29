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
        super.onSaveInstanceState(out)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); library=ProjectLibrary(filesDir)
        deleted=state?.getBoolean("trash") ?: false
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
        worker.execute {
            val result=runCatching { library.entries(deleted) }
            runOnUiThread {
                if(isDestroyed) return@runOnUiThread
                list.removeAllViews()
                result.getOrDefault(emptyList()).forEach { entry ->
                    list.addView(Button(this).apply {
                        text=entry.name;isAllCaps=false;minHeight=(64*resources.displayMetrics.density).toInt()
                        setOnClickListener { options(entry) }
                    },LinearLayout.LayoutParams(-1,-2))
                }
                if(list.childCount==0) list.addView(TextView(this).apply { setText(R.string.no_projects);setTextColor(-1);textSize=16f })
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
    override fun onDestroy() { worker.shutdown();super.onDestroy() }
}
