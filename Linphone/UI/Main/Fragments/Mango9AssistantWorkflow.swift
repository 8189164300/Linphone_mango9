// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import WebKit

struct Mango9AssistantWorkflow: Identifiable {
    let raw: [String: Any]
    let plan: [String: Any]
    let steps: [[String: Any]]
    let blockers: [String]
    let missing: [String]
    let owner: Int
    let actor: Int?
    var id: String { (plan["definition"] as? [String: Any])?["name"] as? String ?? "Workflow" }
    init?(_ data: Data?) {
        guard let data, data.count <= 131072, let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              raw["version"] as? Int == 1, let owner = raw["ownerId"] as? Int, owner > 0,
              let plan = raw["plan"] as? [String: Any], plan["kind"] is String,
              let steps = raw["steps"] as? [[String: Any]], steps.count <= 100,
              let blockers = raw["blockers"] as? [String], let missing = raw["missingTopics"] as? [String], blockers.count <= 80, missing.count <= 80,
              steps.allSatisfy({ row in
                  guard row["id"] is String, row["label"] is String, let settings = row["settings"] as? [[String: Any]], settings.count <= 80 else { return false }
                  return settings.allSatisfy { $0["label"] is String && $0["value"] is String }
              }) else { return nil }
        if let buyer = plan["buyerId"] as? Int, buyer != owner { return nil }
        self.raw = raw; self.plan = plan; self.steps = steps; self.blockers = blockers; self.missing = missing; self.owner = owner; actor = raw["actorId"] as? Int
    }
    func canOpen(_ session: Mango9Session, agent: Bool) -> Bool {
        guard actor.map(String.init) == session.userId, plan["buyerId"] != nil || String(owner) == session.userId else { return false }
        if agent { return raw["canPrepareAgent"] as? Bool == true && plan["buyerId"] == nil && (plan["inbound"] as? [String: Any])?["mode"] as? String == "new" }
        return raw["canBuild"] as? Bool == true && plan["definition"] is [String: Any] && blockers.isEmpty && missing.isEmpty && (plan["question"] == nil || plan["question"] is NSNull)
    }
    func handoff(session: Mango9Session, agent: Bool) -> (url: URL, script: String)? {
        guard canOpen(session, agent: agent), let base = URL(string: session.crmApiBaseUrl), let host = base.host,
              base.scheme == "https", ["2.mango9.com", "us.mango9.com"].contains(host) else { return nil }
        let origin = "https://" + host
        let path = agent ? "/app/voiceai/wizard" : plan["buyerId"] == nil ? "/app/workflow/new" : "/app/buyer-workspaces/\(owner)/workflow/new"
        let input: [String: Any] = ["origin": origin, "url": path, "design": raw, "target": agent ? "agent" : "workflow"]
        guard let data = try? JSONSerialization.data(withJSONObject: input), let json = String(data: data, encoding: .utf8), let url = URL(string: origin + "/app/") else { return nil }
        return (url, Self.script.replacingOccurrences(of: "__HANDOFF_JSON__", with: json))
    }
    private static let script = #"""
(async function(){const h=__HANDOFF_JSON__;if(location.origin!==h.origin||location.pathname===h.url||window.__ephonixWorkflowOpening)return;window.__ephonixWorkflowOpening=true;
    const tell=(type,message)=>window.webkit.messageHandlers.Mango9Workflow.postMessage(JSON.stringify({type,message}));
    try{const r=await fetch('/app/api/v2/mobile/workflows/designer/review',{method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify(h.design.plan)});
      if(!r.ok)throw Error('Sign in to the matching CRM account, then tap Continue.');const result=await r.json(),f=result.success===true?result.data:null;
      if(f?.version!==1||f.ownerId!==h.design.ownerId||f.actorId!==h.design.actorId||(f.plan?.buyerId??null)!==(h.design.plan.buyerId??null))throw Error('This CRM session belongs to a different account. Sign in to the account used in this chat.');
      if(h.target==='workflow'){if(!f.canBuild||!f.plan.definition||f.plan.question||f.blockers?.length||f.missingTopics?.length)throw Error('This plan needs another review. Return to chat and check its settings.');
        sessionStorage.setItem('wfai_pending',JSON.stringify({...f.plan.definition,status:'draft',_supervisor_owner:f.ownerId,_supervisor_actor:f.actorId,_supervisor_setup:f.setupTasks||[]}));
      }else{if(!f.canPrepareAgent||f.plan.inbound?.mode!=='new'||f.plan.buyerId!==undefined)throw Error('Return to chat and finish the agent setup.');
        sessionStorage.setItem('mango9_inbound_pending',JSON.stringify({ownerId:f.ownerId,inbound:f.plan.inbound,answers:f.plan.answers}));}
      tell('opening','');location.assign(h.url);
    }catch(e){tell('error',e.message||'Could not open this draft.');window.__ephonixWorkflowOpening=false;}})();true;
"""#
}
struct Mango9AssistantWorkflowCard: View {
    let design: Mango9AssistantWorkflow
    @ObservedObject var model: Mango9SupervisorModel
    @State private var builder: Builder?
    private struct Builder: Identifiable { let id = UUID(); let url: URL; let script: String }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(design.id).font(.headline)
            ForEach(Array(design.steps.enumerated()), id: \.offset) { _, step in
                DisclosureGroup(step["label"] as? String ?? "Step") {
                    if let detail = step["detail"] as? String { Text(detail).font(.footnote).frame(maxWidth: .infinity, alignment: .leading) }
                    ForEach(Array((step["settings"] as? [[String: Any]] ?? []).enumerated()), id: \.offset) { _, field in
                        HStack(alignment: .top) { Text(field["label"] as? String ?? "").foregroundColor(.secondary); Spacer(); Text(field["value"] as? String ?? "").textSelection(.enabled) }.font(.caption)
                    }
                }.font(.subheadline)
            }
            ForEach(design.blockers + design.missing, id: \.self) { Text($0).font(.caption).foregroundColor(.secondary) }
            if let question = design.plan["question"] as? [String: Any], let text = question["text"] as? String {
                Text(text).font(.subheadline)
                ForEach(question["options"] as? [String] ?? [], id: \.self) { option in Button(option) { model.draft = option }.font(.subheadline) }
            }
            ForEach([false, true], id: \.self) { agent in
                if design.canOpen(model.api.original, agent: agent) {
                    Button { if model.api.active, let handoff = design.handoff(session: model.api.original, agent: agent) { builder = .init(url: handoff.url, script: handoff.script) } }
                    label: { Label(agent ? "Review agent setup" : "Review workflow draft", systemImage: "arrow.up.right.square") }.disabled(!model.authorized || !model.hasAIConsent)
                }
            }
        }.padding(14).background(Color.mango9Surface).cornerRadius(16)
            .sheet(item: $builder) { item in Mango9AssistantWorkflowBrowser(url: item.url, script: item.script, active: { model.api.active && model.hasAIConsent }) }
    }
}
struct Mango9AssistantWorkflowBrowser: View {
    let url: URL; let script: String; let active: () -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var web = WKWebView()
    @State private var error = "Sign in to the matching CRM account, then tap Continue to review this draft."
    var body: some View {
        NavigationView {
            VStack(spacing: 8) {
                Text(error).font(.caption).padding(.horizontal)
                Mango9AssistantWorkflowWeb(web: web, url: url, active: active, notice: { error = $0 })
                Button("Continue") { if active() { web.evaluateJavaScript(script) { _, failure in if failure != nil { error = "Sign in to the CRM, then try Continue again." } } } }.buttonStyle(.borderedProminent).padding(8)
            }.navigationTitle("Review draft").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Image(systemName: "chevron.left") }.accessibilityLabel("Back to chat") } }
        }.navigationViewStyle(.stack).tint(.mango9Primary)
    }
}
private struct Mango9AssistantWorkflowWeb: UIViewRepresentable {
    let web: WKWebView; let url: URL; let active: () -> Bool; let notice: (String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(host: url.host!, active: active, notice: notice) }
    func makeUIView(context: Context) -> WKWebView {
        web.navigationDelegate = context.coordinator
        web.configuration.userContentController.add(context.coordinator, name: "Mango9Workflow")
        web.load(URLRequest(url: url)); return web
    }
    func updateUIView(_ uiView: WKWebView, context: Context) { if !active() { uiView.stopLoading() } }
    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) { uiView.stopLoading(); uiView.configuration.userContentController.removeScriptMessageHandler(forName: "Mango9Workflow") }
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        let host: String; let active: () -> Bool; let notice: (String) -> Void
        init(host: String, active: @escaping () -> Bool, notice: @escaping (String) -> Void) { self.host = host; self.active = active; self.notice = notice }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard active(), let url = action.request.url, url.scheme == "https", [host, "account.mango9.com"].contains(url.host ?? ""), url.user == nil, url.password == nil,
                  !(url.path.removingPercentEncoding ?? url.path).lowercased().contains("/api/") else { decisionHandler(.cancel); return }
            decisionHandler(.allow)
        }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard active(), message.frameInfo.isMainFrame, message.frameInfo.securityOrigin.host == host,
                  let text = message.body as? String, let data = text.data(using: .utf8), let value = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return }
            notice(value["type"] == "opening" ? "Review and save your draft in the CRM." : String((value["message"] ?? "Could not open this draft.").prefix(500)))
        }
    }
}
