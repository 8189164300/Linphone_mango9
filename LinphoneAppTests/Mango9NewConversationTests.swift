import Combine
import SwiftUI
import XCTest
@testable import LinphoneApp

@MainActor final class Mango9NewConversationTests: XCTestCase {
	func testRapidTypingSearchesOnlyLatestQueryAndCanClear() async throws {
		var queries: [String] = []
		let typed = expectation(description: "Latest typed query")
		let cleared = expectation(description: "Cleared query")
		let search = Mango9ConversationContactSearch(delay: 30_000_000) {
			queries.append($0)
			if queries.count == 1 { typed.fulfill() } else { cleared.fulfill() }
		}
		for length in 1...20 { search.update(String(repeating: "a", count: length)) }
		await fulfillment(of: [typed], timeout: 5)
		XCTAssertEqual(queries, [String(repeating: "a", count: 20)])
		search.update("+1 (202) 555-0142")
		search.update("")
		await fulfillment(of: [cleared], timeout: 5)
		XCTAssertEqual(queries.last, "")
		XCTAssertEqual(queries.count, 2)
	}

	func testDismissAndImmediateSearchCancelDelayedWork() async throws {
		var queries: [String] = []
		var search: Mango9ConversationContactSearch? = Mango9ConversationContactSearch(delay: 30_000_000) { queries.append($0) }
		search?.update("stale")
		search?.cancel()
		try await Task.sleep(nanoseconds: 80_000_000)
		XCTAssertTrue(queries.isEmpty)
		search?.update("stale again")
		search?.update("sip:fixture@example.invalid", immediately: true)
		try await Task.sleep(nanoseconds: 80_000_000)
		XCTAssertEqual(queries, ["sip:fixture@example.invalid"])
		search?.update("discard on dismissal")
		search = nil
		try await Task.sleep(nanoseconds: 80_000_000)
		XCTAssertEqual(queries.count, 1)
	}

	func testRecipientUsesTappedContactAndKeepsMultipleDestinations() {
		let contact = ContactAvatarModel(friend: nil, name: "Same name", address: "", withPresence: false)
		XCTAssertTrue(StartConversationFragment.recipientDestinations(contact).isEmpty)
		contact.phoneNumbersWithLabel = [(label: "mobile", phoneNumber: "+12025550142")]
		XCTAssertEqual(StartConversationFragment.recipientDestinations(contact), ["+12025550142"])
		contact.addresses = ["sip:fixture@example.invalid"]
		contact.phoneNumbersWithLabel.append((label: "work", phoneNumber: "+12025550143"))
		XCTAssertEqual(StartConversationFragment.recipientDestinations(contact),
			["sip:fixture@example.invalid", "+12025550142", "+12025550143"])
		let namesake = ContactAvatarModel(friend: nil, name: "Same name", address: "", withPresence: false)
		namesake.phoneNumbersWithLabel = [(label: "mobile", phoneNumber: "+12025550199")]
		XCTAssertEqual(StartConversationFragment.recipientDestinations(namesake), ["+12025550199"])
	}

	func testLargeRowSnapshotKeepsIdentityAndLazyAlphabetHeadings() {
		var contacts = (0..<50_000).map {
			ContactAvatarModel(friend: nil, name: String(format: "Person %05d", $0), address: "", withPresence: false)
		}
		let rows = ContactsListFragment.rows(contacts)
		XCTAssertEqual(rows.count, 50_000)
		XCTAssertEqual(rows[49_999].id, contacts[49_999].id)
		XCTAssertEqual(rows[0].heading, "P")
		XCTAssertEqual(rows[49_999].heading, "")
		XCTAssertEqual(ContactsListFragment.rows(contacts, limit: 100).count, 100)
		XCTAssertEqual(ContactsListFragment.rows(contacts, limit: -1).count, 0)
		contacts.removeAll()
		XCTAssertEqual(rows[49_999].contact.name, "Person 49999")
		let names = ["", "Alice", "anne", "Émile", "émma", "Ζωή"]
		let alphabetical = ContactsListFragment.rows(names.map { ContactAvatarModel(friend: nil, name: $0, address: "", withPresence: false) })
		XCTAssertEqual(alphabetical.map(\.heading), ["#", "A", "", "E", "", "Ζ"])
	}

	func testPagingReachesAllFiftyThousandContactsWithoutDuplicateGrowth() {
		var page = Mango9ConversationContactPage()
		XCTAssertEqual(page.limit, 100)
		page.reveal(near: 0, total: 50_000)
		XCTAssertEqual(page.limit, 100)
		for expected in stride(from: 200, through: 50_000, by: 100) {
			let lastIndex = page.limit - 1
			for index in (lastIndex - 9)...lastIndex { page.reveal(near: index, total: 50_000) }
			XCTAssertEqual(page.limit, expected, "A cluster of appearing rows must only load one next page")
		}
		page.reveal(near: 49_999, total: 50_000)
		XCTAssertEqual(page.limit, 50_000)
		page.reset()
		XCTAssertEqual(page.limit, 100, "New search/import must start with a bounded viewport")
		page.reveal(near: 99, total: 125)
		XCTAssertEqual(page.limit, 125)
	}

	func testNewConversationRecyclesTwentyThousandContactsAndSurvivesRefresh() async throws {
		let manager = ContactsManager.shared
		let original = manager.avatarListModel
		let originalFilter = MagicSearchSingleton.shared.currentFilter
		let contacts = (0..<20_000).map {
			ContactAvatarModel(friend: nil, name: String(format: "Contact %05d", $0), address: "", withPresence: false)
		}
		manager.avatarListModel = contacts
		MagicSearchSingleton.shared.currentFilter = ""
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		defer {
			window.isHidden = true; window.rootViewController = nil
			manager.avatarListModel = original
			MagicSearchSingleton.shared.currentFilter = originalFilter
		}
		window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
		let began = CACurrentMediaTime()
		window.rootViewController = UIHostingController(rootView:
			StartConversationFragment(isShowStartConversationFragment: .constant(true))
				.environmentObject(ContactsListViewModel()).environmentObject(ConversationsListViewModel()))
		window.makeKeyAndVisible()
		try await Task.sleep(nanoseconds: 500_000_000)
		window.layoutIfNeeded()
		let collection = try XCTUnwrap(descendants(window).compactMap { $0 as? UICollectionView }.max {
			itemCount($0) < itemCount($1)
		})
		XCTAssertGreaterThanOrEqual(itemCount(collection), 100)
		XCTAssertLessThan(itemCount(collection), 200, "Initial identity/layout work must also be bounded")
		XCTAssertEqual(manager.avatarListModel.count, 20_000, "Paging must not truncate the shared search directory")
		XCTAssertGreaterThan(collection.visibleCells.count, 0)
		XCTAssertLessThan(collection.visibleCells.count, 80, "Only the viewport should have cells, not the full address book")
		let elapsed = CACurrentMediaTime() - began
		print("[NewConversationFixture] 20000 contacts: first layout \(elapsed)s; \(collection.visibleCells.count) visible cells")
		XCTAssertLessThan(elapsed, 8, "Guard against rebuilding thousands of views before the first frame")
		let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
		let attachment = XCTAttachment(image: image)
		attachment.name = "New conversation with 20,000 synthetic contacts"; attachment.lifetime = .keepAlways; add(attachment)
		for _ in 0..<3 {
			manager.avatarListModel = Array(contacts.prefix(2))
			try await Task.sleep(nanoseconds: 100_000_000)
			manager.avatarListModel = contacts
			try await Task.sleep(nanoseconds: 100_000_000)
		}
		for _ in 0..<3 {
			let before = itemCount(collection)
			let section = try XCTUnwrap((0..<collection.numberOfSections).last(where: { collection.numberOfItems(inSection: $0) > 0 }))
			collection.scrollToItem(at: IndexPath(item: collection.numberOfItems(inSection: section) - 1, section: section), at: .bottom, animated: false)
			try await Task.sleep(nanoseconds: 400_000_000)
			XCTAssertGreaterThan(itemCount(collection), before, "Scrolling must reveal the next page automatically")
		}
		XCTAssertLessThan(collection.visibleCells.count, 80)
		XCTAssertEqual(manager.avatarListModel.last?.id, contacts.last?.id)
	}

	private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
	private func itemCount(_ view: UICollectionView) -> Int { (0..<view.numberOfSections).reduce(0) { $0 + view.numberOfItems(inSection: $1) } }
}
