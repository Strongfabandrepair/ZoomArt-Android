package com.example.zoomart

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Insets are added to the original padding, never accumulated on repeated dispatch. */
object WindowInsetsSupport {
    fun apply(view: View) {
        (view.context as? android.app.Activity)?.let { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window,false) }
        val left=view.paddingLeft;val top=view.paddingTop;val right=view.paddingRight;val bottom=view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v,insets ->
            val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(left+safe.left,top+safe.top,right+safe.right,bottom+safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
