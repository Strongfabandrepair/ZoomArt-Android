package com.example.zoomart

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs

/**
 * v0.7.0 title page — the app's new entry point. Replaces MainActivity as
 * the launcher. Offers three paths:
 *
 *   • **Resume** — loads the most recent session (if one exists) and opens
 *     the canvas mid-drawing.
 *   • **Start New** — opens a fresh canvas.
 *   • **Settings** — opens [SettingsActivity] to configure brush defaults,
 *     auto-hide, autosave interval, etc. before entering the canvas.
 *
 * Visual language: dark glass panel centered on screen, spring show-in,
 * press-scale feedback on every button, ≥48dp touch targets. The canvas
 * (MainActivity) is launched with an intent extra telling it whether to
 * resume or start fresh.
 */
class TitleActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Centered glass card (mirrors FloatingPanel glass styling).
        val card = FrameLayout(this).apply {
            val p = resources.displayMetrics.density
            val pad = (p * 20).toInt()
            setPadding(pad, pad, pad, pad)
            background = GlassStyle.panelBackground(resources.displayMetrics.density)
            foreground = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = GlassStyle.CORNER_DP * p
                setStroke(maxOf(1, p.toInt()), GlassStyle.BORDER)
            }
            elevation = GlassStyle.ELEVATION_DP * p
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, width, height, GlassStyle.CORNER_DP * p)
                }
            }
            val lp = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.82f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            layoutParams = lp
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setGravity(Gravity.CENTER)
        }

        val title = TextView(this).apply {
            text = "ZoomArt"
            textSize = 40f
            setTextColor(GlassStyle.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (resources.displayMetrics.density * 16).toInt())
        }
        col.addView(title)

        val subtitle = TextView(this).apply {
            text = "Infinite zoom canvas"
            textSize = 14f
            setTextColor(GlassStyle.TEXT_SECONDARY)
            setPadding(0, 0, 0, (resources.displayMetrics.density * 32).toInt())
        }
        col.addView(subtitle)

        val resumeBtn = glassButton("Resume Project") {
            openResume()
        }
        val newBtn = glassButton("Start New") {
            openCanvas(resume = false)
        }
        val settingsBtn = glassButton("Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        for (b in listOf(resumeBtn, newBtn, settingsBtn)) {
            col.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (resources.displayMetrics.density * 10).toInt() })
        }

        // Disable resume when there's nothing to resume.
        resumeBtn.isEnabled = AppSettings.hasResumeableSession(this)
        resumeBtn.alpha = if (resumeBtn.isEnabled) 1f else 0.5f

        card.addView(col)
        root.addView(card)
        setContentView(root)

        // Spring the card in on show.
        mainHandler.postDelayed({
            card.alpha = 0f
            card.scaleX = 0.92f
            card.scaleY = 0.92f
            card.alpha = 1f
            Springs.springScale(card, 1f)
        }, 60L)
    }

    private fun glassButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            GlassStyle.button(this)
            Springs.attachPressFeedback(this)
            setOnClickListener { onClick() }
            minHeight = (resources.displayMetrics.density * 48).toInt()
            setAllCaps(false)
        }

    private fun openResume() {
        // Load the existing session then hand off to the canvas.
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("zoomart.mode", "resume")
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun openCanvas(resume: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("zoomart.mode", if (resume) "resume" else "new")
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    @SuppressLint("SetTextI18n")
    private fun toast(msg: String?) {
        Toast.makeText(this, msg ?: "", Toast.LENGTH_SHORT).show()
    }
}
