// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R

/** One viewport-based latest-message control for all chat channels. */
internal object Mango9ChatNavigation {
    fun button(context: Context, action: () -> Unit) = ImageButton(context).apply {
        contentDescription = "Latest messages"
        setImageResource(R.drawable.caret_double_down)
        imageTintList = ColorStateList.valueOf(0xff4053c8.toInt())
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        val d = resources.displayMetrics.density
        setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        elevation = 3 * d; isVisible = false
        setOnClickListener { action() }
    }

    fun wrap(list: RecyclerView): FrameLayout {
        val host = FrameLayout(list.context)
        host.addView(list, FrameLayout.LayoutParams(-1, -1))
        val button = button(list.context) {
            val count = list.adapter?.itemCount ?: 0
            if (count > 0) list.scrollToPosition(count - 1)
        }
        val d = list.resources.displayMetrics.density
        host.addView(
            button,
            FrameLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = (12 * d).toInt(); bottomMargin = (12 * d).toInt()
        }
        )
        bind(list, button)
        return host
    }

    fun bind(list: RecyclerView, button: View) {
        fun update() { button.isVisible = list.canScrollVertically(1) }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = update()
        })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    }
}
