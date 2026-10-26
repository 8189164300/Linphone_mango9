import Foundation
import SwiftUI

// Team/SMS are staff-ID maps. Provider DMs deliberately use a separate model.
struct Mango9ReactionCapabilities: Codable, Equatable {
	var supported: Bool
	var scope: String
	var recipientVisible: Bool
	var textFallback: Bool?
	var providerReactions: Bool?
	var canAdd: Bool?
	var canRemove: Bool?
	var reason: String?

	func permits(_ channel: Mango9ReactionChannel, removing: Bool = false) -> Bool {
		guard supported, textFallback != true else { return false }
		switch channel {
		case .team: return scope == "conversation"
		case .sms: return scope == "workspace" && !recipientVisible && providerReactions == false
		case .social: return scope == "provider" && recipientVisible && (removing ? canRemove : canAdd) == true
		}
	}
}

struct Mango9StaffReactions: Codable, Equatable {
	var reactions: [String: [Int]] = [:]
	var version: Int = 0
	var capabilities: Mango9ReactionCapabilities?

	static let empty = Self()
	static func history(_ value: [String: Any]) -> Self {
		let map = value["reactions"] as? [String: Any] ?? [:]
		var normalized: [String: [Int]] = [:]
		for (emoji, raw) in map where !emoji.isEmpty {
			// Do not coerce a provider reaction object or a string ID into a staff map.
			guard JSONSerialization.isValidJSONObject(raw), let data = try? JSONSerialization.data(withJSONObject: raw),
				let ids = try? JSONDecoder().decode([Int].self, from: data) else { continue }
			let valid = Set(ids.filter { $0 > 0 }).sorted()
			if !valid.isEmpty { normalized[emoji] = valid }
		}
		let capabilities = (value["reactionCapabilities"] ?? value["capabilities"]).flatMap { raw in
			guard JSONSerialization.isValidJSONObject(raw) else { return nil as Mango9ReactionCapabilities? }
			return (try? JSONSerialization.data(withJSONObject: raw)).flatMap {
				try? JSONDecoder().decode(Mango9ReactionCapabilities.self, from: $0)
			}
		}
		let version = (value["reactionVersion"] ?? value["version"]) as? Int ?? 0
		return Self(reactions: normalized, version: max(0, version), capabilities: capabilities)
	}

	func merging(_ other: Self) -> Self { other.version >= version ? other : self }
	func contains(_ emoji: String, actor: Int) -> Bool { reactions[emoji]?.contains(actor) == true }
}

struct Mango9SocialReactions: Decodable, Equatable {
	struct Entry: Decodable, Equatable {
		let emoji: String
		let action: String
		let fromMe: Bool
		let at: String
		var isActive: Bool { action == "added" && !emoji.isEmpty }
	}
	let currentAccount: Entry?
	let participant: Entry?
	private enum CodingKeys: String, CodingKey { case currentAccount = "self", participant }
	init(from decoder: Decoder) throws {
		// PHP encodes the empty map as []; nonempty social state is an object.
		if let empty = try? decoder.singleValueContainer().decode([String].self), empty.isEmpty {
			currentAccount = nil; participant = nil; return
		}
		let c = try decoder.container(keyedBy: CodingKeys.self)
		currentAccount = try c.decodeIfPresent(Entry.self, forKey: .currentAccount)
		participant = try c.decodeIfPresent(Entry.self, forKey: .participant)
	}
	var activeAccount: Entry? { currentAccount?.isActive == true && currentAccount?.fromMe == true ? currentAccount : nil }
	var activeParticipant: Entry? { participant?.isActive == true && participant?.fromMe == false ? participant : nil }
}

enum Mango9ReactionChannel: String, Codable { case team, sms, social }

struct Mango9ReactionTarget: Codable, Equatable, Identifiable {
	let account: String
	let channel: Mango9ReactionChannel
	let conversation: String
	let message: String
	var id: String { [account, channel.rawValue, conversation, message].map { "\($0.utf8.count):\($0)" }.joined() }
	static func accountKey(_ session: Mango9Session) -> String {
		[session.crmBaseUrl, session.crmApiBaseUrl, session.smsChatApi, session.crmId,
		 session.userId, session.parentClientId, session.sipIdentity ?? ""].map { "\($0.utf8.count):\($0)" }.joined()
	}
	func matches(_ session: Mango9Session) -> Bool { account == Self.accountKey(session) }
}

struct Mango9ReactionCommand: Codable, Equatable {
	let target: Mango9ReactionTarget
	let requestId: String
	let version: Int
	let emoji: String
	let remove: Bool

	init(target: Mango9ReactionTarget, version: Int, emoji: String, remove: Bool) {
		self.target = target; self.version = version; self.emoji = emoji; self.remove = remove
		requestId = UUID().uuidString.lowercased()
	}
	var body: [String: Any] {
		if target.channel == .social {
			var result: [String: Any] = ["id": target.conversation, "messageId": Int(target.message) ?? 0,
				"version": version, "requestId": requestId, "action": remove ? "unreact" : "react"]
			if !remove { result["emoji"] = emoji }
			return result
		}
		return ["emoji": emoji, "remove": remove, "requestId": requestId, "version": version,
			"scope": target.channel == .sms ? "workspace" : "conversation"]
	}
}

enum Mango9ReactionOutcome: String, Decodable {
	case succeeded, failed, unknown
	init(from decoder: Decoder) throws {
		let raw = try decoder.singleValueContainer().decode(String.self)
		self = Self(rawValue: raw) ?? .unknown
	}
	static func transportFailure(_ error: Error, previouslyUnconfirmed: Bool) -> Self {
		// A denied receipt lookup cannot prove an earlier provider command failed.
		guard !previouslyUnconfirmed else { return .unknown }
		if case Mango9MessagingError.rejected(let status, _) = error,
			(400..<500).contains(status), status != 408, status != 429 { return .failed }
		return .unknown
	}
}

enum Mango9MessagingError: LocalizedError {
	case scopeChanged, invalidResponse, rejected(Int, String), pending
	var errorDescription: String? {
		switch self {
		case .scopeChanged: return "The account or conversation changed. Reopen this message."
		case .invalidResponse: return "The server response could not be confirmed."
		case .pending: return "A previous reaction is unconfirmed. Check its saved receipt before making another change."
		case .rejected(_, let code):
			if code == "posting_session_required" { return "Social Chat is not authorized for this account’s supervisor access. Team Chat and SMS are still available." }
			if code.contains("version_changed") { return "This message changed on another device. Review the refreshed reactions and tap again." }
			return "This action is unavailable (\(code))."
		}
	}
}

/// Stores only command identity/intent, never credentials or message bodies.
/// Persist before sending so a relaunch cannot turn an unknown outcome into a new command.
@MainActor final class Mango9ReactionJournal {
	static let shared = Mango9ReactionJournal()
	private let defaults: UserDefaults
	private let key = "mango9_reaction_pending_v1"
	private var commands: [String: Mango9ReactionCommand]
	private var inFlight: Set<String> = []
	init(defaults: UserDefaults = .standard) {
		self.defaults = defaults
		commands = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([String: Mango9ReactionCommand].self, from: $0) } ?? [:]
	}
	func pending(_ target: Mango9ReactionTarget) -> Mango9ReactionCommand? { commands[target.id] }
	func begin(_ command: Mango9ReactionCommand) throws {
		guard !inFlight.contains(command.target.id) else { throw Mango9MessagingError.pending }
		if let old = commands[command.target.id], old != command { throw Mango9MessagingError.pending }
		commands[command.target.id] = command
		defaults.set(try JSONEncoder().encode(commands), forKey: key)
		inFlight.insert(command.target.id)
	}
	func finish(_ command: Mango9ReactionCommand, outcome: Mango9ReactionOutcome) {
		inFlight.remove(command.target.id)
		if outcome != .unknown, commands[command.target.id] == command {
			commands.removeValue(forKey: command.target.id)
			defaults.set(try? JSONEncoder().encode(commands), forKey: key)
		}
	}
}

/// Separate bearer paths. Never forward browser cookies to the social API.
final class Mango9MessagingRedirectPolicy: NSObject, URLSessionTaskDelegate {
	func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
		newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
		// A redirect must not change a command's origin, method, or credential scope.
		completionHandler(nil)
	}
}

@MainActor final class Mango9MessagingAPI {
	struct Envelope<T: Decodable>: Decodable { let success: Bool; let data: T? }
	static let shared = Mango9MessagingAPI()
	private let transport: URLSession
	private var actorIDs: [String: Int] = [:]
	func actorID(for session: Mango9Session) -> Int? { actorIDs[Mango9ReactionTarget.accountKey(session)] }
	func putSocialAsset(url: URL, mime: String, file: URL, session: Mango9Session) async throws {
		guard Mango9SessionStore.isActive(session), !Task.isCancelled else { throw Mango9MessagingError.scopeChanged }
		var put = URLRequest(url: url); put.httpMethod = "PUT"; put.timeoutInterval = 120
		put.httpShouldHandleCookies = false
		put.setValue(mime, forHTTPHeaderField: "Content-Type")
		// The signed storage URL must never receive the CRM bearer or cookies.
		let (_, response) = try await transport.upload(for: put, fromFile: file)
		guard Mango9SessionStore.isActive(session), !Task.isCancelled else { throw Mango9MessagingError.scopeChanged }
		guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
			throw Mango9MessagingError.rejected(502, "Attachment upload failed. Nothing was sent.")
		}
	}
	func downloadSocialAsset(url: URL, session: Mango9Session) async throws -> Attachment {
		guard url.scheme == "https", url.host != nil, url.user == nil, url.password == nil else { throw Mango9MessagingError.invalidResponse }
		guard Mango9SessionStore.isActive(session), !Task.isCancelled else { throw Mango9MessagingError.scopeChanged }
		var request = URLRequest(url: url); request.httpShouldHandleCookies = false; request.timeoutInterval = 60
		let attachment = try await Self.fetchSocialAsset(request: request, transport: transport)
		guard Mango9SessionStore.isActive(session), !Task.isCancelled else {
			try? FileManager.default.removeItem(at: attachment.full)
			throw Mango9MessagingError.scopeChanged
		}
		return attachment
	}
	/// Buffered network reads and file writes must not monopolize the UI actor,
	/// particularly when forwarding a video. Enforce the limit even without a
	/// Content-Length header, then validate the account again before publishing it.
	nonisolated private static func fetchSocialAsset(request: URLRequest, transport: URLSession) async throws -> Attachment {
		let (bytes, response) = try await transport.bytes(for: request)
		guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
			let mime = response.mimeType, let ext = ["image/jpeg": "jpg", "image/png": "png", "video/mp4": "mp4"][mime],
			response.expectedContentLength <= 25 * 1048576 else {
			throw Mango9MessagingError.rejected(400, "This attachment cannot be opened or forwarded in the app. Supported: JPG, PNG, MP4 up to 25 MB.")
		}
		var data = Data()
		for try await byte in bytes {
			guard data.count < 25 * 1048576 else { throw Mango9MessagingError.rejected(400, "Attachment exceeds 25 MB.") }
			data.append(byte)
			if data.count % 65536 == 0 {
				try Task.checkCancellation()
			}
		}
		try Task.checkCancellation()
		guard !data.isEmpty else { throw Mango9MessagingError.invalidResponse }
		let id = UUID().uuidString
		let url = FileManager.default.temporaryDirectory.appendingPathComponent(id + "." + ext)
		try data.write(to: url, options: [.atomic, .completeFileProtection])
		return Attachment(id: id, name: "Attachment." + ext, url: url, type: ext == "mp4" ? .video : .image, size: data.count)
	}
	init(transport: URLSession? = nil) {
		let config = URLSessionConfiguration.ephemeral
		config.httpCookieStorage = nil; config.httpShouldSetCookies = false
		config.urlCache = nil
		self.transport = transport ?? URLSession(configuration: config, delegate: Mango9MessagingRedirectPolicy(), delegateQueue: nil)
	}

	func request<T: Decodable>(_ type: T.Type, session original: Mango9Session, social: Bool,
		path: [String], query: [URLQueryItem] = [], body: [String: Any]? = nil, timeout: TimeInterval = 20) async throws -> T {
		var session = Mango9SessionStore.load() ?? original
		let scope = Mango9ReactionTarget.accountKey(original)
		func check() throws {
			guard !Task.isCancelled, Mango9SessionStore.isActive(original),
				let active = Mango9SessionStore.load(), Mango9ReactionTarget.accountKey(active) == scope else {
				throw Mango9MessagingError.scopeChanged
			}
		}
		try check()
		for attempt in 0...1 {
			var token = session.accessToken
			if !social {
				let bootstrap: Mango9ChatBootstrap
				do { bootstrap = try await Mango9CRMAPI.chatBootstrap(session: session, transport: transport) }
				catch Mango9CRMAPIError.unauthorized {
					session = try await Mango9CRMAPI.refresh(session: session, transport: transport)
					try check()
					bootstrap = try await Mango9CRMAPI.chatBootstrap(session: session, transport: transport)
				}
				try check()
				actorIDs[scope] = bootstrap.userId
				token = bootstrap.token
			}
			try check()
			guard var url = URL(string: social ? session.crmBaseUrl : session.smsChatApi),
				url.scheme == "https", url.host != nil else { throw Mango9MessagingError.invalidResponse }
			if social { url = URL(string: "/app/ad-manager/api/social-posting/", relativeTo: url)!.absoluteURL }
			for component in path { url.appendPathComponent(component) }
			var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
			if !query.isEmpty { components.queryItems = query }
			var request = URLRequest(url: components.url!)
			request.timeoutInterval = timeout; request.httpShouldHandleCookies = false
			request.httpMethod = body == nil ? "GET" : "POST"
			request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
			request.setValue("application/json", forHTTPHeaderField: "Accept")
			if let body {
				request.httpBody = try JSONSerialization.data(withJSONObject: body)
				request.setValue("application/json", forHTTPHeaderField: "Content-Type")
			}
			try await Mango9SessionRenewal.shared.checkKnownExpiredBearer(request)
			let (data, response) = try await transport.data(for: request)
			try check()
			guard let http = response as? HTTPURLResponse else { throw Mango9MessagingError.invalidResponse }
			if http.statusCode == 401 && attempt == 0 {
				session = try await Mango9CRMAPI.refresh(session: session, transport: transport)
				try check()
				continue // Identical command ID/payload; only replace expired credentials.
			}
			guard (200..<300).contains(http.statusCode) else {
				let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
				let error = json?["error"] as? [String: Any]
				throw Mango9MessagingError.rejected(http.statusCode, error?["code"] as? String ?? json?["error"] as? String ?? "request_rejected")
			}
			let envelope = try JSONDecoder().decode(Envelope<T>.self, from: data)
			guard envelope.success, let result = envelope.data else { throw Mango9MessagingError.invalidResponse }
			return result
		}
		throw Mango9MessagingError.invalidResponse
	}
}

struct Mango9StaffReactionReceipt: Decodable {
	let channel: Mango9ReactionChannel
	let messageId: String
	let reactions: [String: [Int]]
	let version: Int
	let capabilities: Mango9ReactionCapabilities
	let state: Mango9ReactionOutcome?
	var snapshot: Mango9StaffReactions { .init(reactions: reactions, version: version, capabilities: capabilities) }
}

/// Native context-menu commands. No standalone picker, no automatic resend.
@MainActor final class Mango9ReactionActions: ObservableObject {
	@Published private(set) var busy = false
	@Published var notice: String?
	@Published private(set) var staffSnapshot: Mango9StaffReactions?
	private var snapshotTarget: Mango9ReactionTarget?
	private let api: Mango9MessagingAPI
	private let refreshHistory: Bool
	init(api: Mango9MessagingAPI? = nil, refreshHistory: Bool = true) {
		self.api = api ?? .shared; self.refreshHistory = refreshHistory
	}

	func staff(_ command: Mango9ReactionCommand, session: Mango9Session) async {
		guard !busy, command.target.matches(session), Mango9SessionStore.isActive(session) else { return }
		busy = true; notice = nil
		let journal = Mango9ReactionJournal.shared
		let previouslyUnconfirmed = journal.pending(command.target) != nil
		var began = false; var outcome = Mango9ReactionOutcome.unknown
		do {
			try journal.begin(command); began = true
			let receipt = try await api.request(Mango9StaffReactionReceipt.self, session: session,
				social: false, path: ["reactions", command.target.channel.rawValue, command.target.message], body: command.body)
			guard receipt.channel == command.target.channel, receipt.messageId == command.target.message else { throw Mango9MessagingError.invalidResponse }
			outcome = receipt.state ?? .unknown
			apply(receipt, target: command.target)
			if outcome != .succeeded { notice = outcome == .failed ? "Reaction not saved." : Self.unknownNotice }
		} catch {
			outcome = .transportFailure(error, previouslyUnconfirmed: previouslyUnconfirmed)
			notice = outcome == .unknown ? Self.unknownNotice : error.localizedDescription
		}
		if began { journal.finish(command, outcome: outcome) }
		// Saved state wins over optimistic UI, including stale-version rejection.
		if let receipt = try? await api.request(Mango9StaffReactionReceipt.self, session: session,
			social: false, path: ["reactions", command.target.channel.rawValue, command.target.message]),
			receipt.channel == command.target.channel, receipt.messageId == command.target.message {
			apply(receipt, target: command.target)
		}
		busy = false
		if refreshHistory { await Mango9ChatStore.shared.refreshAfterForeground() }
	}

	func social(_ command: Mango9ReactionCommand, session: Mango9Session) async {
		guard !busy, command.target.matches(session), Mango9SessionStore.isActive(session) else { return }
		busy = true; notice = nil; defer { busy = false }
		let journal = Mango9ReactionJournal.shared
		let previouslyUnconfirmed = journal.pending(command.target) != nil
		var began = false; var outcome = Mango9ReactionOutcome.unknown
		do {
			try journal.begin(command); began = true
			let receipt = try await api.request(Mango9SocialReactionReceipt.self, session: session,
				social: true, path: ["inbox-moderate"], body: command.body)
			guard receipt.id == command.target.conversation, String(receipt.messageId) == command.target.message,
				receipt.action == (command.remove ? "unreact" : "react") else { throw Mango9MessagingError.invalidResponse }
			outcome = receipt.state
			if outcome != .succeeded {
				notice = outcome == .failed ? "The provider rejected this reaction. \(receipt.error ?? "")" : Self.unknownNotice
			}
		} catch {
			outcome = .transportFailure(error, previouslyUnconfirmed: previouslyUnconfirmed)
			notice = outcome == .unknown ? Self.unknownNotice : error.localizedDescription
		}
		if began { journal.finish(command, outcome: outcome) }
	}
	private func apply(_ receipt: Mango9StaffReactionReceipt, target: Mango9ReactionTarget) {
		guard let active = Mango9SessionStore.load(), target.matches(active) else { return }
		staffSnapshot = (snapshotTarget == target ? staffSnapshot ?? .empty : .empty).merging(receipt.snapshot)
		snapshotTarget = target
		Mango9ChatStore.shared.applyReaction(receipt, target: target)
	}
	private static let unknownNotice = "The reaction outcome is unconfirmed. Hold the message and choose Check saved reaction to retry only the same request. No new reaction will be sent automatically."
}

/// System menus choose the presentation appropriate to the iOS version.
struct Mango9NativeReactionChoices: View {
	let selected: (String) -> Bool
	let choose: (String) -> Void
	let existing: [String]
	private let common = ["❤️", "👍", "👎", "😂", "‼️", "❓"]
	var body: some View {
		if #available(iOS 17.0, *) {
			ControlGroup {
				ForEach(common, id: \.self) { emoji in reaction(emoji) }
			}
		} else {
			ForEach(common, id: \.self) { emoji in reaction(emoji) }
		}
		Menu("More reactions") {
			ForEach(Array(Set(["👍🏽", "👨‍👩‍👧‍👦", "🇺🇸", "😮", "😢", "🎉"] + existing)).sorted(), id: \.self) { emoji in reaction(emoji) }
		}
	}
	private func reaction(_ emoji: String) -> some View {
		Button { choose(emoji) } label: {
			if let symbol = Self.symbols[emoji] {
				Label(selected(emoji) ? "\(emoji) ✓" : emoji, systemImage: symbol)
			} else { Text(selected(emoji) ? "\(emoji) ✓" : emoji) }
		}.accessibilityLabel("\(selected(emoji) ? "Remove" : "Add") \(emoji) reaction")
	}
	// Native menus render SF Symbols reliably in their compact horizontal control group.
	private static let symbols = ["❤️": "heart.fill", "👍": "hand.thumbsup.fill", "👎": "hand.thumbsdown.fill",
		"😂": "face.smiling.fill", "‼️": "exclamationmark.bubble.fill", "❓": "questionmark.bubble.fill"]
}

/// The same compact native input and send control for every conversation tab.
struct Mango9MessageComposer: View {
	@State private var templatePresentation: Mango9TemplatePresentation?
	var templateSession: Mango9Session? = nil
	@Environment(\.verticalSizeClass) private var verticalSizeClass
	private var compact: Bool { verticalSizeClass == .compact }
	@Binding var text: String
	let focus: FocusState<Bool>.Binding
	var placeholder = "Message"
	var enabled = true
	var sending = false
	var sendEnabled = true
	var hasAttachments = false
	var attach: ((Mango9AttachmentRequest) -> Void)?
	var record: (() -> Void)?
	let send: () -> Void
	var body: some View {
		HStack(alignment: .center, spacing: 8) {
			Group {
				// The menu must belong to this button, never to the screen-sized picker host.
				Menu {
					Button { focus.wrappedValue = false; attach?(.init(source: .camera)) } label: {
						Label("Take photo", systemImage: "camera")
					}.disabled(!enabled || attach == nil || !UIImagePickerController.isSourceTypeAvailable(.camera))
					Button { focus.wrappedValue = false; attach?(.init(source: .photos)) } label: {
						Label("Photo library", systemImage: "photo.on.rectangle")
					}.disabled(!enabled || attach == nil)
					Button { focus.wrappedValue = false; attach?(.init(source: .files)) } label: {
						Label("Choose file", systemImage: "doc")
					}.disabled(!enabled || attach == nil)
					Button {
						focus.wrappedValue = false
						if let session = templateSession ?? Mango9SessionStore.load() { templatePresentation = .init(session: session) }
					} label: { Label("Templates ›", systemImage: "doc.text") }
				} label: {
					Image(systemName: "plus.circle.fill").font(.title2).foregroundStyle(Color.orangeMain500)
						.frame(width: 44, height: 44).contentShape(Rectangle())
				}
				.accessibilityLabel("Add to message").accessibilityIdentifier("chat.attach")
				.mango9FixedComposerMenuOrder()
				.disabled(sending)
			}
			Group {
				if #available(iOS 16.0, *) {
					TextField(placeholder, text: $text, axis: .vertical).lineLimit(1...(compact ? 3 : 5)).focused(focus)
				} else {
					TextField(placeholder, text: $text).focused(focus)
				}
			}.font(compact ? .callout : .body).padding(.horizontal, 14).padding(.vertical, compact ? 7 : 11)
				.frame(minHeight: 44)
				.background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 22))
				.disabled(!enabled || sending).accessibilityIdentifier("chat.composer")
			if text.isEmpty && !hasAttachments, let record {
				Button(action: { focus.wrappedValue = false; record() }) {
					Image(systemName: "mic.fill").font(.title2).foregroundStyle(Color.orangeMain500)
						.frame(width: 44, height: 44).contentShape(Rectangle())
				}.accessibilityLabel("Record a voice message").accessibilityIdentifier("chat.record").disabled(!enabled || sending)
			} else {
				Button(action: { focus.wrappedValue = false; send() }) {
					Group {
						if sending { ProgressView().tint(.white) }
						else { Image(systemName: "arrow.up").font(.body.weight(.bold)) }
					}.foregroundStyle(.white).frame(width: 32, height: 32)
						.background(Color.orangeMain500).clipShape(Circle()).frame(width: 44, height: 44)
				}.disabled(!enabled || !sendEnabled || sending || (text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !hasAttachments))
					.accessibilityLabel("Send message").accessibilityIdentifier("chat.send")
			}
		}.buttonStyle(.plain).tint(Color.orangeMain500).padding(.horizontal, 10).padding(.vertical, compact ? 2 : 8)
			.background(Color(uiColor: .systemBackground).ignoresSafeArea(.container, edges: .bottom))
			.fullScreenCover(item: $templatePresentation) { presentation in
				Mango9TemplatePicker(session: presentation.session) { value in
					guard Mango9TemplateAPI(session: presentation.session).active, !sending else { return }
					text = Mango9TemplateText.inserting(value, into: text); focus.wrappedValue = false
				}
			}
	}
}

struct Mango9StaffReactionMenu: View {
	let target: Mango9ReactionTarget
	let session: Mango9Session
	let state: Mango9StaffReactions
	let actorID: Int?
	@ObservedObject var actions: Mango9ReactionActions
	var body: some View {
		Section(target.channel == .sms ? "Mango9 only · No SMS or tapback sent" : "Reactions") {
			if let pending = Mango9ReactionJournal.shared.pending(target) {
				Button("Check saved reaction") { Task { await actions.staff(pending, session: session) } }
			} else if state.capabilities?.permits(target.channel) == true, let actorID {
				Mango9NativeReactionChoices(selected: { state.contains($0, actor: actorID) }, choose: { emoji in
					let command = Mango9ReactionCommand(target: target, version: state.version, emoji: emoji, remove: state.contains(emoji, actor: actorID))
					Task { await actions.staff(command, session: session) }
				}, existing: Array(state.reactions.keys))
			}
		}.disabled(actions.busy)
	}
}

struct Mango9ReactionBadges: View {
	let state: Mango9StaffReactions
	var body: some View {
		if !state.reactions.isEmpty {
			ScrollView(.horizontal, showsIndicators: false) {
				HStack(spacing: 4) {
					ForEach(state.reactions.keys.sorted(), id: \.self) { emoji in
						Mango9ReactionBadge(text: "\(emoji) \(state.reactions[emoji]?.count ?? 0)")
					}
				}
			}
			.accessibilityLabel("Message reactions. Hold the message to change your reaction.")
		}
	}
}

struct Mango9ReactionBadge: View {
	let text: String
	var body: some View {
		Text(text).font(.caption).padding(.horizontal, 8).padding(.vertical, 4)
			.background(Color(uiColor: .tertiarySystemGroupedBackground)).clipShape(Capsule())
	}
}

#if DEBUG && targetEnvironment(simulator)
/// Offline native UI fixture. Never available in device or App Store builds.
struct Mango9ReactionsUITestFixture: View {
	static var isEnabled: Bool { ProcessInfo.processInfo.environment["UITEST_REACTIONS"] != nil }
	@State private var session: Mango9Session?
	@State private var api: Mango9MessagingAPI?
	var body: some View {
		Group {
			if let session, let api {
				if ProcessInfo.processInfo.environment["UITEST_REACTIONS"] == "new-chat" {
					Mango9NewConversationUITestFixture()
				} else if ProcessInfo.processInfo.environment["UITEST_REACTIONS"] == "social-list" {
					Mango9SocialInboxUITestFixture(session: session, api: api)
				} else if ProcessInfo.processInfo.environment["UITEST_REACTIONS"] == "social" {
					if ProcessInfo.processInfo.environment["UITEST_CONVERSATION_SHELL"] == "1" {
						Mango9ConversationShellUITestFixture {
							socialConversation(session: session, api: api)
						}
					} else { socialConversation(session: session, api: api) }
				} else {
					Mango9NativeReactionFixture(session: session, api: api)
				}
			} else { ProgressView() }
		}.overlay(alignment: .topLeading) { Mango9KeyboardFrameUITestProbe() }
		.task {
			guard session == nil else { return }
			let host = "reactions-ui.example.invalid"
			let session = Mango9Session(crmId: "fixture", crmBaseUrl: "https://\(host)", crmApiBaseUrl: "https://\(host)/app/api/v2",
				userId: "42", parentClientId: "1", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture",
				accessToken: "fixture", refreshToken: "fixture", smsChatApi: "https://\(host)/sms_chat_api",
				connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:42@\(host)")
			try? Mango9SessionStore.save(session, persist: false, makeActive: true)
			UserDefaults.standard.removeObject(forKey: "mango9.personal-message-visibility.v1." + Mango9ReactionTarget.accountKey(session))
			UserDefaults.standard.removeObject(forKey: "mango9.personal-conversation-visibility.v1." + Mango9ReactionTarget.accountKey(session))
			ReactionUIProtocol.reset()
			let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ReactionUIProtocol.self]
			api = Mango9MessagingAPI(transport: URLSession(configuration: config)); self.session = session
		}
	}
	private func socialConversation(session: Mango9Session, api: Mango9MessagingAPI) -> some View {
		NavigationView {
			Mango9SocialConversation(thread: ReactionUIProtocol.socialThread, session: session, api: api)
		}.navigationViewStyle(.stack)
	}
}

private struct Mango9SocialInboxUITestFixture: View {
	@StateObject private var store: Mango9SocialInboxStore
	init(session: Mango9Session, api: Mango9MessagingAPI) { _store = StateObject(wrappedValue: Mango9SocialInboxStore(session: session, api: api)) }
	var body: some View { NavigationView { SocialInboxListContent(store: store, search: "").navigationTitle("Social") }.navigationViewStyle(.stack) }
}

private struct Mango9NewConversationUITestFixture: View {
	@State private var tab: Mango9ConversationTab = .sms
	@State private var showingTeam = false
	@State private var result = "Choose a conversation"
	private let users = [Mango9ChatUser(id: 11, name: "Alex Example", avatar: "", category: "Staff"),
		Mango9ChatUser(id: 12, name: "Sam Example", avatar: "", category: "Staff")]
	var body: some View {
		VStack {
			Mango9ConversationTabs(selectedTab: $tab, smsUnreadCount: 0, teamUnreadCount: 0)
			Text(result); Spacer()
		}.overlay(alignment: .bottomTrailing) {
			Mango9ConversationComposeButton(tab: tab, onSMS: { result = "SMS composer" }, onTeam: { showingTeam = true })
		}.sheet(isPresented: $showingTeam) {
			Mango9TeamMemberPicker(title: "New Team chat", users: users, initialSelection: [], minimumSelection: 1,
				actionTitle: "Start chat", failureMessage: { "Fixture connection failed" }) { selection in
				guard let route = Mango9TeamChatStart(selection: selection, users: users) else { return false }
				if ProcessInfo.processInfo.environment["UITEST_TEAM_CREATE_FAIL"] == "1" { return false }
				switch route {
				case .direct(let user): result = "Team direct: \(user.id)"
				case .group(let ids): result = "Team group: \(ids.sorted().map(String.init).joined(separator: ","))"
				}
				return true
			}
		}
	}
}

/// Exercise the production panel clipping/shadow and navigation visibility, not
/// just a standalone conversation that never had the root's bottom shadow.
private struct Mango9ConversationShellUITestFixture<Content: View>: View {
	@StateObject private var navigation = Mango9ConversationNavigation()
	@ViewBuilder let content: () -> Content
	var body: some View {
		GeometryReader { geometry in
			VStack(spacing: 0) {
				Text("Conversations").font(.title2.bold()).foregroundStyle(.white)
					.frame(maxWidth: .infinity).frame(height: 60)
					.background(Color.orangeMain500.ignoresSafeArea(.container, edges: .top))
				content().mango9ConversationsPanel(isConversationOpen: navigation.hidesAppNavigation,
					isLandscape: geometry.size.width > geometry.size.height)
			}.background(Color.white)
		}.environment(\.mango9ConversationNavigation, navigation)
	}
}

/// XCTest's keyboard element can exclude QuickType. Measure the system's actual
/// keyboard frame instead, without changing any production layout behavior.
private struct Mango9KeyboardFrameUITestProbe: View {
	@State private var top: CGFloat = 0
	var body: some View {
		Text(" ").frame(width: 1, height: 1).allowsHitTesting(false)
			.accessibilityElement(children: .ignore)
			.accessibilityLabel("Keyboard top").accessibilityValue(String(Double(top)))
			.accessibilityIdentifier("fixture.keyboardTop")
			.onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
				if let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect { top = frame.minY }
			}
	}
}

private struct Mango9NativeReactionFixture: View {
	let session: Mango9Session
	@StateObject private var actions: Mango9ReactionActions
	init(session: Mango9Session, api: Mango9MessagingAPI) {
		self.session = session; _actions = StateObject(wrappedValue: Mango9ReactionActions(api: api, refreshHistory: false))
	}
	private var state: Mango9StaffReactions {
		actions.staffSnapshot ?? .init(capabilities: .init(supported: true, scope: "workspace", recipientVisible: false, textFallback: false, providerReactions: false))
	}
	var body: some View {
		NavigationView {
			VStack(alignment: .leading) {
				Text("Hold this message to react").font(.body).padding(14)
					.background(Color(uiColor: .secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 20))
					.mango9MessageActions {
						.staff(target: .init(account: Mango9ReactionTarget.accountKey(session), channel: .sms,
							conversation: "15555550100", message: "fixture-sms"), session: session, state: state, actor: 42, handler: actions)
					}
				Mango9ReactionBadges(state: state)
				Spacer()
			}.padding().navigationTitle("Messages")
		}.navigationViewStyle(.stack)
	}
}

private final class ReactionUIProtocol: URLProtocol {
	private static let lock = NSLock()
	private static var reactions: [String: [Int]] = [:]
	private static var version = 0
	private static var socialReaction: [String: Any] = [:]
	private static var hiddenSocial: Set<Int> = []
	private static var mutedSocial: [String: Bool] = [:]
	private static var hiddenConversations: Set<String> = []
	private static var conversationReceipts: [String: [String: Any]] = [:]
	private static let socialCapabilities: [String: Any] = ["supported": true, "scope": "provider", "recipientVisible": true,
		"textFallback": false, "canAdd": true, "canRemove": true]
	private static var threadData: [String: Any] {
		["id": "fixture-thread", "version": version, "sourceId": "fixture-source", "platform": "instagram", "name": "Customer conversation",
		 "summary": "Hello from the customer", "activityAt": "2026-01-01T12:00:00Z", "accountHandle": "Studio",
		 "unread": false, "connectionCurrent": true, "canReply": true, "reactionCapabilities": socialCapabilities]
	}
	static var socialThread: Mango9SocialThread {
		try! JSONDecoder().decode(Mango9SocialThread.self, from: JSONSerialization.data(withJSONObject: threadData))
	}
	static func reset() { lock.lock(); defer { lock.unlock() }; reactions = [:]; socialReaction = [:]; hiddenSocial = []; mutedSocial = [:]; hiddenConversations = []; conversationReceipts = [:]; version = 0 }
	override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "reactions-ui.example.invalid" }
	override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
	override func startLoading() {
		do {
			Self.lock.lock(); defer { Self.lock.unlock() }
			if request.url?.lastPathComponent == "fixture.png" {
				let image = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 200)).image { context in
					UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: 320, height: 200))
					UIColor.systemYellow.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 205, y: 24, width: 60, height: 60))
					UIColor.systemIndigo.setFill(); context.fill(CGRect(x: 0, y: 130, width: 320, height: 70))
				}
				let data = image.pngData()!
				client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "image/png"])!, cacheStoragePolicy: .notAllowed)
				client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self); return
			}
			var payload: [String: Any]
			if request.url?.path.contains("social-posting") == true {
				switch request.url!.lastPathComponent {
				case "accounts": payload = ["items": [["id": String(repeating: "a", count: 24), "platform": "instagram", "name": "Studio"], ["id": String(repeating: "b", count: 24), "platform": "facebook", "name": "Company Page"]]]
				case "inbox":
					let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
					let platform = query.first { $0.name == "platform" }?.value
					var fb = Self.threadData; fb["id"] = "facebook-thread"; fb["name"] = "Facebook customer"; fb["platform"] = "facebook"; fb["accountHandle"] = "Company Page"
					var tt = Self.threadData; tt["id"] = "tiktok-thread"; tt["name"] = "TikTok customer"; tt["platform"] = "tiktok"; tt["accountHandle"] = "Video Studio"
					let rows = [Self.threadData, fb, tt].filter { platform == nil || $0["platform"] as? String == platform }
					payload = ["items": rows, "next": NSNull()]
				case "inbox-notification-get", "inbox-notification-set":
					let body = request.httpMethod == "POST" ? try Self.body(request) : [:]
					let id = body["id"] as? String ?? URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "id" })?.value ?? "fixture-thread"
					if let value = body["muted"] as? Bool { Self.mutedSocial[id] = value }
					payload = ["id": id, "muted": Self.mutedSocial[id] ?? false]
				case "inbox-attachment": payload = ["url": "https://reactions-ui.example.invalid/fixture.png"]
				case "inbox-conversation-visibility":
					let body = try Self.body(request), ids = body["conversationIds"] as! [String]
					if body["hide"] as? Bool == true { Self.hiddenConversations.formUnion(ids) }
					let hidden = Array(Self.hiddenConversations.intersection(ids))
					payload = ["scope": "user", "channel": "social", "state": "succeeded", "conversationIds": ids, "clearedConversationIds": hidden, "hiddenConversationIds": hidden]
					if let requestID = body["requestId"] as? String {
						payload["requestId"] = requestID
						if let saved = Self.conversationReceipts[requestID] { payload = saved } else { Self.conversationReceipts[requestID] = payload }
					}
				case "inbox-message-visibility":
					let body = try Self.body(request), ids = body["messageIds"] as! [Int]
					if body["hide"] as? Bool == true { Self.hiddenSocial.formUnion(ids) }
					payload = ["scope": "user", "channel": "social", "state": "succeeded", "hiddenIds": Self.hiddenSocial.intersection(ids).map(String.init)]
				case "inbox-detail": payload = Self.threadData
				case "inbox-sends": payload = ["items": [], "open": NSNull()]
				case "inbox-messages":
					var items: [[String: Any]] = [["id": 1, "text": "Hello from the customer", "direction": "incoming", "senderName": "Customer",
						"createdAt": "2026-01-01T12:00:00Z", "deleted": false, "reactions": Self.socialReaction,
						"reactionCapabilities": Self.socialCapabilities]]
					if ProcessInfo.processInfo.environment["UITEST_SOCIAL_ATTACHMENT"] == "1" {
						items[0]["text"] = ""; items[0]["attachments"] = [["index": 0, "type": "image", "name": "Sample.png"]]
					}
					if ProcessInfo.processInfo.environment["UITEST_MULTI_MESSAGE"] == "1" {
						items += [
							["id": 2, "text": "Our reply", "direction": "outgoing", "createdAt": "2026-01-01T12:01:00Z", "deleted": false],
							["id": 3, "text": "Another incoming message", "direction": "incoming", "createdAt": "2026-01-01T12:02:00Z", "deleted": false]
						]
					}
					payload = ["items": items, "next": NSNull()]
				case "inbox-moderate":
					let body = try Self.body(request)
					let remove = body["action"] as? String == "unreact"
					Self.socialReaction["self"] = ["emoji": body["emoji"] as? String ?? "👍", "action": remove ? "removed" : "added", "fromMe": true, "at": "2026-01-01T12:00:00Z"]
					Self.version += 1
					payload = ["id": "fixture-thread", "messageId": 1, "action": remove ? "unreact" : "react", "state": "succeeded"]
				case "inbox-refresh-messages": payload = [:]
				default: throw Mango9MessagingError.invalidResponse
				}
			} else if request.url?.path.hasSuffix("bootstrap") == true {
				payload = ["websocketUrl": "wss://reactions-ui.example.invalid", "token": "fixture", "expiresIn": 900, "userId": 42, "transport": "websocket"]
			} else {
				if request.httpMethod == "POST" {
					let body = try Self.body(request)
					let emoji = body["emoji"] as! String
					if body["remove"] as? Bool == true { Self.reactions.removeValue(forKey: emoji) }
					else { Self.reactions[emoji] = [42] }
					Self.version += 1
				}
				payload = ["channel": "sms", "messageId": "fixture-sms", "reactions": Self.reactions, "version": Self.version,
					"capabilities": ["supported": true, "scope": "workspace", "recipientVisible": false, "textFallback": false, "providerReactions": false], "state": "succeeded"]
			}
			let data = try JSONSerialization.data(withJSONObject: ["success": true, "message": "", "data": payload])
			client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
			client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
		} catch { client?.urlProtocol(self, didFailWithError: error) }
	}
	override func stopLoading() {}
	private static func body(_ request: URLRequest) throws -> [String: Any] {
		var bytes = request.httpBody ?? Data()
		if let stream = request.httpBodyStream {
			stream.open(); defer { stream.close() }; var buffer = [UInt8](repeating: 0, count: 4096)
			while stream.hasBytesAvailable { let n = stream.read(&buffer, maxLength: buffer.count); if n <= 0 { break }; bytes.append(buffer, count: n) }
		}
		return try JSONSerialization.jsonObject(with: bytes) as! [String: Any]
	}
}
#endif
