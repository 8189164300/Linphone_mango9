// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

@MainActor final class Mango9SupervisorModel: ObservableObject {
    let api: any Mango9SupervisorClient
    let voice = Mango9SupervisorVoice()
    @Published var messages: [Mango9SupervisorMessage] = []
    @Published var conversations: [Mango9SupervisorConversation] = []
    @Published var draft = ""
    @Published var loading = true
    @Published var sending = false
    @Published var authorized = false
    @Published private(set) var hasAIConsent = false
    @Published var showVoice = false
    @Published var error: String?
    @Published var pending: Mango9SupervisorCommand?
    @Published var walletBilling = false
    @Published var actions: [[String: Any]] = []
    @Published var activity: [[String: Any]] = []
    @Published var actionDetail: [String: Any]?
    @Published var actionBusy = false
    @Published var moreMessages = false
    @Published var moreConversations = false
    private(set) var conversationID: String
    private var historyCursor = "0"
    private var listCursor: String?
    private var historyTask: Task<Void, Never>?
    private var historyFlight: UUID?
    private var listFlight = UUID()
    private var opened = false
    private var starting = false
    private var restoredPending = false
    private let defaults: UserDefaults
    private let pendingStore: Mango9SupervisorPendingStore
    private var historyRequested = false
    private var operation = UUID()
    private var poll: Task<Void, Never>?
    private let account: String
    private var conversationKey: String { "mango9.supervisor.conversation." + account }

    init(session: Mango9Session, client: (any Mango9SupervisorClient)? = nil, defaults: UserDefaults = .standard,
         pendingStore: Mango9SupervisorPendingStore? = nil) {
        api = client ?? Mango9SupervisorAPI(session: session)
        self.defaults = defaults
        hasAIConsent = Mango9SupervisorConsent.accepted(session, defaults: defaults)
        account = Mango9SupervisorPersistence.key(session)
        self.pendingStore = pendingStore ?? .keychain(account)
        conversationID = defaults.string(forKey: "mango9.supervisor.conversation." + account) ?? UUID().uuidString.lowercased()
        if UUID(uuidString: conversationID) == nil { conversationID = UUID().uuidString.lowercased() }
        voice.onHistoryChanged = { [weak self] in
            guard let self else { return }
            self.historyRequested = true
            Task { await self.refreshHistory() }
        }
    }
    var canSend: Bool { opened && restoredPending && hasAIConsent && api.hasAIConsent && authorized && !loading && !sending && pending == nil && !voice.running && api.active && Mango9SupervisorCommand.valid(draft) }
    var canStartVoice: Bool { opened && restoredPending && hasAIConsent && api.hasAIConsent && authorized && !loading && !sending && pending == nil && api.active }
    func acceptAIConsent() {
        guard api.active else { return }
        Mango9SupervisorConsent.allow(api.original, defaults: defaults)
        hasAIConsent = api.hasAIConsent
    }
    func revokeAIConsent() {
        Mango9SupervisorConsent.revoke(api.original, defaults: defaults)
        hasAIConsent = false; invalidateOperations()
        voice.stop(); showVoice = false; sending = false; actionBusy = false; starting = false; loading = false
        api.cancelAIRequests()
    }
    private func valid(_ stamp: UUID) -> Bool { opened && stamp == operation && api.active && !Task.isCancelled }
    private func invalidateOperations() {
        operation = UUID(); historyTask?.cancel(); historyTask = nil; historyFlight = nil; listFlight = UUID()
    }
    func start() async {
        guard !starting, !sending else { return }
        invalidateOperations(); opened = true; starting = true; loading = true; error = nil
        let stamp = operation
        defer { if stamp == operation { loading = false; starting = false } }
        do {
            // A locked or corrupt Keychain must not look like an empty outbox.
            if !restoredPending {
                pending = try pendingStore.read()
                if let pending { conversationID = pending.conversationID }
                restoredPending = true
            }
            let status = try await api.authorize()
            guard valid(stamp) else { return }
            authorized = true; walletBilling = status["billingEnabled"] as? Bool == true
            try await loadConversations()
            guard valid(stamp) else { return }
            if pending == nil, defaults.string(forKey: conversationKey) == nil, let latest = conversations.first { conversationID = latest.id }
            defaults.set(conversationID, forKey: conversationKey)
            await refreshHistory()
            guard valid(stamp) else { return }
        } catch {
            guard valid(stamp) else { if !api.active { clearForAccountChange() }; return }
            self.error = notice(error); authorized = false
        }
        guard valid(stamp) else { return }
        poll?.cancel()
        poll = Task { [weak self] in
            var ticks = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled, let self, self.opened else { return }
                guard self.api.active else { self.clearForAccountChange(); return }
                ticks += 1
                if ticks % 8 == 0 && self.historyTask == nil && (self.pending != nil || self.voice.running || self.historyRequested) {
                    Task { [weak self] in await self?.refreshHistory() }
                }
            }
        }
    }
    func close() {
        opened = false; invalidateOperations(); poll?.cancel(); poll = nil; voice.stop()
        loading = false; starting = false; sending = false; actionBusy = false; showVoice = false; authorized = false
    }
    func background() { voice.stop(); showVoice = false }
    private func clearForAccountChange() {
        close(); hasAIConsent = false; messages = []; conversations = []; actions = []; activity = []; actionDetail = nil; draft = ""; pending = nil
        error = Mango9SupervisorFailure(status: 0, code: "scope_changed").localizedDescription
    }
    private func notice(_ error: Error) -> String {
        if error is CancellationError { return "The request stopped. Refresh to check saved history." }
        if error is Mango9SupervisorFailure || error is Mango9CRMAPIError { return error.localizedDescription }
        return "Couldn’t reach Supervisor. Check your connection and try again."
    }
    func loadConversations(more: Bool = false) async throws {
        guard authorized, opened, api.active else { return }
        let stamp = operation, flight = UUID(); listFlight = flight
        var body: [String: Any] = ["action": "list"]
        if more, let listCursor { body["before"] = listCursor }
        let result = try await api.request("history", body: body)
        guard valid(stamp), listFlight == flight else { return }
        let rows = (result["items"] as? [[String: Any]] ?? []).compactMap(Mango9SupervisorConversation.init)
        conversations = more ? conversations + rows.filter { row in !conversations.contains { $0.id == row.id } } : rows
        listCursor = result["nextCursor"] as? String; moreConversations = listCursor != nil
    }
    func select(_ id: String) async {
        guard opened, api.active, !loading, !sending, pending == nil, UUID(uuidString: id) != nil else { return }
        voice.stop(); showVoice = false; invalidateOperations(); conversationID = id; actionDetail = nil; actionBusy = false
        messages = []; historyCursor = "0"; moreMessages = false; error = nil
        defaults.set(id, forKey: conversationKey)
        let stamp = operation
        loading = true; await refreshHistory(); if stamp == operation { loading = false }
    }
    func newConversation() async { await select(UUID().uuidString.lowercased()) }
    /// Explicit user choice only. This does not cancel or repeat the old request.
    func leaveUnconfirmedRequest() async {
        guard !sending, api.active else { return }
        do { try pendingStore.write(nil); pending = nil; await newConversation() }
        catch { self.error = notice(error) }
    }

    func refreshHistory() async {
        guard authorized, opened, api.active else { return }
        if let historyTask { historyRequested = true; await historyTask.value; return }
        historyRequested = false
        let id = conversationID, stamp = operation, flight = UUID()
        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }; await self.readHistory(id: id, stamp: stamp)
        }
        historyTask = task; historyFlight = flight
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
        if historyFlight == flight { historyTask = nil; historyFlight = nil }
    }
    private func readHistory(id: String, stamp: UUID) async {
        do {
            let result = try await api.request("history", body: ["action": "read", "conversationId": id, "after": historyCursor])
            guard valid(stamp), id == conversationID else { return }
            let rows = (result["messages"] as? [[String: Any]] ?? []).compactMap(Mango9SupervisorMessage.init)
            for row in rows {
                if let index = messages.firstIndex(where: { $0.id == row.id }) { messages[index] = row } else { messages.append(row) }
            }
            historyCursor = result["cursor"] as? String ?? historyCursor
            moreMessages = result["nextCursor"] as? String != nil
            if let command = pending, messages.contains(where: { $0.role == "assistant" && ($0.id == command.requestID || $0.requestID == command.requestID) }) {
                try pendingStore.write(nil); pending = nil; error = nil
            }
            if result["pending"] as? Bool == true { historyRequested = true }
        } catch let failure as Mango9SupervisorFailure where failure.status == 404 && failure.code == "conversation_not_found" {
            // A new conversation is created only by its first text/voice turn.
            if valid(stamp), id == conversationID, pending != nil { error = Mango9SupervisorFailure(status: 0, code: "history_pending").localizedDescription }
        } catch { if valid(stamp) { self.error = notice(error) } }
    }
    func send() async {
        guard canSend else { return }
        let command = Mango9SupervisorCommand(conversationID: conversationID, text: draft.trimmingCharacters(in: .whitespacesAndNewlines))
        do { try pendingStore.write(command) }
        catch { self.error = "Couldn’t securely save this request. Your message has not been sent."; return }
        pending = command; draft = ""; sending = true; error = nil
        let stamp = operation
        defer { if stamp == operation { sending = false } }
        do {
            let raw = try await api.request("chat", body: command.body)
            guard valid(stamp) else { return }
            guard let message = Mango9SupervisorMessage(raw), message.role == "assistant" else { throw Mango9SupervisorFailure(status: 0, code: "invalid_response") }
            await refreshHistory()
            guard valid(stamp) else { return }
            // JSON confirms a completed turn even when a separate history refresh is offline.
            if !messages.contains(where: { $0.id == command.messageID }), let user = Mango9SupervisorMessage(["id": command.messageID, "role": "user", "parts": [["type": "text", "text": command.text]]]) { messages.append(user) }
            if !messages.contains(where: { $0.id == message.id }) { messages.append(message) }
            try pendingStore.write(nil); pending = nil
            try? await loadConversations()
        } catch {
            guard valid(stamp) else { return }
            self.error = notice(error)
            // No automatic POST replay. Saved history is the source of truth after a lost response.
            await refreshHistory()
        }
    }
    func startVoice() async {
        guard canStartVoice else { return }
        voice.error = nil
        showVoice = true
    }
    func connectVoice() async {
        guard showVoice, canStartVoice, !voice.running else { return }
        await voice.start(api: api, conversationID: conversationID)
    }
    func endVoice() { voice.stop(); showVoice = false; Task { await refreshHistory(); try? await loadConversations() } }

    func loadActions() async {
        guard !actionBusy, opened, authorized, api.active else { return }
        let stamp = operation; actionBusy = true
        defer { if stamp == operation { actionBusy = false } }
        do {
            let rows = try await api.request("actions")["items"] as? [[String: Any]] ?? []
            guard valid(stamp) else { return }
            let result = try await api.request("activity")
            guard valid(stamp) else { return }
            actions = rows; activity = (result["items"] ?? result["events"]) as? [[String: Any]] ?? []
        } catch { if valid(stamp) { self.error = notice(error) } }
    }
    func reviewAction(_ id: String) async {
        guard !actionBusy, opened, authorized, api.active, UUID(uuidString: id) != nil else { return }
        let stamp = operation; actionBusy = true; actionDetail = nil
        defer { if stamp == operation { actionBusy = false } }
        do {
            let detail = try await api.request("actions/" + id)
            guard valid(stamp) else { return }; actionDetail = detail
        } catch { if valid(stamp) { self.error = notice(error) } }
    }
    func decide(_ decision: String, expected: Mango9SupervisorApproval) async {
        guard !actionBusy, opened, authorized, api.active, ["approve", "reject"].contains(decision),
              decision != "approve" || (hasAIConsent && api.hasAIConsent) else { return }
        guard let detail = actionDetail, Mango9SupervisorApproval(detail) == expected else {
            error = Mango9SupervisorFailure(status: 0, code: "action_changed").localizedDescription; return
        }
        let stamp = operation; actionBusy = true
        defer { if stamp == operation { actionBusy = false } }
        do {
            let result = try await api.request("actions/" + expected.id + "/decision", body: ["decision": decision, "approvalDigest": expected.digest])
            guard valid(stamp) else { return }; actionDetail = result
        } catch { if valid(stamp) { self.error = notice(error); actionDetail = nil } }
        guard valid(stamp) else { return }
        actionBusy = false; await loadActions(); await refreshHistory()
    }
}
