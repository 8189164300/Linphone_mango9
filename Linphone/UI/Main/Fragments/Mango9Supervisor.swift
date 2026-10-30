// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

private struct Mango9SupervisorPresentation: Identifiable {
    let id = UUID()
    let session: Mango9Session
}

struct Mango9SupervisorLauncher: View {
    @State private var presentation: Mango9SupervisorPresentation?
    var body: some View {
        Group {
            if Mango9SessionStore.load() != nil {
                Button {
                    if let session = Mango9SessionStore.load() { presentation = Mango9SupervisorPresentation(session: session) }
                } label: { Mango9SupervisorMark(working: false) }
                .buttonStyle(.plain)
                .accessibilityLabel("Open Supervisor")
                .accessibilityIdentifier("supervisor.open")
                .fullScreenCover(item: $presentation) { item in
                    Mango9SupervisorView(session: item.session)
                }
            }
        }
    }
}

struct Mango9SupervisorMark: View {
    var working: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var opening = true
    var body: some View {
        ZStack {
            Circle().fill(Color.white)
            Circle().strokeBorder(Color.mango9Primary.opacity(0.25), lineWidth: 1)
            if working || (opening && !reduceMotion) { ProgressView().tint(Color.mango9Primary) }
            else { Text("AI").font(.system(size: 17, weight: .medium, design: .rounded)).foregroundColor(.mango9Primary) }
        }.frame(width: 40, height: 40)
            .task { try? await Task.sleep(nanoseconds: 900_000_000); opening = false }
            .accessibilityHidden(true)
    }
}

struct Mango9SupervisorView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @StateObject private var model: Mango9SupervisorModel
    @FocusState private var focused: Bool
    @State private var showingHistory = false
    @State private var showingActions = false
    @State private var showingAIPrivacy = false
    @State private var confirmingDecision = false
    @State private var confirmingAction: Mango9SupervisorApproval?
    @State private var confirmingNewChat = false
    private var compact: Bool { verticalSizeClass == .compact }
    init(session: Mango9Session) { _model = StateObject(wrappedValue: Mango9SupervisorModel(session: session)) }

    var body: some View {
        VStack(spacing: 0) {
            header
            if model.showVoice {
                Mango9SupervisorVoicePanel(voice: model.voice, connect: { Task { await model.connectVoice() } }, end: model.endVoice)
            } else { chat }
        }
        .background(Color.gray100.ignoresSafeArea())
        // Match the blue app header's light status-bar content while keeping
        // message, voice and composer surfaces in the app's light appearance.
        .preferredColorScheme(.dark)
        .environment(\.colorScheme, .light)
        .task {
            await model.start()
            if model.authorized && !model.hasAIConsent { showingAIPrivacy = true }
        }
        .onDisappear { model.close() }
        .onChange(of: scenePhase) { phase in
            if phase == .background { focused = false; model.background() }
            else if phase == .active { Task { await model.refreshHistory() } }
        }
        .sheet(isPresented: $showingHistory) { historySheet.preferredColorScheme(.light) }
        .sheet(isPresented: $showingActions) { actionsSheet.preferredColorScheme(.light) }
        .sheet(isPresented: $showingAIPrivacy) {
            Mango9SupervisorConsentView(accepted: model.hasAIConsent,
                allow: { model.acceptAIConsent(); showingAIPrivacy = false },
                revoke: { model.revokeAIConsent(); showingAIPrivacy = false },
                close: { showingAIPrivacy = false })
        }
        .alert("Leave the unconfirmed request?", isPresented: $confirmingNewChat) {
            Button("Cancel", role: .cancel) {}
            Button("Start new chat") { Task { await model.leaveUnconfirmedRequest() } }
        } message: { Text("This does not cancel work already received by the server. Review Actions first. The previous request will not be sent again.") }
    }
    private var header: some View {
        HStack(spacing: 8) {
            Button { focused = false; model.close(); dismiss() } label: {
                Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold)).frame(width: 44, height: 44)
            }.accessibilityLabel("Close Supervisor").accessibilityIdentifier("supervisor.close")
            Text("Supervisor").font(.system(size: compact ? 18 : 20, weight: .semibold)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.85)
            Spacer(minLength: 0)
            if model.authorized {
                Button {
                    focused = false
                    if !model.hasAIConsent { showingAIPrivacy = true }
                    else if model.showVoice { model.endVoice() } else { Task { await model.startVoice() } }
                } label: {
                    Label(model.showVoice ? "Chat" : "Voice", systemImage: model.showVoice ? "message" : "mic")
                        .font(.system(size: 13, weight: .medium)).padding(.horizontal, 11).padding(.vertical, 8)
                        .background(Color.white.opacity(0.16)).clipShape(Capsule()).frame(minHeight: 44)
                }.disabled(model.hasAIConsent && !model.showVoice && !model.canStartVoice)
                    .accessibilityIdentifier("supervisor.mode")
            }
            Menu {
                Button { focused = false; showingAIPrivacy = true } label: { Label("AI privacy", systemImage: "hand.raised") }
                Button { focused = false; showingHistory = true; Task { try? await model.loadConversations() } } label: { Label("History", systemImage: "clock") }
                Button { focused = false; Task { await model.newConversation() } } label: { Label("New chat", systemImage: "square.and.pencil") }
                    .disabled(model.sending || model.pending != nil)
                Button { focused = false; showingActions = true; Task { await model.loadActions() } } label: { Label("Actions & activity", systemImage: "checklist") }
                Button { focused = false; model.close(); dismiss() } label: { Label("Close Supervisor", systemImage: "xmark") }
            } label: { Mango9SupervisorMark(working: model.loading || model.sending) }
                .accessibilityLabel("Supervisor options").accessibilityIdentifier("supervisor.options")
        }
        .foregroundColor(.white).padding(.horizontal, 8).frame(height: compact ? 46 : 56)
        .background(Color.mango9Primary.ignoresSafeArea(.container, edges: [.top, .leading, .trailing]))
    }
    private var chat: some View {
        VStack(spacing: 0) {
            if let error = model.error { notice(error) }
            if model.loading && model.messages.isEmpty { Spacer(); ProgressView("Loading Supervisor…").tint(.mango9Primary); Spacer() }
            else if !model.authorized {
                Spacer()
                Text("Connect with your Mango9 account to use Supervisor.").font(.subheadline).foregroundColor(.grayMain2c500).multilineTextAlignment(.center).padding()
                Button("Try again") { Task { await model.start() } }.foregroundColor(.mango9Primary)
                Spacer()
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 14) {
                            if model.messages.isEmpty && model.pending == nil {
                                Text("What can I help you with?").font(.title3.weight(.semibold)).foregroundColor(.grayMain2c800).padding(.top, 28)
                                Text("Ask about your leads, messages or tasks.").font(.subheadline).foregroundColor(.grayMain2c500)
                            }
                            ForEach(model.messages) { message in bubble(message) }
                            if let command = model.pending, !model.messages.contains(where: { $0.id == command.messageID }) {
                                HStack { Spacer(minLength: 36); Text(command.text).padding(12).background(Color.mango9Surface).clipShape(RoundedRectangle(cornerRadius: 18)) }
                            }
                            if model.sending { ProgressView("Thinking…").tint(.mango9Primary) }
                            if model.moreMessages { Button("Load more messages") { Task { await model.refreshHistory() } }.foregroundColor(.mango9Primary) }
                            Color.clear.frame(height: 1).id("supervisor.bottom")
                        }.padding(.horizontal, compact ? 20 : 14).padding(.vertical, 14)
                    }
                    .accessibilityIdentifier("supervisor.messages")
                    .onChange(of: model.messages.count) { _ in proxy.scrollTo("supervisor.bottom", anchor: .bottom) }
                    .onChange(of: model.sending) { _ in proxy.scrollTo("supervisor.bottom", anchor: .bottom) }
                }
            }
            if model.pending != nil && !model.sending {
                VStack(spacing: 6) {
                    Text("Request status is unconfirmed. It won’t be sent again automatically.").font(.caption).foregroundColor(.grayMain2c500)
                    HStack {
                        Button("Check status") { Task { await model.refreshHistory() } }
                        Spacer()
                        Button("Review actions") { showingActions = true; Task { await model.loadActions() } }
                    }.font(.subheadline).foregroundColor(.mango9Primary)
                    Button("Start new chat without retrying") { confirmingNewChat = true }.font(.caption).foregroundColor(.mango9Primary)
                }.padding(12).background(Color.white)
            }
            if model.hasAIConsent { composer }
            else {
                HStack(spacing: 10) {
                    Image(systemName: "hand.raised").foregroundColor(.mango9Primary)
                    Text("AI sharing is off").font(.subheadline).foregroundColor(.grayMain2c800)
                    Spacer(minLength: 8)
                    Button("Review") { focused = false; showingAIPrivacy = true }
                        .font(.subheadline.weight(.semibold)).foregroundColor(.mango9Primary).frame(minHeight: 44)
                        .accessibilityIdentifier("supervisor.consent.review")
                }.padding(.horizontal, 20).padding(.vertical, 6).background(Color.white)
            }
        }
    }
    private var composer: some View {
        VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 8) {
                Group {
                    if #available(iOS 16.0, *) {
                        TextField("Message Supervisor…", text: $model.draft, axis: .vertical).lineLimit(1...(compact ? 3 : 5))
                    } else { TextField("Message Supervisor…", text: $model.draft) }
                }.font(.system(size: 16)).focused($focused)
                    .padding(.horizontal, 14).padding(.vertical, compact ? 9 : 11).frame(minHeight: 44)
                    .background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 22))
                    .disabled(!model.authorized)
                    .accessibilityIdentifier("supervisor.composer")
                Button(action: send) {
                    Image(systemName: "arrow.up").font(.system(size: 18, weight: .semibold)).foregroundColor(.white)
                        .frame(width: 34, height: 34).background(model.canSend ? Color.mango9Primary : Color.gray300).clipShape(Circle()).frame(width: 44, height: 44)
                }.disabled(!model.canSend).accessibilityLabel("Send to Supervisor").accessibilityIdentifier("supervisor.send")
            }
            if model.draft.utf8.count > 8000 { Text("Please shorten your message before sending.").font(.caption).foregroundColor(.redDanger700) }
            if model.walletBilling { Text("Uses your Mango9 AI credit").font(.caption2).foregroundColor(.grayMain2c500).frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 14) }
        }.padding(.horizontal, 10).padding(.vertical, compact ? 3 : 8).background(Color.white.ignoresSafeArea(.container, edges: .bottom))
            .overlay(alignment: .top) { Color.gray200.frame(height: 1) }
    }
    private func send() { guard model.canSend else { return }; focused = false; Task { await model.send() } }
    private func bubble(_ message: Mango9SupervisorMessage) -> some View {
        let reply = Mango9SupervisorReplyFormatting.reply(message.text, baseURL: model.api.original.crmBaseUrl)
        return HStack(alignment: .bottom, spacing: 0) {
            if message.role == "user" { Spacer(minLength: compact ? 80 : 36) }
            VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 5) {
                VStack(alignment: .leading, spacing: 12) {
                    if message.role == "user" { Text(message.text).textSelection(.enabled) }
                    else {
                        if !reply.body.isEmpty {
                            Text((try? AttributedString(markdown: reply.body, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(reply.body)).textSelection(.enabled)
                        }
                        ForEach(reply.links) { link in
                            Link(destination: link.url) {
                                HStack(alignment: .top, spacing: 9) {
                                    Image(systemName: "arrow.up.right.square").font(.system(size: 17)).padding(.top, 2)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(link.title).font(.subheadline.weight(.medium))
                                        Text(link.url.absoluteString).font(.caption).fixedSize(horizontal: false, vertical: true)
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                }.foregroundColor(.mango9Primary).padding(10).background(Color.mango9Surface).cornerRadius(10)
                            }.accessibilityIdentifier("supervisor.link").accessibilityLabel(link.title + ", " + link.url.absoluteString)
                        }
                    }
                }.font(.system(size: 16)).foregroundColor(.grayMain2c800).tint(.mango9Primary)
                    .padding(12).background(message.role == "user" ? Color.mango9Surface : Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .contextMenu { Button { UIPasteboard.general.string = message.role == "assistant" ? reply.copyText : message.text } label: { Label("Copy", systemImage: "doc.on.doc") } }
                if let date = message.createdAt { Text(date, style: .time).font(.caption2).foregroundColor(.grayMain2c500).frame(maxWidth: .infinity, alignment: .trailing).padding(.horizontal, 4) }
            }
            if message.role != "user" { Spacer(minLength: compact ? 80 : 24) }
        }
    }
    private func notice(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) { Image(systemName: "exclamationmark.circle"); Text(text).fixedSize(horizontal: false, vertical: true) }
            .font(.footnote).foregroundColor(.redDanger700).frame(maxWidth: .infinity, alignment: .leading).padding(12).background(Color.redDanger200.opacity(0.25))
    }
    private var historySheet: some View {
        NavigationView {
            List {
                ForEach(model.conversations) { row in
                    Button(row.title) { showingHistory = false; Task { await model.select(row.id) } }.disabled(model.sending || model.pending != nil)
                }
                if model.moreConversations { Button("Load more") { Task { try? await model.loadConversations(more: true) } } }
                if model.conversations.isEmpty { Text("No saved conversations yet.").foregroundColor(.grayMain2c500) }
            }.navigationTitle("Supervisor history").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showingHistory = false } } }
        }.navigationViewStyle(.stack).tint(.mango9Primary)
    }
    private var actionsSheet: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let error = model.error { notice(error) }
                    if model.actionBusy { ProgressView() }
                    if let action = model.actionDetail {
                        Button("Back to actions") { model.actionDetail = nil }.foregroundColor(.mango9Primary)
                        Text(action["effect"] as? String ?? "Review the proposed changes.").font(.headline)
                        Text(action["state"] as? String ?? "Unknown").font(.caption).foregroundColor(.grayMain2c500)
                        if let review = action["review"] as? [String: Any] {
                            Text(Self.formatted(review)).font(.footnote).textSelection(.enabled)
                        }
                        if let arguments = action["arguments"], !(arguments is NSNull) { Text(Self.formatted(arguments)).font(.system(.footnote, design: .monospaced)).textSelection(.enabled) }
                        if action["state"] as? String == "PENDING_APPROVAL", action["summaryOnly"] as? Bool != true {
                            HStack {
                                Button("Reject", role: .destructive) { if let snapshot = Mango9SupervisorApproval(action) { Task { await model.decide("reject", expected: snapshot) } } }
                                Spacer()
                                Button("Approve") { confirmingAction = Mango9SupervisorApproval(action); confirmingDecision = confirmingAction != nil }.foregroundColor(.mango9Primary).disabled(!model.hasAIConsent)
                            }.disabled(model.actionBusy)
                        }
                        if let result = action["result"], !(result is NSNull) { Text(Self.formatted(result)).font(.footnote).textSelection(.enabled) }
                    } else {
                        ForEach(model.actions.indices, id: \.self) { index in
                            let action = model.actions[index]
                            Button { if let id = action["id"] as? String { Task { await model.reviewAction(id) } } } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(action["effect"] as? String ?? "Review action").foregroundColor(.black)
                                    Text(action["state"] as? String ?? "Unknown").font(.caption).foregroundColor(.grayMain2c500)
                                }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Color.gray100).cornerRadius(12)
                            }
                        }
                        if model.actions.isEmpty { Text("No actions to review.").foregroundColor(.grayMain2c500) }
                        if !model.activity.isEmpty {
                            Text("Recent activity").font(.headline)
                            ForEach(model.activity.indices, id: \.self) { index in
                                let item = model.activity[index]
                                Text(item["summary"] as? String ?? item["title"] as? String ?? item["status"] as? String ?? "Supervisor activity").font(.subheadline)
                            }
                        }
                    }
                }.padding()
            }.navigationTitle("Actions & activity").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Refresh") { Task { await model.loadActions() } } }
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { showingActions = false } }
                }
        }.navigationViewStyle(.stack).tint(.mango9Primary)
            .alert("Approve this action?", isPresented: $confirmingDecision) {
                Button("Cancel", role: .cancel) {}
                Button("Approve") { if let snapshot = confirmingAction { Task { await model.decide("approve", expected: snapshot) } } }
            } message: { Text("Only the exact action and values shown in this review will be approved.") }
    }
    private static func formatted(_ raw: Any) -> String {
        if let text = raw as? String { return text }
        guard JSONSerialization.isValidJSONObject(raw), let data = try? JSONSerialization.data(withJSONObject: raw, options: [.prettyPrinted, .sortedKeys]), let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }
}

private struct Mango9SupervisorVoicePanel: View {
    @ObservedObject var voice: Mango9SupervisorVoice
    let connect: () -> Void
    let end: () -> Void
    var body: some View {
        Mango9SupervisorVoiceScreen(isMuted: voice.muted, isConnected: voice.connected, isConnecting: voice.connecting,
            status: voice.status, level: voice.level, error: voice.error, connect: connect, mute: voice.setMuted, end: end)
    }
}

struct Mango9SupervisorVoiceScreen: View {
    let isMuted: Bool
    let isConnected: Bool
    let isConnecting: Bool
    let status: String
    let level: Double
    let error: String?
    let connect: () -> Void
    let mute: (Bool) -> Void
    let end: () -> Void
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    private var compact: Bool { verticalSizeClass == .compact }
    var body: some View {
        VStack(spacing: 0) {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 16) {
                    if let error { Text(error).font(.footnote).foregroundColor(.redDanger700).multilineTextAlignment(.center).padding(.horizontal, 24) }
                    Mango9SupervisorVoiceControl(isMuted: isMuted, isEnabled: isConnected, status: status, level: level,
                        diameter: min(compact ? 180 : 276, max(120, geometry.size.width - 48)), showsActions: false,
                        canConnect: !isConnected && !isConnecting, onConnect: connect, onMuteChange: mute, onEndVoice: end)
                }.frame(maxWidth: .infinity).frame(minHeight: max(0, geometry.size.height - 24)).padding(.vertical, 12)
            }
        }
        Mango9SupervisorVoiceActions(isMuted: isMuted, isEnabled: isConnected, onMuteChange: mute, onEndVoice: end)
            .padding(.horizontal, 20).padding(.vertical, compact ? 8 : 12)
            .background(Color.white.ignoresSafeArea(.container, edges: .bottom))
            .overlay(alignment: .top) { Color.gray200.frame(height: 1) }
        }.background(Color.gray100)
    }
}

/// Explicit, account-scoped permission before third-party AI processing.
private struct Mango9SupervisorConsentView: View {
    let accepted: Bool
    let allow: () -> Void
    let revoke: () -> Void
    let close: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Group {
            if #available(iOS 16.0, *) {
                content.presentationDetents([.height(530), .large]).presentationDragIndicator(.visible)
            } else { content }
        }.preferredColorScheme(.light)
    }
    private var content: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "hand.raised.fill").foregroundColor(.mango9Primary)
                Text(accepted ? "AI sharing is on" : "Use Supervisor AI?")
                    .font(.headline).foregroundColor(.grayMain2c800)
                Spacer(minLength: 8)
                Button(action: close) { Image(systemName: "xmark").frame(width: 44, height: 44) }
                    .foregroundColor(.grayMain2c500).accessibilityLabel("Close AI privacy")
            }.padding(.horizontal, 20).padding(.top, 16)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(Mango9SupervisorConsent.chatDisclosure)
                    Text(Mango9SupervisorConsent.voiceDisclosure)
                    Text("AI can make mistakes. Review replies and actions.")
                        .font(.subheadline.weight(.medium)).foregroundColor(.grayMain2c800)
                    Link("Privacy policy", destination: URL(string: "https://www.mango9.com/privacy")!)
                        .foregroundColor(.mango9Primary).frame(minHeight: 44, alignment: .leading)
                        .accessibilityIdentifier("supervisor.consent.policy")
                    DisclosureGroup("Your choices & saved history") {
                        Text("Permission is saved for this account in this app. Turn it off from AI → AI privacy at any time. This stops new AI sharing here; earlier requests may finish and saved conversations remain. For history deletion or privacy requests, contact privacy@mango9.com.")
                            .font(.footnote).padding(.top, 8)
                    }.tint(.mango9Primary)
                }.font(.subheadline).foregroundColor(.grayMain2c500)
                    .fixedSize(horizontal: false, vertical: true).padding(.horizontal, 20).padding(.bottom, 16)
            }
            Group {
                if typeSize.isAccessibilitySize { VStack(spacing: 10) { secondary; primary } }
                else { HStack(spacing: 12) { secondary; primary } }
            }.padding(20).background(Color.white)
        }.background(Color.white)
    }
    private var secondary: some View {
        Button(accepted ? "Done" : "Not now", action: close)
            .font(.body.weight(.medium)).foregroundColor(.mango9Primary)
            .frame(maxWidth: .infinity).frame(minHeight: 48)
            .background(Color.mango9Surface).clipShape(Capsule())
            .accessibilityIdentifier("supervisor.consent.decline")
    }
    private var primary: some View {
        Button(accepted ? "Turn off AI" : "Allow AI", action: accepted ? revoke : allow)
            .font(.body.weight(.semibold)).foregroundColor(.white)
            .frame(maxWidth: .infinity).frame(minHeight: 48)
            .background(accepted ? Color.redDanger700 : Color.mango9Primary).clipShape(Capsule())
            .accessibilityIdentifier(accepted ? "supervisor.consent.revoke" : "supervisor.consent.allow")
    }
}
