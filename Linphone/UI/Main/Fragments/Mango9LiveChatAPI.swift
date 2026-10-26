import Foundation
import SwiftUI
import CryptoKit
import Security
import UniformTypeIdentifiers

/// These switches affect this installation only. They never revoke server access or delete history.
@MainActor final class Mango9ChatTabPreferences: ObservableObject {
	static let shared = Mango9ChatTabPreferences()
	@Published var team = true { didSet { save() } }
	@Published var social = true { didSet { save() } }
	@Published var live = true { didSet { save() } }
	private var loading = false
	private let defaults: UserDefaults
	private let account: () -> String
	private var accountObserver: NSObjectProtocol?
	private var key: String {
		return "mango9_chat_tabs_" + SHA256.hash(data: Data(account().utf8)).map { String(format: "%02x", $0) }.joined()
	}
	init(defaults: UserDefaults = .standard, account: @escaping () -> String = { Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) ?? "signed-out" }) {
		self.defaults = defaults; self.account = account
		load()
		accountObserver = NotificationCenter.default.addObserver(forName: .mango9AccountContextChanged, object: nil, queue: .main) { [weak self] _ in
			Task { @MainActor in self?.load() }
		}
	}
	deinit { if let accountObserver { NotificationCenter.default.removeObserver(accountObserver) } }
	private func load() {
		loading = true
		let saved = defaults.dictionary(forKey: key)
		team = saved?["team"] as? Bool ?? true
		social = saved?["social"] as? Bool ?? true
		live = saved?["live"] as? Bool ?? true
		loading = false
	}
	private func save() {
		guard !loading else { return }
		defaults.set(["team": team, "social": social, "live": live], forKey: key)
	}
	func isVisible(_ tab: Mango9ConversationTab) -> Bool {
		switch tab { case .sms: return true; case .team: return team; case .social: return social; case .live: return live }
	}
}

struct Mango9ChatTabSettings: View {
	@ObservedObject private var preferences = Mango9ChatTabPreferences.shared
	var body: some View {
		VStack(alignment: .leading, spacing: 24) {
			Toggle("Team Chat", isOn: $preferences.team).accessibilityIdentifier("settings.chat.team")
			Toggle("Social", isOn: $preferences.social).accessibilityIdentifier("settings.chat.social")
			Toggle("Live Chat", isOn: $preferences.live).accessibilityIdentifier("settings.chat.live")
			if let session = Mango9SessionStore.load() {
				NavigationLink(destination: Mango9LiveChatSettings(session: session)) {
					Label("Live Chat website settings", systemImage: "globe")
				}.accessibilityIdentifier("settings.chat.websites")
			}
			Text("Choose the conversation tabs shown on this phone. Turning a tab off keeps its messages and server settings.")
				.font(.footnote).foregroundStyle(.secondary)
		}.default_text_style_700(styleSize: 15)
	}
}

struct Mango9LiveInputFailure: LocalizedError {
 let message: String
 var errorDescription: String? { message }
}

struct Mango9LiveChatFailure: LocalizedError {
	let status: Int
	var errorDescription: String? {
		switch status {
		case 401: return "Please sign in again to open Live Chat."
		case 403: return "Live Chat is not available to this account. Ask your workspace owner to check access."
		case 404: return "This conversation or Live Chat connection is no longer available."
		case 409: return "This conversation changed. Refresh it before trying again."
		case 413: return "Choose an image, PDF, or voice recording smaller than 5 MB."
		case 429: return "Please wait a moment before trying again."
		case 400: return "The action could not be completed. Refresh the conversation and check the website’s chat settings."
		default: return "We couldn’t reach Live Chat. Check your connection and try again."
		}
	}
}

/// The visitor context is supplied by the mobile API after website privacy settings are applied.
/// Do not infer missing fields, geolocate IP addresses, or treat visitor-entered identity as verified.
struct Mango9LiveVisitorContext {
	let value: [String: Any]
	init(_ value: [String: Any] = [:]) { self.value = value }
	private func string(_ key: String) -> String {
  if key == "timeZone" { return value["timeZone"] as? String ?? value["timezone"] as? String ?? "" }
  return value[key] as? String ?? ""
 }
	var country: [String: Any] { value["country"] as? [String: Any] ?? [:] }
	var location: String {
		let place = ["city", "region", "name"].compactMap { country[$0] as? String }.filter { !$0.isEmpty }.joined(separator: ", ")
		return [country["flag"] as? String ?? "", place].filter { !$0.isEmpty }.joined(separator: " ")
	}
	var compact: String {
		let city = country["city"] as? String ?? ""
		let place = city.isEmpty ? country["name"] as? String ?? "" : city
		let location = [country["flag"] as? String ?? "", place].filter { !$0.isEmpty }.joined(separator: " ")
		let ip = string("ipAddress")
		let page = URL(string: string("entryPage"))?.host ?? ""
		return [location, ip.isEmpty ? "" : "IP " + ip, page].filter { !$0.isEmpty }.joined(separator: " · ")
	}
	var rows: [(String, String)] {
		var result: [(String, String)] = []
		if !location.isEmpty { result.append(("Approximate location", location)) }
		for (key, label) in [("website", "Website"), ("pageUrl", "Current page"), ("entryPage", "Entry page"),
			("pageTitle", "Page title"), ("ipAddress", "IP address"), ("language", "Language"), ("timeZone", "Time zone"),
			("referrerUrl", "Referrer"), ("mode", "Chat mode"), ("routingReason", "Routing"), ("observedAt", "Observed at")] {
			let text = string(key); if !text.isEmpty { result.append((label, text)) }
		}
		if let device = value["device"] as? [String: Any] {
			for (key, label) in [("os", "Operating system"), ("browser", "Browser"), ("type", "Device"), ("versionAccuracy", "Device accuracy")] {
				if let text = device[key] as? String, !text.isEmpty { result.append((label, text)) }
			}
		}
		if let seconds = value["timeBeforeFirstMessageSeconds"] as? NSNumber { result.append(("Time before first message", "\(seconds.intValue) seconds")) }
		if let started = value["siteVisitStartedAt"] as? String { result.append(("Visit started", started)) }
		return result
	}
}

struct Mango9LiveConversation: Identifiable {
	let id: String
	let siteID: String
	let title: String
	let email: String
	let siteName: String
	let summary: String
	let updated: String
	let status: String
	let unread: Bool
	var assignee: Int?
	let context: Mango9LiveVisitorContext
	init?(_ data: [String: Any]) {
		guard let id = data["id"] as? String, UUID(uuidString: id) != nil, let site = data["site_id"] as? String else { return nil }
		self.id = id; siteID = site
		let name = data["name"] as? String ?? ""
		title = name.isEmpty ? "Website visitor" : name
		email = data["email"] as? String ?? ""; siteName = data["site_name"] as? String ?? "Live Chat"
		summary = data["summary"] as? String ?? ""; updated = data["updated_at"] as? String ?? ""
		status = data["status"] as? String ?? "open"; unread = data["unread"] as? Bool ?? false
		assignee = data["assignee_id"] as? Int
			?? (data["assignee_id"] as? String).flatMap(Int.init)
		context = Mango9LiveVisitorContext((data["visitorContext"] ?? data["context"]) as? [String: Any] ?? [:])
	}
}

enum Mango9LiveChatTime {
	static func date(_ value: String) -> Date? {
		let fractional = ISO8601DateFormatter(); fractional.formatOptions.insert(.withFractionalSeconds)
		return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
	}
}

struct Mango9LiveMessage: Identifiable {
	let id: Int
	let text: String
	let direction: String
	let sender: String
	let status: String
	let created: Date
	let context: Mango9LiveVisitorContext
	let attachments: [[String: Any]]
	var hiddenForMe: Bool
	var reactions: Mango9LiveReactions
	init?(_ data: [String: Any]) {
		guard let id = data["id"] as? Int, let direction = data["direction"] as? String,
			["incoming", "outgoing", "note"].contains(direction) else { return nil }
		self.id = id; self.direction = direction
		text = data["content"] as? String ?? ""; sender = (data["sender"] as? [String: Any])?["name"] as? String ?? "Support"
		status = data["status"] as? String ?? "sent"
		created = Date(timeIntervalSince1970: (data["created_at"] as? NSNumber)?.doubleValue ?? 0)
		context = Mango9LiveVisitorContext((data["content_attributes"] as? [String: Any])?["visitorContext"] as? [String: Any] ?? [:])
		attachments = data["attachments"] as? [[String: Any]] ?? []
		hiddenForMe = data["hiddenForMe"] as? Bool ?? false
		reactions = Mango9LiveReactions.parse(data["reactions"]) ?? .empty
	}
}

extension Mango9LiveMessage {
 /// Copy and forward the selected message as one unit, including its supplied context.
 var selectedText: String {
  ([sender, text] + attachments.compactMap { $0["name"] as? String } + context.rows.map { $0.0 + ": " + $0.1 })
   .filter { !$0.isEmpty }.joined(separator: "\n")
 }
}

struct Mango9LiveSite: Identifiable {
	let id: String
	let name: String
	let enabled: Bool
	let settings: [String: Any]
	let capabilities: [String: Any]
	init?(_ data: [String: Any]) {
		guard let id = data["id"] as? String else { return nil }
		self.id = id; name = data["name"] as? String ?? "Website"
		enabled = data["enabled"] as? Bool ?? false
		settings = data["settings"] as? [String: Any] ?? [:]
		if let advertised = data["capabilities"] as? [String: Any] { capabilities = advertised }
		else {
			// The messages endpoint returns the authorized raw site; the sites endpoint
			// returns public capabilities. Apply the same documented website-policy defaults.
			capabilities = ["text": true, "typing": settings["typingIndicators"] as? Bool ?? true,
				"readState": settings["readReceipts"] as? Bool ?? true,
				"attachments": settings["attachmentsEnabled"] as? Bool == true ? ["image/png", "image/jpeg", "image/webp", "application/pdf"] : []]
		}
	}
}

@MainActor final class Mango9LiveChatAPI {
	static let shared = Mango9LiveChatAPI()
	private let transport: URLSession
	init() {
		let config = URLSessionConfiguration.ephemeral
		config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil
		transport = URLSession(configuration: config, delegate: Mango9MessagingRedirectPolicy(), delegateQueue: nil)
	}
	func request(_ operation: String, session: Mango9Session, query: [URLQueryItem] = [], body: [String: Any]? = nil) async throws -> [String: Any] {
		let key = Mango9ReactionTarget.accountKey(session)
		func active() throws -> Mango9Session {
			guard let current = Mango9SessionStore.load(), Mango9SessionStore.isActive(current), Mango9ReactionTarget.accountKey(current) == key else { throw Mango9MessagingError.scopeChanged }
			return current
		}
		var current = try active()
		for attempt in 0...1 {
			try Task.checkCancellation()
			var request = try Mango9CalendarAPI.request(session: current, path: operation, method: body == nil ? "GET" : "POST", query: query, body: body, scope: .livechat)
			request.httpShouldHandleCookies = false
			try await Mango9SessionRenewal.shared.checkKnownExpiredBearer(request)
			let (data, response) = try await transport.data(for: request)
			_ = try active(); try Task.checkCancellation()
			guard let response = response as? HTTPURLResponse else { throw Mango9LiveChatFailure(status: 0) }
			if response.statusCode == 401, attempt == 0 {
				let latest = try active()
				if latest.accessToken != current.accessToken { current = latest; continue }
				let refreshed = try await Mango9CRMAPI.refresh(session: current)
				_ = try active(); current = refreshed; continue
			}
			guard (200..<300).contains(response.statusCode) else { throw Mango9LiveChatFailure(status: response.statusCode) }
			guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any], envelope["success"] as? Bool == true,
				let result = envelope["data"] as? [String: Any] else { throw Mango9LiveChatFailure(status: 0) }
			return result
		}
		throw Mango9LiveChatFailure(status: 401)
	}
	static func message(_ error: Error) -> String {
		if error is Mango9LiveChatFailure || error is Mango9MessagingError { return error.localizedDescription }
		return Mango9LiveChatFailure(status: 0).localizedDescription
	}
}

/// Persist an unconfirmed command in the device keychain, including its original idempotency key.
/// Retrying sends the identical payload; a new command is never created for an uncertain result.
struct Mango9LivePendingSend: Codable {
	let id: String
	let text: String
	let file: Data?
	let name: String?
	let mime: String?
	init(text: String, file: Data? = nil, name: String? = nil, mime: String? = nil) {
		id = UUID().uuidString.lowercased(); self.text = text; self.file = file; self.name = name; self.mime = mime
	}
	var body: [String: Any] {
		var value: [String: Any] = ["text": text, "requestId": id]
		if let file, let name, let mime { value["file"] = ["name": name, "mime": mime, "base64": file.base64EncodedString()] }
		return value
	}
}

enum Mango9LiveOutbox {
	private static func query(account: String, conversation: String) -> [String: Any] {
		let digest = SHA256.hash(data: Data((account + "|" + conversation).utf8)).map { String(format: "%02x", $0) }.joined()
		return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.mango9.livechat.outbox", kSecAttrAccount as String: digest]
	}
	static func load(account: String, conversation: String) -> Mango9LivePendingSend? {
		var query = query(account: account, conversation: conversation)
		query[kSecReturnData as String] = true; query[kSecMatchLimit as String] = kSecMatchLimitOne
		var item: CFTypeRef?
		guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
		return try? JSONDecoder().decode(Mango9LivePendingSend.self, from: data)
	}
	static func save(_ command: Mango9LivePendingSend?, account: String, conversation: String) throws {
		let query = query(account: account, conversation: conversation)
		guard let command else { SecItemDelete(query as CFDictionary); return }
		let data = try JSONEncoder().encode(command)
		let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
		if status == errSecItemNotFound {
			var item = query; item[kSecValueData as String] = data
			item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
			guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw Mango9LiveChatFailure(status: 0) }
		} else if status != errSecSuccess { throw Mango9LiveChatFailure(status: 0) }
	}
}

struct Mango9LiveReactions: Codable, Equatable {
	struct Item: Codable, Equatable, Identifiable {
		let emoji: String
		let count: Int
		let mine: Bool
		var id: String { emoji }
	}
	let items: [Item]
	let version: Int
	let supported: Bool
	let recipientVisible: Bool
	let allowed: [String]
	static let empty = Self(items: [], version: 0, supported: false, recipientVisible: false, allowed: [])
	static func parse(_ raw: Any?) -> Self? {
		guard let raw, JSONSerialization.isValidJSONObject(raw), let data = try? JSONSerialization.data(withJSONObject: raw),
			let value = try? JSONDecoder().decode(Self.self, from: data), value.version >= 0,
			value.items.allSatisfy({ $0.count > 0 && !$0.emoji.isEmpty }), Set(value.items.map(\.emoji)).count == value.items.count else { return nil }
		return value
	}
}

struct Mango9LiveReactionCommand: Codable, Equatable {
	let requestId: String
	let messageId: Int
	let version: Int
	let emoji: String?
	init(messageId: Int, version: Int, emoji: String?) {
		requestId = UUID().uuidString.lowercased(); self.messageId = messageId; self.version = version; self.emoji = emoji
	}
	var body: [String: Any] {
		["requestId": requestId, "messageId": messageId, "version": version, "emoji": emoji as Any? ?? NSNull()]
	}
}

enum Mango9LiveReactionJournal {
	private static func query(_ account: String, _ conversation: String) -> [String: Any] {
		let digest = SHA256.hash(data: Data((account + "|" + conversation).utf8)).map { String(format: "%02x", $0) }.joined()
		return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.mango9.livechat.reaction", kSecAttrAccount as String: digest]
	}
	static func load(account: String, conversation: String) -> Mango9LiveReactionCommand? {
		var q = query(account, conversation); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
		var item: CFTypeRef?
		guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
		return try? JSONDecoder().decode(Mango9LiveReactionCommand.self, from: data)
	}
	static func save(_ command: Mango9LiveReactionCommand?, account: String, conversation: String) throws {
		let q = query(account, conversation)
		guard let command else { SecItemDelete(q as CFDictionary); return }
		let data = try JSONEncoder().encode(command)
		let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
		if status == errSecItemNotFound {
			var item = q; item[kSecValueData as String] = data; item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
			guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw Mango9LiveChatFailure(status: 0) }
		} else if status != errSecSuccess { throw Mango9LiveChatFailure(status: 0) }
	}
}

/// Native photo/file picker and recorder output share one bounded upload path.
enum Mango9LiveAttachmentPayload {
 static func prepare(_ item: Attachment, text: String, allowedMIMEs: [String]) throws -> Mango9LivePendingSend {
  let size = try item.full.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
  guard size > 0, size <= 5 * 1024 * 1024 else { throw Mango9LiveChatFailure(status: 413) }
  var mime = UTType(filenameExtension: item.full.pathExtension)?.preferredMIMEType ?? ""
  if item.type == .voiceRecording || item.full.pathExtension.lowercased() == "m4a" { mime = "audio/mp4" }
  var data = try Data(contentsOf: item.full); var name = item.name
  if item.type == .image, !["image/png", "image/jpeg", "image/webp"].contains(mime),
   let image = UIImage(data: data), let jpeg = image.jpegData(compressionQuality: 0.9) {
   data = jpeg; mime = "image/jpeg"; name = (name as NSString).deletingPathExtension + ".jpg"
  }
  guard !data.isEmpty, data.count <= 5 * 1024 * 1024 else { throw Mango9LiveChatFailure(status: 413) }
  guard allowedMIMEs.contains(mime) else {
   throw Mango9LiveInputFailure(message: "Choose a supported image, PDF, or voice recording. Attachments must be smaller than 5 MB.")
  }
  return Mango9LivePendingSend(text: text, file: data, name: name, mime: mime)
 }
}
