import XCTest
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
