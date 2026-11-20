// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import Security
import CryptoKit

struct Mango9OpportunityFailure: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
    static let invalid = Self(status: 0, message: "The server response could not be confirmed. Please refresh and try again.")
    static let accountChanged = Self(status: 0, message: "Your account changed. Please reopen this screen.")
}

/// The opportunities API deliberately uses its original field names (including custom stage IDs).
enum Mango9OpportunityAPI {
    static func integer(_ value: Any?) -> Int? {
        if let text = value as? String { return Int(text) }
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.rounded() == number.doubleValue { return number.intValue }
        return nil
    }
    static func creationID(_ receipt: [String: Any]) throws -> Int {
        guard let id = integer(receipt["id"]), id > 0, receipt["action"] as? String == "create",
              receipt["saved"] as? Bool == true || (receipt["duplicate"] as? Bool == true && receipt["unchanged"] as? Bool == true && receipt["saved"] as? Bool == false) else { throw Mango9OpportunityFailure.invalid }
        return id
    }
    static func duplicateMatches(_ row: [String: Any], command: [String: Any]) -> Bool {
        guard integer(row["contact_id"]) == integer(command["contactId"]), let values = command["values"] as? [String: Any] else { return false }
        return values.allSatisfy { key, value in
            let stored = row[key]
            if value is NSNull { return stored == nil || stored is NSNull }
            if ["value", "actual_value", "probability"].contains(key) { guard let expected = Decimal(string: String(describing: value)), let actual = stored.flatMap({ Decimal(string: String(describing: $0)) }) else { return false }; return expected == actual }
            if key == "next_due_at" { guard let expected = Mango9OpportunityStyle.parseDate(value as? String), let actual = Mango9OpportunityStyle.parseDate(stored as? String) else { return false }; return expected == actual }
            return String(describing: value) == stored.map { String(describing: $0) }
        }
    }
    static func key(_ session: Mango9Session) -> String {
        [session.crmApiBaseUrl, session.crmId, session.userId, session.sipIdentity ?? "", session.loginGeneration ?? ""].joined(separator: "|")
    }
    static func request(_ session: Mango9Session, path: String, contact: Bool = false,
                        query: [URLQueryItem] = [], body: [String: Any]? = nil, mobilePath: String? = nil) throws -> URLRequest {
        guard var url = URLComponents(string: session.crmApiBaseUrl), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { throw Mango9CRMAPIError.invalidConfiguration }
        let base = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard base.hasSuffix("api/v2"), !path.contains(".."), !path.contains("?") else { throw Mango9CRMAPIError.invalidConfiguration }
        url.path = contact ? "/" + base + "/mobile/contacts/" + path
            : "/" + String(base.dropLast("v2".count)) + "opportunities" + (path.isEmpty ? "" : "/" + path)
        if let mobilePath {
            guard body == nil, mobilePath.range(of: #"^(contacts/[1-9][0-9]*/activity(/item)?|documents/(quotes|invoices)/[1-9][0-9]*|sms/conversations/[a-f0-9]{64}(/messages)?)$"#, options: .regularExpression) != nil else { throw Mango9CRMAPIError.invalidConfiguration }
            url.path = "/" + base + "/mobile/" + mobilePath
        }
        url.queryItems = query.isEmpty ? nil : query
        guard let target = url.url else { throw Mango9CRMAPIError.invalidConfiguration }
        var request = URLRequest(url: target, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpMethod = "POST"
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }
    static func decode(_ data: Data, status: Int) throws -> [String: Any] {
        let envelope = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(status), envelope?["success"] as? Bool == true else {
            let raw = envelope?["message"] as? String ?? ""
            let fallback = status == 403 ? "Your account does not have permission for this action."
                : status == 409 ? "This record changed. Refresh it before trying again."
                : status == 401 ? "Please reconnect your CRM account."
                : "The request could not be confirmed. Please try again."
            // Do not display machine codes as user-facing prose.
            throw Mango9OpportunityFailure(status: status, message: raw.contains(" ") && raw.count < 500 ? raw : fallback)
        }
        guard let result = envelope?["data"] as? [String: Any] else { throw Mango9OpportunityFailure.invalid }
        return result
    }
    @MainActor static func send(_ session: Mango9Session, path: String = "", contact: Bool = false,
                               query: [URLQueryItem] = [], body: [String: Any]? = nil,
                               transport: URLSession = Mango9AuthTransport.session, mobilePath: String? = nil) async throws -> [String: Any] {
        func active() throws -> Mango9Session {
            guard let current = Mango9SessionStore.load(), Mango9SessionStore.isActive(session),
                  key(current) == key(session) else { throw Mango9OpportunityFailure.accountChanged }
            return current
        }
        var current = try active()
        for attempt in 0...1 {
            try Task.checkCancellation()
            do {
                let request = try request(current, path: path, contact: contact, query: query, body: body, mobilePath: mobilePath)
                try await Mango9SessionRenewal.shared.checkKnownExpiredBearer(request)
                let (data, response) = try await transport.data(for: request)
                _ = try active(); try Task.checkCancellation()
                guard let response = response as? HTTPURLResponse else { throw Mango9OpportunityFailure.invalid }
                if response.statusCode == 401 { throw Mango9CRMAPIError.unauthorized }
                return try decode(data, status: response.statusCode)
            } catch Mango9CRMAPIError.unauthorized {
                guard attempt == 0 else { throw Mango9CRMAPIError.unauthorized }
                let latest = try active()
                current = latest.accessToken != current.accessToken ? latest : try await Mango9CRMAPI.refresh(session: current)
                _ = try active()
            }
        }
        throw Mango9OpportunityFailure.invalid
    }
}

struct Mango9OpportunityMetadata {
    struct Stage: Identifiable {
        let id: String, name: String, classification: String
        let probability: Int
        let isDefault: Bool
    }
    struct Owner: Identifiable { let id: Int; let name: String }
    let userID: Int
    let tenantID: Int
    let manager: Bool
    let activityVersion: Int
    let stages: [Stage]
    let displayStages: [Stage]
    let owners: [Owner]
    let sources: [String]
    let permissions: [String: Bool]
    init(_ data: [String: Any]) throws {
        guard let user = Mango9OpportunityAPI.integer(data["userId"]), user > 0,
              let permissions = data["permissions"] as? [String: Bool],
              let specs = data["statusSpecs"] as? [[String: Any]] else { throw Mango9OpportunityFailure.invalid }
        userID = user; tenantID = Mango9OpportunityAPI.integer(data["tenantId"]) ?? user; manager = data["manager"] as? Bool == true; activityVersion = Mango9OpportunityAPI.integer(data["activityVersion"]) ?? 0; self.permissions = permissions
        displayStages = try specs.map {
            guard let id = $0["id"] as? String, let name = $0["name"] as? String,
                  let classification = $0["classification"] as? String,
                  let probability = Mango9OpportunityAPI.integer($0["probability"]), (0...100).contains(probability),
                  ["open", "won", "lost"].contains(classification) else { throw Mango9OpportunityFailure.invalid }
            return Stage(id: id, name: name, classification: classification, probability: probability, isDefault: $0["default"] as? Bool == true)
        }
        stages = displayStages.filter { stage in specs.first { $0["id"] as? String == stage.id }?["retired"] as? Bool != true }
        owners = (data["users"] as? [[String: Any]] ?? []).compactMap {
            guard let id = Mango9OpportunityAPI.integer($0["id"]), let name = $0["name"] as? String else { return nil }
            return Owner(id: id, name: name)
        }
        sources = data["sources"] as? [String] ?? []
    }
}

struct Mango9Opportunity: Identifiable {
    let raw: [String: Any]
    let id: Int
    let title: String, stage: String, owner: String, currency: String
    let value: String?, closeDate: String?, description: String, source: String, nextAction: String, nextDue: String?
    let probability: Int
    let actualValue: String?, actualClose: String?, lossReason: String
    init(_ data: [String: Any]) throws {
        guard let id = Mango9OpportunityAPI.integer(data["id"]), id > 0,
              let title = data["title"] as? String, let stage = data["stage"] as? String else { throw Mango9OpportunityFailure.invalid }
        raw = data; self.id = id; self.title = title; self.stage = stage
        owner = data["owner_name"] as? String ?? ""
        currency = data["currency"] as? String ?? ""
        value = data["value"] as? String; closeDate = data["close_date"] as? String
        description = data["description"] as? String ?? ""; source = data["source"] as? String ?? ""
        nextAction = data["next_action"] as? String ?? ""; nextDue = data["next_due_at"] as? String
        probability = Mango9OpportunityAPI.integer(data["probability"]) ?? 0
        actualValue = data["actual_value"] as? String; actualClose = data["actual_close_date"] as? String
        lossReason = data["loss_reason"] as? String ?? ""
    }
    var amount: String { value.map { currency + " " + $0 } ?? "Value not set" }
}

/// Preserve the exact command across an interrupted save, including its idempotency key.
/// Customer draft data lives in Keychain, never a public diagnostics log or preferences file.
enum Mango9OpportunityCommands {
    static func query(_ session: Mango9Session, contact: Int, operation: String) -> [String: Any] {
        let identity = [session.crmApiBaseUrl, session.crmId, session.userId, session.sipIdentity ?? ""].joined(separator: "|")
        let account = SHA256.hash(data: Data(identity.utf8)).map { String(format: "%02x", $0) }.joined()
        return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.mango9.crm.commands",
                kSecAttrAccount as String: account + ":\(contact):" + operation]
    }
    static func load(_ session: Mango9Session, contact: Int, operation: String) throws -> [String: Any]? {
        var query = query(session, contact: contact, operation: operation)
        query[kSecReturnData as String] = true; query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Mango9OpportunityFailure.invalid }
        return value
    }
    static func save(_ body: [String: Any]?, session: Mango9Session, contact: Int, operation: String) throws {
        let query = query(session, contact: contact, operation: operation)
        guard let body else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw Mango9OpportunityFailure.invalid }; return
        }
        let data = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query; item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw Mango9OpportunityFailure.invalid }
        } else if status != errSecSuccess { throw Mango9OpportunityFailure.invalid }
    }
    static func definiteRejection(_ error: Error) -> Bool {
        guard let error = error as? Mango9OpportunityFailure else { return false }
        return [400, 403, 404, 409, 413, 415, 422, 428].contains(error.status)
    }
}

/// A short-lived, account-scoped first page shared by the CRM entry and list.
/// Its total counts Opportunities, including multiple Opportunities for one contact.
struct Mango9OpportunityOverview {
    let account: String
    let loadedAt: Date
    let metadata: Mango9OpportunityMetadata
    let rows: [Mango9Opportunity]
    let summary: [[String: Any]]
    let total: Int
    let more: Bool
    init(session: Mango9Session, metadata: Mango9OpportunityMetadata, data: [String: Any], now: Date = Date()) throws {
        guard let raw = data["items"] as? [[String: Any]], let summary = data["summary"] as? [[String: Any]],
              let pagination = data["pagination"] as? [String: Any], Mango9OpportunityAPI.integer(pagination["page"]) == 1,
              let total = Mango9OpportunityAPI.integer(pagination["total"]), total >= 0,
              let more = pagination["hasMore"] as? Bool else { throw Mango9OpportunityFailure.invalid }
        account = Mango9OpportunityAPI.key(session); loadedAt = now; self.metadata = metadata
        let rows = try raw.map(Mango9Opportunity.init)
        guard total >= rows.count, Set(rows.map(\.id)).count == rows.count else { throw Mango9OpportunityFailure.invalid }
        self.rows = rows; self.summary = summary; self.total = total; self.more = more
    }
    func matches(_ session: Mango9Session, now: Date = Date()) -> Bool {
        account == Mango9OpportunityAPI.key(session) && now.timeIntervalSince(loadedAt) >= 0 && now.timeIntervalSince(loadedAt) < 30
    }
}

@MainActor final class Mango9OpportunityCards: ObservableObject {
    @Published var metadata: Mango9OpportunityMetadata?
    @Published var counts: [Int: Int] = [:]
    @Published private(set) var overview: Mango9OpportunityOverview?
    private var generation = UUID()
    private var account = ""
    func refresh(ids: [Int], includeOverview: Bool = false) async {
        let generation = UUID(); self.generation = generation
        guard let session = Mango9SessionStore.load() else { metadata = nil; counts = [:]; overview = nil; return }
        if account != Mango9OpportunityAPI.key(session) { metadata = nil; counts = [:]; overview = nil; account = Mango9OpportunityAPI.key(session) }
        do {
            let metadata = try Mango9OpportunityMetadata(await Mango9OpportunityAPI.send(session, path: "metadata"))
            guard generation == self.generation, !Task.isCancelled else { return }
            self.metadata = metadata
            guard metadata.permissions["access"] == true else { counts = [:]; overview = nil; return }
            if includeOverview {
                let data = try await Mango9OpportunityAPI.send(session, query: [
                    .init(name: "sort", value: "updated_at"), .init(name: "direction", value: "desc"),
                    .init(name: "page", value: "1"), .init(name: "limit", value: "25")])
                guard generation == self.generation, !Task.isCancelled else { return }
                overview = try Mango9OpportunityOverview(session: session, metadata: metadata, data: data)
            }
            let ids = Array(Set(ids)).sorted(); var counts: [Int: Int] = [:]
            for start in stride(from: 0, to: ids.count, by: 100) {
                let batch = Array(ids[start..<min(start + 100, ids.count)])
                let data = try await Mango9OpportunityAPI.send(session, path: "contactCounts", query: [.init(name: "ids", value: batch.map(String.init).joined(separator: ","))])
                guard let rows = data["counts"] as? [[String: Any]] else { throw Mango9OpportunityFailure.invalid }
                for id in batch { counts[id] = 0 }
                for row in rows {
                    guard let id = Mango9OpportunityAPI.integer(row["id"]), batch.contains(id),
                          let count = Mango9OpportunityAPI.integer(row["count"]), count >= 0 else { throw Mango9OpportunityFailure.invalid }
                    counts[id] = count
                }
            }
            guard generation == self.generation, !Task.isCancelled else { return }
            self.counts = counts
        } catch {
            guard generation == self.generation, !Task.isCancelled else { return }
            counts = [:] // Unknown is not displayed as zero.
            if let failure = error as? Mango9OpportunityFailure, [401, 403].contains(failure.status) { metadata = nil; overview = nil }
            if let failure = error as? Mango9CRMAPIError, failure.isSignInRequired { metadata = nil; overview = nil }
        }
    }
}

struct Mango9CRMCardActions: View {
    let record: Mango9Lead
    let kind: Mango9CRMRecordKind
    @ObservedObject var opportunities: Mango9OpportunityCards
    private enum Destination: String, Identifiable { case opportunities, create, convert; var id: String { rawValue } }
    @State private var destination: Destination?
    var body: some View {
        VStack(spacing: 0) {
          HStack(spacing: 2) {
            if opportunities.metadata?.permissions["access"] == true, (opportunities.counts[record.id] ?? 0) > 0 {
                Button { destination = .opportunities } label: {
                    VStack(spacing: 1) {
                        Image(systemName: "dollarsign.circle.fill").font(.system(size: 22))
                        if let count = opportunities.counts[record.id], count > 0 { Text(String(count)).font(.system(size: 10, weight: .semibold)) }
                    }.frame(minWidth: 42, minHeight: 44).foregroundColor(Color(red: 0.08, green: 0.49, blue: 0.24))
                }.accessibilityLabel("Opportunities for \(record.name)").accessibilityIdentifier("crm.opportunities.\(record.id)")
            }
            if (kind == .lead && record.permissions?.canUpdate != false) || opportunities.metadata?.permissions["create"] == true {
                Menu {
                    if opportunities.metadata?.permissions["create"] == true {
                        Button { destination = .create } label: { Label("Add Opportunity", systemImage: "plus.circle") }
                    }
                    if kind == .lead && record.permissions?.canUpdate != false { Button { destination = .convert } label: { Label("Convert to Client", systemImage: "person.crop.circle.badge.checkmark") } }
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 18, weight: .semibold)).foregroundColor(.mango9Primary).frame(width: 38, height: 44)
                }.accessibilityLabel("Contact actions").accessibilityIdentifier("crm.\(kind == .lead ? "lead" : "client").actions.\(record.id)")
            }
          }
          if let sharing = record.sharing, !sharing.entries.isEmpty { Mango9SharingBadges(sharing: sharing) }
        }.buttonStyle(.plain).tint(.mango9Primary)
        .background {
            // A contact can have multiple Opportunities; always open its list.
            NavigationLink(tag: Destination.opportunities, selection: $destination) { Mango9OpportunityWorkspace(contact: record) } label: { EmptyView() }.hidden()
            NavigationLink(tag: Destination.create, selection: $destination) { Mango9NewContactOpportunity(record: record) } label: { EmptyView() }.hidden()
            NavigationLink(tag: Destination.convert, selection: $destination) { Mango9ConvertLead(record: record) } label: { EmptyView() }.hidden()
        }
        .onChange(of: destination) { value in
            if value == nil { Task { await opportunities.refresh(ids: Array(opportunities.counts.keys) + [record.id]) } }
        }
    }
}

/// Present creation directly, avoiding a second sheet during the first sheet's animation.
struct Mango9NewContactOpportunity: View {
    let record: Mango9Lead
    @Environment(\.dismiss) private var dismiss
    @State private var session: Mango9Session?
    @State private var metadata: Mango9OpportunityMetadata?
    @State private var error: String?
    @State private var busy = false
    var body: some View {
        Group {
            if let session, let metadata, metadata.permissions["create"] == true {
                Mango9OpportunityForm(record: record, session: session, metadata: metadata) { dismiss() }
            } else {
                Mango9OpportunityPage(title: "New Opportunity", actions: { EmptyView() }) {
                    VStack(spacing: 16) {
                        if busy { ProgressView("Loading Opportunity fields…") }
                        if let error { Text(error); Button("Retry") { Task { await load() } } }
                    }.padding()
                }
            }
        }.tint(.mango9Primary).task { await load() }
    }
    private func load() async {
        guard !busy else { return }
        busy = true; error = nil; defer { busy = false }
        guard let captured = Mango9SessionStore.load() else { error = "Connect your CRM account first."; return }
        do {
            let loaded = try Mango9OpportunityMetadata(await Mango9OpportunityAPI.send(captured, path: "metadata"))
            guard loaded.permissions["create"] == true else { throw Mango9OpportunityFailure(status: 403, message: "Creating Opportunities is not available for this account.") }
            session = captured; metadata = loaded
        } catch { self.error = error.localizedDescription }
    }
}

struct Mango9ConvertLead: View {
    let record: Mango9Lead
    @Environment(\.dismiss) private var dismiss
    @State private var session: Mango9Session?
    @State private var revision: String?
    @State private var permitted = false
    @State private var busy = false
    @State private var error: String?
    @State private var pending: [String: Any]?
    var body: some View {
        Mango9OpportunityPage(title: "Convert to Client", actions: { EmptyView() }) {
            Form {
                Section {
                    Text(record.name).font(.headline)
                    Text("Convert this lead to a client? Its contact details and linked Opportunities will stay with the same record.")
                }
                if busy { ProgressView("Checking record…") }
                if let error { Section { Text(error).foregroundColor(.red); Button("Try again") { Task { await load() } } } }
                if permitted || pending != nil {
                    Section {
                        Button(pending == nil ? "Convert to Client" : "Retry conversion") { Task { await convert() } }
                            .disabled(busy).accessibilityIdentifier("crm.convert.confirm")
                    }
                } else if !busy && error == nil { Text("Conversion is not available for this lead in your account.").foregroundColor(.secondary) }
            }
                .task { session = Mango9SessionStore.load(); await load() }
        }.tint(.mango9Primary)
    }
    private func load() async {
        guard let session else { error = "Connect your CRM account first."; return }
        busy = true; error = nil; defer { busy = false }
        do {
            pending = try Mango9OpportunityCommands.load(session, contact: record.id, operation: "convert")
            let state = try await Mango9OpportunityAPI.send(session, path: "\(record.id)/control-state", contact: true)
            guard Mango9OpportunityAPI.integer(state["id"]) == record.id else { throw Mango9OpportunityFailure.invalid }
            if state["is_client"] as? Bool == true { try completed(session); return }
            guard let revision = state["revision"] as? String, revision.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
                  let permissions = state["permissions"] as? [String: Bool] else { throw Mango9OpportunityFailure.invalid }
            self.revision = revision; permitted = permissions["can_convert_to_client"] == true
        } catch { self.error = error.localizedDescription }
    }
    private func completed(_ session: Mango9Session) throws {
        try Mango9OpportunityCommands.save(nil, session: session, contact: record.id, operation: "convert")
        Mango9CRMUnread.shared.converted(record.id, session: session)
        NotificationCenter.default.post(name: .mango9LeadDidChange, object: nil, userInfo: ["convertedId": record.id])
        NotificationCenter.default.post(name: .mango9ClientDidChange, object: nil)
        dismiss()
    }
    private func convert() async {
        guard let session, let revision, !busy, permitted || pending != nil else { return }
        busy = true; error = nil; defer { busy = false }
        do {
            let body = pending ?? ["requestId": UUID().uuidString, "revision": revision]
            try Mango9OpportunityCommands.save(body, session: session, contact: record.id, operation: "convert"); pending = body
            let state = try await Mango9OpportunityAPI.send(session, path: "\(record.id)/convert-to-client", contact: true, body: body)
            guard Mango9OpportunityAPI.integer(state["id"]) == record.id, state["is_client"] as? Bool == true else { throw Mango9OpportunityFailure.invalid }
            try completed(session)
        } catch {
            if Mango9OpportunityCommands.definiteRejection(error) {
                try? Mango9OpportunityCommands.save(nil, session: session, contact: record.id, operation: "convert")
                pending = nil; permitted = false
            }
            self.error = error.localizedDescription
        }
    }
}

struct Mango9ContactOpportunities: View {
    let record: Mango9Lead
    var body: some View { Mango9OpportunityWorkspace(contact: record) }
}

struct Mango9OpportunityDraft {
    var title = "", stage = "", value = "", currency = "USD", ownerID = 0, probability = 0, manualProbability = false
    var hasCloseDate = false, closeDate = Date(), source = "", description = "", nextAction = "", hasNextDue = false, nextDue = Date()
    var actualValue = "", actualClose = Date(), lossReason = ""
    static let currencies = ["USD", "CAD", "EUR", "GBP", "AUD", "NZD", "AMD", "AED", "INR", "JPY", "CHF", "MXN"]
    static func date(_ date: Date) -> String {
        let formatter = DateFormatter(); formatter.calendar = Calendar(identifier: .gregorian); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"; return formatter.string(from: date)
    }
    func values(metadata: Mango9OpportunityMetadata, existingStage: String? = nil) throws -> [String: Any] {
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.count <= 250, let stage = metadata.displayStages.first(where: { $0.id == stage && ($0.id == existingStage || metadata.stages.contains { $0.id == stage }) }),
              Self.currencies.contains(currency), (0...100).contains(probability) else { throw Mango9OpportunityFailure(status: 422, message: "Enter a title and choose a valid stage, currency and probability.") }
        func amount(_ value: String) throws -> Any {
            let value = value.trimmingCharacters(in: .whitespaces)
            if value.isEmpty { return NSNull() }
            guard value.range(of: "^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?$", options: .regularExpression) != nil else { throw Mango9OpportunityFailure(status: 422, message: "Enter a non-negative value with up to two decimal places, using a period.") }
            return value
        }
        guard source.count <= 150, description.count <= 5000, nextAction.count <= 500, lossReason.count <= 500 else { throw Mango9OpportunityFailure(status: 422, message: "One of the details is too long. Shorten it and try again.") }
        guard !hasNextDue || !nextAction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw Mango9OpportunityFailure(status: 422, message: "Enter a next action for the follow-up reminder.") }
        guard stage.classification != "lost" || !lossReason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw Mango9OpportunityFailure(status: 422, message: "Enter a reason for the lost Opportunity.") }
        return ["title": title, "stage": stage.id, "value": try amount(value), "currency": currency,
                "probability": manualProbability ? probability : stage.probability, "probability_mode": manualProbability ? "manual" : "stage",
                "close_date": hasCloseDate ? Self.date(closeDate) as Any : NSNull(), "source": source, "description": description,
                "next_action": nextAction, "next_due_at": hasNextDue ? ISO8601DateFormatter().string(from: nextDue) as Any : NSNull(),
                "actual_value": stage.classification == "open" ? NSNull() : try amount(actualValue),
                "actual_close_date": stage.classification == "open" ? NSNull() : Self.date(actualClose) as Any,
                "loss_reason": stage.classification == "lost" ? lossReason : ""]
    }
}

struct Mango9OpportunityForm: View {
    let record: Mango9Lead
    let session: Mango9Session
    let metadata: Mango9OpportunityMetadata
    let saved: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft = Mango9OpportunityDraft()
    @State private var expanded = false
    @State private var busy = false
    @State private var loaded = false
    @State private var error: String?
    @State private var pending: [String: Any]?
    private var stage: Mango9OpportunityMetadata.Stage? { metadata.stages.first { $0.id == draft.stage } }
    var body: some View {
        Mango9OpportunityPage(title: "New Opportunity") {
            Button(busy ? "Saving…" : pending == nil ? "Save" : "Retry save") { Task { await save() } }
                .disabled(busy || !loaded).accessibilityIdentifier("crm.opportunity.save")
        } content: {
            Form {
                if let error { Section { Text(error).foregroundColor(.red) } }
                if let pending {
                    Section("Save pending") {
                        Text((pending["values"] as? [String: Any])?["title"] as? String ?? "Opportunity")
                        Text("Retry to confirm this save before creating another Opportunity. Your original details are preserved.").font(.subheadline)
                    }
                } else {
                    fields.disabled(busy)
                }
            }.task {
                    guard !loaded else { return }
                    draft.ownerID = metadata.userID
                    draft.stage = metadata.stages.first(where: \.isDefault)?.id ?? metadata.stages.first?.id ?? ""
                    do { pending = try Mango9OpportunityCommands.load(session, contact: record.id, operation: "create"); loaded = true }
                    catch { self.error = error.localizedDescription }
                }
        }.tint(.mango9Primary)
    }
    private var fields: some View {
        Group {
            Section("Linked contact") { Text(record.name) }
            Section("Opportunity") {
                TextField("Title (required)", text: $draft.title).accessibilityIdentifier("crm.opportunity.title")
                Picker("Stage", selection: $draft.stage) { ForEach(metadata.stages) { Text($0.name).tag($0.id) } }
                TextField("Value (optional)", text: $draft.value).keyboardType(.decimalPad)
                Picker("Currency", selection: $draft.currency) { ForEach(Mango9OpportunityDraft.currencies, id: \.self) { Text($0).tag($0) } }
                Toggle("Expected close date", isOn: $draft.hasCloseDate)
                if draft.hasCloseDate { DatePicker("Date", selection: $draft.closeDate, displayedComponents: .date) }
            }
            if let stage, stage.classification != "open" {
                Section(stage.classification == "won" ? "Won outcome" : "Lost outcome") {
                    DatePicker("Actual close date", selection: $draft.actualClose, in: ...Date(), displayedComponents: .date)
                    TextField("Actual value (optional)", text: $draft.actualValue).keyboardType(.decimalPad)
                    if stage.classification == "lost" { TextField("Loss reason (required)", text: $draft.lossReason) }
                }
            }
            Section {
                DisclosureGroup("More details", isExpanded: $expanded) {
                    if metadata.manager && metadata.permissions["assign"] == true {
                        Picker("Owner", selection: $draft.ownerID) {
                            if !metadata.owners.contains(where: { $0.id == metadata.userID }) { Text("Me").tag(metadata.userID) }
                            ForEach(metadata.owners) { Text($0.name).tag($0.id) }
                        }
                    }
                    Toggle("Set probability manually", isOn: $draft.manualProbability)
                    if draft.manualProbability { Stepper("Probability: \(draft.probability)%", value: $draft.probability, in: 0...100) }
                    else { Mango9OpportunityLabel("Probability", value: "\(stage?.probability ?? 0)% · From stage") }
                    TextField("Source", text: $draft.source)
                    if !metadata.sources.isEmpty { Menu("Choose existing source") { ForEach(metadata.sources, id: \.self) { source in Button(source) { draft.source = source } } } }
                    VStack(alignment: .leading, spacing: 6) { Text("Description").font(.subheadline); TextEditor(text: $draft.description).frame(minHeight: 80).accessibilityLabel("Description") }
                    Text("After saving, open Follow-up to schedule an appointment in your CRM calendar.").font(.footnote).foregroundColor(.secondary)
                }
            }
        }
    }
    private func save() async {
        guard !busy, loaded, metadata.permissions["create"] == true else { return }
        busy = true; error = nil; defer { busy = false }
        do {
            let body: [String: Any]
            if let pending { body = pending }
            else { body = ["requestId": UUID().uuidString, "contactId": record.id, "ownerId": draft.ownerID, "values": try draft.values(metadata: metadata)] }
            try Mango9OpportunityCommands.save(body, session: session, contact: record.id, operation: "create"); pending = body
            let receipt = try await Mango9OpportunityAPI.send(session, body: body)
            let id = try Mango9OpportunityAPI.creationID(receipt)
            if receipt["saved"] as? Bool != true {
                let existing = try await Mango9OpportunityAPI.send(session, path: String(id))
                guard Mango9OpportunityAPI.integer(existing["id"]) == id, Mango9OpportunityAPI.duplicateMatches(existing, command: body) else {
                    throw Mango9OpportunityFailure(status: 409, message: "An Opportunity with this title already exists. Your draft is preserved; review the existing Opportunity or use a different title.")
                }
            }
            try Mango9OpportunityCommands.save(nil, session: session, contact: record.id, operation: "create")
            pending = nil; NotificationCenter.default.post(name: .mango9OpportunityDidChange, object: nil); saved()
        } catch {
            if Mango9OpportunityCommands.definiteRejection(error) {
                try? Mango9OpportunityCommands.save(nil, session: session, contact: record.id, operation: "create"); pending = nil
            }
            self.error = error.localizedDescription
        }
    }
}

private struct Mango9OpportunityLabel: View {
    let title: String
    let value: String
    init(_ title: String, value: String) { self.title = title; self.value = value }
    var body: some View { HStack(alignment: .top) { Text(title); Spacer(); Text(value).foregroundColor(.secondary).multilineTextAlignment(.trailing) } }
}

struct Mango9ContactSharing: Decodable {
    struct Entry: Decodable, Identifiable {
        let id: Int
        let sharedBy: Int
        let sharedByName: String
        let targetUserId: Int
        let sharedWithName: String
        let via: String
    }
    let sharedWithMe: Bool
    let sharedByMe: Bool
    let entries: [Entry]
}
struct Mango9ContactPermissions: Decodable { let canUpdate: Bool; let canDelete: Bool }

enum Mango9CRMSharingFilter: String, CaseIterable, Identifiable {
    case all = "", incoming = "with_me", outgoing = "by_me"
    var id: String { rawValue }
    var title: String { switch self { case .all: return "All sharing"; case .incoming: return "Shared with me"; case .outgoing: return "Shared by me" } }
}

struct Mango9SharingFilterMenu: View {
    @Binding var selection: Mango9CRMSharingFilter
    let changed: () -> Void
    var body: some View {
        HStack {
            Menu {
                ForEach(Mango9CRMSharingFilter.allCases) { filter in
                    Button { selection = filter; changed() } label: {
                        if filter == selection { Label(filter.title, systemImage: "checkmark") } else { Text(filter.title) }
                    }
                }
            } label: {
                Label(selection.title, systemImage: "person.2").font(.system(size: 12, weight: .semibold))
                    .padding(.horizontal, 12).frame(minHeight: 44)
                    .foregroundColor(.mango9Primary).background(Color.white).cornerRadius(12)
            }.accessibilityIdentifier("crm.sharing.filter")
            Spacer()
        }
    }
}

struct Mango9SharingBadges: View {
    let sharing: Mango9ContactSharing
    @State private var showDetails = false
    private var outgoing: Bool { sharing.sharedByMe }
    var body: some View {
        HStack(spacing: 0) {
            if sharing.sharedWithMe { badge(incoming: true) }
            if outgoing { badge(incoming: false) }
        }.sheet(isPresented: $showDetails) {
            NavigationView {
                List {
                    ForEach(sharing.entries) { entry in
                        VStack(alignment: .leading, spacing: 6) {
                            Label("Shared with " + (entry.sharedWithName.isEmpty ? "a team member" : entry.sharedWithName), systemImage: "person.fill").font(.headline)
                            Text("Shared by " + (entry.sharedByName.isEmpty ? "a team member" : entry.sharedByName)).font(.subheadline).foregroundColor(.secondary)
                            if entry.via == "appointment" { Text("Through a shared appointment").font(.caption).foregroundColor(.secondary) }
                        }.padding(.vertical, 4)
                    }
                }.navigationTitle("Sharing").navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { showDetails = false } } }
            }.navigationViewStyle(.stack).tint(.mango9Primary)
        }
    }
    private func badge(incoming: Bool) -> some View {
        Button { showDetails = true } label: {
            Image(systemName: "person.fill").font(.system(size: 16))
                .overlay(alignment: .bottomTrailing) { Image(systemName: incoming ? "arrow.down.left" : "arrow.up.right").font(.system(size: 9, weight: .heavy)).padding(2).background(Color.white).clipShape(Circle()).offset(x: 8, y: 3) }
                .foregroundColor(incoming ? Color(red: 0.1, green: 0.55, blue: 0.26) : Color(red: 0.76, green: 0.55, blue: 0.0))
                .frame(width: 38, height: 44)
        }.buttonStyle(.plain).accessibilityLabel(incoming ? "Shared with me — show people" : "Shared by me — show people")
            .accessibilityIdentifier(incoming ? "crm.sharing.incoming" : "crm.sharing.outgoing")
    }
}
