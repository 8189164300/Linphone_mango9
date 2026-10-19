// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout

/** Address-only preview. Does not request device location or attach CRM credentials. */
@SuppressLint("SetJavaScriptEnabled")
internal class Mango9LeadMapView(context: Context, address: String) : LinearLayout(context) {
    private val maps = Uri.parse("https://maps.google.com/maps").buildUpon().appendQueryParameter("q", address).build()
    private val browser = WebView(context)

    init {
        orientation = VERTICAL
        browser.apply {
            contentDescription = "Map of $address"
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host in setOf("maps.google.com", "www.google.com")) return false
                    return true
                }
            }
            val source = maps.buildUpon().appendQueryParameter("output", "embed").build().toString().replace("&", "&amp;")
            loadDataWithBaseURL("https://maps.google.com/", """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="margin:0"><iframe title="Address map" src="$source" style="position:absolute;inset:0;width:100%;height:100%;border:0" referrerpolicy="no-referrer"></iframe></body></html>""", "text/html", "UTF-8", null)
        }
        addView(browser, LayoutParams(-1, 0, 1f))
        addView(
            Button(context).apply {
            text = "Open in Maps"; isAllCaps = false
            contentDescription = "Open $address in Maps"
            setOnClickListener {
                context.startActivity(Intent(Intent.ACTION_VIEW, maps))
            }
        },
            LayoutParams(-1, -2)
        )
    }

    override fun onDetachedFromWindow() {
        browser.stopLoading(); (browser.parent as? ViewGroup)?.removeView(browser); browser.destroy()
        super.onDetachedFromWindow()
    }
}
