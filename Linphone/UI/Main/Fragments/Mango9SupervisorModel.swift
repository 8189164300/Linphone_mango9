// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import Combine

@MainActor final class Mango9SupervisorModel: ObservableObject {
    let api: any Mango9SupervisorClient
    let voice = Mango9SupervisorVoice()
    let transfer: Mango9AssistantTransfer
    @Published private(set) var replyPhase = "idle"
    @Published private(set) var unread: [String: Int] = [:]
    @Published private(set) var bulkBusy = false
    var readingConversation = true
    private var foreground = true
    private var assistantState = Mango9AssistantState()
    private var stateRestored = false
    private let stateStore: Mango9AssistantStateStore
    private let requestedConversation: String?
    private var draftSave: Task<Void, Never>?
    private var transferObserver: AnyCancellable?
    private var readMessageIDs = Set<String>()
    private var unreadFlight = UUID()
    var unreadChats: Int { unread.count }
    private var textRequests = Set<String>()
    var working: Bool { sending || (assistantState.deliveries ?? []).contains { $0.command.conversationID == conversationID && textRequests.contains($0.command.requestID) } || replyPhase != "idle" }
    func delivery(_ id: String) -> String? {
        guard let receipt = assistantState.deliveries?.first(where: { $0.command.messageID == id }) else { return nil }
        return receipt.failed ? "Not sent" : textRequests.contains(receipt.command.requestID) ? "Sending…" : "Delivery unconfirmed"
    }
    private func syncDelivery() {
        pending = assistantState.deliveries?.first(where: { $0.command.conversationID == conversationID && !$0.failed })?.command
    }
    private func saveDeliveries(_ rows: [Mango9AssistantDelivery]) throws {
        var next = assistantState; next.deliveries = rows
        try stateStore.write(next); assistantState = next; syncDelivery()
    }
    private func includeLocalMessages() {
        for receipt in assistantState.deliveries ?? [] where receipt.command.conversationID == conversationID {
            let c = receipt.command
            if !messages.contains(where: { $0.id == c.messageID }), let row = Mango9SupervisorMessage(["id": c.messageID, "role": "user", "parts": [["type": "text", "text": c.text]], "metadata": ["createdAt": ISO8601DateFormatter().string(from: receipt.createdAt)]]) { messages.append(row) }
        }
    }
    var canAttach: Bool { authorized && hasAIConsent && api.hasAIConsent && !loading && !voice.running && !transfer.active && api.active }

    @Published var messages: [Mango9SupervisorMessage] = []
    @Published var conversations: [Mango9SupervisorConversation] = []
    @Published var draft = ""
    @Published var loading = true
    @Published var sending = false
    @Published var authorized = false
    @Published private(set) var hasAIConsent = false
    @Published var showVoice = false
    @Published var error: String?
    @Published var historyRefreshError: String?
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
         pendingStore: Mango9SupervisorPendingStore? = nil, localStore: Mango9SupervisorLocalStore? = nil, stateStore: Mango9AssistantStateStore? = nil, conversation: String? = nil) {
        let connection = client ?? Mango9SupervisorAPI(session: session)
        api = connection
        transfer = Mango9AssistantTransfer(api: connection)
        self.defaults = defaults
        self.stateStore = stateStore ?? .device(Mango9SupervisorPersistence.key(session))
        requestedConversation = conversation.flatMap { UUID(uuidString: $0) == nil ? nil : $0.lowercased() }
        hasAIConsent = Mango9SupervisorConsent.accepted(session, defaults: defaults)
        account = Mango9SupervisorPersistence.key(session)
        self.pendingStore = pendingStore ?? .keychain(account)
        self.localStore = localStore ?? .device(session)
        conversationID = requestedConversation ?? defaults.string(forKey: "mango9.supervisor.conversation." + account) ?? UUID().uuidString.lowercased()
        if UUID(uuidString: conversationID) == nil { conversationID = UUID().uuidString.lowercased() }
        transferObserver = transfer.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        voice.onHistoryChanged = { [weak self] in
            guard let self else { return }
            self.historyRequested = true
            Task { await self.refreshHistory() }
        }
    }
    var canSend: Bool { opened && restoredPending && hasAIConsent && api.hasAIConsent && authorized && !loading && !sending  && !voice.running && api.active && !transfer.active && (transfer.draft != nil || Mango9SupervisorCommand.valid(draft)) }
    var canStartVoice: Bool { pending == nil && opened && restoredPending && hasAIConsent && api.hasAIConsent && authorized && !loading && !sending  && !transfer.active && api.active }
    var canManageHistory: Bool { opened && authorized && api.active && !loading && !sending  && !actionBusy && !voice.running && deletingConversation == nil && !bulkBusy && !transfer.active }
    var canBrowseHistory: Bool { opened && api.active && !loading && !sending  && !actionBusy && !voice.running && deletingConversation == nil && !bulkBusy && !transfer.active }
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
        transfer.pause(); replyPhase = "idle"; readMessageIDs = []
        operation = UUID(); historyTask?.cancel(); historyTask = nil; historyFlight = nil; listFlight = UUID()
        configurationPrefetch?.cancel(); configurationPrefetch = nil
        configurationDetails = [:]; configurationFlights = []; configurationTokens = [:]; actionDetail = nil
        savedConfigurations = [:]; savedConfigurationIDs = []
        loadingConversations = false; deletingConversation = nil
        olderFlight = UUID(); loadingOlderMessages = false; loadingLatestHistory = false
    }
    func start() async {
        guard !starting, !sending else { return }
        invalidateOperations(); opened = true; foreground = true; starting = true; loading = true; error = nil; historyRefreshError = nil; authorized = false
        historyReady = false
        let stamp = operation
        defer { if stamp == operation { loading = false; starting = false } }
        do {
            // A locked or corrupt Keychain must not look like an empty outbox.
            if !restoredPending {
                pending = try pendingStore.read()
                if let pending, requestedConversation == nil, defaults.string(forKey: conversationKey) == nil { conversationID = pending.conversationID }
                restoredPending = true
            }
            if !stateRestored {
                assistantState = try stateStore.read()
                if assistantState.deleted.contains(conversationID) { conversationID = UUID().uuidString.lowercased(); pending = nil; try pendingStore.write(nil) }
                if let legacy = pending, !(assistantState.deliveries ?? []).contains(where: { $0.command.requestID == legacy.requestID }) {
                    try saveDeliveries((assistantState.deliveries ?? []) + [.init(command: legacy, createdAt: Date())])
                }
                // Migrate the old single-request journal only after the complete
                // receipt list has been stored. Unknown requests are never replayed.
                try pendingStore.write(nil); syncDelivery(); stateRestored = true
                draft = assistantState.drafts[conversationID] ?? draft
            }
            guard valid(stamp) else { clearForAccountChange(); return }
            do { savedChats = try localStore.read(); localStorageNotice = nil }
            catch { localStorageNotice = "Couldn’t open the saved copy on this iPhone. Online chat still works." }
            restoreLocalConversation(); includeLocalMessages()
            let status = try await api.authorize()
            guard valid(stamp) else { return }
            authorized = true; walletBilling = status["billingEnabled"] as? Bool == true
            // A list outage must not prevent loading the current conversation.
            do { try await loadConversations() } catch { if discardForAccessError(error) { throw error } }
            guard valid(stamp) else { return }
            if pending == nil, requestedConversation == nil, defaults.string(forKey: conversationKey) == nil, let latest = conversations.first {
                conversationID = latest.id; draft = assistantState.drafts[conversationID] ?? ""; restoreLocalConversation()
            }
            defaults.set(conversationID, forKey: conversationKey)
            await transfer.configure(conversationID)
            await refreshHistory(); await refreshUnread()
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
                if self.foreground && ticks % (self.working || self.voice.running || self.historyRequested ? 5 : 15) == 0 && self.historyTask == nil {
                    Task { [weak self] in await self?.refreshHistory(); await self?.refreshUnread() }
                }
            }
        }
    }
    func close() {
        saveDraftNow(); opened = false; invalidateOperations(); poll?.cancel(); poll = nil; voice.stop()
        loading = false; starting = false; sending = false; actionBusy = false; showVoice = false; authorized = false
    }
    func background() {
        foreground = false; saveDraftNow(); transfer.pause()
        voice.stop(); showVoice = false; configurationPrefetch?.cancel()
        configurationTokens = [:]; configurationFlights = []; markConfigurationsSaved()
    }
    func resume(latest: Bool = true) async {
        foreground = true
        if !authorized { await start() }
        else { await refreshHistory(latest: latest); await refreshUnread(); prefetchConfigurations() }
    }
    private func clearForAccountChange() {
        close(); hasAIConsent = false; messages = []; conversations = []; actions = []; activity = []; actionDetail = nil; draft = ""; pending = nil
        historyCursor = "0"; historyReady = false; olderCursor = nil; moreMessages = false
        savedChats = []; usingSavedHistory = false
        error = Mango9SupervisorFailure(status: 0, code: "scope_changed").localizedDescription
    }
    private func restoreLocalConversation() {
        guard api.active else { return }
        guard !assistantState.deleted.contains(conversationID), let saved = savedChats.first(where: { $0.id == conversationID }) else {
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
        return "Couldn’t reach Assistant. Check your connection and try again."
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
        conversations = Mango9AssistantState.unique((more ? conversations : []) + rows, excluding: Set(assistantState.deleted))
        let next = result["nextCursor"] as? String
        listCursor = next?.isEmpty == false && next != listCursor ? next : nil; moreConversations = listCursor != nil
    }
    func deleteConversation(_ id: String) async { _ = await deleteConversations([id]) }
    /// Four independent deletes per batch; publish one result instead of flickering row counters.
    func deleteConversations(_ ids: Set<String>) async -> Set<String> {
        guard canManageHistory, !ids.isEmpty, ids.allSatisfy({ UUID(uuidString: $0) != nil }) else { return [] }
        let stamp = operation; bulkBusy = true; historyListError = nil
        listFlight = UUID(); loadingConversations = false
        var removed = Set<String>()
        let ordered = Array(ids)
        for offset in stride(from: 0, to: ordered.count, by: 4) {
            guard valid(stamp) else { break }
            let batch = Array(ordered[offset..<min(offset + 4, ordered.count)])
            let confirmed = await withTaskGroup(of: String?.self, returning: [String].self) { group in
                for id in batch { group.addTask { [api] in
                    do {
                        let result = try await api.request("history", body: ["action": "delete", "conversationId": id])
                        return result["deleted"] as? Bool == true && (result["id"] as? String)?.lowercased() == id ? id : nil
                    } catch let e as Mango9SupervisorFailure where e.status == 404 && e.code == "conversation_deleted" { return id }
                    catch { return nil }
                } }
                var result: [String] = []; for await id in group { if let id { result.append(id) } }; return result
            }
            removed.formUnion(confirmed)
        }
        guard valid(stamp) else { bulkBusy = false; return [] }
        for id in removed { rememberDeleted(id) }
        bulkBusy = false
        if removed.contains(conversationID) { await recoverDeleted(conversationID) }
        if removed.count != ids.count { historyListError = "Some conversations could not be deleted. They remain selected; try again." }
        return removed
    }
    private func rememberDeleted(_ id: String) {
        try? saveDeliveries((assistantState.deliveries ?? []).filter { $0.command.conversationID != id })
        assistantState.deleted.removeAll { $0 == id }; assistantState.deleted.append(id)
        assistantState.deleted = Array(assistantState.deleted.suffix(256))
        assistantState.drafts[id] = nil; unread[id] = nil
        conversations.removeAll { $0.id == id }; savedChats.removeAll { $0.id == id }
        do { try stateStore.write(assistantState) } catch { localStorageNotice = "Couldn’t save the updated chat list on this iPhone." }
        writeLocalConversations()
    }
    private func recoverDeleted(_ id: String) async {
        guard opened, api.active, conversationID == id else { return }
        let unsent = draft
        let replacement: String
        do { replacement = try transfer.recoverDraft(from: id) }
        catch { self.error = "Couldn’t move the attachment draft. Your files are kept; try reopening Assistant."; return }
        rememberDeleted(id)
        do { try pendingStore.write(nil) } catch { self.error = "Couldn’t finish clearing the saved request. Reopen Assistant."; return }
        try? saveDeliveries((assistantState.deliveries ?? []).filter { $0.command.conversationID != id })
        pending = nil; sending = false; draft = ""
        invalidateOperations(); conversationID = replacement; defaults.set(conversationID, forKey: conversationKey)
        messages = []; usingSavedHistory = false; historyCursor = "0"; historyReady = false; olderCursor = nil; moreMessages = false; error = nil
        draft = unsent; saveDraftNow()
        await transfer.configure(conversationID)
    }
    func select(_ id: String) async {
        guard opened, api.active, !loading, !sending, !actionBusy, deletingConversation == nil, UUID(uuidString: id) != nil else { return }
        guard !assistantState.deleted.contains(id.lowercased()), !transfer.active else { return }
        saveDraftNow(); voice.stop(); showVoice = false; invalidateOperations(); conversationID = id.lowercased(); draft = assistantState.drafts[conversationID] ?? ""; actionDetail = nil; actionBusy = false
        messages = []; historyCursor = "0"; historyReady = false; olderCursor = nil; moreMessages = false; preferLatestHistory = false; error = nil
        syncDelivery(); restoreLocalConversation(); includeLocalMessages()
        defaults.set(id, forKey: conversationKey)
        let stamp = operation
        loading = true; await transfer.configure(conversationID); await refreshHistory(); if stamp == operation { loading = false }
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
        if historyFlight == flight { historyTask = nil; historyFlight = nil; await refreshReplyPhase(id: id, stamp: stamp) }
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
            historyRefreshError = nil
            usingSavedHistory = false
            if tail { olderCursor = result["olderCursor"] as? String; moreMessages = olderCursor != nil }
            let remaining = (assistantState.deliveries ?? []).filter { r in
                r.command.conversationID != id || !(result["confirmedRequestId"] as? String == r.command.requestID || rows.contains { $0.id == r.command.messageID || $0.requestID == r.command.requestID || $0.id == r.command.requestID })
            }
            try saveDeliveries(remaining); includeLocalMessages()
            replyPhase = result["pending"] as? Bool == true ? "thinking" : "idle"
            if result["pending"] as? Bool == true || result["nextCursor"] as? String != nil { historyRequested = true }
            let ids = Set(messages.flatMap(\.actionIDs))
            configurationDetails = configurationDetails.filter { ids.contains($0.key) }
            savedConfigurations = savedConfigurations.filter { ids.contains($0.key) }
            savedConfigurationIDs.formIntersection(ids)
            persistLocalConversation(); prefetchConfigurations(); await markVisibleRead()
        } catch let failure as Mango9SupervisorFailure where failure.status == 404 && failure.code == "conversation_deleted" {
            if valid(stamp), id == conversationID { await recoverDeleted(id) }
        } catch let failure as Mango9SupervisorFailure where failure.status == 404 && failure.code == "conversation_not_found" {
            // A new conversation is created only by its first text/voice turn.

            if valid(stamp), id == conversationID {
                messages = []; includeLocalMessages(); configurationDetails = [:]; savedConfigurations = [:]; savedConfigurationIDs = []; usingSavedHistory = false
                savedChats.removeAll { $0.id == id }; writeLocalConversations()
            }
        } catch { if valid(stamp) { if discardForAccessError(error) { self.error = notice(error) } else if !(error is CancellationError) { historyRefreshError = notice(error) } } }
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
        if transfer.draft != nil { await sendAttachment(); return }
        guard (assistantState.deliveries?.count ?? 0) < 100 else { error = "Reconnect to synchronize unconfirmed messages."; return }
        let command = Mango9SupervisorCommand(conversationID: conversationID, text: draft.trimmingCharacters(in: .whitespacesAndNewlines))
        do { try saveDeliveries((assistantState.deliveries ?? []) + [.init(command: command, createdAt: Date())]) }
        catch { self.error = "Couldn’t save this message. Your text is still in the composer."; return }
        draft = ""; saveDraftNow(); error = nil; historyRefreshError = nil; includeLocalMessages(); persistLocalConversation()
        await transmit(command)
    }
    func retryMessage(_ id: String) async {
        guard authorized, hasAIConsent, api.hasAIConsent, api.active, !voice.running,
              let receipt = assistantState.deliveries?.first(where: { $0.command.messageID == id && $0.command.conversationID == conversationID && $0.failed }) else { return }
        var rows = assistantState.deliveries ?? []
        if let i = rows.firstIndex(where: { $0.command.messageID == id }) { rows[i].failed = false }
        do { try saveDeliveries(rows) } catch { self.error = "Couldn’t save message delivery."; return }
        await transmit(receipt.command)
    }
    private func transmit(_ command: Mango9SupervisorCommand) async {
        let stamp = operation
        historyTask?.cancel(); historyTask = nil; historyFlight = nil
        textRequests.insert(command.requestID); objectWillChange.send()
        defer { textRequests.remove(command.requestID); objectWillChange.send() }
        do {
            let raw = try await api.request("chat", body: command.body)
            guard valid(stamp) else { return }
            guard let message = Mango9SupervisorMessage(raw), message.role == "assistant", message.requestID == command.requestID || message.id == command.requestID, ((raw["metadata"] as? [String: Any])?["conversationId"] as? String).map({ $0 == command.conversationID }) ?? true else { throw Mango9SupervisorFailure(status: 0, code: "invalid_response") }
            historyTask?.cancel(); historyTask = nil; historyFlight = nil
            if !messages.contains(where: { $0.id == message.id }) { messages.append(message) }
            try saveDeliveries((assistantState.deliveries ?? []).filter { $0.command.requestID != command.requestID })
            persistLocalConversation(); await refreshHistory(); try? await loadConversations()
        } catch {
            guard valid(stamp) else { return }
            if let failure = error as? Mango9SupervisorFailure,
               [400, 401, 402, 403, 413, 422, 429].contains(failure.status) || (failure.status == 409 && failure.code == "support_pilot_busy") {
                var rows = assistantState.deliveries ?? []
                if let i = rows.firstIndex(where: { $0.command.requestID == command.requestID }) { rows[i].failed = true }
                try? saveDeliveries(rows)
            }
            // Reconcile lost receipts in the background without resubmitting
            // uncertain commands or disabling the next message.
            await refreshHistory()
        }
    }
    func draftChanged() {
        draftSave?.cancel()
        draftSave = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }; self?.saveDraftNow()
        }
    }
    private func saveDraftNow() {
        draftSave?.cancel()
        guard stateRestored, api.active, !assistantState.deleted.contains(conversationID) else { return }
        if assistantState.drafts.count >= 10, assistantState.drafts[conversationID] == nil, let key = assistantState.drafts.keys.sorted().first { assistantState.drafts[key] = nil }
        assistantState.drafts[conversationID] = draft.isEmpty ? nil : String(draft.prefix(8000))
        do { try stateStore.write(assistantState) } catch { localStorageNotice = "Couldn’t save your draft on this iPhone." }
    }
    private func sendAttachment() async {
        let stamp = operation, text = draft
        sending = true
        defer { if stamp == operation { sending = false } }
        guard let receipt = await transfer.send(caption: text), valid(stamp) else { return }
        if draft.trimmingCharacters(in: .whitespacesAndNewlines) == receipt.caption { draft = ""; saveDraftNow() }
        replyPhase = "waiting"; historyRequested = true
        await refreshHistory(); try? await loadConversations()
    }
    private func refreshReplyPhase(id: String, stamp: UUID) async {
        guard foreground, valid(stamp), id == conversationID else { return }
        do {
            let value = try await api.request("transport", body: ["action": "replyStatus", "conversationId": id])
            guard valid(stamp), id == conversationID, value["version"] as? Int == 1,
                  value["conversationId"] as? String == id, let phase = value["phase"] as? String, ["idle", "waiting", "thinking"].contains(phase) else { return }
            replyPhase = phase
        } catch { /* A status outage must never replace readable history with a red banner. */ }
    }
    func refreshUnread() async {
        guard foreground, opened, authorized, api.active else { return }
        let stamp = operation, flight = UUID(); unreadFlight = flight
        do {
            let raw = try await api.request("notifications", body: ["action": "status"])
            guard valid(stamp), unreadFlight == flight, let status = Mango9AssistantUnread(raw) else { return }
            unread = status.counts.filter { !assistantState.deleted.contains($0.key) }
            Mango9InboxUnread.shared.assistant(unread, session: api.original)
        } catch { /* Keep the last verified count during a temporary outage. */ }
    }
    private func markVisibleRead() async {
        guard readingConversation, foreground, opened, api.active else { return }
        let ids = Array(messages.filter { $0.role == "assistant" && UUID(uuidString: $0.id) != nil && !readMessageIDs.contains($0.id) }.suffix(120).map(\.id))
        guard !ids.isEmpty else { return }
        let id = conversationID, stamp = operation
        unreadFlight = UUID()
        do {
            _ = try await api.request("notifications", body: ["action": "read", "conversationId": id, "messageIds": ids])
            guard valid(stamp), id == conversationID else { return }
            readMessageIDs.formUnion(ids); await refreshUnread()
        } catch { }
    }
    func markConversationsRead(_ ids: Set<String>) async {
        guard canManageHistory, !ids.isEmpty else { return }
        unreadFlight = UUID()
        let stamp = operation
        do {
            guard let status = Mango9AssistantUnread(try await api.request("notifications", body: ["action": "status"])), valid(stamp) else { return }
            let rows = Array(ids)
            for offset in stride(from: 0, to: rows.count, by: 200) {
                _ = try await api.request("notifications", body: ["action": "read_conversations", "conversationIds": Array(rows[offset..<min(rows.count, offset+200)]), "through": status.through])
                guard valid(stamp) else { return }
            }
            await refreshUnread()
        } catch { if valid(stamp) { historyListError = "Couldn’t mark these conversations read. Try again." } }
    }
    func allHistoryIDs() async -> Set<String> {
        guard canManageHistory else { return [] }
        var cursors = Set<String>()
        while moreConversations, let cursor = listCursor, cursors.insert(cursor).inserted {
            do { try await loadConversations(more: true) } catch { return [] }
        }
        return Set(conversations.map(\.id))
    }
    func report(_ message: Mango9SupervisorMessage, reason: String, details: String, reportID: String) async throws {
        guard opened, api.active, message.role == "assistant", UUID(uuidString: message.id) != nil,
              ["unsafe", "inappropriate", "incorrect", "privacy", "other"].contains(reason), details.utf8.count <= 2000 else { throw Mango9AssistantMediaContract.invalid() }
        let id = conversationID, stamp = operation
        let response = try await api.request("reports", body: ["conversationId": id, "messageId": message.id, "reason": reason, "details": details, "text": String(message.text.prefix(4000)), "reportId": reportID])
        guard valid(stamp), id == conversationID, response["accepted"] as? Bool == true, response["reportId"] as? String == reportID else { throw Mango9AssistantMediaContract.invalid() }
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
