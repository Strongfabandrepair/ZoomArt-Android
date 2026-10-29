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
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

/** Base and legacy action colors. Current orbital identity is documented in BRAND_GUIDE.md. */
object Brand {
    /** Deep navy welcome/settings base; the drawing canvas remains separate. */
    const val BACKGROUND = 0xFF111929.toInt()
    /** Inner-ring blue (#2E66F6). */
    const val BLUE = 0xFF2E66F6.toInt()
    /** Pressed/active blue (#1E4FB8). */
    const val BLUE_DARK = 0xFF1E4FB8.toInt()
    /** Outer ring / text white (#FFFFFF). */
    const val WHITE = 0xFFFFFFFF.toInt()
}

/** Shared orbital surfaces, readable text and amber selected-tool feedback.
 * The pale Atelier variant keeps brush previews distinct from dark control surfaces.
 * See docs/BRAND_GUIDE.md for the current identity; older token names remain compatible.
 */
object GlassStyle {

    // ----- neutral chrome (measured: icons.jpg bg #222127, crisp opaque) -----

    /** Near-opaque neutral panel fill: #16171B @ 90% (crisp, not frosted). */
    const val SCRIM = 0xE616171B.toInt()

    /** Chip/border hairline: white @ 12% — neutral, not blue. */
    const val BORDER = 0x1FFFFFFF.toInt()

    /** Primary light text on the dark chrome. */
    const val TEXT_PRIMARY = 0xFFECEDEF.toInt()

    /** Secondary (label/hint) text. */
    const val TEXT_SECONDARY = 0xFF98A0A8.toInt()

    /** Panel/dock button fill: solid quiet gray (wheel.jpg chrome family). */
    const val BUTTON_FILL = 0xFF303346.toInt()

    /** Active button fill: deep amber (fills use the darker measured variant). */
    const val BUTTON_ACTIVE = 0xFFE0A000.toInt()

    /** Amber accent #FFAD01 — selected/active states, thumbs, level number
     *  (measured exact from the brushes2.jpg reference). */
    const val ACCENT = 0xFFFFAD01.toInt()

    /** Deep amber #E0A000 — filled active chips (12% darker, for contrast). */
    const val ACCENT_DEEP = 0xFFE0A000.toInt()

    // ----- light reading panels (measured: brushes.jpg rows #F0F0F0/#FCFCFC) -----

    /** Light panel fill (brush panel shell). */
    const val LIGHT_PANEL = 0xFFF2F2F2.toInt()

    /** Light row card inside a light panel. */
    const val LIGHT_ROW = 0xFFFAFAFA.toInt()

    /** Ink on light panels (measured #1E1E1E). */
    const val LIGHT_INK = 0xFF1E1E1E.toInt()

    /** Secondary ink on light panels. */
    const val LIGHT_INK_SECONDARY = 0xFF6E6E6E.toInt()

    /** 1px rule on light panels: ink @ 10%. */
    const val LIGHT_BORDER = 0x1A1E1E1E.toInt()

    /** Corner radius: compact/crisp (was 28f). */
    const val CORNER_DP = 12f

    /** Panel elevation: flat + quiet (was 12f). */
    const val ELEVATION_DP = 6f

    /** Backdrop blur radius: a subtle depth cue only (was 12dp) — the
     *  reference chrome is crisp; the machinery is kept, the prominence
     *  reduced (spec section 2). */
    const val BLUR_DP = 5f

    /** Neutral rounded panel background: near-opaque fill + 1px hairline. */
    fun panelBackground(density: Float): GradientDrawable =
        GradientDrawable().apply {
            orientation=GradientDrawable.Orientation.TL_BR
            colors=intArrayOf(0xF521293C.toInt(),0xF5332945.toInt(),0xF51C343B.toInt())
            cornerRadius = CORNER_DP * density
            setStroke(maxOf(1, (density).toInt()), BORDER)
        }

    /** Light variant for reading panels (brush list) — same shape, light ink. */
    fun lightPanelBackground(density: Float): GradientDrawable =
        GradientDrawable().apply {
            orientation=GradientDrawable.Orientation.TL_BR
            colors=intArrayOf(0xFFF4EEF9.toInt(),0xFFF8F4F0.toInt(),0xFFEAF5F2.toInt())
            cornerRadius = CORNER_DP * density
            setStroke(maxOf(1, (density).toInt()), LIGHT_BORDER)
        }

    /** Small rounded chip background (HUD, section labels). */
    fun chipBackground(density: Float): GradientDrawable =
        GradientDrawable().apply {
            orientation=GradientDrawable.Orientation.TL_BR
            colors=intArrayOf(0xF521293C.toInt(),0xF5332945.toInt(),0xF51C343B.toInt())
            cornerRadius = CORNER_DP * 0.5f * density
            setStroke(maxOf(1, (density).toInt()), BORDER)
        }

    /** The drag handle's grip pill (a short neutral bar). */
    fun gripBackground(density: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(0x42FFFFFF)
            cornerRadius = 1.5f * density
        }

    /** Neutral restyle for a text button (48dp+ targets preserved). */
    fun button(b: Button) {
        b.backgroundTintList = ColorStateList.valueOf(BUTTON_FILL)
        b.setTextColor(TEXT_PRIMARY)
        b.isAllCaps = false
        b.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Line-icon button restyle: TRANSPARENT background (the strip is one
     * bar, not per-button chips), neutral stroke icon; [active] tints the
     * icon amber. Press feedback stays in Springs.
     */
    fun iconButton(b: ImageButton, active: Boolean = false) {
        b.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        b.setColorFilter(if (active) ACCENT else TEXT_PRIMARY)
        b.scaleType = ImageView.ScaleType.CENTER_INSIDE
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
            if (android.provider.Settings.System.getInt(context.contentResolver,
                    android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
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
            if (android.provider.Settings.System.getInt(context.contentResolver,
                    android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
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
