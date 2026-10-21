import SwiftUI

enum Mango9TeamChatStart: Equatable {
	case direct(Mango9ChatUser)
	case group(Set<Int>)
	init?(selection: Set<Int>, users: [Mango9ChatUser]) {
		guard !selection.isEmpty, selection.allSatisfy({ $0 > 0 }),
			selection.isSubset(of: Set(users.map(\.id))) else { return nil }
		if selection.count == 1, let user = users.first(where: { selection.contains($0.id) }) {
			self = .direct(user)
		} else { self = .group(selection) }
	}
}

/// The action lives with the selected inbox, not above all three tabs. Social
/// replies start from an authorized existing thread, never from the SMS picker.
struct Mango9ConversationComposeButton: View {
	let tab: Mango9ConversationTab
	let onSMS: () -> Void
	let onTeam: () -> Void
	var body: some View {
		if tab == .sms || tab == .team {
			Button(action: tab == .team ? onTeam : onSMS) {
				Image(systemName: "square.and.pencil")
					.font(.title2).foregroundStyle(.white)
					.frame(width: 56, height: 56)
					.background(Color.orangeMain500).clipShape(Circle())
					.shadow(color: .black.opacity(0.2), radius: 4)
			}
			.accessibilityLabel(tab == .team ? "New Team chat" : "New message")
			.accessibilityIdentifier("conversations.compose")
			.padding()
		}
	}
}

/// Short-lived, bounded snapshots avoid blank/loading flashes when returning to
/// a thread. They are never an authorization source; opening still refreshes it.
struct Mango9ConversationSnapshotCache<Message> {
	private struct Key: Hashable { let account: String; let room: String }
	private struct Snapshot { let messages: [Message]; let saved: Date }
	private var snapshots: [Key: Snapshot] = [:]
	mutating func save(_ messages: [Message], account: String, room: String, now: Date = Date()) {
		snapshots[Key(account: account, room: room)] = Snapshot(messages: Array(messages.suffix(300)), saved: now)
		while snapshots.count > 10, let oldest = snapshots.min(by: { $0.value.saved < $1.value.saved })?.key { snapshots.removeValue(forKey: oldest) }
	}
	func messages(account: String, room: String, now: Date = Date()) -> [Message] {
		guard let value = snapshots[Key(account: account, room: room)], now.timeIntervalSince(value.saved) < 300 else { return [] }
		return value.messages
	}
	mutating func remove(account: String, room: String) { snapshots.removeValue(forKey: Key(account: account, room: room)) }
	mutating func removeAll() { snapshots.removeAll() }
}

/// Scoped to one app root, not a singleton: another window or account cannot
/// inherit a stale hide flag. Owners make overlapping navigation transitions safe.
@MainActor
final class Mango9ConversationNavigation: ObservableObject {
	@Published private(set) var hidesAppNavigation = false
	private var visibleConversations: Set<UUID> = []

	func setVisible(_ visible: Bool, owner: UUID) {
		if visible { visibleConversations.insert(owner) }
		else { visibleConversations.remove(owner) }
		let hidden = !visibleConversations.isEmpty
		if hidden != hidesAppNavigation { hidesAppNavigation = hidden }
	}
}

private struct Mango9ConversationNavigationKey: EnvironmentKey {
	static let defaultValue: Mango9ConversationNavigation? = nil
}

extension EnvironmentValues {
	var mango9ConversationNavigation: Mango9ConversationNavigation? {
		get { self[Mango9ConversationNavigationKey.self] }
		set { self[Mango9ConversationNavigationKey.self] = newValue }
	}
}

private struct Mango9ConversationNavigationModifier: ViewModifier {
	@Environment(\.mango9ConversationNavigation) private var navigation
	@State private var owner = UUID()
	var showsNavigationBar = true

	func body(content: Content) -> some View {
		Group {
   if #available(iOS 16.0, *) {
    content.toolbarBackground(Color(uiColor: .systemBackground), for: .navigationBar)
     .toolbarBackground(.visible, for: .navigationBar)
   } else { content }
  }
   .navigationBarHidden(!showsNavigationBar)
			.onAppear { navigation?.setVisible(true, owner: owner) }
			.onDisappear { navigation?.setVisible(false, owner: owner) }
	}
}

extension View {
	/// Hide the app's tabs/side rail, not the conversation's composer or Back button.
	func mango9ConversationNavigationHidden(showsNavigationBar: Bool = true) -> some View {
		modifier(Mango9ConversationNavigationModifier(showsNavigationBar: showsNavigationBar))
	}

	func mango9ConversationsPanel(isConversationOpen: Bool, isLandscape: Bool) -> some View {
		modifier(Mango9ConversationsPanel(isConversationOpen: isConversationOpen, isLandscape: isLandscape))
	}
}

/// A navigation container draws its toolbar into the horizontal safe areas.
/// Clip only its decorative background: clipping the container itself trims
/// Back and menu buttons in landscape, even with a zero corner radius.
private struct Mango9ConversationsPanel: ViewModifier {
	let isConversationOpen: Bool
	let isLandscape: Bool
	func body(content: Content) -> some View {
		content
			.shadow(color: isConversationOpen || isLandscape ? .clear : .black.opacity(0.2), radius: 25)
			.background {
				Color(uiColor: .systemBackground)
					.roundedCorner(isConversationOpen || isLandscape ? 0 : 25, corners: [.topRight, .topLeft])
					.ignoresSafeArea(.container, edges: .bottom)
			}
	}
}

private struct Mango9InboxControlsModifier: ViewModifier {
 @Environment(\.mango9ConversationNavigation) private var navigation
 func body(content: Content) -> some View {
  if let navigation { Mango9InboxControlsContent(content: content, navigation: navigation) }
  else { content }
 }
}
private struct Mango9InboxControlsContent<Content: View>: View {
 let content: Content
 @ObservedObject var navigation: Mango9ConversationNavigation
 var body: some View { content.accessibilityHidden(navigation.hidesAppNavigation).allowsHitTesting(!navigation.hidesAppNavigation) }
}
extension View {
 func mango9InboxControls() -> some View { modifier(Mango9InboxControlsModifier()) }
}


/// All message notifications carry an account scope and one explicit channel.
/// Payload identifiers are navigation hints; each detail API still authorizes access.
struct Mango9MessagePushRoute {
	enum Destination {
		case sms(Mango9SMSTarget), team(Mango9ChatTarget), social(Mango9SocialPushTarget), live(Mango9LivePushTarget)
	}
	let identity: String
	let crmID: String
	let destination: Destination
	var tab: Mango9ConversationTab {
		switch destination { case .sms: return .sms; case .team: return .team; case .social: return .social; case .live: return .live }
	}
	init?(userInfo: [AnyHashable: Any], delegate: AppDelegate) {
		let data = Self.payload(userInfo)
		guard let crm = data["crm_id"] as? String, !crm.isEmpty,
			let identity = Mango9SessionStore.normalizedIdentity(data["sip_identity"] as? String) else { return nil }
		self.identity = identity; crmID = crm
		let canonical: [AnyHashable: Any] = ["mango9": data]
		switch data["event"] as? String {
		case "sms.received":
			guard let target = delegate.mango9SMSTarget(from: canonical) else { return nil }; destination = .sms(target)
		case "chat.message":
			guard let target = delegate.mango9ChatTarget(from: canonical) else { return nil }; destination = .team(target)
		case "social.message":
			guard let target = Mango9SocialPushTarget(userInfo: canonical) else { return nil }; destination = .social(target)
		case "livechat.message":
			guard let target = Mango9LivePushTarget(userInfo: canonical) else { return nil }; destination = .live(target)
		default: return nil
		}
	}
	static func payload(_ info: [AnyHashable: Any]) -> [String: Any] {
		var data = Dictionary(uniqueKeysWithValues: info.compactMap { key, value in (key as? String).map { ($0, value) } })
		if let nested = info["mango9"] as? [String: Any] { data.merge(nested) { _, nested in nested } }
		if data["event"] == nil { data["event"] = data["mango9_event"] }
		if data["crm_id"] == nil { data["crm_id"] = data["crmId"] }
		if data["sip_identity"] == nil { data["sip_identity"] = data["sipIdentity"] }
		return data
	}
	func matches(_ session: Mango9Session) -> Bool {
		session.crmId == crmID && Mango9SessionStore.normalizedIdentity(session.sipIdentity) == identity
	}
}

struct Mango9LivePushTarget: Equatable, Identifiable {
	let id: String
	let identity: String
	let crmID: String
	init?(userInfo: [AnyHashable: Any]) {
		let data = Mango9MessagePushRoute.payload(userInfo)
		guard data["event"] as? String == "livechat.message",
			let id = data["conversation_id"] as? String, UUID(uuidString: id) != nil,
			let crm = data["crm_id"] as? String, !crm.isEmpty,
			let identity = Mango9SessionStore.normalizedIdentity(data["sip_identity"] as? String) else { return nil }
		self.id = id.lowercased(); crmID = crm; self.identity = identity
	}
	func matches(_ session: Mango9Session) -> Bool {
		session.crmId == crmID && Mango9SessionStore.normalizedIdentity(session.sipIdentity) == identity
	}
}

/// Keep cold-start taps until ContentView subscribes. Only the newest tap may
/// finish account activation; consuming an older request cannot clear a newer one.
@MainActor final class Mango9MessagePushInbox: ObservableObject {
	static let shared = Mango9MessagePushInbox()
	struct Request: Identifiable { let id: UUID; let route: Mango9MessagePushRoute }
	@Published private(set) var pending: Request?
	private var latest = UUID()
	func begin() -> UUID { latest = UUID(); pending = nil; return latest }
	func publish(_ route: Mango9MessagePushRoute, request: UUID) {
		guard request == latest else { return }
		pending = Request(id: request, route: route)
	}
	func consume(_ id: UUID) { if pending?.id == id { pending = nil } }
}

struct Mango9LivePushConversation: View {
	let target: Mango9LivePushTarget
	let onClose: () -> Void
	@State private var conversation: Mango9LiveConversation?
	@State private var session: Mango9Session?
	@State private var error: String?
	@State private var retry = 0
	var body: some View {
		NavigationView {
			Group {
				if let conversation, let session { Mango9LiveChatConversation(conversation: conversation, session: session) }
				else if let error { VStack(spacing: 16) { Text(error); Button("Try again") { retry += 1 } }.padding() }
				else { ProgressView("Opening conversation…") }
			}
			.toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("Back", action: onClose).accessibilityIdentifier("push.conversation.back") } }
			.mango9ConversationNavigationHidden()
		}.navigationViewStyle(.stack)
			.accessibilityIdentifier("push.live." + target.id)
			.task(id: retry) {
				error = nil
				guard let active = Mango9SessionStore.load(), target.matches(active) else { error = "This account is no longer available."; return }
				do {
					let data = try await Mango9LiveChatAPI.shared.request("messages", session: active, query: [.init(name: "id", value: target.id)])
					guard let raw = data["conversation"] as? [String: Any], let result = Mango9LiveConversation(raw), result.id.lowercased() == target.id else { throw Mango9LiveChatFailure(status: 0) }
					guard !Task.isCancelled, Mango9SessionStore.isActive(active) else { return }
					session = active; conversation = result
				} catch { if !Task.isCancelled { self.error = Mango9LiveChatAPI.message(error) } }
			}
			.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in onClose() }
	}
}
