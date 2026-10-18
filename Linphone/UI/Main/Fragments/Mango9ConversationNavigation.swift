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
		if tab != .social {
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

	func body(content: Content) -> some View {
		content
			.navigationBarHidden(false)
			.onAppear { navigation?.setVisible(true, owner: owner) }
			.onDisappear { navigation?.setVisible(false, owner: owner) }
	}
}

extension View {
	/// Hide the app's tabs/side rail, not the conversation's composer or Back button.
	func mango9ConversationNavigationHidden() -> some View {
		modifier(Mango9ConversationNavigationModifier())
	}

	func mango9ConversationsPanel(isConversationOpen: Bool, isLandscape: Bool) -> some View {
		modifier(Mango9ConversationsPanel(isConversationOpen: isConversationOpen, isLandscape: isLandscape))
	}
}

/// Team and Social are pushed inside the rounded Conversations panel. Its clip
/// cuts off a child's safe-area background, and its list shadow used to bleed
/// below the composer. Fill the home-indicator area *outside* that clip, without
/// extending the controls or disabling keyboard avoidance. Keep the list style.
private struct Mango9ConversationsPanel: ViewModifier {
	let isConversationOpen: Bool
	let isLandscape: Bool
	func body(content: Content) -> some View {
		content
			.roundedCorner(25, corners: [.topRight, .topLeft])
			.shadow(color: isConversationOpen || isLandscape ? .clear : .black.opacity(0.2), radius: 25)
			.background {
				if isConversationOpen {
					Color(uiColor: .systemBackground)
						.roundedCorner(25, corners: [.topRight, .topLeft])
						.ignoresSafeArea(.container, edges: .bottom)
				}
			}
	}
}
