// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

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

    func socialUnread(_ row: Mango9SocialThread, session: Mango9Session) -> Bool {
        guard scope == Mango9ReactionTarget.accountKey(session), login == session.loginGeneration else { return row.unread }
        return row.markedUnread == true ? row.unread : reads.unread(row.unread, id: row.id, channel: "social", version: row.readThrough.map(Double.init))
    }
    func socialReceipt(_ row: Mango9SocialThread, session: Mango9Session) {
        guard Mango9SessionStore.isActive(session) else { return }; bind(session)
        if row.unread { revision += 1; channels["social", default: []].insert(row.id); syncBadge() }
        else { didRead(row.id, channel: "social", session: session, version: row.readThrough.map(Double.init)) }
    }
    func socialReceipts(_ rows: [Mango9SocialReadState], session: Mango9Session) {
        guard Mango9SessionStore.isActive(session) else { return }; bind(session)
        var ledger = reads, ids = channels["social", default: []]
        for row in rows {
            if row.unread { ids.insert(row.id) }
            else { ledger.read(row.id, channel: "social", version: Double(row.readThrough)); ids.remove(row.id) }
        }
        revision += 1; reads = ledger; channels["social"] = ids; syncBadge()
        // The toolbar performs one authoritative refresh after the entire action.
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
        UIApplication.shared.applicationIconBadgeNumber = Mango9ChatStore.shared.smsUnreadCount + (tabs.team ? Mango9ChatStore.shared.teamUnreadCount : 0) + externalTotal
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
            for channel in ["social", "live"] {
                guard !Task.isCancelled, Mango9SessionStore.isActive(session), scope == account else { return }
                if (channel == "social" && !preferences.social) || (channel == "live" && !preferences.live) { channels[channel] = []; continue }
                do {
                    let ids = try await fetch(channel, session: session)
                    guard !Task.isCancelled, Mango9SessionStore.isActive(session), scope == account else { return }
                    if version == revision { channels[channel] = ids } else { again = true }
                } catch {
                    if Task.isCancelled { return }
                    var status = (error as? Mango9LiveChatFailure)?.status
                    if case Mango9MessagingError.rejected(let code, _) = error { status = code }
                    if version == revision, [401,403,404].contains(status ?? 0) { channels[channel] = [] }
                }
            }
            syncBadge()
        } while again && !Task.isCancelled && Mango9SessionStore.isActive(session)
    }
    private func fetch(_ channel: String, session: Mango9Session) async throws -> Set<String> {

        var before: String?, beforeID: String?, visited = Set<String>(), found = Set<String>(), pages = 0
        repeat {
            try Task.checkCancellation(); pages += 1; guard pages <= 100 else { throw Mango9MessagingError.invalidResponse }
            if channel == "social" {
                var query = [URLQueryItem(name: "kind", value: "dms"), URLQueryItem(name: "view", value: "unread"), URLQueryItem(name: "limit", value: "50")]
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
            let receipt = try JSONDecoder().decode(Mango9PersonalConversationVisibility.Receipt.self, from: JSONSerialization.data(withJSONObject: raw))
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
