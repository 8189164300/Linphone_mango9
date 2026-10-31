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
    @Published var configurationDetails: [String: [String: Any]] = [:]
    @Published private(set) var configurationFlights = Set<String>()
    @Published private(set) var savedConfigurationIDs = Set<String>()
    @Published private(set) var usingSavedHistory = false
    @Published private(set) var localStorageNotice: String?
    @Published var moreMessages = false
    @Published private(set) var loadingOlderMessages = false
    @Published private(set) var loadingLatestHistory = false
    private var olderCursor: String?
    private var historyReady = false
    private var preferLatestHistory = false
    private var olderFlight = UUID()
    @Published var moreConversations = false
    @Published private(set) var loadingConversations = false
    @Published private(set) var deletingConversation: String?
    @Published var historyListError: String?
    private var historyQuery = ""
    private var historyChannel = "all"
    private(set) var conversationID: String
    private var historyCursor = "0"
    private var listCursor: String?
    private var historyTask: Task<Void, Never>?
    private var historyFlight: UUID?
    private var listFlight = UUID()
    private var configurationTokens: [String: UUID] = [:]
    private var opened = false
    private var starting = false
    private var restoredPending = false
    private let defaults: UserDefaults
    private let pendingStore: Mango9SupervisorPendingStore
    private let localStore: Mango9SupervisorLocalStore
    private var savedChats: [Mango9SupervisorSavedChat] = []
    private var savedConfigurations: [String: Mango9SupervisorSavedConfiguration] = [:]
    private var configurationPrefetch: Task<Void, Never>?
    private var historyRequested = false
    private var operation = UUID()
    private var poll: Task<Void, Never>?
    private let account: String
    private var conversationKey: String { "mango9.supervisor.conversation." + account }

    init(session: Mango9Session, client: (any Mango9SupervisorClient)? = nil, defaults: UserDefaults = .standard,
         pendingStore: Mango9SupervisorPendingStore? = nil, localStore: Mango9SupervisorLocalStore? = nil) {
        api = client ?? Mango9SupervisorAPI(session: session)
        self.defaults = defaults
        hasAIConsent = Mango9SupervisorConsent.accepted(session, defaults: defaults)
        account = Mango9SupervisorPersistence.key(session)
        self.pendingStore = pendingStore ?? .keychain(account)
        self.localStore = localStore ?? .device(session)
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
    var canManageHistory: Bool { opened && authorized && api.active && !loading && !sending && pending == nil && !actionBusy && !voice.running && deletingConversation == nil }
    var canBrowseHistory: Bool { opened && api.active && !loading && !sending && pending == nil && !actionBusy && !voice.running && deletingConversation == nil }
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
        configurationPrefetch?.cancel(); configurationPrefetch = nil
        configurationDetails = [:]; configurationFlights = []; configurationTokens = [:]; actionDetail = nil
        savedConfigurations = [:]; savedConfigurationIDs = []
        loadingConversations = false; deletingConversation = nil
        olderFlight = UUID(); loadingOlderMessages = false; loadingLatestHistory = false
    }
    func start() async {
        guard !starting, !sending else { return }
        invalidateOperations(); opened = true; starting = true; loading = true; error = nil; authorized = false
        historyReady = false
        let stamp = operation
        defer { if stamp == operation { loading = false; starting = false } }
        do {
            // A locked or corrupt Keychain must not look like an empty outbox.
            if !restoredPending {
                pending = try pendingStore.read()
                if let pending { conversationID = pending.conversationID }
                restoredPending = true
            }
            guard valid(stamp) else { clearForAccountChange(); return }
            do { savedChats = try localStore.read(); localStorageNotice = nil }
            catch { localStorageNotice = "Couldn’t open the saved copy on this iPhone. Online chat still works." }
            restoreLocalConversation()
            let status = try await api.authorize()
            guard valid(stamp) else { return }
            authorized = true; walletBilling = status["billingEnabled"] as? Bool == true
            // A list outage must not prevent loading the current conversation.
            do { try await loadConversations() } catch { if discardForAccessError(error) { throw error } }
            guard valid(stamp) else { return }
            if pending == nil, defaults.string(forKey: conversationKey) == nil, let latest = conversations.first {
                conversationID = latest.id; restoreLocalConversation()
            }
            defaults.set(conversationID, forKey: conversationKey)
            await refreshHistory()
            guard valid(stamp) else { return }
        } catch {
            guard valid(stamp) else { if !api.active { clearForAccountChange() }; return }
            self.error = notice(error); authorized = false
            _ = discardForAccessError(error)
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
    func background() {
        voice.stop(); showVoice = false; configurationPrefetch?.cancel()
        configurationTokens = [:]; configurationFlights = []; markConfigurationsSaved()
    }
    func resume(latest: Bool = true) async {
        if !authorized { await start() }
        else { await refreshHistory(latest: latest); prefetchConfigurations() }
    }
    private func clearForAccountChange() {
        close(); hasAIConsent = false; messages = []; conversations = []; actions = []; activity = []; actionDetail = nil; draft = ""; pending = nil
        historyCursor = "0"; historyReady = false; olderCursor = nil; moreMessages = false
        savedChats = []; usingSavedHistory = false
        error = Mango9SupervisorFailure(status: 0, code: "scope_changed").localizedDescription
    }
    private func restoreLocalConversation() {
        guard api.active else { return }
        guard let saved = savedChats.first(where: { $0.id == conversationID }) else {
            messages = []; usingSavedHistory = false; return
        }
        messages = saved.messages; usingSavedHistory = !messages.isEmpty
        let ids = Set(messages.flatMap(\.actionIDs))
        savedConfigurations = saved.configurations.filter { ids.contains($0.key) && $0.value.detail?["id"] as? String == $0.key }
        configurationDetails = savedConfigurations.compactMapValues(\.detail)
        savedConfigurationIDs = Set(configurationDetails.keys)
        if conversations.isEmpty { conversations = savedChats.compactMap(\.conversation).sorted { ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) } }
    }
    private func persistLocalConversation() {
        guard api.active, opened else { return }
        let rows = Array(messages.suffix(200)), ids = Set(rows.flatMap(\.actionIDs))
        let saved = Mango9SupervisorSavedChat(id: conversationID, savedAt: Date(),
            conversation: conversations.first { $0.id == conversationID }, messages: rows,
            configurations: savedConfigurations.filter { ids.contains($0.key) })
        savedChats.removeAll { $0.id == conversationID }
        if !rows.isEmpty { savedChats.insert(saved, at: 0) }
        savedChats = Mango9SupervisorLocalStore.bounded(savedChats)
        writeLocalConversations()
    }
    private func writeLocalConversations() {
        do { try localStore.write(savedChats); localStorageNotice = nil }
        catch { localStorageNotice = "Couldn’t save a local copy on this iPhone. Your server history is unchanged." }
    }
    private func rememberConfiguration(_ detail: [String: Any], id: String) {
        configurationDetails[id] = detail; savedConfigurationIDs.remove(id)
        if let saved = Mango9SupervisorSavedConfiguration(detail) { savedConfigurations[id] = saved }
        else { savedConfigurations[id] = nil }
        persistLocalConversation()
    }
    private func markConfigurationsSaved() {
        for (id, saved) in savedConfigurations { configurationDetails[id] = saved.detail }
        savedConfigurationIDs = Set(configurationDetails.keys)
    }
    private func discardForAccessError(_ error: Error) -> Bool {
        let denied: Bool
        if let failure = error as? Mango9SupervisorFailure { denied = [401, 403].contains(failure.status) }
        else if let failure = error as? Mango9CRMAPIError {
            switch failure { case .unauthorized, .sessionExpired, .staleSession, .permissionDenied: denied = true; default: denied = false }
        } else { denied = false }
        guard denied else { return false }
        invalidateOperations(); loading = false; starting = false; actionBusy = false; sending = false
        savedChats = []; savedConfigurations = [:]; configurationDetails = [:]; savedConfigurationIDs = []
        messages = []; conversations = []; usingSavedHistory = false; authorized = false
        configurationPrefetch?.cancel(); writeLocalConversations()
        return true
    }
    private func prefetchConfigurations() {
        configurationPrefetch?.cancel()
        let ids = Array(Set(messages.flatMap(\.actionIDs))), stamp = operation
        configurationPrefetch = Task { [weak self] in
            for id in ids {
                guard let self, self.valid(stamp), !Task.isCancelled else { return }
                if self.configurationDetails[id] == nil || self.savedConfigurationIDs.contains(id) { await self.loadConfiguration(id) }
            }
        }
    }
    private func notice(_ error: Error) -> String {
        if error is CancellationError { return "The request stopped. Refresh to check saved history." }
        if error is Mango9SupervisorFailure || error is Mango9CRMAPIError { return error.localizedDescription }
        return "Couldn’t reach Supervisor. Check your connection and try again."
    }
    func loadConversations(more: Bool = false, query: String? = nil, channel: String? = nil) async throws {
        guard opened, api.active else { return }
        let query = String((query ?? (more ? historyQuery : "")).trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
        let channel = channel ?? (more ? historyChannel : "all")
        guard Mango9SupervisorHistoryFilter(rawValue: channel) != nil else { return }
        if !authorized {
            conversations = savedChats.compactMap(\.conversation).filter {
                (query.isEmpty || $0.title.localizedStandardContains(query)) && (channel == "all" || $0.hasVoice == (channel == "voice"))
            }.sorted { ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }
            moreConversations = false; historyListError = nil; return
        }
        if more { guard !loadingConversations, listCursor != nil, query == historyQuery, channel == historyChannel else { return } }
        if historyQuery != query || historyChannel != channel { conversations = []; listCursor = nil; moreConversations = false }
        historyQuery = query; historyChannel = channel
        let stamp = operation, flight = UUID(); listFlight = flight
        loadingConversations = true; historyListError = nil
        defer { if valid(stamp), listFlight == flight { loadingConversations = false } }
        var body: [String: Any] = ["action": "list"]
        if !query.isEmpty { body["query"] = query }
        if channel != "all" { body["channel"] = channel }
        if more, let listCursor { body["before"] = listCursor }
        let result: [String: Any]
        do { result = try await api.request("history", body: body) }
        catch { if valid(stamp), listFlight == flight, !(error is CancellationError) { historyListError = notice(error) }; throw error }
        guard valid(stamp), listFlight == flight else { return }
        let rows = (result["items"] as? [[String: Any]] ?? []).compactMap(Mango9SupervisorConversation.init)
        conversations = more ? conversations + rows.filter { row in !conversations.contains { $0.id == row.id } } : rows
        listCursor = result["nextCursor"] as? String; moreConversations = listCursor != nil
    }
    func deleteConversation(_ id: String) async {
        guard canManageHistory, conversations.contains(where: { $0.id == id }), UUID(uuidString: id) != nil else { return }
        let stamp = operation
        deletingConversation = id; historyListError = nil; listFlight = UUID(); loadingConversations = false
        defer { if valid(stamp) { deletingConversation = nil } }
        do {
            let result = try await api.request("history", body: ["action": "delete", "conversationId": id])
            guard valid(stamp), result["deleted"] as? Bool == true, result["id"] as? String == id else { return }
            conversations.removeAll { $0.id == id }
            savedChats.removeAll { $0.id == id }; writeLocalConversations()
            deletingConversation = nil
            if id == conversationID { await newConversation() }
            try await loadConversations(query: historyQuery, channel: historyChannel)
        } catch { if valid(stamp) { historyListError = "Couldn’t confirm deletion. Refresh History before trying again." } }
    }
    func select(_ id: String) async {
        guard opened, api.active, !loading, !sending, !actionBusy, deletingConversation == nil, pending == nil, UUID(uuidString: id) != nil else { return }
        voice.stop(); showVoice = false; invalidateOperations(); conversationID = id; actionDetail = nil; actionBusy = false
        messages = []; historyCursor = "0"; historyReady = false; olderCursor = nil; moreMessages = false; preferLatestHistory = false; error = nil
        restoreLocalConversation()
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

    func refreshHistory(latest: Bool = false) async {
        guard authorized, opened, api.active else { return }
        if latest { preferLatestHistory = true; olderFlight = UUID(); loadingOlderMessages = false; markConfigurationsSaved() }
        if let historyTask { historyRequested = true; await historyTask.value; return }
        historyRequested = false
        let id = conversationID, stamp = operation, flight = UUID()
        let task = Task<Void, Never> { [weak self] in
            guard let self else { return }; await self.readHistory(id: id, stamp: stamp)
            if self.preferLatestHistory && self.valid(stamp) { await self.readHistory(id: id, stamp: stamp) }
        }
        historyTask = task; historyFlight = flight
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
        if historyFlight == flight { historyTask = nil; historyFlight = nil }
    }
    private func readHistory(id: String, stamp: UUID) async {
        defer { if stamp == operation { loadingLatestHistory = false } }
        do {
            let replace = preferLatestHistory
            let tail = !historyReady || replace
            loadingLatestHistory = tail
            preferLatestHistory = false
            var body: [String: Any] = ["action": "read", "conversationId": id]
            if tail { body["latest"] = true } else { body["after"] = historyCursor }
            if let pending, pending.conversationID == id { body["requestId"] = pending.requestID }
            let result = try await api.request("history", body: body)
            guard valid(stamp), id == conversationID else { return }
            if preferLatestHistory && !replace { return }
            let rows = (result["messages"] as? [[String: Any]] ?? []).compactMap(Mango9SupervisorMessage.init)
            // Publish one complete snapshot. Clearing a cached page first can
            // remove the chat ScrollView while startup is still loading, then
            // recreate it with an invalid initial offset.
            var updated = tail ? [] : messages
            for row in rows {
                if let index = updated.firstIndex(where: { $0.id == row.id }) { updated[index] = row } else { updated.append(row) }
            }
            if messages != updated { messages = updated }
            historyCursor = result["cursor"] as? String ?? historyCursor
            historyReady = true
            usingSavedHistory = false
            if tail { olderCursor = result["olderCursor"] as? String; moreMessages = olderCursor != nil }
            if let command = pending, result["confirmedRequestId"] as? String == command.requestID || messages.contains(where: { $0.role == "assistant" && ($0.id == command.requestID || $0.requestID == command.requestID) }) {
                try pendingStore.write(nil); pending = nil; error = nil
            }
            if result["pending"] as? Bool == true || result["nextCursor"] as? String != nil { historyRequested = true }
            let ids = Set(messages.flatMap(\.actionIDs))
            configurationDetails = configurationDetails.filter { ids.contains($0.key) }
            savedConfigurations = savedConfigurations.filter { ids.contains($0.key) }
            savedConfigurationIDs.formIntersection(ids)
            persistLocalConversation(); prefetchConfigurations()
        } catch let failure as Mango9SupervisorFailure where failure.status == 404 && failure.code == "conversation_not_found" {
            // A new conversation is created only by its first text/voice turn.
            if valid(stamp), id == conversationID, pending != nil { error = Mango9SupervisorFailure(status: 0, code: "history_pending").localizedDescription }
            if valid(stamp), id == conversationID {
                messages = []; configurationDetails = [:]; savedConfigurations = [:]; savedConfigurationIDs = []; usingSavedHistory = false
                savedChats.removeAll { $0.id == id }; writeLocalConversations()
            }
        } catch { if valid(stamp) { self.error = notice(error); _ = discardForAccessError(error) } }
    }
    func loadOlderHistory() async {
        guard opened, authorized, api.active, !loadingOlderMessages, !loadingLatestHistory, let olderCursor else { return }
        let id = conversationID, stamp = operation, flight = UUID(); olderFlight = flight; loadingOlderMessages = true
        defer { if stamp == operation, olderFlight == flight { loadingOlderMessages = false } }
        do {
            let result = try await api.request("history", body: ["action": "read", "conversationId": id, "before": olderCursor])
            guard valid(stamp), olderFlight == flight, conversationID == id else { return }
            let rows = (result["messages"] as? [[String: Any]] ?? []).compactMap(Mango9SupervisorMessage.init)
            let known = Set(messages.map(\.id))
            messages.insert(contentsOf: rows.filter { !known.contains($0.id) }, at: 0)
            self.olderCursor = result["olderCursor"] as? String; moreMessages = self.olderCursor != nil
            persistLocalConversation(); prefetchConfigurations()
        } catch { if valid(stamp), olderFlight == flight { self.error = notice(error); _ = discardForAccessError(error) } }
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
            if valid(stamp) { persistLocalConversation(); prefetchConfigurations() }
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
        guard let detail = configurationDetails[expected.id] ?? actionDetail, Mango9SupervisorApproval(detail) == expected else {
            error = Mango9SupervisorFailure(status: 0, code: "action_changed").localizedDescription; return
        }
        guard !savedConfigurationIDs.contains(expected.id) else { error = "Refresh these saved settings before applying changes."; return }
        let stamp = operation; actionBusy = true
        configurationTokens.removeValue(forKey: expected.id); configurationFlights.remove(expected.id)
        defer { if stamp == operation { actionBusy = false } }
        do {
            let result = try await api.request("actions/" + expected.id + "/decision", body: ["decision": decision, "approvalDigest": expected.digest])
            guard valid(stamp) else { return }; actionDetail = result; rememberConfiguration(result, id: expected.id)
        } catch { if valid(stamp) {
            self.error = notice(error); actionDetail = nil; markConfigurationsSaved(); _ = discardForAccessError(error)
        } }
        guard valid(stamp) else { return }
        actionBusy = false; await refreshHistory(); await loadConfiguration(expected.id)
        for _ in 0..<12 {
            guard valid(stamp), ["APPROVED", "EXECUTING"].contains(configurationDetails[expected.id]?["state"] as? String ?? "") else { break }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard valid(stamp) else { break }
            await loadConfiguration(expected.id)
        }
    }
    func loadConfiguration(_ id: String) async {
        guard opened, authorized, api.active, messages.contains(where: { $0.actionIDs.contains(id) }), UUID(uuidString: id) != nil, !actionBusy, !configurationFlights.contains(id) else { return }
        let stamp = operation, token = UUID(); configurationTokens[id] = token; configurationFlights.insert(id)
        defer { if stamp == operation, configurationTokens[id] == token { configurationTokens.removeValue(forKey: id); configurationFlights.remove(id) } }
        do {
            let detail = try await api.request("actions/" + id)
            guard valid(stamp), configurationTokens[id] == token, detail["id"] as? String == id, !actionBusy else { return }
            rememberConfiguration(detail, id: id)
        } catch { if valid(stamp), configurationTokens[id] == token {
            self.error = notice(error)
            if discardForAccessError(error) { return }
            if (error as? Mango9SupervisorFailure)?.status == 404 {
                configurationDetails[id] = nil; savedConfigurations[id] = nil; savedConfigurationIDs.remove(id); persistLocalConversation()
            } else {
                if let saved = savedConfigurations[id] { configurationDetails[id] = saved.detail }
                savedConfigurationIDs.insert(id)
            }
        } }
    }
}
