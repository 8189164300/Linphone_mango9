// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.*
import androidx.navigation.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.linphone.R
import org.linphone.mango9.*
import org.linphone.mango9.Mango9OpportunityPresentation as P

/** Lightweight activity links. Native chats and authoritative record APIs own detail loading. */
internal class Mango9CrmActivityUI(
    private val context: Context, private val scope: CoroutineScope, private val lease: Mango9SessionLease, private val page: (String, LinearLayout) -> Mango9FullPage, private val opportunity: (Int) -> Unit,
    private val beforeChat: () -> Unit = {
    }
){
 private val api = Mango9Opportunities(context)
 private val sessions = Mango9SessionStore(context)

 private fun current() = sessions.currentSession(lease) != null

 private fun dp(v: Int) = (context.resources.displayMetrics.density * v).toInt()

 private fun column() = LinearLayout(context).apply{
     orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
 }

 private fun text(v: String, small: Boolean = false) = TextView(context).apply{
     text = v; textSize = if (small)Mango9CrmText.label else Mango9CrmText.value; setTextColor(Color.rgb(38, 58, 82)); setPadding(0, dp(4), 0, dp(4)); hyphenationFrequency = 0
 }

 private fun button(title: String, action: (View) -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply{
     text = title; contentDescription = title; isAllCaps = false; textSize = Mango9CrmText.value; setTextColor(Color.rgb(66, 83, 199)); setOnClickListener{
         if (current())action(it)
     }
 }

 private val labels =
     mapOf("fast" to "Recent activity", "all" to "All activity", "note" to "Notes", "email" to "Email", "sms" to "SMS", "calls" to "Calls", "document" to "Quotes & invoices", "activity" to "Tasks & appointments", "opportunity" to "Opportunities", "social" to "Social DMs", "live" to "Live Chat")

 fun section(contactId: Int, collapsed: Boolean = false): View {
  val root = column(); val body = column(); var expanded = !collapsed; var source = "fast"; var cursor: String? = null; var generation = 0; var busy = false; var loadedSource = ""; var job: Job? = null
  val picker = button("Recent activity ▾"){
      }; val entries = column(); val status = column(); body.addView(picker); body.addView(entries); body.addView(status); body.addView(text("Calls load separately when selected.", true)); body.visibility = if (expanded)View.VISIBLE else View.GONE

  fun load(reset: Boolean) {
   if (!reset && busy)return
   val seq = ++generation; job?.cancel(); busy = true; status.removeAllViews(); status.addView(ProgressBar(context)); if (loadedSource != source){
       entries.removeAllViews(); cursor = null
   }
   job = scope.launch{
       try {
    val query = mutableMapOf("source" to source); if (!reset)cursor?.let{query["cursor"] = it}
    val data = api.request(lease, query = query, mobilePath = "contacts/$contactId/activity")
    if (!current() || seq != generation)return@launch
    require(data.optInt("version") == 1); val rows = data.getJSONArray("items").opportunityObjects(); require(
        rows.all{
        it.optString("key").isNotBlank()
    }
    )
    if (reset)entries.removeAllViews()
    rows.forEach{r ->
        if (entries.findViewWithTag<View>(r.getString("key")) == null){
        val item = column(); item.tag = r.getString("key"); val t = Mango9CrmActivityTarget.parse(r.optJSONObject("open")); val heading = text(P.text(r, "title")).apply{
            setTypeface(typeface, Typeface.BOLD)
        }; item.addView(heading); P.text(r, "summary").takeIf{
            it.isNotBlank()
        }?.let{
            item.addView(
                text(it).apply{
                maxLines = 2
            }
            )
        }; item.addView(
            text(
                listOf(P.text(r, "actor"), P.date(java.time.Instant.ofEpochMilli((r.optDouble("at") * 1000).toLong()).toString(), true)).filter{
            it.isNotBlank()
        }.joinToString(" · "),
            true
            )
        ); if (t != null){
            item.isClickable = true; item.isFocusable = true; item.contentDescription = P.text(r, "title") + ", open"; item.setOnClickListener{
                if (current())open(t, it)
            }
        }; entries.addView(item)
    }
    }
    cursor = data.optString("next").takeIf{it.isNotBlank() && it != "null"}; loadedSource = source; status.removeAllViews()
    val sources = data.getJSONArray("sources"); val ids = (0 until sources.length()).map{
        sources.getString(it)
    }.filter{labels.containsKey(it)}
    picker.text = (labels[source] ?: source) + " ▾"; picker.setOnClickListener{
        MaterialAlertDialogBuilder(context).setTitle("Show activity").setItems(
            ids.map{
            labels[it]
        }.toTypedArray()
        ){_, n -> source = ids[n]; load(true)}.show()
    }
    data.optJSONArray("warnings")?.let{w -> for (i in 0 until w.length())status.addView(text(w.getString(i), true))}
    if (entries.childCount == 0)status.addView(text("No activity to show.", true))
    if (cursor != null)status.addView(button("Load more"){load(false)})
   }catch (e: Exception){
       if (e is CancellationException)throw e; if (current() && seq == generation){
           status.removeAllViews(); status.addView(text(e.message ?: "Activity unavailable.")); status.addView(
               button("Retry"){
               load(true)
           }
           )
       }
   }finally {if (seq == generation)busy = false}
   }
  }
  if (collapsed){
      root.addView(
          button("Activity  ▾"){
      expanded = !expanded; body.visibility = if (expanded)View.VISIBLE else View.GONE; if (expanded)load(true)
  }
      )
  }
  root.addView(body); if (expanded)load(true)
  root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v: View) {
      }

  override fun onViewDetachedFromWindow(v: View) {generation++; job?.cancel(); busy = false}})
  return root
 }

 fun documents(rows: JSONArray?): View {
     val root = column(); val items = rows?.opportunityObjects().orEmpty(); if (items.isNotEmpty())root.addView(text("Recent quotes & invoices")); items.forEach{d ->
         val t = Mango9CrmActivityTarget.parse(JSONObject().put("screen", d.optString("kind")).put("id", d.opt("id"))); if (t != null){
         root.addView(
             button(listOf(P.text(d, "number"), P.text(d, "status"), P.money(P.text(d, "total"), P.text(d, "currency"))).joinToString(" · ")){
         open(t, it)
     }
         )
     }
     }; return root
 }

 private fun recording(url: String, screen: Mango9FullPage): View {
  val root = column(); val state = text("", true); val play = button("▶ Play recording") {
      }; root.addView(play); root.addView(state)
  val audio = context.getSystemService(AudioManager::class.java)
  val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
  var player: MediaPlayer? = null; var route: Mango9MediaPlaybackRoute? = null; var owner: LifecycleOwner? = null
  lateinit var focus: AudioFocusRequest

  fun release() {
      val old = player; player = null; route?.close(); route = null; runCatching {
          old?.release()
      }; audio.abandonAudioFocusRequest(focus); play.text = "▶ Play recording"
  }
  focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener {
      if (it < 0) release()
  }.build()
  val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) release() }
  root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
   override fun onViewAttachedToWindow(v: View) {
       owner = v.findViewTreeLifecycleOwner(); owner?.lifecycle?.addObserver(observer)
   }

   override fun onViewDetachedFromWindow(v: View) { owner?.lifecycle?.removeObserver(observer); owner = null; release() }
  })
  screen.whenDismissed { release() }
  play.setOnClickListener {
   if (!current() || !screen.isShowing) return@setOnClickListener
   if (player != null) { release(); state.text = "Stopped"; return@setOnClickListener }
   if (audio.mode != AudioManager.MODE_NORMAL || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
       state.text = "Audio is in use. Try again after the call."; return@setOnClickListener
   }
   try {
    val uri = Uri.parse(url); require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
    val next = MediaPlayer(); player = next; next.setAudioAttributes(attributes); next.setDataSource(context, uri)
    next.setOnPreparedListener {
        if (player === it && current() && screen.isShowing) {
            route = Mango9MediaPlaybackRoute(context, it); it.start(); play.text = "■ Stop recording"; state.text = "Playing · ${it.duration / 1000} seconds"
        }
    }
    next.setOnCompletionListener { if (player === it) { release(); state.text = "Finished" } }
    next.setOnErrorListener { failed, _, _ ->
        if (player === failed) {
        release(); state.text = "Recording unavailable. Tap to retry."
    }; true
    }
    state.text = "Loading recording…"; play.text = "Cancel loading"; next.prepareAsync()
   } catch (_: Exception) { release(); state.text = "Recording unavailable. Tap to retry." }
  }
  return root
 }

 private fun open(target: Mango9CrmActivityTarget, anchor: View) {
  if (!current())return
  if (target.screen == "opportunity"){opportunity(target.id.toInt()); return}
  if (target.screen in setOf("sms", "social", "live")){
   if (!Mango9ChatTabPreferences(context).visible(target.screen)) {
       Toast.makeText(context, "This chat tab is disabled in your app settings.", Toast.LENGTH_LONG).show(); return
   }
   val args = Bundle().apply{
       Mango9NavigationSession.bind(this, lease); if (target.screen == "live"){
       putString("liveId", target.id)
   } else {
       putString(Mango9MessagingListFragment.ARG_TYPE, if (target.screen == "sms")Mango9MessagingListFragment.TYPE_SMS else Mango9MessagingListFragment.TYPE_SOCIAL); putInt(Mango9MessagingListFragment.ARG_USER_ID, 0); putString(Mango9MessagingListFragment.ARG_ROOM_ID, if (target.screen == "social")target.id else null); putString(Mango9MessagingListFragment.ARG_TARGET, target.title); putString(Mango9MessagingListFragment.ARG_PHONE, target.phone)
   }
   }
   beforeChat(); anchor.findNavController().navigate(if (target.screen == "live")R.id.action_global_mango9LiveChatFragment else R.id.action_global_mango9MessagingConversationFragment, args); return
  }
  val body = column(); val screen = page(target.title, body); var job: Job? = null

  fun load() {
      job?.cancel(); body.removeAllViews(); body.addView(ProgressBar(context)); job = scope.launch{
          try {
   val data = api.request(lease, query = if (target.screen == "activity")mapOf("key" to target.key)else emptyMap(), mobilePath = target.path)
   if (!current() || !screen.isShowing)return@launch
   body.removeAllViews()

   fun fact(label: String, v: String) {
       if (v.isNotBlank()){body.addView(text(label, true)); body.addView(text(v).apply{setTextIsSelectable(true)})}
   }
   if (target.screen == "activity"){
    require(data.getString("key") == target.key); val d = data.getJSONObject("data"); fact("", P.text(d, "title")); fact("Details", P.text(d, "text")); listOf("actor", "status", "duration", "cause", "start", "end", "outcome", "recipient", "note").forEach{
        fact(if (it == "duration")"Duration (seconds)"else it.replaceFirstChar(Char::uppercase), P.text(d, it))
    }; val url = P.text(d, "recording"); if (url.startsWith("https://")) body.addView(recording(url, screen))
   }else {
    val d = data.getJSONObject("document"); require(d.getString("id") == target.id); val currency = P.text(d, "currency")
    fact(target.title, P.text(d, if (target.screen == "quote")"quote_number"else "invoice_number")); fact("Status", P.text(d, "status")); fact("Customer", P.text(d, "bill_to_name")); fact("Issued", P.date(P.text(d, "issue_date"))); fact(if (target.screen == "quote")"Expires"else "Due", P.date(P.text(d, if (target.screen == "quote")"expiry_date"else "due_date")))
    listOf("subtotal", "discount_total", "tax_total", "grand_total", "balance_due").forEach{
        if (d.has(it))fact(it.replace('_', ' ').replaceFirstChar(Char::uppercase), P.money(P.text(d, it), currency))
    }
    data.getJSONArray("items").opportunityObjects().forEach{r -> fact("Item", P.text(r, "name")); fact("", P.text(r, "description")); fact("Quantity", P.text(r, "quantity")); fact("Amount", P.money(P.text(r, "line_total"), currency))}
    data.optJSONArray("payments")?.opportunityObjects()?.forEach{r -> fact("Payment", P.money(P.text(r, "amount"), currency)); fact("Paid", P.date(P.text(r, "paid_on"))); fact("Method", P.text(r, "method"))}
    fact("Notes", P.text(d, "notes")); fact("Terms", P.text(d, "terms"))
   }
  }catch (e: Exception){
      if (e is CancellationException)throw e; if (current() && screen.isShowing){
          body.removeAllViews(); body.addView(text(e.message ?: "Activity unavailable.")); body.addView(button("Retry"){load()})
      }
  }
      }
  }
  screen.whenDismissed{job?.cancel()}; load()
 }
}
