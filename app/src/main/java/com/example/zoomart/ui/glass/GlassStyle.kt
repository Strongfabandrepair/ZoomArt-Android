package com.example.zoomart.ui.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

/** Brand palette (mirrors app/src/main/res/values/colors.xml). */
object Brand {
    /** Dark navy canvas background (#1A1C24). */
    const val BACKGROUND = 0xFF1A1C24.toInt()
    /** Inner-ring blue (#2E66F6). */
    const val BLUE = 0xFF2E66F6.toInt()
    /** Pressed/active blue (#1E4FB8). */
    const val BLUE_DARK = 0xFF1E4FB8.toInt()
    /** Outer ring / text white (#FFFFFF). */
    const val WHITE = 0xFFFFFFFF.toInt()
}

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

    /** Dock/panel button fill (dark translucent glass). */
    const val BUTTON_FILL = 0x802A2E37.toInt()   // 50% opaque glass

    /** Active button fill (brand blue translucent). */
    const val BUTTON_ACTIVE = 0x992E66F6.toInt()  // 60% opaque brand blue

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
        b.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    }

    /** Dark-mode restyle for an icon ImageButton: glass fill, white icon,
     *  brand-blue tint when active. [active] applies the brand-blue tint.
     *  Includes a subtle drop shadow layer so the button pops off glass. */
    fun iconButton(b: ImageButton, active: Boolean = false) {
        b.backgroundTintList = ColorStateList.valueOf(
            if (active) BUTTON_ACTIVE else BUTTON_FILL)
        b.setColorFilter(TEXT_PRIMARY)
        b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.setPadding((b.resources.displayMetrics.density * 6).toInt(), 0,
            (b.resources.displayMetrics.density * 6).toInt(), 0)
        b.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
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

    /** Trigger a short selection haptic tap (tool toggles, panel opens, etc). */
    fun selectionTap(context: Context) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(10)
            }
        } catch (_: Throwable) { /* haptics not available on this device */ }
    }

    /** Trigger a light press haptic (button down). */
    fun pressTap(context: Context) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(5)
            }
        } catch (_: Throwable) { /* haptics not available on this device */ }
    }
}
