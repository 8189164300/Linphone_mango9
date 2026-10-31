// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

private struct Mango9SupervisorPresentation: Identifiable {
    let id = UUID()
    let session: Mango9Session
}
private struct SupervisorCreatedTask: Identifiable { let id: Int }

enum Mango9SupervisorTypography {
    // One native text step below Body, retaining the user's Dynamic Type setting. Cards
    // retain their own semantic title, footnote and caption styles.
    static let message = Font.callout
}

enum Mango9SupervisorScrollTarget {
    static let empty = "supervisor.bottom"
    static let thinking = "supervisor.thinking"
    static func id(messages: [Mango9SupervisorMessage], pending: Mango9SupervisorCommand?, sending: Bool) -> String {
        if sending { return thinking }
        if let pending, !messages.contains(where: { $0.id == pending.messageID }) { return pending.messageID }
        return messages.last?.id ?? empty
    }
}

struct Mango9SupervisorMessageWindow {
    static let pageSize = 40
    private(set) var firstID: String?
    private func start(in messages: [Mango9SupervisorMessage]) -> Int {
        firstID.flatMap { id in messages.firstIndex(where: { $0.id == id }) }
            ?? max(0, messages.count - Self.pageSize)
    }
    func visible(in messages: [Mango9SupervisorMessage]) -> [Mango9SupervisorMessage] {
        Array(messages.dropFirst(start(in: messages)))
    }
    mutating func preserve(in messages: [Mango9SupervisorMessage]) {
        firstID = messages.dropFirst(start(in: messages)).first?.id
    }
    mutating func expand(in messages: [Mango9SupervisorMessage]) {
        firstID = messages.dropFirst(max(0, start(in: messages) - Self.pageSize)).first?.id
    }
}

struct Mango9SupervisorScrollLayout: Equatable {
    let id: String
    let frame: CGRect
    static func contentChanged(from prior: Self?, to current: Self?) -> Bool {
        prior?.id != current?.id || prior?.frame.size != current?.frame.size
    }
    static func isNearBottom(_ bottom: CGFloat, viewportHeight: CGFloat) -> Bool {
        guard bottom.isFinite, viewportHeight.isFinite, viewportHeight > 0 else { return false }
        return bottom >= max(0, viewportHeight - 40) && bottom <= viewportHeight + 40
    }
    static func needsAlignment(target: String, layout: Self?, viewportHeight: CGFloat) -> Bool {
        guard viewportHeight.isFinite, viewportHeight > 0 else { return false }
        guard let layout, layout.id == target, layout.frame.minY.isFinite,
              layout.frame.maxY.isFinite, layout.frame.width > 0, layout.frame.height > 0 else { return true }
        // The native bottom anchor includes the stack's footer and padding.
        // Keep that small natural gap instead of correcting it back and forth.
        return layout.frame.maxY < viewportHeight - 40 || layout.frame.maxY > viewportHeight + 1
    }
}

private struct SupervisorLatestRow: PreferenceKey {
    static var defaultValue: Mango9SupervisorScrollLayout? = nil
    static func reduce(value: inout Mango9SupervisorScrollLayout?, nextValue: () -> Mango9SupervisorScrollLayout?) {
        if let next = nextValue() { value = next }
    }
}

private struct SupervisorBottomPosition: PreferenceKey {
    static var defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

private extension View {
    func supervisorScrollTarget(_ id: String, latest: String) -> some View {
        self.id(id).background {
            if id == latest {
                GeometryReader { geometry in
                    Color.clear.preference(key: SupervisorLatestRow.self,
                        value: Mango9SupervisorScrollLayout(id: id, frame: geometry.frame(in: .named("supervisor.viewport"))))
                }
            }
        }
    }
    @ViewBuilder func supervisorInitialBottomAnchor() -> some View {
        if #available(iOS 18.0, *) { self.defaultScrollAnchor(.bottom, for: .initialOffset) }
        else if #available(iOS 17.0, *) { self.defaultScrollAnchor(.bottom) }
        else { self }
    }
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
    @State private var showingAIPrivacy = false
    @State private var confirmingNewChat = false
    @State private var historyQuery = ""
    @State private var historyFilter = Mango9SupervisorHistoryFilter.all
    @State private var deletingHistoryID: String?
    @State private var followingLatest = true
    @State private var draggingHistory = false
    @State private var waitingForUserScrollToSettle = false
    @State private var bottomPosition: CGFloat = .infinity
    @State private var messageWindow = Mango9SupervisorMessageWindow()
    @State private var latestLayout: Mango9SupervisorScrollLayout?
    @State private var scrollTask: Task<Void, Never>?
    @State private var createdTask: SupervisorCreatedTask?
    @Environment(\.sizeCategory) private var historyTextSize
    private var compact: Bool { verticalSizeClass == .compact }
    private var visibleMessages: [Mango9SupervisorMessage] { messageWindow.visible(in: model.messages) }
    private var latestScrollTarget: String { Mango9SupervisorScrollTarget.id(messages: model.messages, pending: model.pending, sending: model.sending) }
    private var latestMessageBottom: CGFloat? {
        guard let latestLayout, latestLayout.id == latestScrollTarget,
              latestLayout.frame.width > 0, latestLayout.frame.height > 0 else { return nil }
        return latestLayout.frame.maxY
    }
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
        .onDisappear { scrollTask?.cancel(); scrollTask = nil; model.close() }
        .onChange(of: scenePhase) { phase in
            if phase == .background { focused = false; draggingHistory = false; scrollTask?.cancel(); model.background() }
            else if phase == .active { draggingHistory = false; Task { await model.resume(latest: followingLatest) } }
        }
        .sheet(isPresented: $showingHistory) { historySheet.preferredColorScheme(.light) }
        .sheet(item: $createdTask) { task in
            if Mango9SessionStore.isActive(model.api.original) {
                Mango9ManagedTaskDetail(taskID: task.id, session: model.api.original).preferredColorScheme(.light)
            } else { Text("Reopen Supervisor in your current account.").padding() }
        }
        .sheet(isPresented: $showingAIPrivacy) {
            Mango9SupervisorConsentView(accepted: model.hasAIConsent,
                allow: { model.acceptAIConsent(); showingAIPrivacy = false },
                revoke: { model.revokeAIConsent(); showingAIPrivacy = false },
                close: { showingAIPrivacy = false })
        }
        .alert("Leave the unconfirmed request?", isPresented: $confirmingNewChat) {
            Button("Cancel", role: .cancel) {}
            Button("Start new chat") { Task { await model.leaveUnconfirmedRequest() } }
        } message: { Text("This does not cancel work already received by the server. Check the saved request status first. The previous request will not be sent again.") }
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
                Button { focused = false; showingHistory = true } label: { Label("History", systemImage: "clock") }
                Button { focused = false; Task { await model.newConversation() } } label: { Label("New chat", systemImage: "square.and.pencil") }
                    .disabled(model.sending || model.pending != nil)
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
            if let warning = model.localStorageNotice { Text(warning).font(.caption).foregroundColor(.grayMain2c500).padding(8) }
            if model.usingSavedHistory && !model.loading {
                HStack {
                    Label("Saved on this iPhone", systemImage: "iphone").font(.caption)
                    Spacer()
                    Button("Refresh") { Task { await model.resume() } }.font(.caption).disabled(model.loading)
                }.foregroundColor(.grayMain2c500).padding(.horizontal, 16).padding(.vertical, 6)
            }
            if model.loading && model.messages.isEmpty { Spacer(); ProgressView("Loading Supervisor…").tint(.mango9Primary); Spacer() }
            else if !model.authorized && model.messages.isEmpty {
                Spacer()
                Text("Connect with your Mango9 account to use Supervisor.").font(.subheadline).foregroundColor(.grayMain2c500).multilineTextAlignment(.center).padding()
                Button("Try again") { Task { await model.start() } }.foregroundColor(.mango9Primary)
                Spacer()
            } else {
                ScrollViewReader { proxy in
                  GeometryReader { viewport in
                    ScrollView {
                        // Measure a bounded page completely before positioning
                        // it. Lazy estimates for tall replies can otherwise leave
                        // a blank viewport until the user physically scrolls.
                        VStack(alignment: .leading, spacing: 14) {
                            if visibleMessages.count < model.messages.count || model.moreMessages {
                                Button(model.loadingOlderMessages ? "Loading…" : "Load older messages") {
                                    followingLatest = false
                                    waitingForUserScrollToSettle = false
                                    messageWindow.preserve(in: model.messages)
                                    let first = visibleMessages.first?.id
                                    let conversation = model.conversationID
                                    Task {
                                        if visibleMessages.count == model.messages.count { await model.loadOlderHistory() }
                                        guard conversation == model.conversationID else { return }
                                        messageWindow.expand(in: model.messages)
                                        await Task.yield()
                                        if !followingLatest, let first { proxy.scrollTo(first, anchor: .top) }
                                    }
                                }.foregroundColor(.mango9Primary).disabled(model.loadingOlderMessages || model.loadingLatestHistory)
                            }
                            if model.messages.isEmpty && model.pending == nil {
                                Text("What can I help you with?").font(.title3.weight(.semibold)).foregroundColor(.grayMain2c800).padding(.top, 28)
                                Text("Ask about your leads, messages or tasks.").font(.subheadline).foregroundColor(.grayMain2c500)
                            }
                            ForEach(visibleMessages) { message in bubble(message).supervisorScrollTarget(message.id, latest: latestScrollTarget) }
                            if let command = model.pending, !model.messages.contains(where: { $0.id == command.messageID }) {
                                HStack { Spacer(minLength: 36); Text(command.text).font(Mango9SupervisorTypography.message).padding(12).background(Color.mango9Surface).clipShape(RoundedRectangle(cornerRadius: 18)) }
                                    .supervisorScrollTarget(command.messageID, latest: latestScrollTarget)
                            }
                            if model.sending { ProgressView("Thinking…").tint(.mango9Primary).supervisorScrollTarget(Mango9SupervisorScrollTarget.thinking, latest: latestScrollTarget) }
                            Color.clear.frame(height: 1).supervisorScrollTarget(Mango9SupervisorScrollTarget.empty, latest: latestScrollTarget)
                                .background(GeometryReader { geometry in Color.clear.preference(key: SupervisorBottomPosition.self, value: geometry.frame(in: .named("supervisor.viewport")).maxY) })
                        }.padding(.horizontal, compact ? 20 : 14).padding(.vertical, 14)
                    }
                    .coordinateSpace(name: "supervisor.viewport")
                    .supervisorInitialBottomAnchor()
                    .accessibilityIdentifier("supervisor.messages")
                    .onAppear { messageWindow.preserve(in: model.messages); draggingHistory = false; waitingForUserScrollToSettle = false; followingLatest = true; requestLatestScroll(proxy, height: viewport.size.height) }
                    .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in
                        if !draggingHistory { followingLatest = false }
                        draggingHistory = true; waitingForUserScrollToSettle = true; scrollTask?.cancel()
                    }.onEnded { _ in
                        draggingHistory = false
                        if Mango9ChatScrollMetrics.reachedLatest(bottom: latestMessageBottom, viewportHeight: viewport.size.height) {
                            followingLatest = true; waitingForUserScrollToSettle = false
                        }
                    })
                    .onPreferenceChange(SupervisorBottomPosition.self) { bottom in
                        bottomPosition = bottom
                        let nearBottom = Mango9SupervisorScrollLayout.isNearBottom(bottom, viewportHeight: viewport.size.height)
                        if draggingHistory { followingLatest = nearBottom }
                        else if waitingForUserScrollToSettle && nearBottom {
                            followingLatest = true; waitingForUserScrollToSettle = false
                        }
                    }
                    .onPreferenceChange(SupervisorLatestRow.self) { layout in
                        let prior = latestLayout; latestLayout = layout
                        if !draggingHistory && waitingForUserScrollToSettle &&
                            Mango9ChatScrollMetrics.reachedLatest(bottom: latestMessageBottom, viewportHeight: viewport.size.height) {
                            followingLatest = true; waitingForUserScrollToSettle = false
                        }
                        // Scrolling changes position, not content. Only a new
                        // last row or a real bubble/card resize can request a pass.
                        if Mango9SupervisorScrollLayout.contentChanged(from: prior, to: layout) {
                            requestLatestScroll(proxy, height: viewport.size.height)
                        }
                    }
                    .onChange(of: model.messages.last?.id) { _ in messageWindow.preserve(in: model.messages); requestLatestScroll(proxy, height: viewport.size.height) }
                    .onChange(of: model.pending?.messageID) { _ in requestLatestScroll(proxy, height: viewport.size.height) }
                    .onChange(of: model.sending) { value in if value { followingLatest = true }; requestLatestScroll(proxy, height: viewport.size.height) }
                    .onChange(of: model.loadingLatestHistory) { value in if !value { requestLatestScroll(proxy, height: viewport.size.height) } }
                    .onChange(of: model.conversationID) { _ in messageWindow = Mango9SupervisorMessageWindow(); draggingHistory = false; waitingForUserScrollToSettle = false; followingLatest = true; requestLatestScroll(proxy, height: viewport.size.height) }
                    .onChange(of: viewport.size.height) { _ in requestLatestScroll(proxy, height: viewport.size.height) }
                    .onChange(of: focused) { _ in requestLatestScroll(proxy, height: viewport.size.height) }
                    .overlay(alignment: .bottomTrailing) {
                        if !followingLatest && Mango9ChatScrollMetrics.hasMoreBelow(bottom: latestMessageBottom, viewportHeight: viewport.size.height) {
                            Mango9LatestMessagesButton { followingLatest = true; waitingForUserScrollToSettle = false; requestLatestScroll(proxy, height: viewport.size.height) }
                                .padding(20)
                        }
                    }
                  }
                }
            }
            if model.pending != nil && !model.sending {
                VStack(spacing: 6) {
                    Text("Request status is unconfirmed. It won’t be sent again automatically.").font(.caption).foregroundColor(.grayMain2c500)
                    HStack {
                        Button("Check status") { Task { await model.refreshHistory() } }
                        Spacer()
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
    @MainActor private func requestLatestScroll(_ proxy: ScrollViewProxy, height: CGFloat) {
        guard followingLatest, !draggingHistory else { return }
        scrollTask?.cancel()
        scrollTask = Task { await scrollToLatest(proxy, height: height) }
    }
    @MainActor private func scrollToLatest(_ proxy: ScrollViewProxy, height: CGFloat) async {
        await Task.yield()
        guard followingLatest, !draggingHistory, !Task.isCancelled else { return }
        guard Mango9SupervisorScrollLayout.needsAlignment(target: latestScrollTarget, layout: latestLayout, viewportHeight: height) else { return }
        var transaction = Transaction(animation: nil); transaction.disablesAnimations = true
        withTransaction(transaction) { proxy.scrollTo(latestScrollTarget, anchor: .bottom) }
    }
    private var composer: some View {
        VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 8) {
                Group {
                    if #available(iOS 16.0, *) {
                        TextField("Message Supervisor…", text: $model.draft, axis: .vertical).lineLimit(1...(compact ? 3 : 5))
                    } else { TextField("Message Supervisor…", text: $model.draft) }
                }.font(Mango9SupervisorTypography.message).focused($focused)
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
            if model.walletBilling || model.loading {
                Text("Uses your Mango9 AI credit").font(.caption2).foregroundColor(.grayMain2c500)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 14)
                    .opacity(model.walletBilling ? 1 : 0).accessibilityHidden(!model.walletBilling)
            }
        }.padding(.horizontal, 10).padding(.vertical, compact ? 3 : 8).background(Color.white.ignoresSafeArea(.container, edges: .bottom))
            .overlay(alignment: .top) { Color.gray200.frame(height: 1) }
    }
    private func send() { guard model.canSend else { return }; focused = false; Task { await model.send() } }
    private func bubble(_ message: Mango9SupervisorMessage) -> some View {
        let reply = Mango9SupervisorReplyFormatting.reply(message.text, baseURL: model.api.original.crmBaseUrl)
        let proofs = message.creationData.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [[String: Any]] } ?? []
        let creations = proofs.compactMap { Mango9SupervisorCreation(["state": "SUCCEEDED", "recap": ["creation": $0]], baseURL: model.api.original.crmBaseUrl) }
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
                }.font(Mango9SupervisorTypography.message).foregroundColor(.grayMain2c800).tint(.mango9Primary)
                    .padding(12).background(message.role == "user" ? Color.mango9Surface : Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .contextMenu { Button { UIPasteboard.general.string = message.role == "assistant" ? reply.copyText : message.text } label: { Label("Copy", systemImage: "doc.on.doc") } }
                if let date = message.createdAt {
                    Text(date, style: .time).font(.caption2).foregroundColor(.grayMain2c500)
                        .frame(maxWidth: .infinity, alignment: .trailing).padding(.horizontal, 4)
                        .accessibilityIdentifier(message.id == model.messages.last?.id ? "supervisor.latest.timestamp" : "supervisor.timestamp")
                }
                ForEach(message.actionIDs, id: \.self) { id in configuration(id) }
                ForEach(Array(creations.enumerated()), id: \.offset) { _, creation in
                    creationStatus(creation).padding(14).background(Color(red: 0.918, green: 0.965, blue: 1)).clipShape(RoundedRectangle(cornerRadius: 16))
                }
            }
        }.frame(maxWidth: .infinity, alignment: message.role == "user" ? .trailing : .leading)
    }
    private func notice(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) { Image(systemName: "exclamationmark.circle"); Text(text).fixedSize(horizontal: false, vertical: true) }
            .font(.footnote).foregroundColor(.redDanger700).frame(maxWidth: .infinity, alignment: .leading).padding(12).background(Color.redDanger200.opacity(0.25))
    }
    private var historySheet: some View {
        NavigationView {
            VStack(spacing: 0) {
                Picker("Conversation type", selection: $historyFilter) {
                    ForEach(Mango9SupervisorHistoryFilter.allCases) { filter in Text(filter.title).tag(filter) }
                }.pickerStyle(.segmented).padding(.horizontal).padding(.vertical, 8).accessibilityIdentifier("supervisor.history.filter")
                List {
                    ForEach(model.conversations) { row in historyRow(row) }
                    if model.loadingConversations { HStack { Spacer(); ProgressView(); Spacer() }.listRowBackground(Color.clear) }
                    if let error = model.historyListError {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(error).font(.footnote).foregroundColor(.redDanger700)
                            Button("Refresh") { Task { try? await model.loadConversations(query: historyQuery, channel: historyFilter.rawValue) } }
                        }
                    }
                    if model.moreConversations {
                        Button(historyQuery.isEmpty ? "Load more" : "Search older history") { Task { try? await model.loadConversations(more: true) } }.disabled(model.loadingConversations)
                    }
                    if model.conversations.isEmpty && !model.loadingConversations && model.historyListError == nil {
                        Text(historyQuery.isEmpty ? "No saved conversations." : model.moreConversations ? "No matching titles in this page." : "No matching conversations.")
                            .font(.subheadline).foregroundColor(.grayMain2c500)
                    }
                }
                .listStyle(.insetGrouped)
                .refreshable { try? await model.loadConversations(query: historyQuery, channel: historyFilter.rawValue) }
            }.navigationTitle("History").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $historyQuery, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search conversation titles")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showingHistory = false } } }
                .task(id: historyFilter.rawValue + ":" + historyQuery) {
                    do {
                        if !historyQuery.isEmpty { try await Task.sleep(nanoseconds: 300_000_000) }
                        try Task.checkCancellation()
                        try await model.loadConversations(query: historyQuery, channel: historyFilter.rawValue)
                    } catch { /* The model displays non-cancellation errors inline. */ }
                }
        }.navigationViewStyle(.stack).tint(.mango9Primary)
            // One sheet-level alert avoids a popover attached to a recycled or
            // still-open swipe row. The selected row is captured for this alert.
            .alert("Delete conversation?", isPresented: Binding(
                get: { deletingHistoryID != nil },
                set: { if !$0 { deletingHistoryID = nil } }),
                presenting: model.conversations.first { $0.id == deletingHistoryID }) { row in
                    Button("Cancel", role: .cancel) { deletingHistoryID = nil }
                    Button("Delete", role: .destructive) {
                        deletingHistoryID = nil
                        Task { await model.deleteConversation(row.id) }
                    }.disabled(!model.canManageHistory)
                } message: { row in
                    Text("Delete “\(String(row.title.prefix(80)))”? Tasks and other changes will be kept.")
                }
    }
    private func historyRow(_ row: Mango9SupervisorConversation) -> some View {
        Button {
            guard model.canBrowseHistory else { return }
            showingHistory = false; Task { await model.select(row.id) }
        } label: {
            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    if row.id == model.conversationID { Circle().fill(Color.mango9Primary).frame(width: 7, height: 7).accessibilityLabel("Current conversation") }
                    Text(row.title).font(.body).foregroundColor(.grayMain2c800).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                }
                historyMetadata(row)
            }.padding(.vertical, 5).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!model.canBrowseHistory).accessibilityIdentifier("supervisor.history.row")
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) { deletingHistoryID = row.id } label: { Label("Delete", systemImage: "trash") }.disabled(!model.canManageHistory)
            }
            .contextMenu { Button(role: .destructive) { deletingHistoryID = row.id } label: { Label("Delete conversation", systemImage: "trash") }.disabled(!model.canManageHistory) }
    }
    @ViewBuilder private func historyMetadata(_ row: Mango9SupervisorConversation) -> some View {
        if historyTextSize.isAccessibilityCategory {
            historyStackedMetadata(row)
                .font(.caption).foregroundColor(.grayMain2c500)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                historyMessageCount(row).fixedSize(horizontal: true, vertical: false)
                historyTimestamp(row).lineLimit(2).frame(maxWidth: .infinity, alignment: .trailing)
            }.font(.caption).foregroundColor(.grayMain2c500)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
    private func historyMessageCount(_ row: Mango9SupervisorConversation) -> some View {
        let count = row.messageCount == 1 ? "1 message" : "\(row.messageCount) messages"
        return HStack(spacing: 4) {
            Image(systemName: row.hasVoice ? "mic" : "text.bubble").accessibilityHidden(true)
            Text(row.messageCount > 0 ? count : row.hasVoice ? "Voice" : "Text")
        }.accessibilityElement(children: .ignore)
            .accessibilityLabel((row.hasVoice ? "With voice, " : "Text, ") + count)
    }
    private func historyTimestamp(_ row: Mango9SupervisorConversation) -> some View {
        Text(Mango9SupervisorHistoryDate.label(row.updatedAt)).multilineTextAlignment(.trailing)
    }
    private func historyStackedMetadata(_ row: Mango9SupervisorConversation) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            historyMessageCount(row)
            historyTimestamp(row).frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
    private func configuration(_ id: String) -> some View {
        let detail = model.configurationDetails[id]
        let recap = detail?["recap"] as? [String: Any]
        let fields = recap?["fields"] as? [[String: Any]] ?? []
        let snapshot = detail.flatMap(Mango9SupervisorApproval.init)
        let readable = recap?["version"] as? Int == 1 && !fields.contains { $0["label"] as? String == nil || $0["value"] as? String == nil }
        let complete = readable && !fields.contains { $0["incomplete"] as? Bool == true }
        let state = detail?["state"] as? String ?? ""
        let saved = model.savedConfigurationIDs.contains(id)
        let creation = detail.flatMap { Mango9SupervisorCreation($0, baseURL: model.api.original.crmBaseUrl) }
        return VStack(alignment: .leading, spacing: 10) {
            Text(recap?["title"] as? String ?? "Settings").font(.subheadline.weight(.semibold))
            if readable {
                if let effect = recap?["effect"] as? String, !effect.isEmpty { Text(effect).font(.footnote).foregroundColor(.grayMain2c500) }
                configurationRows(Array(fields.prefix(5)))
                if fields.count > 5 {
                    DisclosureGroup("More settings") { configurationRows(Array(fields.dropFirst(5))) }.font(.caption)
                }
                if !complete { Text("Some settings couldn’t load. Refresh before applying.").font(.caption).foregroundColor(.grayMain2c500) }
                if saved { Label("Saved on this iPhone", systemImage: "iphone").font(.caption).foregroundColor(.grayMain2c500) }
                if state == "PENDING_APPROVAL" {
                    Text(recap?["question"] as? String ?? "Does this look right?").font(.footnote)
                    HStack {
                        Button("Change") {
                            guard let snapshot else { return }
                            model.draft = "I’d like to change these settings."; focused = true
                            Task { await model.decide("reject", expected: snapshot) }
                        }
                        Spacer()
                        Button("Apply") { if let snapshot { Task { await model.decide("approve", expected: snapshot) } } }.font(.subheadline.weight(.semibold)).disabled(!complete)
                    }.disabled(saved || snapshot == nil || !model.authorized || model.actionBusy || model.sending || !model.hasAIConsent)
                    if saved { Text("Refresh to check these settings before applying.").font(.caption).foregroundColor(.grayMain2c500) }
                } else if let creation {
                    creationStatus(creation)
                } else {
                    Text(recap?["statusText"] as? String ?? (state == "SUCCEEDED" ? "Completed. Delivery isn’t confirmed." : state == "OUTCOME_UNKNOWN" ? "The result is unconfirmed. Check before trying again." : state == "REJECTED" || state == "CANCELLED" ? "Not applied." : "Check the result.")).font(.caption).foregroundColor(.grayMain2c500)
                }
            } else if model.configurationFlights.contains(id) { ProgressView("Loading settings…").font(.footnote) }
            else { Text("Couldn’t load settings. You can refresh or keep chatting.").font(.footnote).foregroundColor(.grayMain2c500) }
            if saved || state != "PENDING_APPROVAL" || !complete {
                Button(model.configurationFlights.contains(id) ? "Refreshing…" : "Refresh") {
                    Task { if model.authorized { await model.loadConfiguration(id) } else { await model.resume() } }
                }.font(.caption).disabled(model.actionBusy || model.configurationFlights.contains(id) || model.loading)
            }
        }.foregroundColor(.grayMain2c800).tint(.mango9Primary).padding(14).background(Color(red: 0.918, green: 0.965, blue: 1))
            .clipShape(RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(red: 0.812, green: 0.906, blue: 0.98), lineWidth: 1))
            .task(id: id) { await model.loadConfiguration(id) }
            .accessibilityIdentifier("supervisor.configuration")
    }
    private func creationStatus(_ creation: Mango9SupervisorCreation) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(creation.status, systemImage: "checkmark.circle.fill")
                .font(.subheadline.bold()).foregroundColor(Color(red: 0.08, green: 0.40, blue: 0.20))
                .accessibilityIdentifier("supervisor.created.status")
            ForEach(creation.links) { link in
                if let taskID = Mango9SupervisorCreation.nativeTaskID(link.url) {
                    Button { focused = false; createdTask = .init(id: taskID) } label: {
                        Label(link.title, systemImage: "arrow.up.right.square").font(.subheadline.weight(.semibold))
                    }.accessibilityIdentifier("supervisor.created.link")
                } else {
                    Link(destination: link.url) { Label(link.title, systemImage: "arrow.up.right.square").font(.subheadline.weight(.semibold)) }
                        .accessibilityIdentifier("supervisor.created.link")
                }
            }
            if !creation.recipients.isEmpty {
                Text("Confirmation recipients").font(.caption.weight(.medium)).foregroundColor(.grayMain2c500)
                ForEach(Array(creation.recipients.enumerated()), id: \.offset) { _, recipient in
                    Text(recipient).font(.footnote).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                }
            }
        }.tint(.mango9Primary)
    }
    private func configurationRows(_ fields: [[String: Any]]) -> some View {
        ForEach(fields.indices, id: \.self) { index in
            HStack(alignment: .top, spacing: 12) {
                Text(fields[index]["label"] as? String ?? "").foregroundColor(.grayMain2c500).frame(maxWidth: .infinity, alignment: .leading)
                Text(fields[index]["value"] as? String ?? "").frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
            }.font(.footnote)
        }
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
