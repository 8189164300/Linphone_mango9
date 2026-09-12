import XCTest
import SwiftUI
@testable import LinphoneApp

final class Mango9ChatPushNavigationTests: XCTestCase {
	private let identity = "sip:700@tenant.example.com"

	private func room(
		_ id: String,
		direct: Bool = true,
		userIds: [Int] = [42],
		latest: String = ""
	) -> Mango9ChatRoom {
		Mango9ChatRoom(
			id: id,
			userIds: userIds,
			latest: latest,
			lastMessage: "Test message",
			unread: 1,
			isDirect: direct
		)
	}

	func testRoomsSortNewestFirstAfterLiveUpdates() {
		let older = room("12", latest: "2026-09-06T12:00:00.000Z")
		let newest = room("91", latest: "2026-09-06T12:01:00.000Z")
		XCTAssertEqual(
			Mango9TeamChatOrdering.roomsByRecency([older, newest]).map(\.id),
			["91", "12"]
		)
	}

	func testPeopleWithConversationsSortByRecencyBeforeDirectoryOnlyUsers() {
		let alphabeticalOnly = Mango9ChatUser(id: 1, name: "Aaron", avatar: "", category: "")
		let older = Mango9ChatUser(id: 2, name: "Beth", avatar: "", category: "")
		let newest = Mango9ChatUser(id: 3, name: "Zoe", avatar: "", category: "")
		let ordered = Mango9TeamChatOrdering.usersByRecency(
			[alphabeticalOnly, older, newest],
			roomPreviews: [
				older.id: room("12", userIds: [older.id], latest: "2026-09-06T12:00:00.000Z"),
				newest.id: room("91", userIds: [newest.id], latest: "2026-09-06T12:01:00.000Z"),
			]
		)
		XCTAssertEqual(ordered.map(\.id), [newest.id, older.id, alphabeticalOnly.id])
	}

	func testPushAlwaysResolvesExactRoomNotSenderConversation() throws {
		let target = Mango9ChatTarget(userId: 42, name: "Teammate", roomId: "91")
		let notifiedRoom = room("91", direct: false)
		XCTAssertEqual(try target.resolve(rooms: [room("12"), notifiedRoom], users: []), .room(notifiedRoom))
	}

	func testColdDirectoryDoesNotFallBackToCreatingSenderChat() {
		let target = Mango9ChatTarget(userId: 42, name: "Teammate", roomId: "91")
		XCTAssertThrowsError(try target.resolve(rooms: [], users: []))
		XCTAssertThrowsError(try target.resolve(rooms: [room("12")], users: []))
	}

	func testDirectoryArrivalResolvesSamePushAfterRetry() throws {
		let target = Mango9ChatTarget(userId: 42, name: "Teammate", roomId: "91")
		XCTAssertThrowsError(try target.resolve(rooms: [], users: []))
		XCTAssertEqual(try target.resolve(rooms: [room("91")], users: []), .room(room("91")))
	}

	func testRoomOnlyPushAndExistingContactNavigationRemainSupported() throws {
		XCTAssertEqual(
			try Mango9ChatTarget(userId: 0, name: "Group", roomId: "91").resolve(rooms: [room("91")], users: []),
			.room(room("91"))
		)
		XCTAssertEqual(
			try Mango9ChatTarget(userId: 42, name: "Teammate").resolve(rooms: [], users: []),
			.user(Mango9ChatUser(id: 42, name: "Teammate", avatar: "", category: ""))
		)
	}

	func testOutgoingScreenCannotCloseReplacementEvenForSameRoom() {
		var state = Mango9ChatOpenState()
		let outgoingOwner = UUID()
		let old = state.begin(owner: outgoingOwner, identity: identity)
		let current = state.begin(owner: UUID(), identity: identity)
		XCTAssertFalse(state.cancel(owner: outgoingOwner))
		XCTAssertFalse(state.isCurrent(old, identity: identity))
		XCTAssertTrue(state.isCurrent(current, identity: identity))
	}

	func testRetryRejectsLateResultFromEarlierAttempt() {
		var state = Mango9ChatOpenState()
		let owner = UUID()
		let timedOut = state.begin(owner: owner, identity: identity)
		let retry = state.begin(owner: owner, identity: identity)
		XCTAssertFalse(state.isCurrent(timedOut, identity: identity))
		XCTAssertTrue(state.isCurrent(retry, identity: identity))
		XCTAssertTrue(state.cancel(owner: owner))
		XCTAssertFalse(state.isCurrent(retry, identity: identity))
	}

	func testAccountSwitchRejectsPendingHistory() {
		var state = Mango9ChatOpenState()
		let request = state.begin(owner: UUID(), identity: identity)
		XCTAssertFalse(state.isCurrent(request, identity: "sip:700@other.example.com"))
		XCTAssertFalse(state.isCurrent(request, identity: nil))
		XCTAssertTrue(state.isCurrent(request, identity: identity))
	}

	@MainActor
	func testChatPushAcceptsStringAndNumericRoomIDsWithoutChangingOtherPushTypes() {
		let delegate = AppDelegate()
		for roomID: Any in ["91", 91] {
			let target = delegate.mango9ChatTarget(from: ["mango9": [
				"event": "chat.message", "sender_user_id": 42, "room_id": roomID, "name": "Teammate"
			]])
			XCTAssertEqual(target, Mango9ChatTarget(userId: 42, name: "Teammate", roomId: "91"))
		}
		XCTAssertNil(delegate.mango9ChatTarget(from: ["mango9": ["event": "sms.message", "room_id": "91"]]))
		XCTAssertNil(delegate.mango9ChatTarget(from: ["CallId": "call-only"]))
		XCTAssertNil(delegate.mango9ChatTarget(from: ["mango9": ["event": "chat.message"]]))
	}
}

// Exercise the real conversation builder, not just SMS transport helpers. The
// device crash was in generic View metadata construction before messages rendered.
final class Mango9ConversationRenderingTests: XCTestCase {
	@MainActor
	func testSMSConversationRepeatedPresentationAndLiveMessageUpdates() async throws {
#if targetEnvironment(simulator)
		let shared = SharedMainViewModel.shared
		let previousSMS = shared.displayedSMS
		let previousConversation = shared.displayedConversation
		let previousDraft = shared.pendingSMSComposerText
		shared.displayedSMS = Mango9SMSTarget(phone: "15555550123", name: "SMS render test")
		shared.displayedConversation = nil
		defer {
			shared.displayedSMS = previousSMS
			shared.displayedConversation = previousConversation
			shared.pendingSMSComposerText = previousDraft
		}
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		defer { window.isHidden = true; window.rootViewController = nil }
		let listModel = ConversationsListViewModel()
		let profile = AccountProfileViewModel()
		let navigation = NavigationManager()
		for iteration in 0..<8 {
			let model = ConversationViewModel(startAutomatically: false)
			XCTAssertTrue(model.isSMSConversation)
			// Drain the initial Combine subscriptions before injecting offline rows.
			try await Task.sleep(nanoseconds: 30_000_000)
			let view = screen(model: model)
				.environmentObject(listModel)
				.environmentObject(profile)
				.environmentObject(navigation)
			window.rootViewController = UIHostingController(rootView: view)
			window.makeKeyAndVisible()
			try await Task.sleep(nanoseconds: 50_000_000)
			window.layoutIfNeeded()
			var rows: [EventLogMessage] = []
			for index in 0..<12 {
				let message = Message(id: "render-\(iteration)-\(index)", status: index.isMultiple(of: 3) ? .sent : .received,
					createdAt: Date(timeIntervalSince1970: 1_789_000_000 + Double(index)),
					isOutgoing: index.isMultiple(of: 2), isEditable: false, isRetractable: true,
					isEdited: false, isRetracted: false, dateReceived: 1_789_000_000,
					address: "15555550123", isFirstMessage: true, text: "Offline SMS render fixture \(index)")
				rows.insert(EventLogMessage(eventModel: EventModel(carrierMessageId: message.id), message: message), at: 0)
				model.conversationMessagesSection = [MessagesSection(date: .init(timeIntervalSince1970: 1_789_000_000), chatRoomID: "sms:15555550123", rows: rows)]
				model.displayedConversationHistorySize = rows.count
				try await Task.sleep(nanoseconds: 30_000_000)
				window.layoutIfNeeded()
			}
			// Let the existing UITableView insertion animations settle before capture.
			try await Task.sleep(nanoseconds: 400_000_000)
			let table = try XCTUnwrap(firstTable(in: window))
			XCTAssertEqual(table.numberOfRows(inSection: 0), rows.count)
			if iteration == 0 { capture(window, name: "SMS incoming and outgoing messages") }
			var selected = try XCTUnwrap(iteration.isMultiple(of: 2) ? rows.first : rows.first(where: { $0.message.isOutgoing }))
			if selected.message.isOutgoing { selected.message.status = .error }
			model.selectedMessage = selected
			try await Task.sleep(nanoseconds: 60_000_000)
			window.layoutIfNeeded()
			if iteration == 0 { capture(window, name: "SMS long press actions") }
			if iteration == 1 { capture(window, name: "SMS failed outgoing message actions") }
			model.selectedMessage = nil
			model.messageToReply = rows.last
			model.mediasToSend = [Attachment(id: "document-fixture", name: "Example.pdf", url: URL(fileURLWithPath: "/nonexistent/example.pdf"), type: .pdf)]
			try await Task.sleep(nanoseconds: 60_000_000)
			window.layoutIfNeeded()
			if iteration == 0 { capture(window, name: "SMS reply and attachment composer") }
			model.messageToReply = nil
			model.mediasToSend = []
			window.rootViewController = nil
		}
#else
		throw XCTSkip("Offline fixture test is simulator-only; never alter a real phone's account state.")
#endif
	}

	@MainActor
	private func firstTable(in view: UIView) -> UITableView? {
		if let table = view as? UITableView { return table }
		for child in view.subviews {
			if let table = firstTable(in: child) { return table }
		}
		return nil
	}

	@MainActor
	private func screen(model: ConversationViewModel) -> ConversationFragment {
		ConversationFragment(conversationViewModel: model,
			isShowConversationFragment: .constant(true), isShowStartCallGroupPopup: .constant(false),
			isShowDeleteMessagePopup: .constant(false), isShowEditContactFragment: .constant(false),
			isShowEditContactFragmentAddress: .constant(""), isShowScheduleMeetingFragment: .constant(false),
			isShowScheduleMeetingFragmentSubject: .constant(""), isShowScheduleMeetingFragmentParticipants: .constant([]),
			isShowConversationInfoPopup: .constant(false), conversationInfoPopupText: .constant(""),
			isShowRemoveParticipantPopup: .constant(false), showLeaveConversationPopup: .constant(false),
			showDeleteConversationPopup: .constant(false), showDeleteConversationHistoryPopup: .constant(false))
	}

	@MainActor
	private func capture(_ window: UIWindow, name: String) {
		let image = UIGraphicsImageRenderer(size: window.bounds.size).image { _ in
			XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
		}
		let attachment = XCTAttachment(image: image)
		attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
	}
}
