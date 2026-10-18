import XCTest
import SwiftUI
import Combine
@testable import LinphoneApp

final class Mango9ConversationNavigationTests: XCTestCase {
	func testNewTeamChatSelectsOnlyTeammateTargets() {
		let alex = Mango9ChatUser(id: 11, name: "Alex Example", avatar: "", category: "Staff")
		let sam = Mango9ChatUser(id: 12, name: "Sam Example", avatar: "", category: "Staff")
		let users = [alex, sam]
		XCTAssertEqual(Mango9TeamChatStart(selection: [11], users: users), .direct(alex))
		XCTAssertEqual(Mango9TeamChatStart(selection: [11, 12], users: users), .group([11, 12]))
		XCTAssertNil(Mango9TeamChatStart(selection: [], users: users))
		XCTAssertNil(Mango9TeamChatStart(selection: [99], users: users))
		XCTAssertNil(Mango9TeamChatStart(selection: [11, 99], users: users))
		XCTAssertNil(Mango9TeamChatStart(selection: [11], users: []), "A stale directory cannot select someone after an account switch")
	}

	@MainActor func testConversationPanelDoesNotCastShadowBelowComposer() async throws {
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		defer { window.isHidden = true; window.rootViewController = nil }
		for open in [false, true] {
			let host = UIHostingController(rootView: ConversationPanelSurfaceFixture(open: open))
			host.overrideUserInterfaceStyle = .light
			// Also exercise the home-indicator gap on older/home-button simulators.
			host.additionalSafeAreaInsets.bottom = 34
			window.rootViewController = host; window.makeKeyAndVisible()
			host.view.layoutIfNeeded()
			try await Task.sleep(nanoseconds: 500_000_000)
			let boundary = window.bounds.height - host.view.safeAreaInsets.bottom
			let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
				window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
			}
			let attachment = XCTAttachment(image: image)
			attachment.name = open ? "Chat panel safe-area surface" : "List panel shadow baseline"
			attachment.lifetime = .keepAlways; add(attachment)
			let brightness = try pixelBrightness(image, at: CGPoint(x: 30, y: boundary + 4))
			if open {
				XCTAssertGreaterThan(brightness, 0.99, "No panel shadow may bleed below the composer")
				XCTAssertGreaterThan(try pixelBrightness(image, at: CGPoint(x: 30, y: boundary + 24)), 0.99)
			} else {
				XCTAssertLessThan(brightness, 0.98, "Control: the list retains its original shadow")
			}
		}
	}

	private func pixelBrightness(_ image: UIImage, at point: CGPoint) throws -> Double {
		let cgImage = try XCTUnwrap(image.cgImage)
		let pixelImage = try XCTUnwrap(cgImage.cropping(to: CGRect(x: point.x * image.scale, y: point.y * image.scale, width: 1, height: 1)))
		var pixel = [UInt8](repeating: 0, count: 4)
		let context = try XCTUnwrap(CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
			space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
		context.draw(pixelImage, in: CGRect(x: 0, y: 0, width: 1, height: 1))
		return (Double(pixel[0]) + Double(pixel[1]) + Double(pixel[2])) / 765
	}

	func testConversationSnapshotsAreBoundedScopedAndExpire() {
		var cache = Mango9ConversationSnapshotCache<Int>()
		let now = Date(timeIntervalSince1970: 1000)
		cache.save(Array(0..<500), account: "account-A", room: "1", now: now)
		XCTAssertEqual(cache.messages(account: "account-A", room: "1", now: now), Array(200..<500))
		XCTAssertTrue(cache.messages(account: "account-B", room: "1", now: now).isEmpty)
		XCTAssertTrue(cache.messages(account: "account-A", room: "2", now: now).isEmpty)
		XCTAssertTrue(cache.messages(account: "account-A", room: "1", now: now.addingTimeInterval(300)).isEmpty)
		for index in 2...11 { cache.save([index], account: "account-A", room: String(index), now: now.addingTimeInterval(Double(index))) }
		XCTAssertTrue(cache.messages(account: "account-A", room: "1", now: now.addingTimeInterval(12)).isEmpty)
		cache.remove(account: "account-A", room: "11")
		XCTAssertTrue(cache.messages(account: "account-A", room: "11", now: now.addingTimeInterval(12)).isEmpty)
		cache.removeAll()
		XCTAssertTrue(cache.messages(account: "account-A", room: "10", now: now.addingTimeInterval(12)).isEmpty)
	}
	@MainActor func testNativeNavigationPushAndPopDriveVisibility() async {
		let navigation = Mango9ConversationNavigation()
		let route = ConversationNavigationFixtureRoute()
		let host = UIHostingController(rootView: ConversationNavigationFixture(route: route)
			.environment(\.mango9ConversationNavigation, navigation))
		let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
		let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: UIScreen.main.bounds)
		window.rootViewController = host
		window.makeKeyAndVisible()
		defer { window.isHidden = true; window.rootViewController = nil }
		host.view.layoutIfNeeded()
		try? await Task.sleep(nanoseconds: 800_000_000)
		for _ in 0..<3 {
			route.open = true
			await waitForVisibility(true, navigation: navigation)
			// onAppear precedes the native push animation completing.
			try? await Task.sleep(nanoseconds: 600_000_000)
			route.open = false
			await waitForVisibility(false, navigation: navigation)
			try? await Task.sleep(nanoseconds: 600_000_000)
		}
	}

	@MainActor private func waitForVisibility(_ hidden: Bool, navigation: Mango9ConversationNavigation) async {
		for _ in 0..<100 {
			if navigation.hidesAppNavigation == hidden { break }
			try? await Task.sleep(nanoseconds: 20_000_000)
		}
		XCTAssertEqual(navigation.hidesAppNavigation, hidden)
	}

	@MainActor func testRepeatedOpenBackAndInterruptedBackRestoreNavigation() {
		let navigation = Mango9ConversationNavigation()
		let owner = UUID()
		XCTAssertFalse(navigation.hidesAppNavigation)
		for _ in 0..<10 {
			navigation.setVisible(true, owner: owner)
			navigation.setVisible(true, owner: owner)
			XCTAssertTrue(navigation.hidesAppNavigation)
			navigation.setVisible(false, owner: owner)
			XCTAssertFalse(navigation.hidesAppNavigation)
		}
		// A cancelled interactive Back can reappear with the same identity.
		navigation.setVisible(true, owner: owner)
		XCTAssertTrue(navigation.hidesAppNavigation)
		navigation.setVisible(false, owner: owner)
		XCTAssertFalse(navigation.hidesAppNavigation)
	}

	@MainActor func testOldConversationDisappearingCannotRestoreBarOverNewConversation() {
		let navigation = Mango9ConversationNavigation()
		let team = UUID(), social = UUID()
		navigation.setVisible(true, owner: team)
		navigation.setVisible(true, owner: social)
		navigation.setVisible(false, owner: team)
		navigation.setVisible(false, owner: team)
		XCTAssertTrue(navigation.hidesAppNavigation)
		navigation.setVisible(false, owner: social)
		XCTAssertFalse(navigation.hidesAppNavigation)
	}

	@MainActor func testSeparateRootsDoNotShareVisibility() {
		let first = Mango9ConversationNavigation(), second = Mango9ConversationNavigation()
		first.setVisible(true, owner: UUID())
		XCTAssertTrue(first.hidesAppNavigation)
		XCTAssertFalse(second.hidesAppNavigation)
	}
}

private struct ConversationPanelSurfaceFixture: View {
	let open: Bool
	@State private var text = ""
	@FocusState private var focus: Bool
	var body: some View {
		VStack(spacing: 0) {
			Text("Conversations").frame(maxWidth: .infinity).frame(height: 60).background(Color.orangeMain500)
			Color(uiColor: .systemGroupedBackground)
				.safeAreaInset(edge: .bottom, spacing: 0) {
					Mango9MessageComposer(text: $text, focus: $focus, send: {})
				}
				.mango9ConversationsPanel(isConversationOpen: open, isLandscape: false)
		}.background(Color(uiColor: .white).ignoresSafeArea())
	}
}

@MainActor private final class ConversationNavigationFixtureRoute: ObservableObject {
	@Published var open = false
}

private struct ConversationNavigationFixture: View {
	@ObservedObject var route: ConversationNavigationFixtureRoute
	var body: some View {
		NavigationView {
			List {
			NavigationLink(isActive: $route.open) {
				VStack {
					Text("Messages")
					Spacer()
					TextField("Message", text: .constant(""))
				}.mango9ConversationNavigationHidden()
			} label: { Text("Conversation") }
			}
		}.navigationViewStyle(.stack)
	}
}
