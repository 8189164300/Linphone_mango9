import SwiftUI
import UIKit
import PhotosUI
import ContactsUI

/// Conversation deletes are snapshots, not an unsend or a leave-group command.
/// Keep the request ID durable until acknowledged: a retry must never clear a
/// second snapshot containing messages that arrived after the original command.
@MainActor final class Mango9PersonalConversationVisibility: ObservableObject {
	static let shared = Mango9PersonalConversationVisibility()
	typealias Channel = Mango9PersonalMessageVisibility.Channel
	struct Receipt: Decodable {
		let scope: String, channel: String, state: String
		let conversationIds: [String], clearedConversationIds: [String], hiddenConversationIds: [String]
		let requestId: String?
	}
	private struct Cache: Codable {
		var hidden: Set<String> = [], cleared: Set<String> = []
		var pending: [String: String] = [:]
	}
	@Published private var cache = Cache()
	private let api: Mango9MessagingAPI
	private let defaults: UserDefaults
	private var account: String?
	private var observer: NSObjectProtocol?
	private var revision = 0
	private var running: Set<String> = []
	init(api: Mango9MessagingAPI? = nil, defaults: UserDefaults = .standard) {
		self.api = api ?? .shared; self.defaults = defaults; reload()
		observer = NotificationCenter.default.addObserver(forName: .mango9AccountContextChanged, object: nil, queue: .main) { [weak self] _ in
			Task { @MainActor in self?.reload() }
		}
	}
	deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
	private var cacheKey: String { "mango9.personal-conversation-visibility.v1." + (account ?? "none") }
	private func reload() {
		account = Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey); revision += 1
		cache = account.flatMap { _ in defaults.data(forKey: cacheKey) }.flatMap { try? JSONDecoder().decode(Cache.self, from: $0) } ?? Cache()
	}
	private func save() { if let data = try? JSONEncoder().encode(cache) { defaults.set(data, forKey: cacheKey) } }
	static func canonical(_ id: String, channel: Channel) -> String { channel == .sms ? Mango9SMSMutePreferences.normalizedPhone(id) : id }
	private func key(_ id: String, _ channel: Channel) -> String { channel.rawValue + ":" + Self.canonical(id, channel: channel) }
	func isHidden(_ id: String, channel: Channel) -> Bool {
		account == Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) && cache.hidden.contains(key(id, channel))
	}
	func wasCleared(_ id: String, channel: Channel) -> Bool {
		account == Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) && cache.cleared.contains(key(id, channel))
	}
	private func request(session: Mango9Session, channel: Channel, ids: [String], requestID: String? = nil) async throws -> Receipt {
		guard Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
		if account != Mango9ReactionTarget.accountKey(session) { reload() }
		var body: [String: Any] = ["conversationIds": ids, "hide": requestID != nil]
		if let requestID { body["requestId"] = requestID }
		let receipt = try await api.request(Receipt.self, session: session, social: channel == .social,
			path: channel == .social ? ["inbox-conversation-visibility"] : ["conversation-visibility", channel.rawValue], body: body)
		guard Mango9SessionStore.isActive(session), account == Mango9ReactionTarget.accountKey(session) else { throw Mango9MessagingError.scopeChanged }
		let requested = Set(ids), cleared = Set(receipt.clearedConversationIds), hidden = Set(receipt.hiddenConversationIds)
		guard receipt.scope == "user", receipt.channel == channel.rawValue, receipt.state == "succeeded",
			Set(receipt.conversationIds) == requested, cleared.isSubset(of: requested), hidden.isSubset(of: cleared),
			receipt.requestId == requestID, requestID == nil || cleared == requested else { throw Mango9MessagingError.invalidResponse }
		return receipt
	}
	private func apply(_ receipt: Receipt, channel: Channel) {
		let queried = Set(receipt.conversationIds.map { key($0, channel) })
		cache.hidden.subtract(queried); cache.hidden.formUnion(receipt.hiddenConversationIds.map { key($0, channel) })
		cache.cleared.formUnion(receipt.clearedConversationIds.map { key($0, channel) })
		save()
	}
	func refresh(session: Mango9Session, channel: Channel, ids: [String]) async throws {
		let unique = Array(Set(ids.map { Self.canonical($0, channel: channel) })).sorted()
		for start in stride(from: 0, to: unique.count, by: 200) {
			let chunk = Array(unique[start..<min(start + 200, unique.count)])
			let version = revision
			let receipt = try await request(session: session, channel: channel, ids: chunk)
			if version == revision { apply(receipt, channel: channel) } // Ignore reads started before a delete/account change.
		}
	}
	func delete(session: Mango9Session, channel: Channel, id: String) async throws {
		guard Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
		if account != Mango9ReactionTarget.accountKey(session) { reload() }
		let id = Self.canonical(id, channel: channel), target = key(id, channel)
		let operation = Mango9ReactionTarget.accountKey(session) + ":" + target
		guard !running.contains(operation) else { return }
		running.insert(operation); defer { running.remove(operation) }
		let requestID = cache.pending[target] ?? UUID().uuidString.lowercased()
		cache.pending[target] = requestID; save(); revision += 1
		do {
			let receipt = try await request(session: session, channel: channel, ids: [id], requestID: requestID)
			revision += 1; apply(receipt, channel: channel); cache.pending.removeValue(forKey: target); save()
			// A replay returns its original receipt. Reconcile any newer arrival now.
			try? await refresh(session: session, channel: channel, ids: [id])
		} catch {
			throw ConversationDeleteError.unconfirmed(error.localizedDescription)
		}
	}
	private enum ConversationDeleteError: LocalizedError {
		case unconfirmed(String)
		var errorDescription: String? { switch self { case .unconfirmed(let detail): return "Deletion could not be confirmed. Retry to check the same saved request; no new deletion will be created. \(detail)" } }
	}
}

private struct Mango9ConversationVisibilityRefresh: ViewModifier {
	@Environment(\.scenePhase) private var phase
	let store: Mango9PersonalConversationVisibility
	let channel: Mango9PersonalMessageVisibility.Channel
	let ids: [String]
	private struct Key: Hashable { let active: Bool; let account: String?; let ids: [String] }
	func body(content: Content) -> some View {
		content.task(id: Key(active: phase == .active, account: Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey), ids: ids)) {
			guard phase == .active, !ids.isEmpty, let session = Mango9SessionStore.load() else { return }
			while !Task.isCancelled {
				try? await store.refresh(session: session, channel: channel, ids: ids)
				do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
			}
		}
	}
}
extension View {
	func mango9ConversationVisibilityRefresh(store: Mango9PersonalConversationVisibility = .shared, channel: Mango9PersonalMessageVisibility.Channel, ids: [String]) -> some View {
		modifier(Mango9ConversationVisibilityRefresh(store: store, channel: channel, ids: ids))
	}
}

/// Server-confirmed, per-login visibility. Deliberately separate from local
/// moderation and from provider deletion/unsending. Confirmed reads reconcile
/// restorations too; a read started before a delete cannot undo that delete.
@MainActor final class Mango9PersonalMessageVisibility: ObservableObject {
	static let shared = Mango9PersonalMessageVisibility()
	enum Channel: String { case team, sms, social }
	struct Receipt: Decodable {
		let scope: String
		let channel: String
		let hiddenIds: [String]
		let state: String
	}
	@Published private(set) var hidden: Set<String> = []
	private let api: Mango9MessagingAPI
	private let defaults: UserDefaults
	private var account: String?
	private var observer: NSObjectProtocol?
	private var revision = 0
	init(api: Mango9MessagingAPI? = nil, defaults: UserDefaults = .standard) {
		self.api = api ?? .shared; self.defaults = defaults
		reload()
		observer = NotificationCenter.default.addObserver(forName: .mango9AccountContextChanged, object: nil, queue: .main) { [weak self] _ in
			Task { @MainActor in self?.reload() }
		}
	}
	deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
	private var cacheKey: String { "mango9.personal-message-visibility.v1." + (account ?? "none") }
	private func reload() {
		revision += 1
		account = Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey)
		hidden = account == nil ? [] : Set(defaults.stringArray(forKey: cacheKey) ?? [])
	}
	private func key(_ channel: Channel, _ thread: String?, _ id: String) -> String {
		[channel.rawValue, channel == .social ? thread ?? "" : "", id].map { "\($0.utf8.count):\($0)" }.joined()
	}
	func isHidden(_ id: String, channel: Channel, thread: String? = nil) -> Bool {
		guard account == Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) else { return false }
		return hidden.contains(key(channel, thread, id))
	}
	func socialHiddenKeys(thread: String) -> Set<String> {
		guard account == Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) else { return [] }
		let prefix = ["social", thread].map { "\($0.utf8.count):\($0)" }.joined()
		return Set(hidden.filter { $0.hasPrefix(prefix) })
	}
	func update(session: Mango9Session, channel: Channel, thread: String? = nil, ids: [String], hide: Bool) async throws {
		guard Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
		let expectedAccount = Mango9ReactionTarget.accountKey(session)
		if account != expectedAccount { reload() }
		let unique = Array(Set(ids)).sorted()
		for start in stride(from: 0, to: unique.count, by: 200) {
			if hide { revision += 1 }
			let requestedRevision = revision
			let chunk = Array(unique[start..<min(start + 200, unique.count)])
			var body: [String: Any] = ["messageIds": chunk, "hide": hide]
			let path: [String]
			if channel == .social {
				guard let thread, !thread.isEmpty else { throw Mango9MessagingError.invalidResponse }
				let numbers = chunk.compactMap(Int.init)
				guard numbers.count == chunk.count, numbers.allSatisfy({ $0 > 0 }) else { throw Mango9MessagingError.invalidResponse }
				body["id"] = thread; body["messageIds"] = numbers; path = ["inbox-message-visibility"]
			} else { path = ["visibility", channel.rawValue] }
			let receipt = try await api.request(Receipt.self, session: session, social: channel == .social, path: path, body: body)
			guard Mango9SessionStore.isActive(session), account == expectedAccount else { throw Mango9MessagingError.scopeChanged }
			let returned = Set(receipt.hiddenIds)
			guard receipt.scope == "user", receipt.channel == channel.rawValue, receipt.state == "succeeded",
				returned.isSubset(of: Set(chunk)), !hide || returned == Set(chunk) else { throw Mango9MessagingError.invalidResponse }
			if hide { revision += 1 }
			else if revision != requestedRevision { throw Mango9MessagingError.pending }
			// Only the requested IDs are authoritative. Leave other pages, threads,
			// channels and accounts alone, including after an offline read fails.
			let queried = Set(chunk.map { key(channel, thread, $0) })
			let updated = hidden.subtracting(queried).union(returned.map { key(channel, thread, $0) })
			if updated != hidden { revision += 1; hidden = updated; defaults.set(hidden.sorted(), forKey: cacheKey) }
		}
	}
}

private struct Mango9VisibilityRefresh: ViewModifier {
	@Environment(\.scenePhase) private var phase
	let store: Mango9PersonalMessageVisibility
	let channel: Mango9PersonalMessageVisibility.Channel
	let thread: String?
	let ids: [String]
	@State private var checked: Set<String> = []
	@State private var checkedScope: String?
	@State private var failed = false
	@State private var retry = 0
	private var scope: String { (Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) ?? "") + ":" + channel.rawValue + ":" + (thread ?? "") }
	private var ready: Bool { ids.isEmpty || (checkedScope == scope && Set(ids).isSubset(of: checked)) }
	private struct RefreshKey: Hashable { let active: Bool; let scope: String; let ids: [String]; let retry: Int }
	func body(content: Content) -> some View {
		// Keep the history mounted (and its scroll position), but never flash a
		// freshly loaded page before its personal deletion markers are reconciled.
		content.opacity(ready ? 1 : 0).allowsHitTesting(ready).accessibilityHidden(!ready)
		.overlay {
			if !ready {
				VStack(spacing: 12) {
					if failed { Text("Couldn’t refresh your saved history.").font(.subheadline); Button("Try again") { retry += 1 } }
					else { ProgressView("Loading messages…") }
				}.frame(maxWidth: .infinity, maxHeight: .infinity)
			}
		}
		.task(id: RefreshKey(active: phase == .active, scope: scope, ids: ids, retry: retry)) {
			guard phase == .active, !ids.isEmpty, let session = Mango9SessionStore.load() else { return }
			let expectedScope = scope
			if checkedScope != expectedScope { checked = [] }
			failed = false
			while !Task.isCancelled {
				// Failed/offline reads retain only previously confirmed markers. Deletion
				// commands surface failures separately and never silently fall back locally.
				do {
					try await store.update(session: session, channel: channel, thread: thread, ids: ids, hide: false)
					guard !Task.isCancelled, scope == expectedScope else { return }
					checked.formUnion(ids); checkedScope = expectedScope; failed = false
				} catch { if !Task.isCancelled { failed = true } }
				do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
			}
		}
	}
}
extension View {
	func mango9VisibilityRefresh(store: Mango9PersonalMessageVisibility = .shared, channel: Mango9PersonalMessageVisibility.Channel, thread: String? = nil, ids: [String]) -> some View {
		modifier(Mango9VisibilityRefresh(store: store, channel: channel, thread: thread, ids: ids))
	}
}

struct Mango9MessageAction: Identifiable {
	let title: String
	let icon: String
	var destructive = false
	let perform: () -> Void
	var id: String { title }
}

struct Mango9MessageActionSet {
	var reactions: [String] = []
	var selectedReactions: Set<String> = []
	var allowsMoreReactions = true
	var reactionNotice: String?
	var react: ((String) -> Void)?
	var actions: [Mango9MessageAction] = []
	static let emoji = ["❤️", "👍", "👎", "😂", "‼️", "❓", "👍🏽", "😮", "😢", "🎉", "👨‍👩‍👧‍👦", "🇺🇸"]
	static func social(thread: Mango9SocialThread, message: Mango9SocialMessage) -> Self {
		var menu = Self()
		let active = message.reactions?.activeAccount?.emoji
		let canAdd = thread.permitsReaction(on: message)
		let canRemove = thread.permitsReaction(on: message, removing: true)
		menu.allowsMoreReactions = canAdd
		menu.reactions = canAdd ? emoji.filter { $0 != active || canRemove } : []
		if canRemove, let active, !menu.reactions.contains(active) { menu.reactions.append(active) }
		menu.selectedReactions = Set([active].compactMap { $0 })
		if !menu.reactions.isEmpty { menu.reactionNotice = "Recipient-visible · Shared account reaction" }
		return menu
	}
	@MainActor static func staff(target: Mango9ReactionTarget, session: Mango9Session, state: Mango9StaffReactions,
		actor: Int?, handler: Mango9ReactionActions) -> Self {
		var menu = Self()
		if target.channel == .sms { menu.reactionNotice = "Mango9 only · No SMS or tapback sent" }
		if let pending = Mango9ReactionJournal.shared.pending(target) {
			menu.actions.append(.init(title: "Check saved reaction", icon: "arrow.clockwise", perform: {
				Task { await handler.staff(pending, session: session) }
			}))
		} else if state.capabilities?.permits(target.channel) == true, let actor, !handler.busy {
			menu.reactions = emoji + state.reactions.keys.filter { !emoji.contains($0) }.sorted()
			menu.selectedReactions = Set(state.reactions.keys.filter { state.contains($0, actor: actor) })
			menu.react = { emoji in
				let command = Mango9ReactionCommand(target: target, version: state.version, emoji: emoji, remove: state.contains(emoji, actor: actor))
				Task { await handler.staff(command, session: session) }
			}
		}
		return menu
	}
}

/// Public UIKit popover presentation keeps the emoji strip horizontal on every
/// supported iPhone. UIMenu compact groups overflow after three/four elements.
private struct Mango9MessageActionsModifier: ViewModifier {
	let enabled: Bool
	let tap: (() -> Void)?
	let actions: () -> Mango9MessageActionSet
	@State private var showing = false
	@State private var pending: (() -> Void)?
	func body(content: Content) -> some View {
		interactive(content)
			.accessibilityAction(named: Text("Message actions")) { if enabled { showing = true } }
			.background(Mango9MessageActionsPopover(showing: $showing, pending: $pending, actions: actions()))
			.onDisappear { showing = false; pending = nil }
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in showing = false; pending = nil }
	}
	@ViewBuilder private func interactive(_ content: Content) -> some View {
		if let tap {
			// One UIKit touch target prevents SwiftUI's nested button/hold
			// recognizers from swallowing short taps or opening both presentations.
			content.overlay(Mango9MessageTouchTarget(tapped: tap, held: showActions))
		} else {
			content.onLongPressGesture(minimumDuration: 0.45, perform: showActions)
		}
	}
	private func showActions() {
		guard enabled else { return }
		showing = true; UIImpactFeedbackGenerator(style: .light).impactOccurred()
	}
}

private struct Mango9MessageTouchTarget: UIViewRepresentable {
	let tapped: () -> Void
	let held: () -> Void
	func makeCoordinator() -> Coordinator { Coordinator(self) }
	func makeUIView(context: Context) -> UIView {
		let view = UIView(); view.backgroundColor = .clear
		// The SwiftUI content supplies the accessible button and default action.
		view.isAccessibilityElement = false; view.accessibilityElementsHidden = true
		let hold = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.hold(_:)))
		hold.minimumPressDuration = 0.45
		let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap(_:)))
		tap.require(toFail: hold)
		view.addGestureRecognizer(hold); view.addGestureRecognizer(tap)
		return view
	}
	func updateUIView(_ view: UIView, context: Context) { context.coordinator.parent = self }
	final class Coordinator: NSObject {
		var parent: Mango9MessageTouchTarget
		init(_ parent: Mango9MessageTouchTarget) { self.parent = parent }
		@objc func tap(_ gesture: UITapGestureRecognizer) { if gesture.state == .ended { parent.tapped() } }
		@objc func hold(_ gesture: UILongPressGestureRecognizer) { if gesture.state == .began { parent.held() } }
	}
}

extension View {
	func mango9MessageActions(enabled: Bool = true, onTap: (() -> Void)? = nil, actions: @escaping () -> Mango9MessageActionSet) -> some View {
		modifier(Mango9MessageActionsModifier(enabled: enabled, tap: onTap, actions: actions))
	}
}

/// The native menu and its confirmation are attached to this row, never to the
/// list/screen. Mutations retain the account and conversation captured by the row.
private struct Mango9ConversationRowActions: ViewModifier {
	let id: String
	let title: String
	let session: Mango9Session
	let loadMute: () async throws -> Bool
	let setMute: (Bool) async throws -> Bool
	let delete: (() async throws -> Void)?
	let deleteNotice: String
	let showMuteBadge: Bool
	@State private var muted: Bool?
	@State private var busy = false
	@State private var confirming = false
	@State private var pending: (() -> Void)?
	@State private var error: String?
	@Environment(\.scenePhase) private var phase
	private var scope: String { Mango9ReactionTarget.accountKey(session) + ":" + id }
	func body(content: Content) -> some View {
		content
			.overlay(alignment: .leading) {
				if muted == true, showMuteBadge {
					Image(systemName: "bell.slash.fill").font(.system(size: 10)).foregroundStyle(.secondary)
						.frame(width: 20, height: 20).background(Color(uiColor: .systemBackground)).clipShape(Circle())
						.offset(x: 32, y: 16).allowsHitTesting(false)
						.accessibilityLabel("Notifications muted").accessibilityIdentifier("chat.muted." + id)
				}
			}
			.contextMenu {
				Button {
					guard let muted else { return }
					run { self.muted = try await setMute(!muted) }
				} label: { Label(muted == true ? "Unmute conversation" : "Mute conversation", systemImage: muted == true ? "bell" : "bell.slash") }
				.disabled(muted == nil || busy)
				if delete != nil {
					Button(role: .destructive) { confirming = true } label: { Label("Delete conversation", systemImage: "trash") }.disabled(busy)
				}
			}
			.background(Mango9MessageActionsPopover(showing: $confirming, pending: $pending, actions: confirmation))
			.alert("Conversation not updated", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
				Button("OK", role: .cancel) { error = nil }
			} message: { Text(error ?? "") }
			.task(id: scope + (phase == .active ? ":active" : ":inactive")) {
				guard phase == .active else { return }
				while !Task.isCancelled, Mango9SessionStore.isActive(session) {
					do {
						let value = try await loadMute()
						guard !Task.isCancelled, Mango9SessionStore.isActive(session) else { return }
						muted = value
					} catch { if !Task.isCancelled { muted = nil } }
					do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
				}
			}
			.onDisappear { confirming = false; pending = nil }
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in confirming = false; pending = nil; muted = nil }
	}
	private var confirmation: Mango9MessageActionSet {
		var menu = Mango9MessageActionSet()
		menu.reactionNotice = "Delete \(title)?\n" + deleteNotice
		menu.actions = [
			.init(title: "Delete for me", icon: "trash", destructive: true, perform: { if let delete { run(delete) } }),
			.init(title: "Cancel", icon: "xmark", perform: {})
		]
		return menu
	}
	private func run(_ operation: @escaping () async throws -> Void) {
		guard !busy, Mango9SessionStore.isActive(session) else { return }
		busy = true
		Task { @MainActor in
			defer { busy = false }
			do { try await operation() }
			catch { if Mango9SessionStore.isActive(session) { self.error = error.localizedDescription } }
		}
	}
}

extension View {
	func mango9ConversationActions(id: String, title: String, session: Mango9Session,
		loadMute: @escaping () async throws -> Bool, setMute: @escaping (Bool) async throws -> Bool,
		showMuteBadge: Bool = true, deleteNotice: String = "Only your history is hidden. Other participants keep their copies.", delete: (() async throws -> Void)? = nil) -> some View {
		modifier(Mango9ConversationRowActions(id: id, title: title, session: session, loadMute: loadMute, setMute: setMute, delete: delete, deleteNotice: deleteNotice, showMuteBadge: showMuteBadge))
	}
	@ViewBuilder func mango9SMSConversationActions(phone: String, title: String) -> some View {
		if let session = Mango9SessionStore.load() {
			mango9ConversationActions(id: "sms:" + Mango9SMSMutePreferences.normalizedPhone(phone), title: title, session: session,
				loadMute: { Mango9SMSMutePreferences.isMuted(phone: phone, identity: session.sipIdentity) },
				setMute: { muted in
					guard Mango9SessionStore.isActive(session) else { throw Mango9MessagingError.scopeChanged }
					Mango9SMSMutePreferences.setMuted(muted, phone: phone, identity: session.sipIdentity); return muted
				}, showMuteBadge: false, delete: {
					try await Mango9PersonalConversationVisibility.shared.delete(session: session, channel: .sms, id: phone)
				})
		} else { self }
	}
	@ViewBuilder func mango9TeamConversationActions(room: Mango9ChatRoom?, title: String) -> some View {
		if let session = Mango9SessionStore.load(), let room {
			mango9ConversationActions(id: "team:" + room.id, title: title, session: session,
				loadMute: { try await Mango9ChatStore.shared.chatNotificationPreference(roomID: room.id, expectedSession: session) },
				setMute: { try await Mango9ChatStore.shared.chatNotificationPreference(roomID: room.id, muted: $0, expectedSession: session) }, delete: {
					try await Mango9PersonalConversationVisibility.shared.delete(session: session, channel: .team, id: room.id)
				})
		} else { self }
	}
}

enum Mango9PopoverAnchor {
	@MainActor static func visibleBounds(of view: UIView, in window: UIWindow) -> CGRect {
		var visible = view.convert(view.bounds, to: window).intersection(window.safeAreaLayoutGuide.layoutFrame)
		var ancestor = view.superview
		while let parent = ancestor {
			if parent.clipsToBounds { visible = visible.intersection(parent.convert(parent.bounds, to: window)) }
			ancestor = parent.superview
		}
		guard !visible.isNull, !visible.isEmpty else { return .zero }
		return view.convert(visible, from: window)
	}
}

private struct Mango9MessageActionsPopover: UIViewRepresentable {
	@Binding var showing: Bool
	@Binding var pending: (() -> Void)?
	let actions: Mango9MessageActionSet
	func makeCoordinator() -> Coordinator { Coordinator(self) }
	func makeUIView(context: Context) -> UIView {
		let view = UIView(); view.backgroundColor = .clear; view.isUserInteractionEnabled = false
		return view
	}
	func updateUIView(_ view: UIView, context: Context) {
		context.coordinator.parent = self
		if showing, context.coordinator.host == nil, let window = view.window, var presenter = window.rootViewController {
			// A context menu needs the message viewport, not the remaining strip
			// above the composer keyboard (especially on smaller iPhones).
			window.endEditing(true)
			window.layoutIfNeeded()
			while let presented = presenter.presentedViewController, !presented.isBeingDismissed { presenter = presented }
			let coordinator = context.coordinator
			let host = UIHostingController(rootView: Mango9MessageActionsPanel(actions: actions, moreReactions: { [weak view, weak coordinator] in
				guard let view else { return }
				coordinator?.presentEmojiPicker(from: view, actions: actions)
			}) { action in
				coordinator.parent.pending = action
				coordinator.parent.showing = false
			})
			host.modalPresentationStyle = .popover
			host.preferredContentSize = CGSize(width: min(350, window.bounds.width - 32),
				height: min(500, CGFloat(actions.actions.count * 48 + (actions.reactions.isEmpty ? 0 : 64) + (actions.reactionNotice == nil ? 0 : 44) + 12)))
			if let popover = host.popoverPresentationController {
				popover.sourceView = view
				// A long bubble can extend above/below the viewport. Anchor only to
				// its visible part, not an offscreen center that looks unrelated.
				popover.sourceRect = Mango9PopoverAnchor.visibleBounds(of: view, in: window)
				popover.permittedArrowDirections = [.up, .down]
				popover.delegate = coordinator
			}
			coordinator.host = host
			coordinator.account = Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey)
			presenter.present(host, animated: true)
		} else if !showing, let host = context.coordinator.host {
			context.coordinator.host = nil
			let action = pending
			let account = context.coordinator.account
			host.dismiss(animated: true) {
				guard let account, Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) == account else { return }
				action?()
			}
			DispatchQueue.main.async { pending = nil }
		}
	}
	static func dismantleUIView(_ view: UIView, coordinator: Coordinator) {
		coordinator.host?.dismiss(animated: false); coordinator.host = nil
		coordinator.emojiHost?.dismiss(animated: false); coordinator.emojiHost = nil
	}
	final class Coordinator: NSObject, UIPopoverPresentationControllerDelegate {
		var parent: Mango9MessageActionsPopover
		var host: UIViewController?
		var emojiHost: UIViewController?
		var account: String?
		init(_ parent: Mango9MessageActionsPopover) { self.parent = parent }
		func adaptivePresentationStyle(for controller: UIPresentationController) -> UIModalPresentationStyle { .none }
		func adaptivePresentationStyle(for controller: UIPresentationController, traitCollection: UITraitCollection) -> UIModalPresentationStyle { .none }
		func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
			host = nil; parent.pending = nil; parent.showing = false
		}
		func presentEmojiPicker(from view: UIView, actions: Mango9MessageActionSet) {
			guard emojiHost == nil, let react = actions.react, let account,
				Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) == account,
				let window = view.window, var presenter = window.rootViewController else { return }
			while let presented = presenter.presentedViewController { presenter = presented }
			let picker = Mango9SystemEmojiPickerController(notice: actions.reactionNotice) { [weak self] emoji in
				guard let self else { return }
				let host = self.emojiHost; self.emojiHost = nil
				host?.dismiss(animated: true) {
					guard let emoji, Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) == account else { return }
					react(emoji)
				}
			}
			let navigation = UINavigationController(rootViewController: picker)
			navigation.modalPresentationStyle = .pageSheet
			navigation.sheetPresentationController?.detents = [.medium(), .large()]
			navigation.sheetPresentationController?.prefersGrabberVisible = true
			// A choice or Cancel always resolves the captured target before dismissal.
			navigation.isModalInPresentation = true
			emojiHost = navigation
			presenter.present(navigation, animated: true)
		}
	}
}

/// A tap target for the same bubble-anchored panel used by long press.
struct Mango9MessageActionsButton: View {
 let actions: () -> Mango9MessageActionSet
 @State private var showing = false
 @State private var pending: (() -> Void)?
 var body: some View {
  Button { showing = true } label: { Image(systemName: "face.smiling").padding(7) }
   .background(Mango9MessageActionsPopover(showing: $showing, pending: $pending, actions: actions()))
   .onDisappear { showing = false; pending = nil }
 }
}

private struct Mango9MessageActionsPanel: View {
	let actions: Mango9MessageActionSet
	let moreReactions: () -> Void
	let perform: (@escaping () -> Void) -> Void
	var body: some View {
		ScrollView {
			VStack(spacing: 0) {
				if !actions.reactions.isEmpty, let react = actions.react {
					HStack(spacing: 4) {
					ScrollView(.horizontal, showsIndicators: false) {
						HStack(spacing: 2) {
							ForEach(actions.reactions, id: \.self) { emoji in
								Button { perform { react(emoji) } } label: {
									Text(emoji).font(.system(size: 30)).frame(width: 48, height: 52)
										.background(actions.selectedReactions.contains(emoji) ? Color.accentColor.opacity(0.15) : .clear)
										.clipShape(Capsule())
								}.accessibilityLabel("\(actions.selectedReactions.contains(emoji) ? "Remove" : "Add") \(emoji) reaction")
							}
						}.padding(.horizontal, 6)
					}.frame(height: 62).accessibilityIdentifier("chat.reactionStrip")
					if actions.allowsMoreReactions {
					Button { perform(moreReactions) } label: {
						Image(systemName: "face.smiling").symbolRenderingMode(.monochrome).font(.system(size: 24))
							.foregroundStyle(Color(uiColor: .systemGray)).frame(width: 44, height: 44)
							.background(Color(uiColor: .systemGray5)).clipShape(Circle())
					}.accessibilityLabel("More emoji reactions").accessibilityIdentifier("chat.moreReactions")
					}
					}.padding(.trailing, 8)
				}
				if let notice = actions.reactionNotice { Text(notice).font(.caption).foregroundStyle(.secondary).padding(8) }
				ForEach(actions.actions) { action in
					Divider()
					Button { perform(action.perform) } label: {
						HStack { Text(action.title); Spacer(); Image(systemName: action.icon) }
							.foregroundStyle(action.destructive ? Color.red : Color.primary)
							.padding(.horizontal, 18).frame(minHeight: 48).contentShape(Rectangle())
					}
				}
			}.padding(.vertical, 6)
		}.buttonStyle(.plain).background(.regularMaterial)
	}
}

enum Mango9EmojiInput {
	static func isSingleEmoji(_ value: String) -> Bool {
		guard value.count == 1, let character = value.first else { return false }
		let scalars = character.unicodeScalars
		return scalars.contains { $0.properties.isEmojiPresentation }
			|| (scalars.contains { $0.properties.isEmoji } && scalars.contains { $0.value == 0xFE0F || $0.value == 0x20E3 })
	}
}

private final class Mango9EmojiTextField: UITextField {
	override var textInputContextIdentifier: String? { "mango9.reaction.emoji" }
	override var textInputMode: UITextInputMode? {
		// Only choose a local keyboard; never log or transmit the keyboard inventory.
		UITextInputMode.activeInputModes.first { $0.primaryLanguage == "emoji" } ?? super.textInputMode
	}
}

private final class Mango9SystemEmojiPickerController: UIViewController, UITextFieldDelegate {
	private let input = Mango9EmojiTextField()
	private let notice: String?
	private let completion: (String?) -> Void
	private var accountObserver: NSObjectProtocol?
	private var completed = false
	init(notice: String?, completion: @escaping (String?) -> Void) {
		self.notice = notice; self.completion = completion
		super.init(nibName: nil, bundle: nil)
	}
	required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
	deinit { if let accountObserver { NotificationCenter.default.removeObserver(accountObserver) } }
	override func viewDidLoad() {
		super.viewDidLoad()
		title = "Choose a reaction"; view.backgroundColor = .systemBackground
		navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
		let label = UILabel(); label.numberOfLines = 0; label.font = .preferredFont(forTextStyle: .footnote); label.textColor = .secondaryLabel
		label.text = notice ?? "Choose an emoji to react to this message."
		input.delegate = self; input.font = .systemFont(ofSize: 30); input.textAlignment = .center
		input.placeholder = "Choose an emoji"; input.accessibilityIdentifier = "chat.emojiInput"
		input.autocorrectionType = .no; input.spellCheckingType = .no
		input.backgroundColor = .secondarySystemBackground; input.layer.cornerRadius = 12
		let stack = UIStackView(arrangedSubviews: [label, input]); stack.axis = .vertical; stack.spacing = 12
		stack.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(stack)
		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
			stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
			stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
			input.heightAnchor.constraint(equalToConstant: 52)
		])
		accountObserver = NotificationCenter.default.addObserver(forName: .mango9AccountContextChanged, object: nil, queue: .main) { [weak self] _ in self?.finish(nil) }
	}
	override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); input.becomeFirstResponder() }
	@objc private func cancel() { finish(nil) }
	private func finish(_ emoji: String?) {
		guard !completed else { return }; completed = true
		input.resignFirstResponder(); completion(emoji)
	}
	func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
		guard !string.isEmpty else { return true }
		if Mango9EmojiInput.isSingleEmoji(string) { finish(string) }
		return false
	}
}

struct Mango9ForwardPayload: Identifiable {
	let id = UUID()
	let account = Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey)
	let text: String
	var files: [String] = []
	var senderID: String = ""
	var isCurrent: Bool { account != nil && account == Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) }
}

struct Mango9TeamForwardSheet: View {
	let payload: Mango9ForwardPayload
	@ObservedObject private var store = Mango9ChatStore.shared
	@Environment(\.dismiss) private var dismiss
	@State private var search = ""
	var body: some View {
		NavigationView {
			List {
				Section { Text("Choose a recipient, then review and send the forwarded message.").font(.footnote) }
				Section("People") {
					ForEach(store.users.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }) { user in
						NavigationLink(user.name) { Mango9ChatFragment(user: user, initialText: payload.isCurrent ? payload.text : "", initialFiles: payload.isCurrent ? payload.files : []) }.disabled(!payload.isCurrent)
					}
				}
				Section("Groups") {
					ForEach(store.rooms.filter { !$0.isDirect && (search.isEmpty || store.groupTitle($0).localizedCaseInsensitiveContains(search)) }) { room in
						NavigationLink(store.groupTitle(room)) { Mango9ChatFragment(room: room, initialText: payload.isCurrent ? payload.text : "", initialFiles: payload.isCurrent ? payload.files : []) }.disabled(!payload.isCurrent)
					}
				}
			}.searchable(text: $search).navigationTitle("Forward message")
				.toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
				.task { await store.refreshDirectory() }
		}.navigationViewStyle(.stack)
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
	}
}

struct Mango9SocialForwardSheet: View {
	let session: Mango9Session
	let text: String
	var attachments: [Attachment] = []
	@StateObject private var inbox: Mango9SocialInboxStore
	@Environment(\.dismiss) private var dismiss
	@State private var search = ""
	init(session: Mango9Session, text: String, attachments: [Attachment] = []) {
		self.session = session; self.text = text; self.attachments = attachments
		_inbox = StateObject(wrappedValue: Mango9SocialInboxStore(session: session))
	}
	var body: some View {
		NavigationView {
			List {
				Text("Social forwarding sends a new message to an existing authorized conversation. Review it before sending.").font(.footnote)
				if let error = inbox.error { Text(error); Button("Retry") { Task { await inbox.loadInbox(search: search) } } }
				ForEach(inbox.threads) { thread in
					NavigationLink(thread.title + " · " + thread.platform.capitalized) {
						Mango9SocialForwardComposer(thread: thread, session: session, text: text, attachments: attachments)
					}.disabled(!thread.permitsSending)
				}
				if inbox.nextThread != nil { Button("More conversations") { Task { await inbox.loadInbox(search: search, more: true) } }.disabled(inbox.busy) }
			}.searchable(text: $search).navigationTitle("Forward message")
				.toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
				.task(id: search) { try? await Task.sleep(nanoseconds: 250_000_000); guard !Task.isCancelled else { return }; await inbox.loadInbox(search: search) }
		}.navigationViewStyle(.stack).onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
	}
}

private struct Mango9SocialForwardComposer: View {
	let thread: Mango9SocialThread
	let text: String
	let attachments: [Attachment]
	@StateObject private var store: Mango9SocialInboxStore
	@State private var prepared = false
	init(thread: Mango9SocialThread, session: Mango9Session, text: String, attachments: [Attachment]) {
		self.thread = thread; self.text = text; self.attachments = attachments; _store = StateObject(wrappedValue: Mango9SocialInboxStore(session: session))
	}
	var body: some View {
		Form {
			Text(thread.title).font(.headline)
			TextEditor(text: $store.draft).frame(minHeight: 120).disabled(store.sending || store.unresolvedSend)
			if !store.selectedMedia.isEmpty { Mango9PendingAttachmentStrip(attachments: $store.selectedMedia).disabled(store.sending || store.unresolvedSend) }
			if let notice = store.sendNotice { Text(notice).font(.footnote) }
			if let error = store.error { Text(error).font(.footnote) }
			if store.unresolvedSend { Button("Check delivery") { Task { await store.checkSend() } }.disabled(store.sending) }
			else { Button(store.sending ? "Sending…" : "Forward") { Task { await store.send() } }.disabled(!store.canSend) }
			if store.sends.first?.confirmedSent == true { Label("Sent", systemImage: "checkmark.circle") }
		}.navigationTitle("Review forward").task {
			if !prepared && Mango9SessionStore.isActive(store.session) { store.draft = text; store.selectedMedia = attachments; prepared = true }
			await store.loadThread(id: thread.id)
		}
	}
}

struct Mango9SMSForwardSheet: View {
	let payload: Mango9ForwardPayload
	@ObservedObject private var store = Mango9ChatStore.shared
	@Environment(\.dismiss) private var dismiss
	@State private var phone = ""
	@State private var pickingContact = false
	@State private var sending = false
	@State private var attempted = false
	@State private var notice: String?
	var body: some View {
		NavigationView {
			Form {
				Section("Recipient") {
					TextField("Phone number", text: $phone).keyboardType(.phonePad)
					Button("Choose iPhone contact") { pickingContact = true }
				}
				Section("Message") {
					Text(payload.text)
					if !payload.files.isEmpty { Label("\(payload.files.count) attachment(s)", systemImage: "paperclip") }
				}
				if let notice { Text(notice).font(.footnote) }
				Button(sending ? "Forwarding…" : "Forward") {
					sending = true; attempted = true
					Task {
						defer { sending = false }
						do { guard payload.isCurrent else { throw Mango9MessagingError.scopeChanged }
							try await store.sendSMS(to: phone, from: payload.senderID, message: payload.text, forwardedFiles: payload.files, expectedAccount: payload.account)
							notice = "Submitted. Delivery status will appear in SMS history."
						} catch { notice = "Forward not confirmed. Check SMS history before trying again. Nothing will be resent automatically. " + error.localizedDescription }
					}
				}.disabled(sending || attempted || phone.filter(\.isNumber).count < 7 || payload.senderID.isEmpty || !payload.isCurrent)
				if payload.senderID.isEmpty { Text("Choose a sending number in the SMS conversation before forwarding.").font(.footnote) }
				Section("Recent recipients") { ForEach(store.smsParties) { party in Button(party.phone) { phone = party.phone } } }
			}.navigationTitle("Forward SMS")
				.toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() }.disabled(sending) } }
		}.navigationViewStyle(.stack)
			.sheet(isPresented: $pickingContact) { Mango9PhoneNumberPicker { phone = $0 } }
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in dismiss() }
	}
}

private struct Mango9PhoneNumberPicker: UIViewControllerRepresentable {
	let choose: (String) -> Void
	func makeCoordinator() -> Coordinator { Coordinator(choose: choose) }
	func makeUIViewController(context: Context) -> CNContactPickerViewController {
		let picker = CNContactPickerViewController(); picker.delegate = context.coordinator
		picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
		picker.predicateForEnablingContact = NSPredicate(format: "phoneNumbers.@count > 0")
		picker.predicateForSelectionOfContact = NSPredicate(value: false)
		picker.predicateForSelectionOfProperty = NSPredicate(format: "key == 'phoneNumbers'")
		return picker
	}
	func updateUIViewController(_ controller: CNContactPickerViewController, context: Context) {}
	final class Coordinator: NSObject, CNContactPickerDelegate {
		let choose: (String) -> Void
		init(choose: @escaping (String) -> Void) { self.choose = choose }
		func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
			if let number = contactProperty.value as? CNPhoneNumber { choose(number.stringValue) }
		}
	}
}

struct Mango9MessageSelectionBar: View {
	let count: Int
	let delete: () -> Void
	let forward: () -> Void
	let cancel: () -> Void
	var body: some View {
		HStack {
			Button(role: .destructive, action: delete) { Image(systemName: "trash").frame(width: 44, height: 44) }.disabled(count == 0).accessibilityLabel("Delete selected messages for me")
			Spacer(); Text("\(count) selected").font(.subheadline); Spacer()
			Button(action: forward) { Image(systemName: "arrowshape.turn.up.right").frame(width: 44, height: 44) }.disabled(count == 0).accessibilityLabel("Forward selected messages")
			Button("Done", action: cancel)
		}.padding(.horizontal).padding(.vertical, 6).background(Color(uiColor: .systemBackground))
	}
}

struct Mango9ReplyPreview: View {
	let text: String
	let cancel: () -> Void
	var body: some View {
		HStack { Image(systemName: "arrowshape.turn.up.left"); VStack(alignment: .leading) {
			Text("Reply · Sent as quoted text").font(.caption).foregroundStyle(.secondary)
			Text(text).font(.subheadline).lineLimit(2)
		}; Spacer(); Button(action: cancel) { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Cancel reply") }.padding(12)
	}
}

enum Mango9MessageText {
	static func reply(_ text: String, to quote: String?) -> String {
		guard let quote else { return text }
		return "> " + String(quote.prefix(500)).replacingOccurrences(of: "\n", with: "\n> ") + "\n\n" + text
	}
	static func forward(_ texts: [String]) -> String { "Forwarded message:\n" + texts.joined(separator: "\n\n") }
}

struct Mango9AttachmentRequest: Identifiable {
	enum Source { case camera, photos, files }
	let id = UUID()
	let source: Source
	// Capture scope when the menu item is chosen, not when an asynchronous picker finishes.
	let account = Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey)
}

private struct Mango9AttachmentPickerModifier: ViewModifier {
	@Binding var request: Mango9AttachmentRequest?
	@Binding var selected: [Attachment]
	let limit: Int
	var photosOnly = false
	let error: (String) -> Void
	func body(content: Content) -> some View {
		content
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in request = nil }
		.fullScreenCover(item: presentationRequest(camera: true)) { selection in
			Mango9CameraPhotoPicker(picked: { attachment in append([attachment], for: selection) }, failed: error)
				.background(Color.black).ignoresSafeArea().statusBarHidden(true)
		}
		.sheet(item: presentationRequest(camera: false)) { selection in
			switch selection.source {
			case .camera:
				EmptyView() // The camera is exclusively presented full-screen above.
			case .photos:
				PhotoPicker(filter: photosOnly ? .images : .any(of: [.images, .videos]), limit: max(1, limit - selected.count)) { results in
					PhotoPicker.convertToAttachmentArray(fromResults: results) { values, failure in
						if let values { append(values, for: selection) }; if let failure { error(failure.localizedDescription) }
					}
				}
			case .files:
				FilePicker { urls in FilePicker.convertToAttachmentArray(fromResults: urls) { values, failure in
					if let values { append(values, for: selection) }; if let failure { error(failure.localizedDescription) }
				} }
			}
		}
	}
	private func presentationRequest(camera: Bool) -> Binding<Mango9AttachmentRequest?> {
		Binding(get: {
			guard let current = request, (current.source == .camera) == camera else { return nil }
			return current
		}, set: { value in
			// Dismissing an inactive sheet must not clear the other presentation.
			if let value { request = value }
			else if let current = request, (current.source == .camera) == camera { request = nil }
		})
	}
	private func append(_ values: [Attachment], for selection: Mango9AttachmentRequest) {
		guard let account = selection.account, Mango9SessionStore.load().map(Mango9ReactionTarget.accountKey) == account else { return }
		if values.count + selected.count > limit {
            for item in values { for url in Set([item.full, item.thumbnail]) where url.path.hasPrefix(FileManager.default.temporaryDirectory.path) { try? FileManager.default.removeItem(at: url) } }
            error("Choose up to \(max(0, limit - selected.count)) more attachments. Nothing was added.")
            return
        }
        selected.append(contentsOf: values)
		if request?.id == selection.id { request = nil }
	}
}

private struct Mango9MessageSelectionModifier: ViewModifier {
	let active: Bool
	let selected: Bool
	let toggle: () -> Void
	func body(content: Content) -> some View {
		HStack(spacing: 10) {
			if active {
				Image(systemName: selected ? "checkmark.circle.fill" : "circle")
					.font(.system(size: 22)).foregroundStyle(selected ? Color.orangeMain500 : Color.secondary)
					.frame(width: 28).accessibilityHidden(true)
			}
			content.frame(maxWidth: .infinity).allowsHitTesting(!active).accessibilityHidden(active)
		}.contentShape(Rectangle())
			.overlay {
				if active {
				Button(action: toggle) {
					Color.clear
						.frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
				}.buttonStyle(.plain).accessibilityLabel(selected ? "Deselect message" : "Select message")
				}
			}
			.animation(.easeInOut(duration: 0.18), value: active)
	}
}

extension View {
	func mango9MessageSelection(active: Bool, selected: Bool, toggle: @escaping () -> Void) -> some View {
		modifier(Mango9MessageSelectionModifier(active: active, selected: selected, toggle: toggle))
	}
}

extension View {
	func mango9Attachments(request: Binding<Mango9AttachmentRequest?>, selected: Binding<[Attachment]>, limit: Int = 10, photosOnly: Bool = false, error: @escaping (String) -> Void) -> some View {
		modifier(Mango9AttachmentPickerModifier(request: request, selected: selected, limit: limit, photosOnly: photosOnly, error: error))
	}
}

private struct Mango9CameraPhotoPicker: UIViewControllerRepresentable {
	let picked: (Attachment) -> Void
	let failed: (String) -> Void
	@Environment(\.dismiss) private var dismiss
	func makeCoordinator() -> Coordinator { Coordinator(self) }
	func makeUIViewController(context: Context) -> UIImagePickerController {
		let picker = UIImagePickerController()
		picker.sourceType = .camera
		picker.mediaTypes = ["public.image"]
		picker.modalPresentationStyle = .fullScreen
		picker.delegate = context.coordinator
		return picker
	}
	func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
	final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
		let parent: Mango9CameraPhotoPicker
		init(_ parent: Mango9CameraPhotoPicker) { self.parent = parent }
		func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
		func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
			defer { parent.dismiss() }
			guard let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.85) else { parent.failed("Could not prepare the photo. Please try again."); return }
			let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
			do { try data.write(to: url); parent.picked(Attachment(id: UUID().uuidString, name: "Photo.jpg", url: url, type: .image, size: data.count)) }
			catch { parent.failed("Could not save the photo. Check available storage and try again.") }
		}
	}
}

// A selection belongs to one account, channel and filter. New rows are never
// selected merely because a background refresh adds them to the list.
@MainActor final class Mango9ChatListSelection: ObservableObject {
    enum Action: String { case read, unread, delete }
    @Published var selecting = false
    @Published private(set) var selected = Set<String>()
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    @Published private(set) var allPagesSelected = false
    private var scope = "", revision = 0
    private var preserveSnapshot = false
    func bind(_ value: String) {
        guard scope != value else { return }
        scope = value; revision += 1; selecting = false; selected = []; busy = false; error = nil; allPagesSelected = false; preserveSnapshot = false
    }
    func toggle(_ id: String) { guard !busy else { return }; allPagesSelected = false; selecting = true; if !selected.insert(id).inserted { selected.remove(id) } }
    func finish() { guard !busy else { return }; selected = []; selecting = false; error = nil; preserveSnapshot = false; allPagesSelected = false }
    func prune(_ ids: [String]) { if !busy && error == nil && !preserveSnapshot { selected.formIntersection(ids) } }
    func all(_ load: @escaping @MainActor () async throws -> [String]) async {
        guard !busy else { return }; let turn = revision
        busy = true; error = nil
        defer { if turn == revision { busy = false } }
        do { let ids = try await load(); guard turn == revision else { return }; selected = Set(ids); selecting = true; allPagesSelected = true; preserveSnapshot = true }
        catch { if turn == revision { self.error = "Could not load every chat. Retry Select all." } }
    }
    func deselectAll() { guard !busy else { return }; selected = []; preserveSnapshot = false; allPagesSelected = false }
    func runBatch(_ action: Action, work: @escaping @MainActor ([String], Action) async throws -> Void) async {
        guard !busy, !selected.isEmpty, let session = Mango9SessionStore.load() else { return }
        let turn = revision, ids = Array(selected).sorted(); busy = true; error = nil
        do {
            try await work(ids, action)
            guard revision == turn, Mango9SessionStore.isActive(session) else { return }
            selected = []; selecting = false; allPagesSelected = false; preserveSnapshot = false
        } catch {
            guard revision == turn, Mango9SessionStore.isActive(session) else { return }
            self.error = "Could not update the selected chats. Retry, or select them again."
        }
        if revision == turn { busy = false }
    }
    func run(_ action: Action, work: @escaping @MainActor (String, Action) async throws -> Void) async {
        guard !busy, !selected.isEmpty, let session = Mango9SessionStore.load() else { return }
        let turn = revision, ids = Array(selected).sorted(); busy = true; error = nil
        let current = { self.revision == turn && Mango9SessionStore.isActive(session) }
        await withTaskGroup(of: (String, Bool).self) { group in
            var next = 0
            func enqueue(_ id: String) { group.addTask { @MainActor in guard current() else { return (id, false) }; do { try await work(id, action); return (id, true) } catch { return (id, false) } } }
            for id in ids.prefix(4) { enqueue(id); next += 1 }
            while let (id, ok) = await group.next() {
                guard current() else { group.cancelAll(); break }
                if ok { selected.remove(id) }
                if next < ids.count { enqueue(ids[next]); next += 1 }
            }
        }
        guard current() else { return }
        busy = false; selecting = !selected.isEmpty
        if selecting { error = "Some chats could not be updated. They remain selected for retry." }
    }
}

struct Mango9ChatListToolbar: View {
    @ObservedObject var selection: Mango9ChatListSelection
    let scope: String, ids: [String]
    var loadAll: (@MainActor () async throws -> [String])?
    var loadByState: (@MainActor (String) async throws -> [String])?
    var batchWork: (@MainActor ([String], Mango9ChatListSelection.Action) async throws -> Void)?
    let work: @MainActor (String, Mango9ChatListSelection.Action) async throws -> Void
    let refresh: @MainActor () async -> Void
    @State private var confirmDelete = false
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                control(selection.selecting ? "xmark" : "checkmark.circle", selection.selecting ? "Cancel selection" : "Select chats") {
                    if selection.selecting { selection.finish() } else { selection.selecting = true }
                }.disabled(!selection.selecting && ids.isEmpty)
                if selection.selecting {
                    let all = selection.allPagesSelected && !ids.isEmpty && Set(ids).isSubset(of: selection.selected)
                    if let loadByState {
                        Menu {
                            Button { Task { await selection.all { try await loadByState("unread") } } } label: { Label("Select unread", systemImage: "envelope.badge") }
                            Button { Task { await selection.all { try await loadByState("read") } } } label: { Label("Select read", systemImage: "envelope.open") }
                            Button { Task { await selection.all { try await loadByState("all") } } } label: { Label("Select all", systemImage: "checkmark.square") }
                            if !selection.selected.isEmpty { Button("Deselect all") { selection.deselectAll() } }
                        } label: {
                            Image(systemName: all ? "checkmark.square.fill" : "square").font(.system(size: 20)).frame(width: 44, height: 44)
                        }.accessibilityLabel("Choose chats to select")
                    } else {
                    control(all ? "checkmark.square.fill" : "square", all ? "Deselect all" : "Select all chats in this list") {
                        if all { selection.deselectAll() }
                        else { Task { await selection.all(loadAll ?? { ids }) } }
                    }
                    }
                    Text("\(selection.selected.count) selected").font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                    Spacer(minLength: 0)
                    if selection.busy { ProgressView().padding(.horizontal, 12) }
                    else {
                        control("envelope.open", "Mark selected chats read") { perform(.read) }.disabled(selection.selected.isEmpty)
                        control("envelope.badge", "Mark selected chats unread") { perform(.unread) }.disabled(selection.selected.isEmpty)
                        control("trash", "Delete selected chats") { confirmDelete = true }.foregroundStyle(.red).disabled(selection.selected.isEmpty)
                    }
                } else { Spacer(minLength: 0) }
            }.disabled(selection.busy).padding(.horizontal, 8)
            if let error = selection.error { Text(error).font(.caption).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.bottom, 6) }
        }.background(Color(UIColor.systemBackground)).tint(Color.orangeMain500)
        .onAppear { selection.bind(scope) }.onChange(of: scope) { selection.bind($0) }
        .onChange(of: ids) { selection.prune($0) }
        .alert("Delete selected chats?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { perform(.delete) }
        } message: { Text("This removes saved history for you. Other participants keep their messages. New messages can bring a chat back.") }
    }
    private func control(_ symbol: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 20)).frame(width: 44, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.plain).accessibilityLabel(title)
    }
    private func perform(_ action: Mango9ChatListSelection.Action) {
        Task {
            if let batchWork { await selection.runBatch(action, work: batchWork) }
            else { await selection.run(action, work: work) }
            await refresh(); await Mango9InboxUnread.shared.refresh()
        }
    }
}

struct Mango9SelectableChatRow<Row: View, Destination: View>: View {
    @ObservedObject var selection: Mango9ChatListSelection
    let id: String?
    let destination: Destination
    @ViewBuilder let row: () -> Row
    var body: some View {
        if selection.selecting {
            Button { if let id { selection.toggle(id) } } label: {
                HStack(spacing: 10) {
                    Image(systemName: id.map { selection.selected.contains($0) } == true ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 21)).foregroundStyle(Color.orangeMain500).frame(width: 28)
                    row()
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).disabled(id == nil || selection.busy)
                .accessibilityAddTraits(id.map { selection.selected.contains($0) } == true ? .isSelected : [])
        } else { NavigationLink(destination: destination, label: row) }
    }
}

/// Captures a lightweight server snapshot, then applies one atomic command.
/// A lost response retains the same request identifier for a safe retry.
struct Mango9ChatBulkResponse: Decodable {
    let type: String
    let channel: String?
    let selectionId: String?
    let requestId: String?
    let action: String?
    let items: [Mango9SocialReadState]
    let hiddenIds: [String]?
    let skippedIds: [String]?
    static func decode(_ raw: Any) throws -> Self {
        try JSONDecoder().decode(Self.self, from: JSONSerialization.data(withJSONObject: raw))
    }
}
@MainActor final class Mango9ChatBulkActions {
    typealias Post = @MainActor (String, [String: Any]) async throws -> Mango9ChatBulkResponse
    let channel: String
    private let post: Post
    private let current: @MainActor () throws -> Void
    private var snapshot: (id: String, query: Data, ids: Set<String>)?
    private var pending: (key: String, body: [String: Any])?
    private var generation = 0
    init(channel: String, post: @escaping Post, current: @escaping @MainActor () throws -> Void) {
        precondition(["sms", "team", "social", "live"].contains(channel))
        self.channel = channel; self.post = post; self.current = current
    }
    private func valid(_ id: String) -> Bool {
        switch channel {
        case "sms": return id.range(of: "^[0-9]{3,20}$", options: .regularExpression) != nil
        case "team": return id.range(of: "^[1-9][0-9]*$", options: .regularExpression) != nil
        default: return UUID(uuidString: id) != nil
        }
    }
    private func checkRows(_ rows: [Mango9SocialReadState]) throws {
        guard rows.count <= 5000, Set(rows.map(\.id)).count == rows.count,
              rows.allSatisfy({ valid($0.id) && $0.readThrough >= 0 }) else { throw Mango9MessagingError.invalidResponse }
    }
    private func queryKey(_ query: [String: Any]) throws -> Data {
        var identity = query; identity.removeValue(forKey: "ids")
        return try JSONSerialization.data(withJSONObject: identity, options: .sortedKeys)
    }
    func select(_ mode: String, query: [String: Any] = [:], ids: [String]? = nil) async throws -> [String] {
        try current()
        guard ["all", "read", "unread"].contains(mode), ids == nil || (!ids!.isEmpty && ids!.count <= 5000 && ids!.allSatisfy(valid)) else { throw Mango9MessagingError.invalidResponse }
        let requested = ids ?? (query["ids"] as? [String])
        let key = try queryKey(query)
        generation += 1; let epoch = generation, id = UUID().uuidString.lowercased()
        var body = query; body.removeValue(forKey: "localSearch"); body["mode"] = mode; body["requestId"] = id
        if let ids { body["ids"] = ids }
        let result = try await post("select", body)
        try current(); guard epoch == generation else { throw CancellationError() }
        try checkRows(result.items)
        guard result.type == (channel == "social" ? "inbox-selection" : "chat-selection"), result.selectionId == id,
              channel == "social" || result.channel == channel,
              result.items.allSatisfy({ (requested == nil || requested!.contains($0.id)) && (mode == "all" || $0.unread == (mode == "unread")) }) else { throw Mango9MessagingError.invalidResponse }
        let found = result.items.map(\.id)
        snapshot = (id, key, Set(found)); pending = nil; return found
    }
    func apply(_ ids: [String], action: Mango9ChatListSelection.Action, query: [String: Any] = [:]) async throws -> Mango9ChatBulkResponse? {
        try current()
        guard !ids.isEmpty, ids.count <= 5000, Set(ids).count == ids.count, ids.allSatisfy(valid) else { throw Mango9MessagingError.invalidResponse }
        let queryKey = try queryKey(query)
        if snapshot?.query != queryKey || !Set(ids).isSubset(of: snapshot?.ids ?? []) { _ = try await select("all", query: query, ids: ids) }
        guard let captured = snapshot else { throw Mango9MessagingError.invalidResponse }
        let targets = ids.filter { captured.ids.contains($0) }.sorted()
        if targets.isEmpty { return nil }
        let key = captured.id + ":" + action.rawValue + ":" + targets.joined(separator: ",")
        if pending?.key != key { pending = (key, ["selectionId": captured.id, "ids": targets, "action": action.rawValue, "requestId": UUID().uuidString.lowercased()]) }
        let command = pending!
        let result = try await post("apply", command.body)
        try current(); guard snapshot?.id == captured.id else { throw CancellationError() }
        try checkRows(result.items)
        let legacy = channel == "social" && action != .delete
        let covered = result.items.map(\.id) + (result.hiddenIds ?? []) + (result.skippedIds ?? [])
        guard result.type == (legacy ? "inbox-bulk-read" : "chat-bulk-result"), legacy || result.channel == channel,
              result.requestId == command.body["requestId"] as? String, result.action == action.rawValue,
              result.skippedIds != nil, action == .delete || (result.hiddenIds ?? []).isEmpty,
              covered.count == targets.count, Set(covered) == Set(targets) else { throw Mango9MessagingError.invalidResponse }
        pending = nil; snapshot = nil; return result
    }
}
