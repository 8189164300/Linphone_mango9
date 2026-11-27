// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import CryptoKit

/// CRM has no shared read receipt. Keep only record IDs, scoped to this phone's account.
struct Mango9CRMNewRecords: Codable, Equatable {
    var initialized = false
    var known = Set<Int>()
    var unread = Set<Int>()
    mutating func reconcile(_ ids: Set<Int>) {
        if initialized { unread.formUnion(ids.subtracting(known)) }
        unread.formIntersection(ids); known = ids; initialized = true
    }
    mutating func read(_ id: Int) { known.insert(id); unread.remove(id) }
}

@MainActor final class Mango9CRMUnread: ObservableObject {
    static let shared = Mango9CRMUnread()
    @Published private(set) var records: [String: Mango9CRMNewRecords] = [:]
    var opportunityReceipts: [Int: Bool] { Dictionary(uniqueKeysWithValues: (records["opportunities"]?.unread ?? []).map { ($0, true) }) }
    private var account = ""
    private var running = false
    private var again = false
    private let defaults = UserDefaults.standard
    var total: Int { count(.lead) + count(.client) + opportunityReceipts.count }
    private func key(_ kind: Mango9CRMRecordKind) -> String { kind == .lead ? "leads" : "clients" }
    func count(_ kind: Mango9CRMRecordKind) -> Int { records[key(kind)]?.unread.count ?? 0 }
    func isNew(_ id: Int, kind: Mango9CRMRecordKind) -> Bool { records[key(kind)]?.unread.contains(id) == true }
    private func bind(_ session: Mango9Session?) {
        let next = session.map { SHA256.hash(data: Data([$0.crmApiBaseUrl, $0.userId, $0.sipIdentity ?? ""].joined(separator: "|").utf8)).map { String(format: "%02x", $0) }.joined() } ?? ""
        if account != next {
            account = next
            records = next.isEmpty ? [:] : defaults.data(forKey: "mango9.crm.new." + next).flatMap { try? JSONDecoder().decode([String: Mango9CRMNewRecords].self, from: $0) } ?? [:]
        }
    }
    private func save() { if !account.isEmpty, let data = try? JSONEncoder().encode(records) { defaults.set(data, forKey: "mango9.crm.new." + account) } }
    func read(_ id: Int, kind: Mango9CRMRecordKind, session: Mango9Session) {
        guard Mango9SessionStore.isActive(session) else { return }
        bind(session); var state = records[key(kind)] ?? .init(); state.read(id); records[key(kind)] = state; save()
    }
    func converted(_ id: Int, session: Mango9Session) {
        guard Mango9SessionStore.isActive(session) else { return }
        bind(session)
        var leads = records[key(.lead)] ?? .init(); leads.read(id); records[key(.lead)] = leads
        var clients = records[key(.client)] ?? .init(); clients.read(id); records[key(.client)] = clients
        save()
    }
    func refresh() async {
        bind(Mango9SessionStore.load())
        guard var session = Mango9SessionStore.load(), UIApplication.shared.applicationState == .active else { return }
        if running { again = true; return }
        running = true; defer { running = false }
        repeat {
            again = false; let scope = account
            await refreshOpportunities(session: session)
            for kind in [Mango9CRMRecordKind.lead, .client] {
                do {
                    let ids: Set<Int>
                    do { ids = try await snapshot(kind, session: session) }
                    catch Mango9CRMAPIError.unauthorized { session = try await Mango9CRMAPI.refresh(session: session); ids = try await snapshot(kind, session: session) }
                    guard !Task.isCancelled, scope == account, Mango9SessionStore.isActive(session) else { return }
                    // Merge into the current state so reading during an in-flight fetch wins.
                    var state = records[key(kind)] ?? .init(); state.reconcile(ids); records[key(kind)] = state; save()
                } catch {
                    if Task.isCancelled { return }
                    // An incomplete page or an outage must never reset a good count.
                }
            }
        } while again && !Task.isCancelled && Mango9SessionStore.isActive(session)
    }
    func refreshOpportunities(session: Mango9Session) async {
        guard Mango9SessionStore.isActive(session) else { return }; bind(session); let owner = account
        do {
            var ids = Set<Int>(), page = 1, total: Int?
            while page <= 200 {
                let result = try await Mango9OpportunityAPI.send(session, query: [.init(name: "page", value: String(page)), .init(name: "limit", value: "50"), .init(name: "sort", value: "created_at")])
                guard let rows = result["items"] as? [[String: Any]], let p = result["pagination"] as? [String: Any],
                      p["page"] as? Int == page, p["limit"] as? Int == 50, let expected = p["total"] as? Int, expected >= 0,
                      total == nil || total == expected, let more = p["hasMore"] as? Bool,
                      rows.count == min(50, max(0, expected - (page - 1) * 50)), more == (page * 50 < expected) else { throw Mango9OpportunityFailure.invalid }
                total = expected
                for row in rows { guard let id = Mango9OpportunityAPI.integer(row["id"]), id > 0, ids.insert(id).inserted else { throw Mango9OpportunityFailure.invalid } }
                if !more { break }; page += 1
            }
            guard ids.count == total, owner == account, Mango9SessionStore.isActive(session) else { return }
            var state = records["opportunities"] ?? .init(); state.reconcile(ids); records["opportunities"] = state; save()
        } catch { /* As with Leads, incomplete snapshots never erase a verified count. */ }
    }
    func readOpportunity(_ id: Int, session: Mango9Session) async {
        guard Mango9SessionStore.isActive(session) else { return }; bind(session)
        var state = records["opportunities"] ?? .init(); state.read(id); records["opportunities"] = state; save()
    }
    private func snapshot(_ kind: Mango9CRMRecordKind, session: Mango9Session) async throws -> Set<Int> {
        var ids = Set<Int>(), page = 1, pages = 1, total: Int?
        repeat {
            try Task.checkCancellation(); guard page <= 200, Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
            let rows: [Int], expected: Int, returnedPage: Int
            if kind == .lead {
                let result = try await Mango9CRMAPI.leads(session: session, search: "", status: "", page: page)
                rows = result.leads.map(\.id); expected = result.pagination.total; pages = result.pagination.pages; returnedPage = result.pagination.page
            } else {
                let result = try await Mango9CRMAPI.clients(session: session, search: "", page: page)
                rows = result.clients.map(\.id); expected = result.pagination.total; pages = result.pagination.pages; returnedPage = result.pagination.page
            }
            guard returnedPage == page, pages <= 200, expected >= 0, total == nil || total == expected, rows.allSatisfy({ $0 > 0 }), Set(rows).count == rows.count else { throw Mango9MessagingError.invalidResponse }
            total = expected; ids.formUnion(rows); page += 1
        } while page <= pages
        guard ids.count == total else { throw Mango9MessagingError.invalidResponse }
        return ids
    }
}

struct Mango9CRMNewBadge: View {
    let count: Int
    var color: Color = .mango9Primary
    var itemLabel = "new records"
    var body: some View {
        if count > 0 {
            Text(count > 99 ? "99+" : String(count)).font(.system(size: 10, weight: .bold)).foregroundColor(.white)
                .lineLimit(1).fixedSize(horizontal: true, vertical: true)
                .padding(.horizontal, 5).frame(minWidth: 18, minHeight: 18).background(color).clipShape(Capsule())
                .accessibilityLabel("\(count) \(itemLabel)")
        }
    }
}

/// The CRM navigation stack stays between the same app header and footer as the other tabs.
struct Mango9CRMFooter: View {
    let messages: Int
    let missedCalls: Int
    var showChat = true
    var showMeetings = false
    let select: (Int) -> Void
    @ObservedObject private var crm = Mango9CRMUnread.shared
    var body: some View {
        HStack(spacing: 0) {
            item("Contacts", icon: "address-book", destination: 0, count: 0)
            item("CRM", icon: "users-three-square", destination: 4, count: crm.total)
            item("Calls", icon: "phone", destination: 1, count: missedCalls)
            if showChat { item("Chat", icon: "chat-teardrop-text", destination: 2, count: messages) }
            if showMeetings { item("Meetings", icon: "video-conference", destination: 3, count: 0) }
        }.padding(.top, 9).padding(.bottom, 8).background(Color.white)
            .overlay(alignment: .top) { Color.gray200.frame(height: 1) }
    }
    private func item(_ label: String, icon: String, destination: Int, count: Int) -> some View {
        Button { select(destination) } label: {
            VStack(spacing: 4) {
                Image(icon).renderingMode(.template).resizable().frame(width: 25, height: 25)
                    .overlay(alignment: .topTrailing) { Mango9CRMNewBadge(count: count, color: destination == 4 ? .mango9Primary : .redDanger500, itemLabel: destination == 4 ? "new records" : "unread").offset(x: 11, y: -5) }
                Text(label).font(.system(size: 10, weight: destination == 4 ? .semibold : .regular))
            }.foregroundColor(destination == 4 ? .mango9Primary : .grayMain2c600).frame(maxWidth: .infinity).frame(minHeight: 44)
        }.buttonStyle(.plain).accessibilityLabel(label).accessibilityIdentifier("crm.footer." + label.lowercased())
    }
}
