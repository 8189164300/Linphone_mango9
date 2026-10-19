/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.adapter

import android.content.Context
import androidx.appcompat.widget.AppCompatImageView
import coil3.asDrawable
import org.linphone.mango9.Mango9ChatMedia

/** Detached/recycled previews hold neither an image nor an in-flight request. */
internal class Mango9ThumbnailView(context: Context) : AppCompatImageView(context) {
    private var owner: Mango9ThumbnailLoader? = null
    private var media: Mango9ChatMedia? = null
    private var subscription: AutoCloseable? = null

    fun bind(value: Mango9ChatMedia?, loader: Mango9ThumbnailLoader?) {
        if (value == media && owner === loader) return
        clear()
        media = value
        owner = loader
        start()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }

    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) { clear(); start() }
    }

    private fun start() {
        val item = media ?: return
        val loader = owner ?: return
        if (!isAttachedToWindow || width <= 0 || height <= 0 || subscription != null) return
        subscription = loader.observe(item, width, height) { image ->
            setImageDrawable(image?.asDrawable(resources))
        }
    }

    private fun clear() {
        subscription?.close()
        subscription = null
        setImageDrawable(null)
    }
}
