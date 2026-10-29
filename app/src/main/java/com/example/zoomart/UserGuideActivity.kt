package com.example.zoomart

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import com.example.zoomart.ui.glass.GlassStyle

/** Offline native reader. The build packages the same guide maintained in docs/. */
class UserGuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density=resources.displayMetrics.density
        fun dp(value: Int)=(value*density).toInt()
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(12),dp(16),dp(12))
            background=GlassStyle.panelBackground(density)
        }
        val toolbar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val back=Button(this).apply {
            setText(R.string.guide_back);GlassStyle.button(this);minHeight=dp(48)
            setOnClickListener { finish() }
        }
        toolbar.addView(back,LinearLayout.LayoutParams(-2,-2))
        val title=TextView(this).apply {
            setText(R.string.user_guide);textSize=22f;setTextColor(GlassStyle.TEXT_PRIMARY)
            setPadding(dp(12),0,0,0);ViewCompat.setAccessibilityHeading(this,true)
        }
        toolbar.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(toolbar,LinearLayout.LayoutParams(-1,-2))
        val topics=Button(this).apply {
            setText(R.string.guide_topics);GlassStyle.button(this);minHeight=dp(48)
        }
        root.addView(topics,LinearLayout.LayoutParams(-1,-2))
        val scroll=ScrollView(this).apply { id=R.id.user_guide_scroll;isFillViewport=true }
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(12),dp(12),dp(32))
        }
        val page=FrameLayout(this).apply {
            addView(body,FrameLayout.LayoutParams(
                minOf(dp(760),(resources.displayMetrics.widthPixels-dp(32)).coerceAtLeast(1)),
                -2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        scroll.addView(page,FrameLayout.LayoutParams(-1,-2))
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val headings=mutableListOf<Pair<String,View>>()
        fun paragraph(text: String,heading: Boolean=false) {
            val label=TextView(this).apply {
                this.text=if(heading) text else inlineText(text)
                textSize=if(heading) 22f else 16f
                setTextColor(GlassStyle.TEXT_PRIMARY)
                setLineSpacing(dp(4).toFloat(),1f)
                setPadding(0,dp(if(heading) 24 else 6),0,dp(8))
                if(heading) {
                    typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
                    ViewCompat.setAccessibilityHeading(this,true)
                } else setTextIsSelectable(true)
            }
            body.addView(label,LinearLayout.LayoutParams(-1,-2))
            if(heading) headings.add(text to label)
        }
        paragraph(getString(R.string.guide_intro))
        val buffer=StringBuilder()
        fun flush() { if(buffer.isNotBlank()) { paragraph(buffer.toString().trim());buffer.clear() } }
        assets.open("USER_GUIDE.md").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("# ") -> Unit
                    line.startsWith("## ") -> { flush();paragraph(line.removePrefix("## "),true) }
                    line.isBlank() -> flush()
                    line.startsWith("|") -> {
                        flush()
                        val cells=line.trim().trim('|').split('|').map { it.trim() }
                        if(!cells.all { it.matches(Regex("[-: ]+")) }) paragraph(cells.joinToString(" — "))
                    }
                    line.startsWith("- ") -> { flush();paragraph("• "+line.removePrefix("- ")) }
                    line.matches(Regex("^\\d+\\. .*")) -> { flush();paragraph(line) }
                    else -> { if(buffer.isNotEmpty()) buffer.append(' ');buffer.append(line) }
                }
            }
            flush()
        }
        topics.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.guide_topics)
                .setItems(headings.map { it.first }.toTypedArray()) { _,which ->
                    val target=headings[which].second
                    scroll.scrollTo(0,target.top)
                    target.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
                }.show()
        }
        setContentView(root)
        WindowInsetsSupport.apply(root)
    }

    private fun inlineText(source: String): CharSequence {
        // Repository links stay readable offline without offering broken web navigation.
        val plain=source.replace(Regex("\\[([^]]+)]\\([^)]+\\)"),"$1")
        val escaped=TextUtils.htmlEncode(plain)
        val html=escaped.replace(Regex("\\*\\*(.+?)\\*\\*"),"<b>$1</b>")
        return HtmlCompat.fromHtml(html,HtmlCompat.FROM_HTML_MODE_LEGACY)
    }
}
