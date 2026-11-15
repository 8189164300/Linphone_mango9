// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Combine
import UIKit

/// Local drafts and confirmed deletions are isolated by login identity.
struct Mango9AssistantDelivery: Codable {
    let command: Mango9SupervisorCommand
    var failed: Bool = false
    let createdAt: Date
}
struct Mango9AssistantState: Codable {
    var deliveries: [Mango9AssistantDelivery]? = nil
    var drafts: [String: String] = [:]
    var deleted: [String] = []
    static func location(_ account: String) throws -> URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("AssistantState", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var excluded = root; var values = URLResourceValues(); values.isExcludedFromBackup = true; try excluded.setResourceValues(values)
        return root.appendingPathComponent(account + ".json")
    }
    static func read(_ account: String) throws -> Self {
        let file = try location(account)
        guard FileManager.default.fileExists(atPath: file.path) else { return .init() }
        let state = try JSONDecoder().decode(Self.self, from: Data(contentsOf: file))
        guard (state.deliveries?.count ?? 0) <= 100,
              (state.deliveries ?? []).allSatisfy({ UUID(uuidString: $0.command.conversationID) != nil && UUID(uuidString: $0.command.requestID) != nil && UUID(uuidString: $0.command.messageID) != nil && Mango9SupervisorCommand.valid($0.command.text) }),
              state.drafts.count <= 10, state.deleted.count <= 256,
              state.drafts.allSatisfy({ UUID(uuidString: $0.key) != nil && $0.value.utf8.count <= 32_000 }),
              state.deleted.allSatisfy({ UUID(uuidString: $0) != nil }) else { throw Mango9AssistantMediaContract.invalid() }
        return state
    }
    func write(_ account: String) throws {
        try JSONEncoder().encode(self).write(to: Self.location(account), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static func unique(_ rows: [Mango9SupervisorConversation], excluding: Set<String>) -> [Mango9SupervisorConversation] {
        var unique: [String: Mango9SupervisorConversation] = [:]
        for row in rows where !excluding.contains(row.id.lowercased()) {
            if let previous = unique[row.id], (previous.updatedAt ?? .distantPast) > (row.updatedAt ?? .distantPast) { continue }
            unique[row.id] = row
        }
        return unique.values.sorted { ($0.updatedAt ?? .distantPast) > ($1.updatedAt ?? .distantPast) }
    }
}

struct Mango9AssistantUnread {
    let counts: [String: Int]
    let through: String
    init?(_ raw: [String: Any]) {
        guard let counts = raw["conversations"] as? [String: Int], let unread = raw["unread"] as? Int,
              let through = raw["readThrough"] as? String, !through.isEmpty, through.allSatisfy({ $0.isASCII && $0.isNumber }),
              counts.count <= 10000, counts.allSatisfy({ UUID(uuidString: $0.key) != nil && $0.value > 0 && $0.value <= 1_000_000 }),
              counts.values.reduce(0, +) == unread else { return nil }
        var normalized: [String: Int] = [:]
        for (id, count) in counts { guard normalized[id.lowercased()] == nil else { return nil }; normalized[id.lowercased()] = count }
        self.counts = normalized; self.through = through
    }
}

struct Mango9AssistantStateStore {
    let read: () throws -> Mango9AssistantState
    let write: (Mango9AssistantState) throws -> Void
    static func device(_ account: String) -> Self { .init(read: { try Mango9AssistantState.read(account) }, write: { try $0.write(account) }) }
}

struct Mango9AssistantPushTarget: Identifiable, Equatable {
    let id: String
    let identity: String
    let crmID: String
    let recipient: String
    init?(userInfo: [AnyHashable: Any]) {
        let data = Mango9MessagePushRoute.payload(userInfo)
        guard data["event"] as? String == "supervisor.reply",
              let id = (data["conversation_id"] ?? data["conversationId"]) as? String, UUID(uuidString: id) != nil,
              let crm = data["crm_id"] as? String, !crm.isEmpty,
              let identity = Mango9SessionStore.normalizedIdentity(data["sip_identity"] as? String) else { return nil }
        let raw = data["recipient_user_id"] ?? data["recipientUserId"]
        let recipient = (raw as? String) ?? (raw as? NSNumber)?.stringValue ?? ""
        guard let numeric = Int(recipient), numeric > 0, String(numeric) == recipient else { return nil }
        self.id = id.lowercased(); self.identity = identity; crmID = crm; self.recipient = recipient
    }
    func matches(_ session: Mango9Session) -> Bool { session.crmId == crmID && session.userId == recipient && Mango9SessionStore.normalizedIdentity(session.sipIdentity) == identity }
}
@MainActor final class Mango9AssistantPushInbox: ObservableObject {
    static let shared = Mango9AssistantPushInbox()
    @Published var target: Mango9AssistantPushTarget?
}
extension Notification.Name { static let mango9AssistantReply = Notification.Name("mango9AssistantReply") }

/// Acknowledged content stays read when an older list request finishes later.
/// A newer sequence/timestamp is never cleared by an earlier receipt.
struct Mango9InboxReadLedger {
    private var through: [String: Double] = [:]
    mutating func read(_ id: String, channel: String, version: Double?) {
        guard let version, version.isFinite, version > 0 else { return }
        let key = channel + ":" + id
        through[key] = max(through[key] ?? 0, version)
    }
    func unread(_ value: Bool, id: String, channel: String, version: Double?) -> Bool {
        guard value, let version, let read = through[channel + ":" + id] else { return value }
        return version > read
    }
}

/// Unfiltered channel totals. A read invalidates in-flight snapshots before refreshing.
@MainActor final class Mango9InboxUnread: ObservableObject {
    static let shared = Mango9InboxUnread()
    @Published private(set) var reads = Mango9InboxReadLedger()
    @Published private(set) var channels: [String: Set<String>] = [:]
    private var scope = ""
    private var login: String?
    private var running = false
    private var again = false
    private var revision = 0
    func count(_ channel: String) -> Int {
        guard let session = Mango9SessionStore.load(), scope == Mango9ReactionTarget.accountKey(session), login == session.loginGeneration else { return 0 }
        return channels[channel]?.count ?? 0
    }
    var externalTotal: Int {
        let tabs = Mango9ChatTabPreferences.shared
        return Mango9InboxCountPolicy.total(["social": count("social"), "live": count("live")], enabled: Set( (tabs.social ? ["social"] : []) + (tabs.live ? ["live"] : [])))
    }
    func assistant(_ counts: [String: Int], session: Mango9Session) {
        guard Mango9SessionStore.isActive(session) else { return }; bind(session)
        revision += 1; channels["assistant"] = Set(counts.keys); syncBadge()
    }
    func socialUnread(_ row: Mango9SocialThread, session: Mango9Session) -> Bool {
        guard scope == Mango9ReactionTarget.accountKey(session), login == session.loginGeneration else { return row.unread }
        return reads.unread(row.unread, id: row.id, channel: "social", version: row.readThrough.map(Double.init))
    }
    func socialReceipt(_ row: Mango9SocialThread, session: Mango9Session) {
        guard Mango9SessionStore.isActive(session) else { return }; bind(session)
        if row.unread { revision += 1; channels["social", default: []].insert(row.id); syncBadge() }
        else { didRead(row.id, channel: "social", session: session, version: row.readThrough.map(Double.init)) }
    }
    func didRead(_ id: String, channel: String, session: Mango9Session, version: Double? = nil) {
        guard Mango9SessionStore.isActive(session) else { return }; bind(session)
        reads.read(id, channel: channel, version: version)
        revision += 1; channels[channel]?.remove(id); syncBadge()
        Task { await refresh() }
    }
    private func bind(_ session: Mango9Session) {
        let key = Mango9ReactionTarget.accountKey(session)
        if scope != key || login != session.loginGeneration { scope = key; login = session.loginGeneration; revision += 1; reads = .init(); channels = [:] }
    }
    private func syncBadge() {
        guard Mango9SessionStore.load() != nil else { return }
        let tabs = Mango9ChatTabPreferences.shared
        UIApplication.shared.applicationIconBadgeNumber = Mango9ChatStore.shared.smsUnreadCount + (tabs.team ? Mango9ChatStore.shared.teamUnreadCount : 0) + externalTotal + count("assistant")
    }
    func refresh() async {
        guard let session = Mango9SessionStore.load() else { scope = ""; revision += 1; reads = .init(); channels = [:]; return }
        bind(session)
        guard UIApplication.shared.applicationState == .active else { return }
        if running { again = true; return }
        running = true; defer { running = false }
        repeat {
            again = false; let version = revision, account = scope
            let preferences = Mango9ChatTabPreferences.shared
            for channel in ["social", "live", "assistant"] {
                guard !Task.isCancelled, Mango9SessionStore.isActive(session), scope == account else { return }
                if (channel == "social" && !preferences.social) || (channel == "live" && !preferences.live) { channels[channel] = []; continue }
                do {
                    let ids = try await fetch(channel, session: session)
                    guard !Task.isCancelled, Mango9SessionStore.isActive(session), scope == account else { return }
                    if version == revision { channels[channel] = ids } else { again = true }
                } catch {
                    if Task.isCancelled { return }
                    var status = (error as? Mango9LiveChatFailure)?.status ?? (error as? Mango9SupervisorFailure)?.status
                    if case Mango9MessagingError.rejected(let code, _) = error { status = code }
                    if version == revision, [401,403,404].contains(status ?? 0) { channels[channel] = [] }
                }
            }
            syncBadge()
        } while again && !Task.isCancelled && Mango9SessionStore.isActive(session)
    }
    private func fetch(_ channel: String, session: Mango9Session) async throws -> Set<String> {
        if channel == "assistant" {
            let api = Mango9SupervisorAPI(session: session); _ = try await api.authorize()
            guard let status = Mango9AssistantUnread(try await api.request("notifications", body: ["action": "status"])) else { throw Mango9MessagingError.invalidResponse }
            let deleted = (try? Mango9AssistantState.read(Mango9SupervisorPersistence.key(session)).deleted) ?? []
            return Set(status.counts.keys).subtracting(deleted)
        }
        var before: String?, beforeID: String?, visited = Set<String>(), found = Set<String>(), pages = 0
        repeat {
            try Task.checkCancellation(); pages += 1; guard pages <= 100 else { throw Mango9MessagingError.invalidResponse }
            if channel == "social" {
                var query = [URLQueryItem(name: "kind", value: "dms"), URLQueryItem(name: "limit", value: "50")]
                if let before { query.append(.init(name: "before", value: before)) }
                let page = try await Mango9MessagingAPI.shared.request(Mango9SocialPage<Mango9SocialThread,String>.self, session: session, social: true, path: ["inbox"], query: query)
                guard page.items.count <= 50, page.next == nil || (page.next == page.items.last?.id && !page.items.isEmpty) else { throw Mango9MessagingError.invalidResponse }
                found.formUnion(page.items.filter { socialUnread($0, session: session) }.map(\.id)); before = page.next
            } else {
                var query = [URLQueryItem(name: "limit", value: "100"), URLQueryItem(name: "unread", value: "true")]
                if let before, let beforeID { query += [.init(name: "before", value: before), .init(name: "beforeId", value: beforeID)] }
                let page = try await Mango9LiveChatAPI.shared.request("list", session: session, query: query)
                guard let rows = page["conversations"] as? [[String: Any]], rows.count <= 100 else { throw Mango9MessagingError.invalidResponse }
                let parsed = rows.compactMap(Mango9LiveConversation.init); guard parsed.count == rows.count else { throw Mango9MessagingError.invalidResponse }
                found.formUnion(parsed.filter(\.unread).map(\.id)); before = page["next"] as? String; beforeID = page["nextId"] as? String
                guard before == nil || (!rows.isEmpty && beforeID == parsed.last?.id) else { throw Mango9MessagingError.invalidResponse }
            }
            if let before { guard !before.isEmpty, visited.insert(before + ":" + (beforeID ?? "")).inserted else { throw Mango9MessagingError.invalidResponse } }
        } while before != nil
        if channel == "social" {
            let visibility = Mango9PersonalConversationVisibility.shared
            try await visibility.refresh(session: session, channel: .social, ids: Array(found))
            return Set(found.filter { !visibility.isHidden($0, channel: .social) })
        }
        let rows = Array(found)
        for start in stride(from: 0, to: rows.count, by: 200) {
            let batch = Array(rows[start..<min(start + 200, rows.count)])
            let raw = try await Mango9LiveChatAPI.shared.request("conversation-visibility", session: session,
                body: ["conversationIds": batch, "hide": false])
            let receipt = try Mango9AssistantMediaContract.decode(raw, as: Mango9PersonalConversationVisibility.Receipt.self)
            guard receipt.scope == "user", receipt.channel == "livechat", receipt.state == "succeeded", Set(receipt.conversationIds) == Set(batch), Set(receipt.hiddenConversationIds).isSubset(of: Set(batch)) else { throw Mango9MessagingError.invalidResponse }
            found.subtract(receipt.hiddenConversationIds)
        }
        return found
    }
}

enum Mango9InboxCountPolicy {
    static func ids(_ rows: [(String, Int)], sms: Bool = false) -> Set<String> {
        Set(rows.filter { $0.1 > 0 && !$0.0.isEmpty }.map { sms ? String($0.0.drop(while: { $0 == "+" })) : $0.0 })
    }
    static func total(_ counts: [String: Int], enabled: Set<String>) -> Int {
        enabled.reduce(0) { $0 + max(0, counts[$1] ?? 0) }
    }
}
