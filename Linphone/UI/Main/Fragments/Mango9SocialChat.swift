import SwiftUI
import QuickLook
import ImageIO
import AVFoundation

/// Only small thumbnails live in memory. Original downloads are transient and
/// removed after decoding; full-size viewing still uses the authorized endpoint.
@MainActor final class Mango9SocialThumbnailCache {
	static let shared = Mango9SocialThumbnailCache()
	private let images = NSCache<NSString, UIImage>()
	private var tasks: [String: Task<UIImage, Error>] = [:]
	private var slots = [Task<Void, Never>?](repeating: nil, count: 3)
	private var nextSlot = 0
	private var account: String?
	private var generation = UUID()
	private var observer: NSObjectProtocol?
	init() {
		images.totalCostLimit = 24 * 1024 * 1024; images.countLimit = 48
		observer = NotificationCenter.default.addObserver(forName: .mango9AccountContextChanged, object: nil, queue: .main) { [weak self] _ in
			Task { @MainActor in self?.clear() }
		}
	}
	deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
	func clear() {
		generation = UUID()
		for task in tasks.values { task.cancel() }
		tasks = [:]; slots = [nil, nil, nil]; images.removeAllObjects(); account = nil
	}
	func thumbnail(session: Mango9Session, thread: String, message: Int, media: Mango9SocialMessage.Media, api: Mango9MessagingAPI) async throws -> UIImage {
		guard Mango9SessionStore.isActive(session), !Task.isCancelled else { throw Mango9MessagingError.scopeChanged }
		let scope = Mango9ReactionTarget.accountKey(session)
		if account != scope { clear(); account = scope }
		let generation = self.generation
		let key = "\(scope):\(thread):\(message):\(media.index)"
		if let image = images.object(forKey: key as NSString) { return image }
		if let task = tasks[key] { return try await task.value }
		let slot = nextSlot; nextSlot = (nextSlot + 1) % slots.count
		let previous = slots[slot]
		let task = Task<UIImage, Error> {
			await previous?.value
			try Task.checkCancellation()
			guard Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
			let file = try await api.socialAttachment(session: session, thread: thread, message: message, index: media.index)
			defer { try? FileManager.default.removeItem(at: file.full) }
			let image = try await Self.decode(file.full, video: file.type == .video)
			try Task.checkCancellation()
			guard Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
			return image
		}
		tasks[key] = task
		slots[slot] = Task { _ = try? await task.value }
		do {
			let image = try await task.value
			guard self.generation == generation, account == scope, Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
			images.setObject(image, forKey: key as NSString, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
			tasks[key] = nil
			return image
		} catch { if self.generation == generation, account == scope { tasks[key] = nil }; throw error }
	}
	nonisolated static func decode(_ url: URL, video: Bool) async throws -> UIImage {
		try await Task.detached(priority: .utility) {
			if video {
				let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
				generator.appliesPreferredTrackTransform = true; generator.maximumSize = CGSize(width: 512, height: 512)
				return UIImage(cgImage: try generator.copyCGImage(at: .zero, actualTime: nil))
			}
			guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
				let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
					kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 512] as CFDictionary) else {
				throw Mango9MessagingError.rejected(400, "Preview unavailable. Tap to try opening the original attachment.")
			}
			return UIImage(cgImage: image)
		}.value
	}
}

private struct Mango9SocialAttachmentPreview: View {
	let session: Mango9Session
	let thread: String
	let message: Mango9SocialMessage
	let media: Mango9SocialMessage.Media
	let api: Mango9MessagingAPI
	let open: () -> Void
	let actions: () -> Mango9MessageActionSet
	let selectionMode: Bool
	@State private var thumbnail: UIImage?
	@State private var failed = false
	var body: some View {
		ZStack {
			if let thumbnail {
				Image(uiImage: thumbnail).resizable().scaledToFit().frame(maxWidth: 240, maxHeight: 240)
					.accessibilityIdentifier("social.attachment.preview.\(message.id).\(media.index)")
				if media.type == "video" { Image(systemName: "play.circle.fill").font(.system(size: 40)).foregroundStyle(.white).shadow(radius: 2) }
			} else {
				VStack(spacing: 8) {
					if failed { Image(systemName: "photo").font(.title2); Text("Open attachment").font(.caption) }
					else { ProgressView(); Text("Loading preview…").font(.caption) }
				}.frame(width: 200, height: 130).background(Color(uiColor: .secondarySystemBackground))
			}
		}.clipShape(RoundedRectangle(cornerRadius: 16)).contentShape(Rectangle())
			.accessibilityElement(children: .ignore).accessibilityAddTraits(.isButton).accessibilityAction { open() }
			.accessibilityLabel(media.type == "video" ? "Open video attachment" : "Open image attachment")
			.accessibilityIdentifier("social.attachment.\(message.id).\(media.index)")
			.accessibilityValue(thumbnail != nil ? "Preview loaded" : failed ? "Preview unavailable" : "Loading preview")
			.mango9MessageActions(enabled: !selectionMode, onTap: open, actions: actions)
			.task(id: "\(Mango9ReactionTarget.accountKey(session)):\(thread):\(message.id):\(media.index)") {
				do {
					let image = try await Mango9SocialThumbnailCache.shared.thumbnail(session: session, thread: thread, message: message.id, media: media, api: api)
					guard !Task.isCancelled, Mango9SessionStore.isActive(session) else { return }
					thumbnail = image
				} catch { if !Task.isCancelled { failed = true } }
			}
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in thumbnail = nil }
	}
}

struct Mango9SocialPushTarget: Equatable, Identifiable {
	let id: String
	let crmID: String
	let identity: String
	init?(userInfo: [AnyHashable: Any]) {
		let data = userInfo["mango9"] as? [String: Any] ?? Dictionary(uniqueKeysWithValues: userInfo.compactMap { key, value in (key as? String).map { ($0, value) } })
		guard data["event"] as? String == "social.message", let id = data["thread_id"] as? String, UUID(uuidString: id) != nil,
			let crm = data["crm_id"] as? String, !crm.isEmpty,
			let identity = Mango9SessionStore.normalizedIdentity(data["sip_identity"] as? String) else { return nil }
		self.id = id.lowercased(); crmID = crm; self.identity = identity
	}
	func matches(_ session: Mango9Session) -> Bool { session.crmId == crmID && Mango9SessionStore.normalizedIdentity(session.sipIdentity) == identity }
}

extension Notification.Name {
	static let mango9OpenSocial = Notification.Name("mango9OpenSocial")
	static let mango9SocialInboxDidChange = Notification.Name("mango9SocialInboxDidChange")
}

/// A push is a navigation hint, never authority to read a conversation.
struct Mango9SocialPushConversation: View {
	let target: Mango9SocialPushTarget
	let onClose: () -> Void
	@State private var thread: Mango9SocialThread?
	@State private var session: Mango9Session?
	@State private var error: String?
	@State private var retry = 0
	var body: some View {
		NavigationView {
			Group {
				if let thread, let session { Mango9SocialConversation(thread: thread, session: session) }
				else if let error { VStack(spacing: 16) { Text(error); Button("Try again") { retry += 1 } }.padding() }
				else { ProgressView("Opening conversation…") }
			}.toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("Back", action: onClose) } }
		}.navigationViewStyle(.stack)
		.task(id: retry) {
			error = nil
			guard let active = Mango9SessionStore.load(), target.matches(active) else { error = "This account is no longer available."; return }
			do {
				let result = try await Mango9MessagingAPI.shared.request(Mango9SocialThread.self, session: active, social: true,
					path: ["inbox-detail"], query: [URLQueryItem(name: "id", value: target.id)])
				guard !Task.isCancelled, result.id == target.id, Mango9SessionStore.isActive(active) else { return }
				session = active; thread = result
			} catch { if !Task.isCancelled { self.error = Mango9SocialReadError.message(error) } }
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in onClose() }
	}
}

enum Mango9SocialAttachmentPolicy {
	/// Preserve the original library asset. Downsample HEIC off the UI thread to a
	/// bounded JPEG, because the provider rejects HEIC even from an iPhone camera.
	static func providerAttachment(_ attachment: Attachment) async throws -> Attachment {
		guard ["heic", "heif"].contains(attachment.full.pathExtension.lowercased()) else { return attachment }
		return try await Task.detached(priority: .userInitiated) {
			guard let source = CGImageSourceCreateWithURL(attachment.full as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
				let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
					kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 2048] as CFDictionary) else {
				throw Mango9MessagingError.rejected(400, "Could not prepare this photo. Choose a different photo.")
			}
			let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
			guard let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil) else { throw Mango9MessagingError.invalidResponse }
			CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
			guard CGImageDestinationFinalize(destination) else { throw Mango9MessagingError.rejected(400, "Could not prepare the photo. Check available storage.") }
			return Attachment(id: attachment.id, name: (attachment.name as NSString).deletingPathExtension + ".jpg", url: url, type: .image)
		}.value
	}
	static func mime(extension ext: String, bytes: Int, platform: String) throws -> String {
		let mime = ["jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "mp4": "video/mp4"][ext.lowercased()]
		guard let mime, bytes > 0, bytes <= (platform == "tiktok" ? 3 : 25) * 1048576,
			platform != "tiktok" || mime.hasPrefix("image/") else {
			throw Mango9MessagingError.rejected(400, platform == "tiktok"
				? "TikTok supports one JPG or PNG up to 3 MB, without a caption."
				: "Social supports one JPG, PNG, or MP4 up to 25 MB. Other file types aren’t supported by the provider.")
		}
		return mime
	}
}

extension Mango9MessagingAPI {
	private struct SocialNotifications: Decodable { let id: String; let muted: Bool }
	func socialNotificationPreference(session: Mango9Session, thread: String, muted: Bool? = nil) async throws -> Bool {
		let value = try await request(SocialNotifications.self, session: session, social: true,
			path: [muted == nil ? "inbox-notification-get" : "inbox-notification-set"],
			query: muted == nil ? [URLQueryItem(name: "id", value: thread)] : [],
			body: muted.map { ["id": thread, "muted": $0] })
		guard value.id == thread, Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.invalidResponse }
		Mango9SocialMutePreferences.save(value.muted, session: session, thread: thread)
		return value.muted
	}
	struct SocialMediaAsset: Decodable { let id: String; let state: String }
	struct SocialMediaTicket: Decodable {
		let asset: SocialMediaAsset
		let upload: Upload?
		struct Upload: Decodable { let url: String; let method: String; let headers: [String: String] }
	}
	func uploadSocial(_ attachment: Attachment, session: Mango9Session, platform: String, id: String) async throws -> String {
		let original = attachment
		let attachment = try await Mango9SocialAttachmentPolicy.providerAttachment(original)
		defer { if attachment.full != original.full { try? FileManager.default.removeItem(at: attachment.full) } }
		let bytes = try attachment.full.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
		let mime = try Mango9SocialAttachmentPolicy.mime(extension: attachment.full.pathExtension, bytes: bytes, platform: platform)
		let ticket = try await request(SocialMediaTicket.self, session: session, social: true, path: ["media-prepare"],
			body: ["id": id, "name": attachment.name, "mime": mime, "size": bytes])
		guard ticket.asset.id == id else { throw Mango9MessagingError.invalidResponse }
		if ticket.asset.state == "ready" { return id }
		guard let upload = ticket.upload, upload.method == "PUT", let url = URL(string: upload.url),
			url.scheme == "https", url.user == nil, url.password == nil, url.host != nil,
			upload.headers["Content-Type"] == mime, Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.invalidResponse }
		try await putSocialAsset(url: url, mime: mime, file: attachment.full, session: session)
		let asset = try await request(SocialMediaAsset.self, session: session, social: true, path: ["media-complete"], body: ["id": id], timeout: 90)
		guard asset.id == id, asset.state == "ready" else { throw Mango9MessagingError.invalidResponse }
		return id
	}
	func socialAttachment(session: Mango9Session, thread: String, message: Int, index: Int) async throws -> Attachment {
		struct Link: Decodable { let url: String }
		let link = try await request(Link.self, session: session, social: true, path: ["inbox-attachment"], query: [
			URLQueryItem(name: "id", value: thread), URLQueryItem(name: "messageId", value: String(message)), URLQueryItem(name: "index", value: String(index))])
		guard let url = URL(string: link.url) else { throw Mango9MessagingError.invalidResponse }
		return try await downloadSocialAsset(url: url, session: session)
	}
}

/// Present read failures without exposing implementation names or provider codes.
/// Sending has separate uncertain-delivery handling and must never be auto-retried.
enum Mango9SocialReadError {
	static func message(_ error: Error) -> String {
		if (error as NSError).domain == NSURLErrorDomain {
			switch (error as NSError).code {
			case NSURLErrorTimedOut:
				return "The connection is taking too long. Please try again."
			case NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorCannotFindHost,
				 NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed, NSURLErrorDataNotAllowed:
				return "Couldn’t connect to Mango9. Check your internet connection and try again."
			default: return "Couldn’t refresh Social conversations. Please try again."
			}
		}
		switch error {
		case Mango9CRMAPIError.unauthorized, Mango9CRMAPIError.invalidConfiguration:
			return error.localizedDescription
		case Mango9MessagingError.scopeChanged:
			return "Your active account changed. Reopen Social conversations."
		case Mango9MessagingError.rejected(let status, let code):
			if code == "posting_session_required" { return error.localizedDescription }
			if code == "posting_connection_changed" { return "Reconnect the social account in Mango9’s web inbox, then try again." }
			switch status {
			case 401: return "Your Mango9 session has expired. Sign in again to continue."
			case 403: return "This account no longer has access to this Social conversation."
			case 404: return "This Social conversation is no longer available. Return to the inbox and refresh."
			case 429: return "Social conversations are refreshing too frequently. Wait a moment and try again."
			case 500...599: return "Social conversations are temporarily unavailable. Please try again shortly."
			default: return "Couldn’t refresh Social conversations. Please try again."
			}
		default: return "Couldn’t refresh Social conversations. Please try again shortly."
		}
	}
}

struct Mango9SocialThread: Decodable, Identifiable, Equatable {
	let id: String
	let version: Int
	let sourceId: String
	let platform: String
	let name: String?
	let summary: String?
	let participantHandle: String?
	let accountHandle: String?
	let unread: Bool
	let connectionCurrent: Bool
	let canReply: Bool?
	let deleted: Bool?
	let openSend: OpenSend?
	let reactionCapabilities: Mango9ReactionCapabilities?
	var accountId: String? = nil
	var activityAt: String? = nil
	var readThrough: Int? = nil
	var source: Source? = nil
	struct Source: Decodable, Equatable { let accountName: String?; let summary: String?; var providerStatus: String? = nil }
	struct OpenSend: Decodable, Equatable { let sendId: String; let state: String }
	var permitsSending: Bool { connectionCurrent && canReply == true && deleted != true && ["facebook", "instagram", "tiktok"].contains(platform) }
	var replyUnavailableReason: String? {
		guard !permitsSending else { return nil }
		if deleted == true { return "This conversation has been deleted. Replies are unavailable." }
		if !connectionCurrent { return "Reconnect this social account in Mango9’s web inbox to reply." }
		if !Mango9SocialPlatform.all.contains(platform) { return "Replies to this platform aren’t supported in the app." }
		if source?.providerStatus == "archived" { return "This conversation is archived and replies are disabled. Open an active conversation to reply." }
		return "Replies aren’t currently enabled for this conversation. Check its status in Mango9’s web inbox, then retry."
	}
	var title: String { [name, participantHandle].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.first(where: { !$0.isEmpty }) ?? "Social conversation" }
	var preview: String { [summary, source?.summary].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.first(where: { !$0.isEmpty }) ?? "View conversation" }
	var accountName: String { accountHandle ?? source?.accountName ?? "Connected account" }
	var supportsReactions: Bool { connectionCurrent && deleted != true && ["facebook", "instagram"].contains(platform) }
	func permitsReaction(on message: Mango9SocialMessage, removing: Bool = false) -> Bool {
		supportsReactions && !message.deleted && message.unavailable != true &&
		reactionCapabilities?.permits(.social, removing: removing) == true &&
		message.reactionCapabilities?.permits(.social, removing: removing) == true
	}
}

struct Mango9SocialAccount: Decodable, Identifiable, Equatable {
	let id: String
	let platform: String
	let name: String?
	let username: String?
	var title: String { [name, username].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.first(where: { !$0.isEmpty }) ?? Mango9SocialPlatform.title(platform) }
}

enum Mango9SocialPlatform {
	static let all = ["facebook", "instagram", "tiktok"]
	static func title(_ value: String) -> String { ["facebook": "Facebook", "instagram": "Instagram", "tiktok": "TikTok"][value] ?? "Social" }
}

/// Match SMS: today's inbox entries show a time; older entries show a date.
/// Inside a conversation retain both so messages from different days are unambiguous.
@MainActor enum Mango9ChatDateText {
	static func display(_ value: String?, inConversation: Bool = false, now: Date = Date(), timeZone: TimeZone = .autoupdatingCurrent) -> String {
		guard let value, let date = Mango9SMSConversationAdapter.date(from: value) else { return "" }
		var calendar = Calendar(identifier: .gregorian)
		calendar.timeZone = timeZone
		let formatter = DateFormatter()
		formatter.locale = Locale(identifier: "en_US_POSIX")
		formatter.calendar = calendar
		formatter.timeZone = timeZone
		formatter.dateFormat = inConversation ? "MM-dd-yyyy h:mma" : calendar.isDate(date, inSameDayAs: now) ? "h:mma" : "MM-dd-yyyy"
		return formatter.string(from: date)
	}
}

/// Bundled brand glyphs, not SF Symbols or text approximations. No network load.
struct Mango9SocialPlatformLogo: View {
	let platform: String
	var size: CGFloat = 32
	var body: some View {
		Group {
			if Mango9SocialPlatform.all.contains(platform) {
				Image("social-" + platform).renderingMode(.template).resizable().scaledToFit()
			} else {
				Image(systemName: "bubble.left.and.bubble.right").resizable().scaledToFit()
			}
		}.foregroundStyle(brandColor).frame(width: size, height: size)
			.background { if platform == "facebook" { Circle().fill(Color.white) } }
			.accessibilityLabel(Mango9SocialPlatform.title(platform))
	}
	private var brandColor: Color {
		switch platform {
		case "facebook": return Color(red: 8 / 255, green: 102 / 255, blue: 1)
		case "instagram": return Color(red: 228 / 255, green: 64 / 255, blue: 95 / 255)
		default: return .primary
		}
	}
}

enum Mango9SocialMutePreferences {
	static func key(_ session: Mango9Session, _ thread: String) -> String { "mango9.social-mute." + Mango9ReactionTarget.accountKey(session) + ":" + thread }
	static func isMuted(session: Mango9Session, thread: String, defaults: UserDefaults = .standard, now: Date = Date()) -> Bool {
		let name = key(session, thread)
		let age = now.timeIntervalSince1970 - defaults.double(forKey: name + ".confirmed")
		// The server controls delivery across devices. Only suppress an in-flight
		// alert briefly after a confirmed mute; never retain a stale local mute.
		return age >= 0 && age < 30 && defaults.bool(forKey: name)
	}
	static func save(_ muted: Bool, session: Mango9Session, thread: String, defaults: UserDefaults = .standard, now: Date = Date()) {
		let name = key(session, thread)
		defaults.set(muted, forKey: name); defaults.set(now.timeIntervalSince1970, forKey: name + ".confirmed")
	}
}

struct Mango9SocialInboxQuery: Equatable, Hashable {
	var search = ""
	var platform = ""
	var account = ""
	var fields: [String: String] {
		var result = ["kind": "dms", "limit": "50"]
		if !search.isEmpty { result["search"] = String(search.prefix(200)) }
		if !platform.isEmpty { result["platform"] = platform }
		if !account.isEmpty { result["accountId"] = account }
		return result
	}
}

struct Mango9SocialMessage: Decodable, Identifiable, Equatable {
	let id: Int
	let text: String
	let senderName: String?
	let direction: String
	let createdAt: String?
	let deleted: Bool
	let unavailable: Bool?
	let reactions: Mango9SocialReactions?
	let reactionCapabilities: Mango9ReactionCapabilities?
	let deliveryStatus: String?
	let externalId: String?
	var attachments: [Media]? = nil
	struct Media: Decodable, Equatable, Identifiable {
		let index: Int
		let type: String
		let name: String
		var id: Int { index }
	}
	var outgoing: Bool { direction == "outbound" || direction == "outgoing" }
}

struct Mango9SocialPage<Item: Decodable, Cursor: Decodable>: Decodable {
	let items: [Item]
	let next: Cursor?
}

struct Mango9SocialSendReceipt: Decodable, Equatable {
	let id: String
	let sendId: String
	let requestId: String
	let state: String
	let message: String
	let providerId: String?
	let error: String?
	var confirmedSent: Bool { state == "sent" || state == "confirmed_sent" }
	var terminal: Bool { confirmedSent || state == "failed" || state == "confirmed_unsent" }
	var statusLabel: String { confirmedSent ? "Sent" : terminal ? "Not sent" : "Delivery unconfirmed" }
}

/// Durable intent metadata only. Unknown sends are reconciled, never blindly resent.
@MainActor final class Mango9SocialSendJournal {
	struct Pending: Codable, Equatable { let account: String; let thread: String; let requestId: String }
	static let shared = Mango9SocialSendJournal()
	private let defaults: UserDefaults
	private let key = "mango9_social_pending_sends_v1"
	private var entries: [String: Pending]
	init(defaults: UserDefaults = .standard) {
		self.defaults = defaults
		entries = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([String: Pending].self, from: $0) } ?? [:]
	}
	private func scope(_ account: String, _ thread: String) -> String { "\(account.utf8.count):\(account)\(thread)" }
	func pending(account: String, thread: String) -> Pending? { entries[scope(account, thread)] }
	func begin(account: String, thread: String) throws -> Pending {
		guard pending(account: account, thread: thread) == nil else { throw Mango9MessagingError.pending }
		let pending = Pending(account: account, thread: thread, requestId: UUID().uuidString.lowercased())
		entries[scope(account, thread)] = pending
		defaults.set(try JSONEncoder().encode(entries), forKey: key)
		return pending
	}
	func finish(_ pending: Pending) {
		let id = scope(pending.account, pending.thread)
		guard entries[id] == pending else { return }
		entries.removeValue(forKey: id); defaults.set(try? JSONEncoder().encode(entries), forKey: key)
	}
}

/// Bounded, memory-only history/drafts survive back navigation without crossing accounts.
@MainActor final class Mango9SocialChatCache {
	static let shared = Mango9SocialChatCache()
	private var account: String?
	private var loginGeneration: String?
	private var stores: [String: Mango9SocialInboxStore] = [:]
	private var order: [String] = []
	func store(session: Mango9Session, thread: String? = nil) -> Mango9SocialInboxStore {
		let scope = Mango9ReactionTarget.accountKey(session)
		// Token renewal keeps this login; signing in again must retire its old stores.
		if account != scope || loginGeneration != session.loginGeneration {
			clear(); account = scope; loginGeneration = session.loginGeneration
		}
		let key = thread ?? "inbox"
		if let existing = stores[key] { return existing }
		let value = Mango9SocialInboxStore(session: session)
		stores[key] = value; order.append(key)
		if order.count > 24 { stores.removeValue(forKey: order.removeFirst()) }
		return value
	}
	func clear() {
		for store in stores.values { store.clearPrivateState() }
		stores.removeAll(); order.removeAll(); account = nil; loginGeneration = nil
	}
}

@MainActor final class Mango9SocialInboxStore: ObservableObject {
	@Published private(set) var threads: [Mango9SocialThread] = []
	@Published private(set) var messages: [Mango9SocialMessage] = []
	@Published private(set) var thread: Mango9SocialThread?
	@Published private(set) var error: String?
	@Published private(set) var busy = false
	@Published private(set) var nextThread: String?
	@Published private(set) var nextMessage: Int?
	@Published private(set) var accounts: [Mango9SocialAccount] = []
	@Published private(set) var selectedPlatform = ""
	@Published private(set) var selectedAccount = ""
	@Published private(set) var accountError: String?
	@Published private(set) var muted: Bool?
	@Published private(set) var updatingMute = false
	@Published var draft = ""
	@Published var selectedMedia: [Attachment] = []
	@Published var replyQuote: String?
	private var mediaIDs: [String: String] = [:]
	@Published private(set) var sending = false
	@Published private(set) var sendNotice: String?
	@Published private(set) var sends: [Mango9SocialSendReceipt] = []
	@Published private(set) var unresolvedSend = false
	let session: Mango9Session
	let api: Mango9MessagingAPI
	private let journal: Mango9SocialSendJournal
	private var inboxGeneration = 0
	private var loadedQuery: Mango9SocialInboxQuery?
	private var accountsLoadedAt: Date?
	private var loadingAccounts = false
	private var consumedCursors = Set<String>()
	var hasOlderInboxPages: Bool { !consumedCursors.isEmpty }
	private var lifetime = UUID()
	private var threadGeneration = 0
	private var providerRefreshWarning: String?
	private struct ThreadLoad {
		let id: String
		var more = false
		var refreshProvider = false
		var markRead = false
	}
	private var threadLoadTask: Task<Void, Never>?
	private var pendingThreadLoad: ThreadLoad?
	private var providerAttempt: (id: String, at: Date)?
	init(session: Mango9Session, api: Mango9MessagingAPI? = nil, journal: Mango9SocialSendJournal? = nil) {
		self.session = session; self.api = api ?? .shared; self.journal = journal ?? .shared
	}
	var outgoingText: String { Mango9MessageText.reply(draft.trimmingCharacters(in: .whitespacesAndNewlines), to: replyQuote) }
	var canSend: Bool {
		thread?.permitsSending == true && !sending && !unresolvedSend && thread?.openSend == nil &&
		(!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !selectedMedia.isEmpty) &&
		outgoingText.unicodeScalars.count <= (thread?.platform == "tiktok" ? 6000 : 10000)
	}
	func clearPrivateState() {
		threadLoadTask?.cancel(); threadLoadTask = nil; pendingThreadLoad = nil; providerAttempt = nil
		providerRefreshWarning = nil
		lifetime = UUID()
		inboxGeneration += 1; threadGeneration += 1; threads = []; messages = []; thread = nil
		draft = ""; sends = []; nextThread = nil; nextMessage = nil; loadedQuery = nil; consumedCursors = []
		accounts = []; selectedPlatform = ""; selectedAccount = ""; accountsLoadedAt = nil
		busy = false; sending = false; loadingAccounts = false; unresolvedSend = false; sendNotice = nil
		muted = nil; updatingMute = false
		selectedMedia = []; replyQuote = nil; mediaIDs = [:]
	}
	func inboxQuery(search: String) -> Mango9SocialInboxQuery {
		.init(search: search.trimmingCharacters(in: .whitespacesAndNewlines), platform: selectedPlatform, account: selectedAccount)
	}
	func hasLoadedInbox(search: String) -> Bool { loadedQuery == inboxQuery(search: search) }
	var filteredAccounts: [Mango9SocialAccount] { accounts.filter { selectedPlatform.isEmpty || $0.platform == selectedPlatform } }
	func selectPlatform(_ value: String) {
		guard value.isEmpty || Mango9SocialPlatform.all.contains(value), value != selectedPlatform else { return }
		selectedPlatform = value
		if !filteredAccounts.contains(where: { $0.id == selectedAccount }) { selectedAccount = "" }
		invalidateInbox()
	}
	func selectAccount(_ value: String) {
		guard value.isEmpty || filteredAccounts.contains(where: { $0.id == value }), value != selectedAccount else { return }
		selectedAccount = value; invalidateInbox()
	}
	private func invalidateInbox() {
		inboxGeneration += 1; threads = []; nextThread = nil; loadedQuery = nil; consumedCursors = []; busy = true; error = nil
	}
	func loadAccounts(force: Bool = false) async {
		guard !loadingAccounts, force || accountsLoadedAt.map({ Date().timeIntervalSince($0) >= 300 }) ?? true else { return }
		let life = lifetime
		loadingAccounts = true; defer { if life == lifetime { loadingAccounts = false } }
		do {
			struct Inventory: Decodable { let items: [Mango9SocialAccount] }
			let result = try await api.request(Inventory.self, session: session, social: true, path: ["accounts"])
			guard life == lifetime else { return }
			accounts = result.items.filter { Mango9SocialPlatform.all.contains($0.platform) }
				.sorted { ($0.platform, $0.title) < ($1.platform, $1.title) }
			accountsLoadedAt = Date(); accountError = nil
			if !selectedAccount.isEmpty && !filteredAccounts.contains(where: { $0.id == selectedAccount }) { selectedAccount = ""; invalidateInbox() }
		} catch {
			if Task.isCancelled || life != lifetime { return }
			accountError = "Account filters unavailable. Pull to refresh to try again."
			if Self.requiresClearing(error) { clearPrivateState(); self.error = Mango9SocialReadError.message(error) }
		}
	}
	private func query(_ fields: [String: String]) -> [URLQueryItem] {
		fields.map { URLQueryItem(name: $0.key, value: $0.value) }
	}
	func loadInbox(search: String = "", more: Bool = false) async {
		if more && (busy || nextThread == nil) { return }
		let requested = inboxQuery(search: search)
		if loadedQuery != requested { threads = []; nextThread = nil; consumedCursors = [] }
		if more && nextThread == nil { return }
		let cursor = more ? nextThread : nil
		inboxGeneration += 1
		let generation = inboxGeneration
		busy = true
		defer { if generation == inboxGeneration { busy = false } }
		do {
			var fields = requested.fields
			if let cursor { fields["before"] = cursor }
			let page = try await api.request(Mango9SocialPage<Mango9SocialThread, String>.self,
				session: session, social: true, path: ["inbox"], query: query(fields))
			guard generation == inboxGeneration else { return }
			// Refresh is authoritative: do not retain deleted or newly filtered-out rows.
			var seen = Set(more ? threads.map(\.id) : [])
			let unique = page.items.filter { seen.insert($0.id).inserted }
			threads = more ? threads + unique : unique
			if let cursor { consumedCursors.insert(cursor) } else { consumedCursors = [] }
			nextThread = page.next.flatMap { consumedCursors.contains($0) ? nil : $0 }
			loadedQuery = requested; error = nil
		} catch {
			guard generation == inboxGeneration else { return }
			if Task.isCancelled { return }
			self.error = Mango9SocialReadError.message(error)
			if Self.requiresClearing(error) { clearPrivateState() }
		}
	}
	func loadThread(id: String, more: Bool = false, refreshProvider: Bool = false, markRead: Bool = false) async {
		guard !Task.isCancelled, !more || nextMessage != nil else { return }
		let request = ThreadLoad(id: id, more: more, refreshProvider: refreshProvider, markRead: markRead)
		if let task = threadLoadTask {
			// A send receipt or push can arrive during a slow read. Retain one
			// follow-up read, including the strongest requested refresh, instead of dropping it.
			if !more {
				if var pending = pendingThreadLoad, pending.id == id {
					pending.refreshProvider = pending.refreshProvider || refreshProvider
					pending.markRead = pending.markRead || markRead
					pendingThreadLoad = pending
				} else { pendingThreadLoad = request }
			}
			await task.value
			return
		}
		let life = lifetime
		let task = Task { @MainActor in
			var next: ThreadLoad? = request
			while let current = next, life == lifetime, !Task.isCancelled {
				pendingThreadLoad = nil
				await performThreadLoad(current)
				next = pendingThreadLoad
			}
			if life == lifetime { pendingThreadLoad = nil; threadLoadTask = nil }
		}
		threadLoadTask = task
		await task.value
	}
	func pollThread(id: String, now: Date = Date()) async {
		// Match the web inbox: read saved messages frequently, reconcile the
		// provider at most once per minute, including recovery after a failed attempt.
		let due = providerAttempt.map { $0.id != id || now.timeIntervalSince($0.at) >= 60 } ?? true
		await loadThread(id: id, refreshProvider: due, markRead: true)
	}
	private func performThreadLoad(_ request: ThreadLoad) async {
		let (id, more, refreshProvider, markRead) = (request.id, request.more, request.refreshProvider, request.markRead)
		if thread?.id != id { providerRefreshWarning = nil }
		threadGeneration += 1; let generation = threadGeneration
		busy = true; defer { if generation == threadGeneration { busy = false } }
		do {
			let detail = try await api.request(Mango9SocialThread.self, session: session, social: true,
				path: ["inbox-detail"], query: query(["id": id]))
			guard detail.id == id else { throw Mango9MessagingError.invalidResponse }
			guard generation == threadGeneration else { return }
			if !detail.connectionCurrent { providerRefreshWarning = nil }
			// Show the saved inbox before the potentially slow provider request.
			try await loadSavedMessages(detail: detail, more: more, generation: generation)
			guard generation == threadGeneration else { return }
			await loadSends(id: id)
			await loadNotifications(id: id)
			guard generation == threadGeneration else { return }
			if refreshProvider && detail.connectionCurrent {
				// Existing inbox reconciliation, not a new reaction WebSocket contract.
				providerAttempt = (id, Date())
				do {
					let _: RefreshResult = try await api.request(RefreshResult.self, session: session, social: true,
						path: ["inbox-refresh-messages"], body: ["id": detail.sourceId])
					guard generation == threadGeneration else { return }
					let fresh = try await api.request(Mango9SocialThread.self, session: session, social: true,
						path: ["inbox-detail"], query: query(["id": id]))
					guard fresh.id == id else { throw Mango9MessagingError.invalidResponse }
					try await loadSavedMessages(detail: fresh, more: false, generation: generation)
					guard generation == threadGeneration else { return }
					providerRefreshWarning = nil
				} catch {
					if Task.isCancelled || generation != threadGeneration { return }
					if Self.requiresClearing(error) { throw error }
					providerRefreshWarning = Mango9SocialReadError.message(error) + " Showing saved history."
				}
			}
			guard generation == threadGeneration else { return }
			if markRead, let visible = thread, visible.unread, let sequence = visible.readThrough {
				// Mark only the sequence displayed, never a newer message that arrived during this request.
				do {
					let result = try await api.request(Mango9SocialThread.self, session: session, social: true,
						path: ["inbox-read"], body: ["id": id, "requestId": UUID().uuidString.lowercased(), "readThrough": sequence])
					guard generation == threadGeneration else { return }
					if result.id == id { thread = result }
				} catch {
					if Self.requiresClearing(error) { throw error }
				}
			}
			// Reading our saved copy does not prove that a failed provider refresh recovered.
			error = providerRefreshWarning
		} catch {
			if Task.isCancelled || generation != threadGeneration { return }
			self.error = Mango9SocialReadError.message(error)
			if Self.requiresClearing(error) || thread?.id != id { clearPrivateState() }
		}
	}
	private struct Notifications: Decodable { let id: String; let muted: Bool }
	private func loadNotifications(id: String) async {
		let life = lifetime
		do {
			let value = try await api.request(Notifications.self, session: session, social: true,
				path: ["inbox-notification-get"], query: query(["id": id]))
			guard life == lifetime, value.id == id else { return }
			muted = value.muted; Mango9SocialMutePreferences.save(value.muted, session: session, thread: id)
		} catch {
			guard life == lifetime else { return }
			// Older servers may not expose notification preferences yet. Keep the
			// authorized history, but don't offer a mute control without server support.
			if case Mango9MessagingError.rejected(let status, _) = error, status == 404 {
				muted = nil
			} else if Self.requiresClearing(error) { clearPrivateState() }
		}
	}
	func toggleMute() async throws {
		guard let thread, let muted, !updatingMute else { return }
		let life = lifetime
		updatingMute = true; defer { if life == lifetime { updatingMute = false } }
		let value = try await api.request(Notifications.self, session: session, social: true,
			path: ["inbox-notification-set"], body: ["id": thread.id, "muted": !muted])
		guard life == lifetime, value.id == thread.id else { return }
		self.muted = value.muted; Mango9SocialMutePreferences.save(value.muted, session: session, thread: thread.id)
	}
	private func loadSavedMessages(detail: Mango9SocialThread, more: Bool, generation: Int) async throws {
		var fields = ["id": detail.id, "limit": "50"]
		if more, let nextMessage { fields["before"] = String(nextMessage) }
		let page = try await api.request(Mango9SocialPage<Mango9SocialMessage, Int>.self,
			session: session, social: true, path: ["inbox-messages"], query: query(fields))
		guard generation == threadGeneration, !Task.isCancelled else { return }
		let sameThread = thread?.id == detail.id
		var merged = sameThread ? Dictionary(messages.map { ($0.id, $0) }, uniquingKeysWith: { _, newest in newest }) : [:]
		for message in page.items { merged[message.id] = message }
		messages = merged.values.sorted { ($0.createdAt ?? "", $0.id) < ($1.createdAt ?? "", $1.id) }
		thread = detail
		if more || !sameThread { nextMessage = page.next }
		unresolvedSend = detail.openSend != nil || journal.pending(account: Mango9ReactionTarget.accountKey(session), thread: detail.id) != nil
	}
	private struct SendPage: Decodable { let items: [Mango9SocialSendReceipt]; let open: Mango9SocialSendReceipt? }
	func loadSends(id: String) async {
		let life = lifetime
		do {
			let page = try await api.request(SendPage.self, session: session, social: true, path: ["inbox-sends"], query: query(["id": id]))
			guard life == lifetime else { return }
			guard page.items.allSatisfy({ $0.id == id }), page.open == nil || page.open?.id == id else { throw Mango9MessagingError.invalidResponse }
			sends = page.items
			let account = Mango9ReactionTarget.accountKey(session)
			if let pending = journal.pending(account: account, thread: id),
				let receipt = page.items.first(where: { $0.requestId == pending.requestId }), receipt.terminal {
				if receipt.confirmedSent && outgoingText == receipt.message { draft = ""; replyQuote = nil; selectedMedia = []; mediaIDs = [:] }
				sendNotice = receipt.confirmedSent ? nil : "Message not sent. \(receipt.error ?? "")"
				journal.finish(pending)
			}
			unresolvedSend = page.open != nil || journal.pending(account: account, thread: id) != nil
		} catch {
			guard life == lifetime else { return }
			if Self.requiresClearing(error) { clearPrivateState() }
			// A failed receipt lookup is not proof that a send failed.
		}
	}
	func send() async {
		guard canSend, let thread else { return }
		let text = outgoingText
		let originalDraft = draft
		sending = true; sendNotice = nil; defer { sending = false }
		var intent: Mango9SocialSendJournal.Pending?
		do {
			guard selectedMedia.count <= 1 else { throw Mango9MessagingError.rejected(400, "Social supports one attachment per message.") }
			if thread.platform == "tiktok", !selectedMedia.isEmpty, !text.isEmpty { throw Mango9MessagingError.rejected(400, "Send a TikTok photo without a caption.") }
			var mediaID: String?
			if let attachment = selectedMedia.first {
				let id = mediaIDs[attachment.id] ?? UUID().uuidString.lowercased(); mediaIDs[attachment.id] = id
				mediaID = try await api.uploadSocial(attachment, session: session, platform: thread.platform, id: id)
			}
			let pending = try journal.begin(account: Mango9ReactionTarget.accountKey(session), thread: thread.id)
			intent = pending; unresolvedSend = true
			var body: [String: Any] = ["id": thread.id, "version": thread.version, "message": text, "requestId": pending.requestId]
			if let mediaID { body["mediaId"] = mediaID }
			let receipt = try await api.request(Mango9SocialSendReceipt.self, session: session, social: true,
				path: ["inbox-send"], body: body)
			guard receipt.id == thread.id, receipt.requestId == pending.requestId else { throw Mango9MessagingError.invalidResponse }
			sends.removeAll { $0.sendId == receipt.sendId }; sends.insert(receipt, at: 0)
			if receipt.terminal { journal.finish(pending); unresolvedSend = false }
			if receipt.confirmedSent {
				if draft == originalDraft { draft = ""; replyQuote = nil; selectedMedia = []; mediaIDs = [:] }
			} else { sendNotice = receipt.terminal ? "Message not sent. \(receipt.error ?? "")" : Self.unknownSendNotice }
		} catch {
			if let intent, Mango9ReactionOutcome.transportFailure(error, previouslyUnconfirmed: false) == .failed {
				journal.finish(intent); unresolvedSend = false; sendNotice = error.localizedDescription
			} else { sendNotice = intent == nil ? error.localizedDescription : Self.unknownSendNotice }
		}
		await loadThread(id: thread.id, refreshProvider: true)
	}
	func checkSend() async {
		guard !sending, let thread else { return }; sending = true; defer { sending = false }
		await loadSends(id: thread.id)
		if let receipt = sends.first(where: { !$0.terminal }) {
			do {
				let _: Mango9SocialSendReceipt = try await api.request(Mango9SocialSendReceipt.self, session: session,
					social: true, path: ["inbox-reconcile"], body: ["id": thread.id, "sendId": receipt.sendId])
			} catch { sendNotice = error.localizedDescription }
		}
		await loadThread(id: thread.id)
		if unresolvedSend { sendNotice = Self.unknownSendNotice }
	}
	private static let unknownSendNotice = "Delivery is unconfirmed. Check delivery before sending again. Nothing will be resent automatically; unresolved delivery can be reviewed in the web inbox."
	private static func requiresClearing(_ error: Error) -> Bool {
		switch error {
		case Mango9MessagingError.scopeChanged: return true
		case Mango9MessagingError.rejected(let status, _): return status == 401 || status == 403 || status == 404
		case Mango9CRMAPIError.unauthorized: return true
		default: return false
		}
	}
	private struct RefreshResult: Decodable {}
}

struct Mango9SocialChatList: View {
	let searchText: String
	@Environment(\.scenePhase) private var phase
	@State private var store: Mango9SocialInboxStore?
	@MainActor init(searchText: String = "") {
		self.searchText = searchText
		_store = State(initialValue: Mango9SessionStore.load().map { Mango9SocialChatCache.shared.store(session: $0) })
	}
	var body: some View {
		Group {
			if let store {
				SocialInboxListContent(store: store, search: searchText)
					.id(ObjectIdentifier(store))
			}
			else { Text("Connect your CRM account to view Social Chat.").padding() }
		}.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
		.task { reset() }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in reset() }
	}
	private func reset() {
		guard let session = Mango9SessionStore.load() else { Mango9SocialChatCache.shared.clear(); store = nil; return }
		store = Mango9SocialChatCache.shared.store(session: session)
	}
}

struct SocialInboxListContent: View {
	@ObservedObject var store: Mango9SocialInboxStore
	@StateObject private var conversationVisibility: Mango9PersonalConversationVisibility
	let search: String
	init(store: Mango9SocialInboxStore, search: String) {
		self.store = store; self.search = search
		_conversationVisibility = StateObject(wrappedValue: Mango9PersonalConversationVisibility(api: store.api))
	}
	private var visibleThreads: [Mango9SocialThread] { store.threads.filter { !conversationVisibility.isHidden($0.id, channel: .social) } }
	@Environment(\.scenePhase) private var phase
	private struct RefreshKey: Equatable { let query: Mango9SocialInboxQuery; let active: Bool }
	var body: some View {
		VStack(spacing: 0) {
			Mango9SocialInboxFilters(store: store)
			Divider()
		List {
			if let warning = store.accountError { Text(warning).font(.caption).foregroundStyle(.secondary) }
			if let error = store.error { Section { Text(error); Button("Try again") { Task { await store.loadInbox(search: search) } } } }
			ForEach(visibleThreads) { thread in
				NavigationLink(destination: Mango9SocialConversation(thread: thread, session: store.session).id(thread.id)) {
					Mango9SocialInboxRow(thread: thread, session: store.session, api: store.api)
				}
				.accessibilityIdentifier("social.thread." + thread.id)
				.mango9ConversationActions(id: "social:" + thread.id, title: thread.title, session: store.session,
					loadMute: { try await store.api.socialNotificationPreference(session: store.session, thread: thread.id) },
					setMute: { try await store.api.socialNotificationPreference(session: store.session, thread: thread.id, muted: $0) }, delete: {
						try await conversationVisibility.delete(session: store.session, channel: .social, id: thread.id)
					})
				.listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 12))
			}
			if store.threads.isEmpty && store.error == nil && (store.busy || !store.hasLoadedInbox(search: search)) { ProgressView("Loading conversations…") }
			else if store.hasLoadedInbox(search: search) && visibleThreads.isEmpty && store.error == nil { Text("No social conversations").foregroundStyle(.secondary) }
			if store.nextThread != nil { Button("Load more") { Task { await store.loadInbox(search: search, more: true) } }.disabled(store.busy) }
		}.listStyle(.plain).tint(Color.orangeMain500)
		.mango9ConversationVisibilityRefresh(store: conversationVisibility, channel: .social, ids: store.threads.map(\.id))
		.refreshable { await store.loadAccounts(force: true); await store.loadInbox(search: search) }
		}.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
		.task(id: RefreshKey(query: store.inboxQuery(search: search), active: phase == .active)) {
			guard phase == .active else { return }
			try? await Task.sleep(nanoseconds: 300_000_000)
			guard !Task.isCancelled else { return }
			await store.loadInbox(search: search)
			await store.loadAccounts()
			while !Task.isCancelled {
				try? await Task.sleep(nanoseconds: 20_000_000_000)
				guard !Task.isCancelled else { return }
				// Do not collapse a user's paginated list while they are browsing older chats.
				if !store.hasOlderInboxPages { await store.loadInbox(search: search) }
			}
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9SocialInboxDidChange)) { note in
			guard let target = note.object as? Mango9SocialPushTarget, target.matches(store.session), phase == .active else { return }
			Task { await store.loadInbox(search: search) }
		}
	}
}

struct Mango9SocialInboxFilters: View {
	@ObservedObject var store: Mango9SocialInboxStore
	var body: some View {
		HStack(spacing: 8) {
			Menu {
				ForEach([""] + Mango9SocialPlatform.all, id: \.self) { platform in
					Button { store.selectPlatform(platform) } label: {
						HStack {
							Text(platform.isEmpty ? "All platforms" : Mango9SocialPlatform.title(platform))
							if store.selectedPlatform == platform { Image(systemName: "checkmark") }
						}
					}.accessibilityIdentifier("social.platform." + (platform.isEmpty ? "all" : platform))
				}
			} label: { filterLabel(store.selectedPlatform.isEmpty ? "All platforms" : Mango9SocialPlatform.title(store.selectedPlatform), icon: "line.3.horizontal.decrease") }
			.accessibilityIdentifier("social.filter.platform")
			Menu {
				Button { store.selectAccount("") } label: {
					HStack { Text("All accounts"); if store.selectedAccount.isEmpty { Image(systemName: "checkmark") } }
				}.accessibilityIdentifier("social.account.all")
				ForEach(store.filteredAccounts) { account in
					Button { store.selectAccount(account.id) } label: {
						HStack {
							Text(account.title + " · " + Mango9SocialPlatform.title(account.platform))
							if store.selectedAccount == account.id { Image(systemName: "checkmark") }
						}
					}.accessibilityIdentifier("social.account." + account.id)
				}
			} label: { filterLabel(store.accounts.first(where: { $0.id == store.selectedAccount })?.title ?? "All accounts", icon: "person.crop.circle") }
			.accessibilityIdentifier("social.filter.account")
		}.font(.system(size: 13)).tint(Color.orangeMain500).padding(.horizontal, 16).padding(.vertical, 4)
		.background(Color(UIColor.systemBackground))
	}
	private func filterLabel(_ title: String, icon: String) -> some View {
		HStack(spacing: 5) {
			Image(systemName: icon)
			Text(title).lineLimit(1)
			Spacer(minLength: 0)
			Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
		}.padding(.horizontal, 9).frame(maxWidth: .infinity, minHeight: 36)
		.background(Color(UIColor.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 8))
		.frame(minHeight: 44).contentShape(Rectangle())
	}
}

/// The provider's summary is shared by all staff. A personal preview must first
/// reconcile the same saved-message visibility used inside the conversation.
enum Mango9SocialPreview {
	static func text(thread: Mango9SocialThread, messages: [Mango9SocialMessage], hidden: Set<String>, hasOlder: Bool) -> String {
		if let message = messages.first(where: { !$0.deleted && !hidden.contains(String($0.id)) }) {
			if !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return message.text }
			if !(message.attachments ?? []).isEmpty { return "Attachment" }
			return "Content unavailable"
		}
		// Personal deletion retains saved rows. With no saved history or markers,
		// the provider summary remains useful until the first history refresh.
		if messages.isEmpty && hidden.isEmpty && !hasOlder && thread.deleted != true { return thread.preview }
		return hasOlder ? "Open conversation for earlier messages" : "No visible messages"
	}
	@MainActor static func load(thread: Mango9SocialThread, session: Mango9Session, api: Mango9MessagingAPI,
		visibility: Mango9PersonalMessageVisibility) async throws -> String {
		let page = try await api.request(Mango9SocialPage<Mango9SocialMessage, Int>.self, session: session, social: true,
			path: ["inbox-messages"], query: [URLQueryItem(name: "id", value: thread.id), URLQueryItem(name: "limit", value: "50")])
		try await visibility.update(session: session, channel: .social, thread: thread.id, ids: page.items.map { String($0.id) }, hide: false)
		guard !Task.isCancelled, Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
		let hidden = Set(page.items.filter { visibility.isHidden(String($0.id), channel: .social, thread: thread.id) }.map { String($0.id) })
		if page.items.isEmpty && !visibility.socialHiddenKeys(thread: thread.id).isEmpty { return "No visible messages" }
		return text(thread: thread, messages: page.items, hidden: hidden, hasOlder: page.next != nil)
	}
}

struct Mango9SocialInboxRow: View {
	let thread: Mango9SocialThread
	let session: Mango9Session
	let api: Mango9MessagingAPI
	@ObservedObject private var visibility = Mango9PersonalMessageVisibility.shared
	@Environment(\.scenePhase) private var phase
	@State private var preview = "Loading preview…"
	@State private var resolvedKey: PreviewKey?
	private struct PreviewKey: Equatable {
		let thread: Mango9SocialThread
		let account: String
		let hidden: Set<String>
		let active: Bool
	}
	private var previewKey: PreviewKey {
		.init(thread: thread, account: Mango9ReactionTarget.accountKey(session),
			hidden: visibility.socialHiddenKeys(thread: thread.id), active: phase == .active)
	}
	var body: some View {
		HStack(spacing: 12) {
			Mango9SocialPlatformLogo(platform: thread.platform).frame(width: 46, height: 46)
			VStack(alignment: .leading, spacing: 4) {
				HStack {
					Text(thread.title).font(.custom(thread.unread ? "NotoSans-Bold" : "NotoSans-SemiBold", size: 14)).lineLimit(1)
					Spacer(minLength: 4)
					if thread.unread { Circle().fill(Color.orangeMain500).frame(width: 7, height: 7).accessibilityLabel("Unread") }
				}
				Text(resolvedKey == previewKey ? preview : "Loading preview…")
					.font(.custom("NotoSans-Regular", size: 13)).foregroundStyle(.secondary).lineLimit(2)
					.frame(height: 38, alignment: .topLeading)
				HStack(alignment: .lastTextBaseline, spacing: 8) {
					Text(thread.connectionCurrent ? thread.accountName : "Disconnected · " + thread.accountName)
						.lineLimit(1)
					Spacer(minLength: 0)
					Text(Mango9ChatDateText.display(thread.activityAt))
						.fixedSize().accessibilityIdentifier("social.date." + thread.id)
				}.font(.custom("NotoSans-Regular", size: 11)).foregroundStyle(.secondary)
			}.frame(maxWidth: .infinity, alignment: .leading)
		}.frame(minHeight: 56).contentShape(Rectangle()).accessibilityElement(children: .combine)
		.task(id: previewKey) {
			let requested = previewKey
			guard requested.active else { return }
			while !Task.isCancelled {
				do {
					let text = try await Mango9SocialPreview.load(thread: thread, session: session, api: api, visibility: visibility)
					guard !Task.isCancelled, requested == previewKey else { return }
					preview = text; resolvedKey = requested
				} catch {
					guard !Task.isCancelled, requested == previewKey else { return }
					// An unverified provider summary could expose a deleted message.
					preview = "Preview unavailable"; resolvedKey = requested
				}
				do { try await Task.sleep(nanoseconds: 20_000_000_000) } catch { return }
			}
		}
	}
}

struct Mango9SocialConversation: View {
	let original: Mango9SocialThread
	private var visibleMessages: [Mango9SocialMessage] {
		store.messages.filter { !moderation.isHidden(socialHiddenID($0.id)) &&
			!visibility.isHidden(String($0.id), channel: .social, thread: original.id) }
	}
	@StateObject private var store: Mango9SocialInboxStore
	@StateObject private var reactionActions: Mango9ReactionActions
	@StateObject private var visibility: Mango9PersonalMessageVisibility
	@State private var deletingMessages = false
	@Environment(\.scenePhase) private var phase
	@Environment(\.dismiss) private var dismiss
	@FocusState private var composerFocused: Bool
	@State private var scrolledInitially = false
	@State private var atBottom = true
	@State private var attachmentRequest: Mango9AttachmentRequest?
	@State private var selectionMode = false
	@State private var selectedIDs: Set<String> = []
	@State private var confirmingDelete = false
	@State private var forwardingText: String?
	@State private var forwardingAttachments: [Attachment] = []
	@State private var preparingMedia = false
	@State private var previewURL: URL?
	@State private var voicePreviewOwner = UUID()
	@State private var localError: String?
	@ObservedObject private var moderation = Mango9ChatModerationStore.shared
	init(thread: Mango9SocialThread, session: Mango9Session, api: Mango9MessagingAPI? = nil) {
		original = thread
		_store = StateObject(wrappedValue: api == nil ? Mango9SocialChatCache.shared.store(session: session, thread: thread.id) : Mango9SocialInboxStore(session: session, api: api))
		_reactionActions = StateObject(wrappedValue: Mango9ReactionActions(api: api))
		_visibility = StateObject(wrappedValue: api == nil ? .shared : Mango9PersonalMessageVisibility(api: api))
	}
	var body: some View {
		VStack(spacing: 0) {
		ScrollViewReader { proxy in
		ScrollView {
			LazyVStack(alignment: .leading, spacing: 14) {
				HStack(spacing: 8) {
					Mango9SocialPlatformLogo(platform: original.platform, size: 18)
					Text(Mango9SocialPlatform.title(original.platform)).font(.subheadline).foregroundStyle(.secondary)
				}
				if let error = store.error { Text(error).font(.subheadline); Button("Retry") { Task { await refresh(provider: true) } }.disabled(store.busy) }
				if store.nextMessage != nil { Button("Earlier messages") { Task { await store.loadThread(id: original.id, more: true) } }.disabled(store.busy) }
				if !store.messages.isEmpty && visibleMessages.isEmpty {
					Text("These messages were deleted for you. Other participants keep their copies.")
						.font(.subheadline).foregroundStyle(.secondary)
				}
				ForEach(visibleMessages) { message in
					HStack {
						if message.outgoing { Spacer(minLength: 52) }
						VStack(alignment: .trailing, spacing: 4) {
						VStack(alignment: .leading, spacing: 4) {
							if let name = message.senderName, !name.isEmpty { Text(name).font(.caption).foregroundStyle(.secondary) }
							if message.deleted || !message.text.isEmpty || (message.attachments ?? []).isEmpty {
							Text(message.deleted ? "Message deleted" : message.text.isEmpty ? "Content unavailable" : message.text)
								.font(.custom("NotoSans-Regular", size: 14))
								.fixedSize(horizontal: false, vertical: true).layoutPriority(1)
								.padding(.horizontal, 13).padding(.vertical, 9).foregroundStyle(message.outgoing ? Color.white : Color.primary)
								.background(message.outgoing ? Color.orangeMain500 : Color(uiColor: .secondarySystemGroupedBackground))
								.clipShape(RoundedRectangle(cornerRadius: 20))
								.mango9MessageActions(enabled: !selectionMode) { socialActions(message) }
							}
							if !message.deleted { ForEach(message.attachments ?? []) { media in
								Mango9SocialAttachmentPreview(session: store.session, thread: original.id, message: message, media: media, api: store.api,
									open: { Task { await openMedia(message, media: media) } }, actions: { socialActions(message) }, selectionMode: selectionMode)
									.disabled(preparingMedia)
							} }
							if !message.deleted {
								if let entry = message.reactions?.activeAccount { Mango9ReactionBadge(text: "\(entry.emoji) Your account") }
								if let entry = message.reactions?.activeParticipant { Mango9ReactionBadge(text: "\(entry.emoji) Participant") }
							}
						}
							HStack(spacing: 4) {
								Text(Mango9ChatDateText.display(message.createdAt, inConversation: true))
									.accessibilityIdentifier("social.message.date.\(message.id)")
								if message.outgoing, let status = message.deliveryStatus { Text(status.capitalized) }
							}.font(.custom("NotoSans-Light", size: 12)).foregroundStyle(.secondary)
						}
						.layoutPriority(1)
						if !message.outgoing { Spacer(minLength: 52) }
					}.id(message.id)
					.mango9MessageSelection(active: selectionMode, selected: selectedIDs.contains(String(message.id))) { toggleSelection(String(message.id)) }
				}
				ForEach(store.sends.prefix(1).filter { receipt in
					!receipt.confirmedSent || !store.messages.contains(where: { $0.externalId != nil && $0.externalId == receipt.providerId })
				}, id: \.sendId) { receipt in
					HStack { Spacer(minLength: 52)
						VStack(alignment: .trailing, spacing: 5) {
							Text(receipt.message).font(.custom("NotoSans-Regular", size: 14))
								.fixedSize(horizontal: false, vertical: true)
								.padding(.horizontal, 13).padding(.vertical, 9).foregroundStyle(.white).background(Color.orangeMain500).clipShape(RoundedRectangle(cornerRadius: 20))
							Text(receipt.statusLabel).font(.caption).foregroundStyle(.secondary)
						}
					}
				}
				if store.busy && store.messages.isEmpty { ProgressView("Loading messages…") }
				Color.clear.frame(height: 1).id("latest")
					.onAppear { atBottom = true }.onDisappear { atBottom = false }
			}.padding()
		}.onAppear { if !store.messages.isEmpty { proxy.scrollTo("latest", anchor: .bottom); scrolledInitially = true } }
		.onChange(of: store.messages.last?.id) { _ in
			if !scrolledInitially || atBottom { proxy.scrollTo("latest", anchor: .bottom); scrolledInitially = true }
		}
		.onChange(of: store.sends.first?.sendId) { _ in if atBottom { proxy.scrollTo("latest", anchor: .bottom) } }
		}
		.mango9VisibilityRefresh(store: visibility, channel: .social, thread: original.id, ids: store.messages.map { String($0.id) })
		}
		.safeAreaInset(edge: .bottom, spacing: 0) {
			VStack(spacing: 0) { Divider(); composer }
				.background(Color(uiColor: .systemBackground).ignoresSafeArea(.container, edges: .bottom))
		}
		.background(Color(uiColor: .systemGroupedBackground)).tint(Color.orangeMain500)
		.navigationTitle(original.title).navigationBarTitleDisplayMode(.inline)
		.toolbar {
			ToolbarItem(placement: .navigationBarTrailing) {
				if let muted = store.muted {
					Button {
						Task { do { try await store.toggleMute() } catch { localError = error.localizedDescription } }
					} label: { Image(systemName: muted ? "bell.slash" : "bell") }
					.accessibilityLabel(muted ? "Unmute conversation" : "Mute conversation")
					.disabled(store.updatingMute)
				}
			}
		}
		.mango9ConversationNavigationHidden()
		.navigationBarHidden(false)
		.mango9Attachments(request: $attachmentRequest, selected: $store.selectedMedia, limit: 1, error: { localError = $0 })
		.quickLookPreview($previewURL)
		.modifier(Mango9VoicePreviewLifecycle(owner: voicePreviewOwner, url: $previewURL))
		#if DEBUG && targetEnvironment(simulator)
		.overlay(alignment: .topLeading) {
			if ProcessInfo.processInfo.environment["UITEST_SOCIAL_ATTACHMENT"] == "1" {
				Text(previewURL == nil ? (preparingMedia ? "Loading" : "Idle") : "Ready")
					.font(.system(size: 1)).allowsHitTesting(false).accessibilityIdentifier("fixture.previewState")
			}
		}
		#endif
		.alert("Message", isPresented: Binding(get: { localError != nil }, set: { if !$0 { localError = nil } })) { Button("OK", role: .cancel) {} } message: { Text(localError ?? "") }
		.confirmationDialog("Delete for me on all my devices? Other staff and the recipient will keep their copies. This does not unsend the message.", isPresented: $confirmingDelete, titleVisibility: .visible) {
			Button("Delete for me", role: .destructive) {
				guard !deletingMessages else { return }; deletingMessages = true
				let ids = Array(selectedIDs)
				Task { @MainActor in
					defer { deletingMessages = false }
					do {
						try await visibility.update(session: store.session, channel: .social, thread: original.id, ids: ids, hide: true)
						selectionMode = false; selectedIDs = []
					} catch {
						selectedIDs = selectedIDs.filter { !visibility.isHidden($0, channel: .social, thread: original.id) }
						localError = "Deletion could not be confirmed. Refresh or retry; nothing was unsent. \(error.localizedDescription)"
					}
				}
			}
		}
		.sheet(isPresented: Binding(get: { forwardingText != nil }, set: { if !$0 { forwardingText = nil; forwardingAttachments = [] } })) {
			Mango9SocialForwardSheet(session: store.session, text: forwardingText ?? "", attachments: forwardingAttachments)
		}
		.refreshable { await refresh(provider: true) }
		.alert("Reaction", isPresented: Binding(get: { reactionActions.notice != nil }, set: { if !$0 { reactionActions.notice = nil } })) {
			Button("OK", role: .cancel) {}
		} message: { Text(reactionActions.notice ?? "") }
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in previewURL = nil; forwardingText = nil; forwardingAttachments = []; dismiss() }
		.onReceive(NotificationCenter.default.publisher(for: .mango9SocialInboxDidChange)) { note in
			guard let target = note.object as? Mango9SocialPushTarget, target.matches(store.session), target.id == original.id, phase == .active else { return }
			Task { await refresh() }
		}
		.task(id: phase) {
			guard phase == .active else { return }
			await refresh(provider: true)
			while !Task.isCancelled {
				try? await Task.sleep(nanoseconds: 15_000_000_000)
				guard !Task.isCancelled else { return }
				await store.pollThread(id: original.id)
			}
		}
	}
	@ViewBuilder private var composer: some View {
		if preparingMedia { ProgressView("Preparing attachment…").padding(8) }
		if selectionMode {
			Mango9MessageSelectionBar(count: selectedIDs.count, delete: { confirmingDelete = true }, forward: {
				Task { await prepareForward(store.messages.filter { selectedIDs.contains(String($0.id)) }) }
			}, cancel: { selectionMode = false; selectedIDs = [] }).disabled(preparingMedia)
		} else {
		if let quote = store.replyQuote { Mango9ReplyPreview(text: quote) { store.replyQuote = nil } }
		if !store.selectedMedia.isEmpty { Mango9PendingAttachmentStrip(attachments: $store.selectedMedia) }
		if let notice = store.sendNotice { Text(notice).font(.footnote).foregroundStyle(.secondary).padding(.horizontal) }
		if store.draft.unicodeScalars.count > 10000 { Text("Messages can contain up to 10,000 characters.").font(.footnote).foregroundStyle(.red).padding(.horizontal) }
		if store.unresolvedSend || store.thread?.openSend != nil {
			Button("Check delivery") { Task { await store.checkSend() } }.disabled(store.sending).padding(8)
		} else if let reason = store.thread?.replyUnavailableReason {
			Text(reason)
				.font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
		}
		Mango9MessageComposer(templateSession: store.session, text: $store.draft, focus: $composerFocused, placeholder: "Message",
			enabled: store.thread?.permitsSending == true && !store.unresolvedSend && store.thread?.openSend == nil,
			sending: store.sending, sendEnabled: store.canSend, hasAttachments: !store.selectedMedia.isEmpty,
			attach: { attachmentRequest = $0 }, send: { Task { await store.send() } })
		}
	}
	private func openMedia(_ message: Mango9SocialMessage, media: Mango9SocialMessage.Media) async {
		guard !preparingMedia, !message.deleted else { return }
		preparingMedia = true; defer { preparingMedia = false }
		do {
			let attachment = try await store.api.socialAttachment(session: store.session, thread: original.id, message: message.id, index: media.index)
			guard Mango9SessionStore.isActive(store.session) else { return }
			if media.type == "audio" || attachment.type == .audio || attachment.type == .voiceRecording {
				try Mango9VoicePlaybackSession.shared.acquire(voicePreviewOwner) { previewURL = nil }
			}
			previewURL = attachment.full
		} catch { if Mango9SessionStore.isActive(store.session) { localError = error.localizedDescription } }
	}
	private func prepareForward(_ messages: [Mango9SocialMessage]) async {
		guard !preparingMedia, !messages.isEmpty, Mango9SessionStore.isActive(store.session) else { return }
		let media = messages.flatMap { message in (message.attachments ?? []).map { (message.id, $0) } }
		guard media.count <= 1, !messages.contains(where: \.deleted) else {
			localError = "Forward one attachment at a time. Deleted messages cannot be forwarded."; return
		}
		preparingMedia = true; defer { preparingMedia = false }
		do {
			var attachments: [Attachment] = []
			if let (id, item) = media.first { attachments = [try await store.api.socialAttachment(session: store.session, thread: original.id, message: id, index: item.index)] }
			guard Mango9SessionStore.isActive(store.session), !Task.isCancelled else { return }
			let texts = messages.map(\.text).filter { !$0.isEmpty }
			guard !texts.isEmpty || !attachments.isEmpty else { localError = "This message has no content available to forward."; return }
			forwardingAttachments = attachments
			forwardingText = texts.isEmpty ? "" : Mango9MessageText.forward(texts)
		} catch { if Mango9SessionStore.isActive(store.session) { localError = error.localizedDescription } }
	}
	private func refresh(provider: Bool = false) async { await store.loadThread(id: original.id, refreshProvider: provider, markRead: true) }
	private func socialHiddenID(_ id: Int) -> String { "social:\(Mango9ReactionTarget.accountKey(store.session)):\(original.id):\(id)" }
	private func toggleSelection(_ id: String) { if !selectedIDs.insert(id).inserted { selectedIDs.remove(id) } }
	private func socialActions(_ message: Mango9SocialMessage) -> Mango9MessageActionSet {
		var menu = Mango9MessageActionSet()
		let target = Mango9ReactionTarget(account: Mango9ReactionTarget.accountKey(store.session), channel: .social, conversation: original.id, message: String(message.id))
		if let pending = Mango9ReactionJournal.shared.pending(target) {
			menu.actions.append(.init(title: "Check saved reaction", icon: "arrow.clockwise", perform: { Task { await reactionActions.social(pending, session: store.session); await refresh() } }))
		} else if let thread = store.thread, !reactionActions.busy {
			menu = .social(thread: thread, message: message)
			menu.react = { emoji in
				let remove = message.reactions?.activeAccount?.emoji == emoji
				guard thread.permitsReaction(on: message, removing: remove) else { return }
				Task { await reactionActions.social(.init(target: target, version: thread.version, emoji: emoji, remove: remove), session: store.session); await refresh() }
			}
		}
		if !message.deleted, !message.text.isEmpty {
			if store.thread?.permitsSending == true { menu.actions.append(.init(title: "Reply", icon: "arrowshape.turn.up.left", perform: { store.replyQuote = message.text; composerFocused = true })) }
			menu.actions.append(.init(title: "Copy", icon: "doc.on.doc", perform: { UIPasteboard.general.string = message.text }))
		}
		if !message.deleted, !preparingMedia { menu.actions.append(.init(title: "Forward", icon: "arrowshape.turn.up.right", perform: { Task { await prepareForward([message]) } })) }
		menu.actions.append(.init(title: "Select", icon: "checkmark.circle", perform: { selectedIDs = [String(message.id)]; selectionMode = true; composerFocused = false }))
		return menu
	}
}

struct Mango9SocialReactionReceipt: Decodable {
	let id: String
	let messageId: Int
	let action: String
	let state: Mango9ReactionOutcome
	let error: String?
}
