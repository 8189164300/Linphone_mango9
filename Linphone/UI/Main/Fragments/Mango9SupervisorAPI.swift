// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import CryptoKit
import Security

struct Mango9SupervisorLink: Identifiable {
    let title: String
    let url: URL
    var id: String { url.absoluteString }
}

struct Mango9SupervisorFormattedReply {
    let body: String
    let links: [Mango9SupervisorLink]
    var copyText: String { ([body] + links.map { $0.title + "\n" + $0.url.absoluteString }).filter { !$0.isEmpty }.joined(separator: "\n\n") }
}

enum Mango9SupervisorReplyFormatting {
    /// Navigation from saved replies stays on the authenticated CRM origin.
    /// Only web links are rendered; no custom schemes, credentials or API routes.
    static func webURL(_ target: String, baseURL: String) -> URL? {
        let raw = target.trimmingCharacters(in: .whitespacesAndNewlines)
        let url: URL?
        if raw.hasPrefix("/app/") {
            guard var origin = URLComponents(string: baseURL), origin.scheme == "https", origin.host != nil,
                  origin.user == nil, origin.password == nil else { return nil }
            origin.path = "/"; origin.query = nil; origin.fragment = nil
            url = URL(string: raw, relativeTo: origin.url)?.absoluteURL
        } else { url = URL(string: raw) }
        guard let url, ["https", "http"].contains(url.scheme?.lowercased() ?? ""), url.host != nil,
              url.user == nil, url.password == nil else { return nil }
        let path = url.standardized.path.removingPercentEncoding ?? url.standardized.path
        guard path != "/app/api", !path.hasPrefix("/app/api/"), path != "/api", !path.hasPrefix("/api/") else { return nil }
        return url
    }
    static func reply(_ text: String, baseURL: String) -> Mango9SupervisorFormattedReply {
        var body = text
        var links: [Mango9SupervisorLink] = []
        let pattern = #"(?<!!)\[([^\]\n]+)\]\(<?([^\s<>]+?)>?\)"#
        if let regex = try? NSRegularExpression(pattern: pattern) {
            let source = text as NSString
            let matches = regex.matches(in: text, range: NSRange(location: 0, length: source.length))
            for match in matches {
                let title = source.substring(with: match.range(at: 1)), target = source.substring(with: match.range(at: 2))
                if let url = webURL(target, baseURL: baseURL), !links.contains(where: { $0.url == url }) { links.append(.init(title: title, url: url)) }
            }
            for match in matches.reversed() {
                let line = source.substring(with: source.lineRange(for: match.range)).trimmingCharacters(in: .whitespacesAndNewlines)
                let label = source.substring(with: match.range(at: 1))
                let valid = webURL(source.substring(with: match.range(at: 2)), baseURL: baseURL) != nil
                body = (body as NSString).replacingCharacters(in: match.range, with: valid && line == source.substring(with: match.range) ? "" : label)
            }
        }
        if let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
            let source = body as NSString
            for match in detector.matches(in: body, range: NSRange(location: 0, length: source.length)) {
                guard let raw = match.url?.absoluteString, let url = webURL(raw, baseURL: baseURL),
                      !links.contains(where: { $0.url == url }) else { continue }
                links.append(.init(title: "Open page", url: url))
            }
        }
        return .init(body: body.trimmingCharacters(in: .whitespacesAndNewlines), links: links)
    }
}

struct Mango9SupervisorFailure: LocalizedError {
    let status: Int
    let code: String
    var errorDescription: String? {
        switch code {
        case "storage_unavailable": return "Couldn’t access the saved Supervisor request. Unlock your iPhone and reopen Supervisor before sending again."
        case "action_changed": return "This action changed. Review its latest details before approving it."
        case "ai_consent_required": return "Review AI privacy and allow sharing before using Supervisor chat or voice."
        case "scope_changed": return "Your account changed. Reopen Supervisor in your current account."
        case "supervisor_owner_required", "forbidden": return "Supervisor is available to the account owner."
        case "wallet_insufficient": return "Your available AI credit is too low. Check your Mango9 wallet."
        case "support_pilot_busy": return "Supervisor is still working on the previous request. Refresh to check its progress."
        case "pilot_turn_already_submitted": return "This request was already received. Refresh the conversation to check its result."
        case "voice_session_already_requested": return "This voice session was already requested. Start a new session when you’re ready."
        case "voice_permission": return "Allow microphone access in iPhone Settings to use Supervisor voice."
        case "call_active": return "End your phone call before starting Supervisor voice."
        case "voice_audio_format": return "This audio route is unavailable. Check your microphone or headset and try again."
        case "voice_no_audio": return "No microphone audio was detected. Check the selected microphone."
        case "voice_session_complete": return "Voice session ended. You can continue in chat."
        case "pilot_reauthentication_required", "voice_authentication_required": return "Voice authorization ended. Start voice again to reconnect securely."
        case "voice_connection_lost", "voice_connection_failed", "voice_provider_unavailable": return "Voice disconnected. Your saved conversation is kept. Tap the microphone to reconnect."
        case "voice_interrupted": return "Voice was interrupted by an audio change. Tap the microphone to reconnect."
        case "history_pending": return "The last request has not been confirmed. Refresh history and review Actions before sending another request."
        case "invalid_pilot_chat": return "Enter a message of no more than 8,000 UTF-8 bytes."
        default:
            if status == 401 { return "Your Mango9 sign-in needs attention. Reconnect your account to continue." }
            if status == 403 { return "Supervisor is not enabled for this account." }
            if status == 429 { return "Supervisor is busy. Wait a moment, then try again." }
            if code.contains("wallet") || code.contains("billing") { return "AI billing is currently unavailable. Check your wallet or contact support." }
            return "Couldn’t reach Supervisor. Check your connection and try again."
        }
    }
}

struct Mango9SupervisorMessage: Identifiable, Equatable {
    let id: String
    let role: String
    let text: String
    let requestID: String?
    let createdAt: Date?
    init?(_ raw: [String: Any]) {
        guard let id = raw["id"] as? String, let role = raw["role"] as? String,
              ["user", "assistant"].contains(role) else { return nil }
        self.id = id; self.role = role
        text = (raw["parts"] as? [[String: Any]] ?? []).filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined(separator: "\n")
        let metadata = raw["metadata"] as? [String: Any] ?? [:]
        requestID = metadata["requestId"] as? String
        let value = metadata["createdAt"] as? String ?? ""
        let date = ISO8601DateFormatter(); date.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        createdAt = date.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
}

struct Mango9SupervisorConversation: Identifiable {
    let id: String
    let title: String
    init?(_ raw: [String: Any]) {
        guard let id = raw["id"] as? String, UUID(uuidString: id) != nil else { return nil }
        self.id = id; title = raw["title"] as? String ?? "Supervisor conversation"
    }
}

struct Mango9SupervisorCommand: Codable {
    let conversationID: String
    let requestID: String
    let messageID: String
    let text: String
    init(conversationID: String, text: String) {
        self.conversationID = conversationID; self.text = text
        requestID = UUID().uuidString.lowercased(); messageID = UUID().uuidString.lowercased()
    }
    var body: [String: Any] {
        ["conversationId": conversationID, "requestId": requestID,
         "message": ["id": messageID, "role": "user", "parts": [["type": "text", "text": text]]]]
    }
    static func valid(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.utf8.count <= 8000
    }
}

enum Mango9SupervisorPersistence {
    static func key(_ session: Mango9Session) -> String {
        SHA256.hash(data: Data([session.crmApiBaseUrl, session.userId, session.sipIdentity ?? ""].joined(separator: "|").utf8)).map { String(format: "%02x", $0) }.joined()
    }
    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.mango9.supervisor.pending", kSecAttrAccount as String: account]
    }
    static func pending(_ account: String) throws -> Mango9SupervisorCommand? {
        var q = query(account); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        return try decodePending(status: status, data: item as? Data)
    }
    static func decodePending(status: OSStatus, data: Data?) throws -> Mango9SupervisorCommand? {
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data,
              let command = try? JSONDecoder().decode(Mango9SupervisorCommand.self, from: data),
              UUID(uuidString: command.conversationID) != nil, UUID(uuidString: command.requestID) != nil,
              UUID(uuidString: command.messageID) != nil, Mango9SupervisorCommand.valid(command.text) else {
            throw Mango9SupervisorFailure(status: 0, code: "storage_unavailable")
        }
        return command
    }
    static func checkDeletion(_ status: OSStatus) throws {
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw Mango9SupervisorFailure(status: 0, code: "storage_unavailable")
        }
    }
    static func save(_ command: Mango9SupervisorCommand?, account: String) throws {
        let q = query(account)
        guard let command else { try checkDeletion(SecItemDelete(q as CFDictionary)); return }
        let data = try JSONEncoder().encode(command)
        let result = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if result == errSecItemNotFound {
            var item = q; item[kSecValueData as String] = data; item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw Mango9SupervisorFailure(status: 0, code: "storage_unavailable") }
        } else if result != errSecSuccess { throw Mango9SupervisorFailure(status: 0, code: "storage_unavailable") }
    }
}

enum Mango9SupervisorConsent {
    // Change this version whenever the disclosed recipients or purposes change.
    static let version = "2026-10-30.1"
    static let chatDisclosure = "Mango9 sends your messages, conversation history and relevant account/CRM records to OpenRouter and the configured AI provider: DeepInfra (DeepSeek) or OpenAI. They process this information to answer your requests."
    static let voiceDisclosure = "Voice also sends microphone audio, transcripts and conversation context to Google (Gemini) for spoken replies."
    private struct Receipt: Codable { let version: String; let acceptedAt: Date }
    private static func key(_ session: Mango9Session) -> String { "mango9.supervisor.aiConsent." + Mango9SupervisorPersistence.key(session) }
    static func accepted(_ session: Mango9Session, defaults: UserDefaults = .standard) -> Bool {
        guard let data = defaults.data(forKey: key(session)), let receipt = try? JSONDecoder().decode(Receipt.self, from: data) else { return false }
        return receipt.version == version && receipt.acceptedAt <= Date()
    }
    static func allow(_ session: Mango9Session, defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(Receipt(version: version, acceptedAt: Date())) { defaults.set(data, forKey: key(session)) }
    }
    static func revoke(_ session: Mango9Session, defaults: UserDefaults = .standard) { defaults.removeObject(forKey: key(session)) }
    static func required(path: String, body: [String: Any]?) -> Bool {
        path == "chat" || path == "voice/ticket" || (path.hasPrefix("actions/") && path.hasSuffix("/decision") && body?["decision"] as? String == "approve")
    }
}

struct Mango9SupervisorPendingStore {
    var read: () throws -> Mango9SupervisorCommand?
    var write: (Mango9SupervisorCommand?) throws -> Void
    static func keychain(_ account: String) -> Self {
        .init(read: { try Mango9SupervisorPersistence.pending(account) }, write: { try Mango9SupervisorPersistence.save($0, account: account) })
    }
}

struct Mango9SupervisorApproval: Equatable {
    let id: String
    let digest: String
    init?(_ detail: [String: Any]) {
        guard let id = detail["id"] as? String, UUID(uuidString: id) != nil,
              let digest = detail["approvalDigest"] as? String,
              digest.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil,
              detail["state"] as? String == "PENDING_APPROVAL", detail["summaryOnly"] as? Bool != true else { return nil }
        self.id = id; self.digest = digest
    }
}

/// Serializes the audio callback's handoff to the UI actor, with a bounded queue.
/// Every mutable field is protected by the lock; old mute/stop generations cannot send.
final class Mango9SupervisorCaptureGate: @unchecked Sendable {
    struct Ticket: Equatable { fileprivate let generation: UInt64 }
    enum Claim { case paused, buffer(Ticket), overflow(Ticket) }
    private let lock = NSLock()
    private var generation: UInt64 = 0
    private var enabled = false
    private var overflowed = false
    private var inFlight = 0
    let limit: Int
    init(limit: Int = 8) { self.limit = max(1, limit) }
    func reset(enabled: Bool) {
        lock.lock(); defer { lock.unlock() }
        generation &+= 1; inFlight = 0; overflowed = false; self.enabled = enabled
    }
    func claim() -> Claim {
        lock.lock(); defer { lock.unlock() }
        guard enabled else { return .paused }
        let ticket = Ticket(generation: generation)
        guard inFlight < limit else { enabled = false; overflowed = true; return .overflow(ticket) }
        inFlight += 1; return .buffer(ticket)
    }
    func finish(_ ticket: Ticket) {
        lock.lock(); defer { lock.unlock() }
        if ticket.generation == generation { inFlight = max(0, inFlight - 1) }
    }
    func permits(_ ticket: Ticket) -> Bool {
        lock.lock(); defer { lock.unlock() }; return enabled && ticket.generation == generation
    }
    func didOverflow(_ ticket: Ticket) -> Bool {
        lock.lock(); defer { lock.unlock() }; return overflowed && ticket.generation == generation
    }
}

@MainActor protocol Mango9SupervisorClient: AnyObject {
    var original: Mango9Session { get }
    var active: Bool { get }
    var hasAIConsent: Bool { get }
    func requireAIConsent() throws
    func cancelAIRequests()
    func authorize() async throws -> [String: Any]
    func request(_ path: String, body: [String: Any]?) async throws -> [String: Any]
    func ticket(conversationID: String) async throws -> (value: [String: Any], session: Mango9Session)
}
extension Mango9SupervisorClient {
    func request(_ path: String) async throws -> [String: Any] { try await request(path, body: nil) }
}

@MainActor final class Mango9SupervisorAPI: Mango9SupervisorClient {
    let original: Mango9Session
    let transport: URLSession
    private var aiRequestScope = UUID().uuidString
    init(session: Mango9Session, transport: URLSession? = nil) {
        original = session
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil
        config.timeoutIntervalForRequest = 125; config.timeoutIntervalForResource = 135
        self.transport = transport ?? URLSession(configuration: config, delegate: Mango9MessagingRedirectPolicy(), delegateQueue: nil)
    }
    deinit { transport.invalidateAndCancel() }
    var active: Bool { Mango9SessionStore.isActive(original) }
    var hasAIConsent: Bool { Mango9SupervisorConsent.accepted(original) }
    func requireAIConsent() throws {
        guard hasAIConsent else { throw Mango9SupervisorFailure(status: 0, code: "ai_consent_required") }
    }
    func cancelAIRequests() {
        let previous = aiRequestScope
        aiRequestScope = UUID().uuidString
        transport.getAllTasks { tasks in
            for task in tasks {
                if task.originalRequest?.value(forHTTPHeaderField: "X-Mango9-AI-Request-Scope") == previous { task.cancel() }
            }
        }
    }
    func current() throws -> Mango9Session {
        guard active, let value = Mango9SessionStore.currentLogin(for: original) else { throw Mango9SupervisorFailure(status: 0, code: "scope_changed") }
        return value
    }
    static func validEndpoint(_ path: String) -> Bool {
        if ["identity", "supervisor", "history", "chat", "actions", "activity", "voice/ticket"].contains(path) { return true }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        return (parts.count == 2 || (parts.count == 3 && parts[2] == "decision")) &&
            parts.first == "actions" && UUID(uuidString: String(parts[1])) != nil
    }
    static func url(session: Mango9Session, path: String) throws -> URL {
        guard validEndpoint(path), let base = URL(string: session.crmApiBaseUrl), base.scheme == "https", base.host != nil,
              base.user == nil, base.password == nil, base.query == nil, base.fragment == nil,
              !path.contains(".."), !path.hasPrefix("/"), !path.contains("?") else { throw Mango9CRMAPIError.invalidConfiguration }
        return base.appendingPathComponent("assistant").appendingPathComponent(path)
    }
    static func voiceURL(session: Mango9Session, path: String) throws -> URL {
        let base = try url(session: session, path: "identity")
        guard path == "/app/command-center/voice-stream", var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else { throw Mango9CRMAPIError.invalidConfiguration }
        components.scheme = "wss"; components.path = path; components.query = nil; components.fragment = nil
        guard let url = components.url else { throw Mango9CRMAPIError.invalidConfiguration }; return url
    }
    static func ownerIdentity(_ raw: [String: Any], session: Mango9Session) -> Bool {
        func id(_ value: Any?) -> String { if let number = value as? NSNumber { return number.stringValue }; return value as? String ?? "" }
        return raw["enabled"] as? Bool == true && raw["ownerOnly"] as? Bool == true && raw["supervisorAuth"] as? String == "mobile_owner" &&
            id(raw["userId"]) == session.userId && id(raw["tenantId"]) == session.userId
    }
    func request(_ path: String, body: [String: Any]? = nil) async throws -> [String: Any] {
        try await response(path, body: body).value
    }
    /// Return the exact bearer used for ticket issuance; WebSocket authentication is bound to it.
    func response(_ path: String, body: [String: Any]? = nil) async throws -> (value: [String: Any], session: Mango9Session) {
        let needsConsent = Mango9SupervisorConsent.required(path: path, body: body)
        let consentScope = aiRequestScope
        if needsConsent { try requireAIConsent() }
        var session = try current()
        for attempt in 0...1 {
            try Task.checkCancellation(); _ = try current()
            var request = URLRequest(url: try Self.url(session: session, path: path), cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: path == "chat" ? 125 : 25)
            request.httpMethod = body == nil ? "GET" : "POST"; request.httpShouldHandleCookies = false
            request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            if needsConsent { request.setValue(consentScope, forHTTPHeaderField: "X-Mango9-AI-Request-Scope") }
            if let body { request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = try JSONSerialization.data(withJSONObject: body) }
            try await Mango9SessionRenewal.shared.checkKnownExpiredBearer(request)
            if needsConsent {
                try requireAIConsent()
                guard consentScope == aiRequestScope else { throw Mango9SupervisorFailure(status: 0, code: "ai_consent_required") }
            }
            let (data, reply) = try await Self.boundedResponse(request, transport: transport)
            _ = try current(); try Task.checkCancellation()
            if needsConsent {
                try requireAIConsent()
                guard consentScope == aiRequestScope else { throw Mango9SupervisorFailure(status: 0, code: "ai_consent_required") }
            }
            guard let http = reply as? HTTPURLResponse, data.count <= 2_097_152 else { throw Mango9SupervisorFailure(status: 0, code: "invalid_response") }
            if http.statusCode == 401 && attempt == 0 {
                let latest = try current()
                session = latest.accessToken != session.accessToken ? latest : try await Mango9CRMAPI.refresh(session: session)
                continue
            }
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            guard (200..<300).contains(http.statusCode), json["success"] as? Bool == true, let value = json["data"] as? [String: Any] else {
                throw Mango9SupervisorFailure(status: http.statusCode, code: (json["error"] as? [String: Any])?["code"] as? String ?? "assistant_unavailable")
            }
            return (value, session)
        }
        throw Mango9SupervisorFailure(status: 401, code: "unauthorized")
    }
    nonisolated static func boundedResponse(_ request: URLRequest, transport: URLSession, limit: Int = 2_097_152) async throws -> (Data, URLResponse) {
        let (bytes, response) = try await transport.bytes(for: request)
        defer { bytes.task.cancel() }
        guard response.expectedContentLength <= limit else { throw Mango9SupervisorFailure(status: 0, code: "invalid_response") }
        var data = Data(); data.reserveCapacity(min(limit, max(0, Int(response.expectedContentLength))))
        for try await byte in bytes {
            guard data.count < limit else { throw Mango9SupervisorFailure(status: 0, code: "invalid_response") }
            if data.count % 4096 == 0 { try Task.checkCancellation() }
            data.append(byte)
        }
        return (data, response)
    }
    func authorize() async throws -> [String: Any] {
        let identity = try await request("identity")
        guard Self.ownerIdentity(identity, session: try current()) else { throw Mango9SupervisorFailure(status: 403, code: "supervisor_owner_required") }
        let status = try await request("supervisor")
        guard status["enabled"] as? Bool == true else { throw Mango9SupervisorFailure(status: 403, code: "supervisor_disabled") }
        return status
    }
    func ticket(conversationID: String) async throws -> (value: [String: Any], session: Mango9Session) {
        try requireAIConsent(); let scope = aiRequestScope
        // A fresh access token prevents avoidable voice disconnects at the 15-second server recheck.
        _ = try await Mango9CRMAPI.refresh(session: current()); _ = try current()
        try Task.checkCancellation(); try requireAIConsent()
        guard scope == aiRequestScope else { throw Mango9SupervisorFailure(status: 0, code: "ai_consent_required") }
        return try await response("voice/ticket", body: ["conversationId": conversationID, "requestId": UUID().uuidString.lowercased(), "locale": Locale.current.identifier.replacingOccurrences(of: "_", with: "-")])
    }
}
