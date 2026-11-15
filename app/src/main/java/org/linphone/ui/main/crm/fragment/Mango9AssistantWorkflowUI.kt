// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.fragment
import android.annotation.SuppressLint
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import org.linphone.mango9.*
import org.linphone.ui.main.crm.viewmodel.Mango9SupervisorViewModel
import java.net.URI

internal object Mango9AssistantWorkflowUI {
    fun valid(raw: JSONObject): Boolean = runCatching {
        require(raw.toString().toByteArray().size <= 131072 && raw.getInt("version") == 1 && raw.getInt("ownerId") > 0)
        val plan = raw.getJSONObject("plan"); require(plan.getString("kind").length <= 100)
        if (plan.has("buyerId")) require(plan.getInt("buyerId") == raw.getInt("ownerId"))
        val steps = raw.getJSONArray("steps"); require(steps.length() <= 100)
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i); require(step.getString("id").length <= 200 && step.getString("label").length <= 500)
            val fields = step.getJSONArray("settings"); require(fields.length() <= 80)
            for (j in 0 until fields.length()) {
                val f = fields.getJSONObject(j); require(f.getString("label").length <= 500 && f.getString("value").length <= 8000)
            }
        }
        for (key in listOf("blockers", "missingTopics")) {
            val a = raw.getJSONArray(key); require(a.length() <= 80); for (i in 0 until a.length()) require(a.get(i) is String)
        }
        true
    }.getOrDefault(false)

    fun view(host: Fragment, model: Mango9SupervisorViewModel, raw: JSONObject): View? {
        if (!valid(raw)) return null
        val lease = model.owner() ?: return null; val session = lease.session; val chat = model.conversation

        fun active() = model.owner() == lease && model.conversation == chat && model.accepted
        val ui = Mango9LiveViews(host.requireContext()); val box = ui.column().apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.round(0xffeef1ff.toInt(), 16)
        }
        val plan = raw.getJSONObject("plan")
        box.addView(ui.text(plan.optJSONObject("definition")?.optString("name") ?: "Workflow draft", 16f, true))
        Mango9SupervisorPresentation.objects(raw.getJSONArray("steps")).forEach { step ->
            val detail = ui.column().apply { visibility = View.GONE }
            detail.addView(ui.text(step.optString("detail"), 12f))
            Mango9SupervisorPresentation.objects(step.getJSONArray("settings")).forEach { field ->
                detail.addView(
                    ui.text(field.getString("label") + ": " + field.getString("value"), 12f).apply {
                setTextIsSelectable(true)
            }
                )
            }
            box.addView(
                ui.button(step.getString("label") + "  ›") {
                detail.visibility = if (detail.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            ); box.addView(detail)
        }
        for (key in listOf("blockers", "missingTopics")) {
            val a = raw.getJSONArray(key); for (i in 0 until a.length()) box.addView(ui.text(a.getString(i), 12f))
        }
        plan.optJSONObject("question")?.let { q ->
            box.addView(ui.text(q.optString("text"), 14f, true)); val options = q.optJSONArray("options")
            for (i in 0 until minOf(options?.length() ?: 0, 12)) {
                val option = options!!.optString(i); box.addView(ui.button(option) { model.draft = option; model.notifyChange() })
            }
        }
        val scope = raw.optInt("actorId").toString() == session.userId && (plan.has("buyerId") || raw.getInt("ownerId").toString() == session.userId)
        for (agent in listOf(false, true)) {
            val allowed = scope && if (agent) {
                raw.optBoolean("canPrepareAgent") && !plan.has("buyerId") && plan.optJSONObject("inbound")?.optString("mode") == "new"
            } else {
                raw.optBoolean("canBuild") && plan.optJSONObject("definition") != null && raw.getJSONArray("blockers").length() == 0 && raw.getJSONArray("missingTopics").length() == 0 && (plan.isNull("question") || !plan.has("question"))
            }
            if (allowed) {
                box.addView(
                    ui.button(if (agent) "Review agent setup  ↗" else "Review workflow draft  ↗") {
                if (active()) open(host, session, raw, agent, ::active)
            }
                )
            }
        }
        return box
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(host: Fragment, session: Mango9Session, raw: JSONObject, agent: Boolean, active: () -> Boolean) {
        val base = URI(session.crmApiBaseUrl); if (base.scheme != "https" || base.host !in setOf("2.mango9.com", "us.mango9.com")) return
        val origin = "https://" + base.host; val plan = raw.getJSONObject("plan")
        val path = if (agent) "/app/voiceai/wizard" else if (!plan.has("buyerId")) "/app/workflow/new" else "/app/buyer-workspaces/" + raw.getInt("ownerId") + "/workflow/new"
        val data = JSONObject().put("origin", origin).put("url", path).put("design", raw).put("target", if (agent) "agent" else "workflow")
        val ui = Mango9LiveViews(host.requireContext()); val layout = ui.column(); val notice = ui.text("Sign in to the matching CRM account, then tap Continue to review this draft.", 12f); layout.addView(notice)
        val web = WebView(host.requireContext()); web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true; web.settings.allowFileAccess = false; web.settings.allowContentAccess = false

        fun allowed(url: String) = runCatching {
            val u = URI(url); active() && u.scheme == "https" && u.host in setOf(base.host, "account.mango9.com") && u.userInfo == null && !u.path.lowercase().contains("/api/")
        }.getOrDefault(false)
        web.webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !allowed(request.url.toString()) }
        web.addJavascriptInterface(
            object { @JavascriptInterface fun postMessage(value: String) {
                web.post {
            if (active() && runCatching {
                URI(web.url).host == base.host
            }.getOrDefault(false)
            ) {
                runCatching {
                val m = JSONObject(value); notice.text = if (m.optString("type") == "opening") "Review and save your draft in the CRM." else m.optString("message").take(500)
            }
            }
        }
            } },
            "Mango9Workflow"
        )
        layout.addView(web, LinearLayout.LayoutParams(-1, (host.resources.displayMetrics.heightPixels * .65).toInt()))
        val script = SCRIPT.replace("__HANDOFF_JSON__", data.toString())
        val dialog = MaterialAlertDialogBuilder(host.requireContext()).setTitle("Review draft").setView(layout).setNegativeButton("Back", null).setPositiveButton("Continue", null).create()
        dialog.setOnDismissListener { web.stopLoading(); web.removeJavascriptInterface("Mango9Workflow"); web.destroy() }
        dialog.show(); dialog.getButton(-1).setOnClickListener {
            if (active() && allowed(web.url.orEmpty())) web.evaluateJavascript(script, null)
        }
        web.loadUrl(origin + "/app/")
    }

    private val SCRIPT = """
(async function(){const h=__HANDOFF_JSON__;if(location.origin!==h.origin||location.pathname===h.url||window.__ephonixWorkflowOpening)return;window.__ephonixWorkflowOpening=true;
    const tell=(type,message)=>window.Mango9Workflow.postMessage(JSON.stringify({type,message}));
    try{const r=await fetch('/app/api/v2/mobile/workflows/designer/review',{method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify(h.design.plan)});
      if(!r.ok)throw Error('Sign in to the matching CRM account, then tap Continue.');const result=await r.json(),f=result.success===true?result.data:null;
      if(f?.version!==1||f.ownerId!==h.design.ownerId||f.actorId!==h.design.actorId||(f.plan?.buyerId??null)!==(h.design.plan.buyerId??null))throw Error('This CRM session belongs to a different account. Sign in to the account used in this chat.');
      if(h.target==='workflow'){if(!f.canBuild||!f.plan.definition||f.plan.question||f.blockers?.length||f.missingTopics?.length)throw Error('This plan needs another review. Return to chat and check its settings.');
        sessionStorage.setItem('wfai_pending',JSON.stringify({...f.plan.definition,status:'draft',_supervisor_owner:f.ownerId,_supervisor_actor:f.actorId,_supervisor_setup:f.setupTasks||[]}));
      }else{if(!f.canPrepareAgent||f.plan.inbound?.mode!=='new'||f.plan.buyerId!==undefined)throw Error('Return to chat and finish the agent setup.');
        sessionStorage.setItem('mango9_inbound_pending',JSON.stringify({ownerId:f.ownerId,inbound:f.plan.inbound,answers:f.plan.answers}));}
      tell('opening','');location.assign(h.url);
    }catch(e){tell('error',e.message||'Could not open this draft.');window.__ephonixWorkflowOpening=false;}})();true;
""".trimIndent()
}
