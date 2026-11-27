// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

object Mango9CrmStatusColor {
 fun color(name: String, supplied: String = ""): Int {
  if (supplied.matches(Regex("#[a-fA-F0-9]{6}")))return Color.parseColor(supplied)
  val key = name.trim().lowercase()
  val known =
      mapOf("new" to "#718096", "new lead" to "#718096", "qualified" to "#2563EB", "proposal" to "#7C3AED", "negotiation" to "#B45309", "pending" to "#B45309", "won" to "#15803D", "active" to "#15803D", "client" to "#15803D", "lost" to "#B91C1C", "inactive" to "#B91C1C", "rejected" to "#B91C1C")
  val colors = listOf("#2563EB", "#7C3AED", "#0F766E", "#B45309", "#BE185D")
  val hash = key.fold(0){a, c -> (a * 31 + c.code) % colors.size}
  return Color.parseColor(known[key] ?: colors[hash])
 }

 fun apply(view: TextView, name: String, supplied: String = "", selected: Boolean = false) {
  val base = color(name, supplied); val scale = minOf(1.0, 165.0 / maxOf(Color.red(base), Color.green(base), Color.blue(base), 1))
  val ink = Color.rgb((Color.red(base) * scale).toInt(), (Color.green(base) * scale).toInt(), (Color.blue(base) * scale).toInt())
  val d = view.resources.displayMetrics.density
  view.text = name.ifBlank {
      "No status"
  }; view.textSize = 14f; view.setTypeface(view.typeface, Typeface.BOLD); view.setSingleLine(); view.maxWidth = (140 * d).toInt(); view.ellipsize = android.text.TextUtils.TruncateAt.END
  view.setTextColor(if (selected)Color.WHITE else ink)
  view.setPadding((10 * d).toInt(), (7 * d).toInt(), (10 * d).toInt(), (7 * d).toInt())
  view.background = GradientDrawable().apply{
      setColor(if (selected)ink else (base and 0x00ffffff) or 0x1c000000); cornerRadius = 20 * d
  }
 }
}
