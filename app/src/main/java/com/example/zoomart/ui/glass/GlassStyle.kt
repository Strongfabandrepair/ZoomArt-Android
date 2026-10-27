package com.example.zoomart.ui.glass

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * U1/U2 (UI overhaul): the design tokens + tiny styling helpers for the
 * Procreate-like dark glass language. One place owns every color so the
 * panels keep a consistent dark-mode contrast.
 */
object GlassStyle {

    /** Frosted panel scrim (the decision's rgba(22,24,29,0.72)). */
    const val SCRIM = 0xB816181D.toInt()          // 72% opaque #16181D

    /** Chip/border hairline rgba(255,255,255,0.08). */
    const val BORDER = 0x14FFFFFF.toInt()

    /** Primary light text on the dark scrim. */
    const val TEXT_PRIMARY = 0xFFE8EAEE.toInt()

    /** Secondary (label/hint) text. */
    const val TEXT_SECONDARY = 0xFF9AA3AF.toInt()

    /** Dock/panel button fill. */
    const val BUTTON_FILL = 0xFF2A2E37.toInt()

    /** Accent (active state) tint. */
    const val ACCENT = 0xFF2E66F6.toInt()

    /** Corner radius in dp (the decision's "~28px" corner, density-scaled). */
    const val CORNER_DP = 28f

    /** Panel elevation in dp (soft shadow under the floating shell). */
    const val ELEVATION_DP = 12f

    /** Rounded translucent panel background: scrim + 1px light hairline. */
    fun panelBackground(density: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(SCRIM)
            cornerRadius = CORNER_DP * density
            setStroke(maxOf(1, (density).toInt()), BORDER)
        }

    /** Small rounded chip background (HUD, section labels). */
    fun chipBackground(density: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(SCRIM)
            cornerRadius = CORNER_DP * 0.5f * density
            setStroke(maxOf(1, (density).toInt()), BORDER)
        }

    /** The drag handle's grip pill (a short light bar). */
    fun gripBackground(density: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(0x38FFFFFF)
            cornerRadius = 2f * density
        }

    /** Dark-mode restyle for a Material button (keeps 48dp+ targets). */
    fun button(b: Button) {
        b.backgroundTintList = ColorStateList.valueOf(BUTTON_FILL)
        b.setTextColor(TEXT_PRIMARY)
        b.isAllCaps = true
    }

    /** Light-on-dark restyle for a text view. */
    fun text(t: TextView, secondary: Boolean = false) {
        t.setTextColor(if (secondary) TEXT_SECONDARY else TEXT_PRIMARY)
    }

    /**
     * TRUE backdrop blur — View.setRenderEffect(RenderEffect.createBlurEffect)
     * — is API 31+ only, and it blurs the view's own content, not the canvas
     * behind it; the FloatingPanel therefore hosts a backdrop ImageView
     * (canvas snapshot) and blurs THAT. Tiering (documented in
     * docs/UI_OVERHAUL.md): API 31+ blurred snapshot, API 26-30 scrim-only.
     * Returns false when the platform/shadow pipeline can't take the effect
     * (Robolectric / exotic OEMs) — callers fall back to scrim-only glass.
     */
    fun applyBackdropBlur(view: View, radiusPx: Float): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
            true
        } catch (t: Throwable) {
            // unshadowed RenderNode / OEM RenderThread quirks -> scrim-only
            false
        }
    }

    /** Strips any previously applied backdrop blur (e.g. on tier change). */
    fun clearBackdropBlur(view: View) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                view.setRenderEffect(null)
            } catch (_: Throwable) {
            }
        }
    }

    /** Convenience: scrims a color's RGB channels toward white by [amount] 0..1. */
    fun towardWhite(color: Int, amount: Float): Int {
        val a = (amount.coerceIn(0f, 1f))
        val r = Color.red(color) + ((255 - Color.red(color)) * a).toInt()
        val g = Color.green(color) + ((255 - Color.green(color)) * a).toInt()
        val b = Color.blue(color) + ((255 - Color.blue(color)) * a).toInt()
        return Color.argb(255, r, g, b)
    }
}
