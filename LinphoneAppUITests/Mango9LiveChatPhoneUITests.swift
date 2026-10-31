import XCTest

/// Physical-phone checks. Mutations target only the named temporary internal fixture; no visitor messages are sent.
final class Mango9LiveChatPhoneUITests: XCTestCase {
	func testLatestArrowInTeamChat() throws { try checkLatestArrow(channel: "team") }
	func testLatestArrowInSocialChat() throws { try checkLatestArrow(channel: "social") }
	func testLatestArrowInLiveChat() throws { try checkLatestArrow(channel: "live") }
	private func checkLatestArrow(channel: String) throws {
		continueAfterFailure = false
		defer { XCUIDevice.shared.orientation = .portrait }
		XCUIDevice.shared.orientation = .portrait
		let app = XCUIApplication(); app.launch()
		let conversations = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
		let tab = app.buttons["conversations.tab." + channel]
		XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
		let prefix = channel == "live" ? "livechat.thread." : channel + ".thread."
		let rows = app.descendants(matching: .any).matching(NSPredicate(format:
			"identifier BEGINSWITH %@ AND NOT (identifier CONTAINS %@)", prefix, "team.thread.user."))
		guard rows.firstMatch.waitForExistence(timeout: 25) else { throw XCTSkip("No existing " + channel + " conversation; do not create one.") }
		rows.firstMatch.tap()
		let scroll = app.scrollViews[channel + ".messages"].firstMatch
		XCTAssertTrue(scroll.waitForExistence(timeout: 20))
		let composer = app.descendants(matching: .any)["chat.composer"].firstMatch
		XCTAssertTrue(composer.waitForExistence(timeout: 20))
		let arrow = app.buttons["chat.latest"]
		let initiallyHidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !arrow.exists }, object: nil)
		XCTAssertEqual(XCTWaiter.wait(for: [initiallyHidden], timeout: 5), .completed)
		for _ in 0..<3 { if arrow.exists { break }; scroll.swipeDown() }
		if !arrow.exists {
			// A short real conversation may fit in portrait. Use the smaller
			// landscape viewport to exercise the same control without sending data.
			XCUIDevice.shared.orientation = .landscapeLeft
			let rotated = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.width > app.frame.height }, object: nil)
			XCTAssertEqual(XCTWaiter.wait(for: [rotated], timeout: 10), .completed)
			for _ in 0..<3 { if arrow.exists { break }; scroll.swipeDown() }
		}
		if arrow.exists {
			XCTAssertTrue(arrow.isHittable)
			XCTAssertEqual(arrow.frame.width, 60, accuracy: 1)
			capture(app, channel + " latest arrow above composer")
			for _ in 0..<15 { if !arrow.exists { break }; scroll.swipeUp() }
			XCTAssertFalse(arrow.exists, "Manual return to latest must clear the arrow")
			scroll.swipeDown(); XCTAssertTrue(arrow.waitForExistence(timeout: 5)); arrow.tap()
			let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !arrow.exists }, object: nil)
			XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed)
		} else { print("LATEST_ARROW_SHORT_HISTORY " + channel + ": all messages fit; arrow correctly hidden") }
		for _ in 0..<3 { scroll.swipeUp(); XCTAssertFalse(arrow.exists, "Bottom bounce must keep the arrow hidden") }
		XCTAssertTrue(composer.isHittable)
		capture(app, channel + " latest messages without arrow")
	}
	func testLiveInboxDoesNotShowEmptyBeforeExistingConversationsLoad() {
		continueAfterFailure = false
		XCUIDevice.shared.orientation = .portrait
		let app = XCUIApplication(); app.terminate(); app.launch()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
		let live = app.buttons["conversations.tab.live"]
		XCTAssertTrue(live.waitForExistence(timeout: 10)); live.tap()
		let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.thread.")).firstMatch
		let deadline = Date().addingTimeInterval(25)
		while !row.exists && Date() < deadline {
			XCTAssertFalse(app.staticTexts["livechat.empty"].exists, "An existing inbox must not claim it is empty while loading")
			RunLoop.current.run(until: Date().addingTimeInterval(0.1))
		}
		XCTAssertTrue(row.exists)
		XCTAssertFalse(app.staticTexts["livechat.empty"].exists)
		capture(app, "Live inbox after first server response")
	}
	func testSocialConversationRemainsAvailableAcrossProviderRefresh() throws {
		continueAfterFailure = false
		XCUIDevice.shared.orientation = .portrait
		let app = XCUIApplication(); app.terminate(); app.launch()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
		let social = app.buttons["conversations.tab.social"]
		XCTAssertTrue(social.waitForExistence(timeout: 10)); social.tap()
		let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND NOT (label CONTAINS[c] %@)", "social.thread.", "test")).firstMatch
		guard row.waitForExistence(timeout: 25) else { throw XCTSkip("No existing non-test Social conversation on this phone") }
		row.tap()
		let composer = app.descendants(matching: .any)["chat.composer"].firstMatch
		XCTAssertTrue(composer.waitForExistence(timeout: 20))
		// Allow the real provider refresh at open and the next minute's refresh;
		// this check reads existing history and never sends a customer reply.
		for _ in 0..<15 { RunLoop.current.run(until: Date().addingTimeInterval(5)) }
		// Warnings live above the history in a lazy stack. Bring that area into
		// view so an offscreen warning cannot make this assertion pass falsely.
		let platform = app.staticTexts.matching(NSPredicate(format: "label IN %@", ["Instagram", "Facebook", "TikTok"])).firstMatch
		for _ in 0..<12 where !platform.isHittable { app.scrollViews.firstMatch.swipeDown() }
		XCTAssertTrue(platform.isHittable, "The refresh status area must be visible")
		capture(app, "Social history after automatic provider refresh")
		XCTAssertTrue(composer.exists)
		XCTAssertFalse(app.buttons["Retry"].exists, "The existing Social account should refresh without a provider warning")
		app.terminate(); app.launch()
	}
	func testLiveNotificationColdStartOpensExactConversation() throws { try verifyMessageNotification(channel: "live", cold: true) }
	func testLiveNotificationWarmStartOpensExactConversation() throws { try verifyMessageNotification(channel: "live", cold: false) }
	func testSocialNotificationOpensExactConversation() throws { try verifyMessageNotification(channel: "social", cold: false) }
	func testSMSNotificationOpensExactConversation() throws { try verifyMessageNotification(channel: "sms", cold: false) }
	func testTeamNotificationOpensExactConversation() throws { try verifyMessageNotification(channel: "team", cold: false) }
	private func verifyMessageNotification(channel: String, cold: Bool) throws {
		continueAfterFailure = false
		XCUIDevice.shared.orientation = .portrait
		let app = XCUIApplication(); app.launch()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
		let tab = app.buttons["conversations.tab." + channel]
		XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
		let prefix = channel == "live" ? "livechat.thread." : channel + ".thread."
		let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND NOT (identifier CONTAINS %@)", prefix, "team.thread.user."))
		let row = rows.firstMatch
		guard row.waitForExistence(timeout: 20) else { throw XCTSkip("No existing " + channel + " conversation on this phone") }
		let id = String(row.identifier.dropFirst(prefix.count))
		app.terminate()
		app.launchEnvironment["UITEST_MESSAGE_NOTIFICATION_CHANNEL"] = channel
		app.launchEnvironment["UITEST_MESSAGE_NOTIFICATION_TARGET"] = id
		app.launch()
		XCTAssertTrue(app.buttons["Conversations"].firstMatch.waitForExistence(timeout: 20))
		let status = app.staticTexts["push.check.status"]
		XCTAssertTrue(status.waitForExistence(timeout: 10))
		let ready = NSPredicate(format: "label == %@", "Scheduled")
		XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: ready, object: status)], timeout: 10), .completed, status.label)
		XCUIDevice.shared.press(.home)
		if cold { app.terminate() }
		let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
		// iOS can hide the title/body according to the user's preview preference.
		let notification = springboard.descendants(matching: .any).matching(NSPredicate(format:
			"identifier == %@ AND label CONTAINS[c] %@", "NotificationShortLookView", "Mango9")).firstMatch
		if !notification.waitForExistence(timeout: 12) {
			let hierarchy = XCTAttachment(string: springboard.debugDescription)
			hierarchy.name = "Notification accessibility"; hierarchy.lifetime = .keepAlways; add(hierarchy)
			capture(springboard, "Notification delivery")
		}
		XCTAssertTrue(notification.exists, "The Mango9 iOS notification must appear")
		notification.tap()
		XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
		let composer = app.descendants(matching: .any)["chat.composer"].firstMatch
		XCTAssertTrue(composer.waitForExistence(timeout: 25), "Tap must open the conversation, not just its list")
		capture(app, "Notification opens " + channel + (cold ? " cold" : " warm"))
		if channel == "live" {
			XCTAssertTrue(app.descendants(matching: .any)["push.live." + id].firstMatch.exists)
			app.buttons["push.conversation.back"].firstMatch.tap()
		} else if channel == "sms" { app.images["caret-left"].firstMatch.tap() }
		else {
			let back = app.navigationBars.buttons["BackButton"].firstMatch
			if back.exists { back.tap() } else { app.buttons["Back"].firstMatch.tap() }
		}
		XCTAssertTrue(tab.waitForExistence(timeout: 10)); XCTAssertTrue(tab.isSelected, "Back must reveal the notified channel")
		capture(app, "Notification returns to " + channel)
		app.terminate(); app.launchEnvironment = [:]; app.launch()
	}

	func testChatLabelsRemainVisibleAfterReturningFromEveryChannel() {
		continueAfterFailure = false
		XCUIDevice.shared.orientation = .portrait
		let app = XCUIApplication(); app.terminate(); app.launch()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
		let channels = ["live", "social", "team", "sms"]
		let menu = app.buttons["main.menu"]
		for attempt in 0..<2 {
			for channel in channels {
				let tab = app.buttons["conversations.tab." + channel]
				XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
				XCTAssertTrue(tab.isSelected, "A visible tab must actually change the selected channel, not be covered by a returning navigation bar")
				let baseline = tab.frame
				let prefix = channel == "live" ? "livechat.thread." : channel + ".thread."
				let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
				XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
				let composer = app.descendants(matching: .any).matching(identifier: "chat.composer").firstMatch
				XCTAssertTrue(composer.waitForExistence(timeout: 15))
				if attempt == 1 && composer.isEnabled {
					composer.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
				}
				if channel == "sms" {
					app.images["caret-left"].firstMatch.tap()
				} else if attempt == 1 {
					app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.45))
						.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.45)))
				} else {
					let back = app.navigationBars.buttons["BackButton"].firstMatch
					if back.exists { back.tap() } else { app.buttons["Back"].firstMatch.tap() }
				}
				for sample in 0..<3 {
					capture(app, "Chat labels after \(channel) return \(attempt) sample \(sample)")
					print("LABEL_RETURN \(channel) \(attempt) \(sample): menu=\(menu.frame) tab=\(tab.frame) baseline=\(baseline)")
					XCTAssertTrue(menu.isHittable)
					for name in channels {
						let label = app.buttons["conversations.tab." + name]
						XCTAssertTrue(label.isHittable, "Every chat tab must be visible and usable after returning from \(channel)")
						XCTAssertGreaterThanOrEqual(label.frame.minY, menu.frame.maxY)
						XCTAssertEqual(label.frame.minY, baseline.minY, accuracy: 1)
					}
					assertTabLabelsAreDrawn(app, channels: channels)
					RunLoop.current.run(until: Date().addingTimeInterval(0.25))
				}
			}
		}
	}

	private func assertTabLabelsAreDrawn(_ app: XCUIApplication, channels: [String]) {
		// A stale native toolbar can cover SwiftUI buttons while their accessibility
		// frames still look correct. Check the actual text/icon pixels as well.
		guard let screen = XCUIScreen.main.screenshot().image.cgImage else { XCTFail("Missing screen pixels"); return }
		let scale = CGFloat(screen.width) / app.frame.width
		for channel in channels {
			let frame = app.buttons["conversations.tab." + channel].frame.insetBy(dx: 2, dy: 7)
			let bounds = CGRect(x: frame.minX * scale, y: frame.minY * scale, width: frame.width * scale, height: frame.height * scale)
			guard let crop = screen.cropping(to: bounds) else { XCTFail("Tab is outside the screen"); continue }
			var pixels = [UInt8](repeating: 0, count: crop.width * crop.height * 4)
			let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
				guard let context = CGContext(data: bytes.baseAddress, width: crop.width, height: crop.height, bitsPerComponent: 8,
					bytesPerRow: crop.width * 4, space: CGColorSpaceCreateDeviceRGB(),
					bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
				context.draw(crop, in: CGRect(x: 0, y: 0, width: crop.width, height: crop.height)); return true
			}
			XCTAssertTrue(drawn)
			let ink = stride(from: 0, to: pixels.count, by: 4).filter { pixels[$0] < 180 && pixels[$0 + 1] < 180 }.count
			XCTAssertGreaterThan(Double(ink) / Double(crop.width * crop.height), 0.005, "\(channel) label must be drawn, not covered by a blank navigation area")
		}
	}

	func testReturningFromLiveChatKeepsHeaderSpacing() {
		continueAfterFailure = false
		XCUIDevice.shared.orientation = .portrait
		let app = XCUIApplication(); app.terminate(); app.launch()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
		let tab = app.buttons["conversations.tab.live"]
		XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
		let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.thread.")).firstMatch
		XCTAssertTrue(row.waitForExistence(timeout: 20))
		let menu = app.buttons["main.menu"], filter = app.buttons["livechat.filter.website"]
		let original = [menu.frame.minY, tab.frame.minY, filter.frame.minY]
		capture(app, "Live inbox before opening conversation")
		for attempt in 0..<3 {
			row.tap()
			XCTAssertTrue(app.buttons["Conversation actions"].waitForExistence(timeout: 15))
			app.navigationBars.buttons["BackButton"].firstMatch.tap()
			for sample in 0..<12 {
				let current = [menu.frame.minY, tab.frame.minY, filter.frame.minY]
				print("RETURN_GEOMETRY \(attempt) \(sample): \(current); expected \(original)")
				if sample == 0 || sample == 3 || sample == 11 { capture(app, "Return \(attempt) sample \(sample)") }
				for index in original.indices { XCTAssertEqual(current[index], original[index], accuracy: 1, "Returning must not leave a temporary header gap") }
				RunLoop.current.run(until: Date().addingTimeInterval(0.15))
			}
		}
	}

	func testSocialTabKeepsHeaderAndFiltersStableDuringRefresh() {
		continueAfterFailure = false
		let app = XCUIApplication(); app.terminate(); app.launch()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
		let social = app.buttons["conversations.tab.social"]
		XCTAssertTrue(social.waitForExistence(timeout: 10)); social.tap()
		let filter = app.buttons["social.filter.platform"]
		XCTAssertTrue(filter.waitForExistence(timeout: 10))
		let initial = filter.frame
		let initialTab = social.frame
		for _ in 0..<8 {
			RunLoop.current.run(until: Date().addingTimeInterval(0.4))
			XCTAssertEqual(filter.frame.minY, initial.minY, accuracy: 1, "Social must not move the filter/header while history loads")
			XCTAssertEqual(social.frame.minY, initialTab.minY, accuracy: 1)
		}
		RunLoop.current.run(until: Date().addingTimeInterval(21))
		XCTAssertEqual(filter.frame.minY, initial.minY, accuracy: 1, "A background refresh must retain the same layout")
		let sms = app.buttons["conversations.tab.sms"], live = app.buttons["conversations.tab.live"]
		XCTAssertLessThanOrEqual(sms.frame.minX, 16)
		XCTAssertLessThanOrEqual(app.frame.maxX - live.frame.maxX, 16)
		for tab in [sms, social, live, app.buttons["conversations.tab.team"]] { XCTAssertGreaterThanOrEqual(tab.frame.height, 44); XCTAssertGreaterThanOrEqual(tab.frame.width, 44) }
		capture(app, "Stable Social layout and balanced icon tabs on physical iPhone")
	}
	func testConversationSettingsAndLiveChatOnPhone() throws {
		continueAfterFailure = false
		let app = XCUIApplication(); app.terminate(); app.launch()
		let menu = app.buttons["main.menu"]
		XCTAssertTrue(menu.waitForExistence(timeout: 30), "Open Mango9 with an active account on the phone before running this smoke test.")
		menu.tap()
		let settings = app.descendants(matching: .any).matching(identifier: "main.settings").firstMatch
		XCTAssertTrue(settings.waitForExistence(timeout: 10)); settings.tap()
		let disclosure = app.descendants(matching: .any).matching(identifier: "settings.conversationsDisclosure").firstMatch
		for _ in 0..<4 where !disclosure.isHittable { app.swipeUp() }
		XCTAssertTrue(disclosure.waitForExistence(timeout: 10))
		if !app.switches["settings.chat.team"].exists { disclosure.tap() }
		for name in ["team", "social", "live"] {
			let toggle = app.switches["settings.chat." + name]
			for _ in 0..<3 where !toggle.isHittable { app.swipeUp() }
			XCTAssertTrue(toggle.waitForExistence(timeout: 5))
			let original = toggle.value as? String
			toggle.tap(); XCTAssertNotEqual(toggle.value as? String, original)
			toggle.tap(); XCTAssertEqual(toggle.value as? String, original, "Restore the user's setting")
		}
		capture(app, "Conversation tab switches on physical iPhone")
		let back = app.descendants(matching: .any).matching(identifier: "settings.back").firstMatch
		back.tap()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 10)); chats.tap()
		let social = app.buttons["Social"]
		XCTAssertTrue(social.waitForExistence(timeout: 10)); social.tap()
		XCTAssertTrue(app.buttons["social.filter.platform"].waitForExistence(timeout: 10))
		RunLoop.current.run(until: Date().addingTimeInterval(5))
		capture(app, "Social inbox after stable layout update")
		let live = app.buttons["Live Chat"]
		XCTAssertTrue(live.waitForExistence(timeout: 10)); live.tap()
		XCTAssertTrue(app.buttons["Set available"].waitForExistence(timeout: 15))
		XCTAssertFalse(app.buttons["conversations.compose"].exists)
		RunLoop.current.run(until: Date().addingTimeInterval(5))
		capture(app, "Live Chat inbox on physical iPhone")
		let conversation = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.thread.")).firstMatch
		if conversation.exists {
			conversation.tap()
			XCTAssertTrue(app.buttons["Conversation actions"].waitForExistence(timeout: 10))
			RunLoop.current.run(until: Date().addingTimeInterval(4))
			capture(app, "Live Chat conversation on physical iPhone")
			let compact = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.metadata.")).firstMatch
			XCTAssertTrue(compact.waitForExistence(timeout: 10)); XCTAssertLessThanOrEqual(compact.frame.height, 24)
			let copy = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.copy.")).firstMatch
			XCTAssertTrue(copy.waitForExistence(timeout: 5)); copy.tap(); XCTAssertEqual(copy.value as? String, "Copied")
			let select = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.select.")).firstMatch
			select.tap(); XCTAssertTrue(app.staticTexts["1 selected"].waitForExistence(timeout: 5)); app.buttons["Done"].tap()
			let info = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.details.")).firstMatch
			info.tap(); XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ==[c] %@", "Visit details")).firstMatch.waitForExistence(timeout: 5)); app.buttons["Done"].tap()
			app.buttons["Conversation actions"].tap(); app.buttons["Visitor details"].tap()
			XCTAssertTrue(app.staticTexts["Visitor-provided identity · Not verified"].waitForExistence(timeout: 5))
			capture(app, "Live Chat visitor details on physical iPhone")
			app.buttons["Done"].tap()
		}
	}
	func testCompactLiveHeaderAndReactionMenuOnPhone() throws {
		continueAfterFailure = false
		let app = XCUIApplication(); app.terminate(); app.launch()
		let chats = app.buttons["Conversations"].firstMatch
		XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
		let social = app.buttons["conversations.tab.social"]
		XCTAssertTrue(social.waitForExistence(timeout: 10)); social.tap()
		let socialFilter = app.buttons["social.filter.platform"]
		XCTAssertTrue(socialFilter.waitForExistence(timeout: 10)); let filterFrame = socialFilter.frame
		app.buttons["conversations.tab.live"].tap()
		let website = app.buttons["livechat.filter.website"], status = app.buttons["livechat.filter.status"]
		XCTAssertTrue(website.waitForExistence(timeout: 10)); XCTAssertTrue(status.exists)
		XCTAssertEqual(website.frame.minY, filterFrame.minY, accuracy: 1)
		XCTAssertEqual(website.frame.height, filterFrame.height, accuracy: 1)
		XCTAssertEqual(website.frame.minY, status.frame.minY, accuracy: 1)
		let assigned = app.buttons["Assigned to me"], unread = app.buttons["Unread"], presence = app.buttons["livechat.presence"]
		XCTAssertTrue(assigned.exists && unread.exists && presence.exists)
		XCTAssertLessThanOrEqual(presence.frame.maxY - website.frame.minY, 90)
		XCTAssertEqual(assigned.frame.midY, unread.frame.midY, accuracy: 1)
		XCTAssertEqual(assigned.frame.midY, presence.frame.midY, accuracy: 1)
		RunLoop.current.run(until: Date().addingTimeInterval(4))
		XCTAssertFalse(app.staticTexts["Open"].exists)
		let date = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.date.")).firstMatch
		XCTAssertTrue(date.exists)
		capture(app, "Compact Social-style Live Chat header and dated rows")
		let conversation = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "livechat.thread.", "Mango9 iOS reaction check")).firstMatch
		XCTAssertTrue(conversation.exists, "A temporary internal test conversation is required; never react to a real visitor during automation."); conversation.tap()
		let react = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.react.")).firstMatch
		XCTAssertTrue(react.waitForExistence(timeout: 15)); react.tap()
		XCTAssertTrue(app.buttons["Add 👍 reaction"].waitForExistence(timeout: 5))
		XCTAssertTrue(app.buttons["Add ❤️ reaction"].exists)
		capture(app, "Server-supported Live Chat reaction choices on physical iPhone")
		app.buttons["Add 👍 reaction"].tap()
		let confirmed = app.buttons["👍, 1 reactions, including yours"]
		XCTAssertTrue(confirmed.waitForExistence(timeout: 15))
		capture(app, "Confirmed reaction on internal test conversation")
		confirmed.tap()
		XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: confirmed)], timeout: 15), .completed)
		XCTAssertFalse(app.buttons["Retry saved reaction"].exists)
		capture(app, "Confirmed reaction removal on internal test conversation")
	}
 func testAllChatPortraitAndLandscapeOnPhone() throws {
  continueAfterFailure = false
  defer { XCUIDevice.shared.orientation = .portrait }
  let app = XCUIApplication()
  for channel in ["sms", "team", "social", "live"] {
   XCUIDevice.shared.orientation = .portrait
   app.terminate(); app.launch()
   let chats = app.buttons["Conversations"].firstMatch
   XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
   let tab = app.buttons["conversations.tab." + channel]
   XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
   let prefix = channel == "live" ? "livechat.thread." : channel + ".thread."
   let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
   XCTAssertTrue(row.waitForExistence(timeout: 20), "Signed-in account needs a saved " + channel + " conversation")
   capture(app, channel + " inbox tabs")
   for orientation in [UIDeviceOrientation.landscapeLeft, .landscapeRight, .portrait] {
    XCUIDevice.shared.orientation = orientation
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
     (app.frame.width > app.frame.height) == (orientation != .portrait) && tab.exists && tab.isHittable
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
    capture(app, channel + " inbox " + String(orientation.rawValue))
    let navigation = app.buttons["Conversations"].firstMatch
    XCTAssertGreaterThan(navigation.frame.minY, app.frame.height * 0.6, "Conversations must use bottom navigation, not a left rail")
   }
   row.tap()
   let composer = app.descendants(matching: .any).matching(identifier: "chat.composer").firstMatch
   XCTAssertTrue(composer.waitForExistence(timeout: 15))
   for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .landscapeRight, .portrait] {
    XCUIDevice.shared.orientation = orientation
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
     guard composer.exists else { return false }
     let screen = app.frame, input = composer.frame
     return (screen.width > screen.height) == (orientation != .portrait) && input.width > screen.width * 0.55 && input.maxY <= screen.maxY && input.height < 100
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed)
    capture(app, channel + " settled geometry")
    XCTAssertGreaterThan(composer.frame.width, app.frame.width * 0.55, channel + " composer must fill the page, not a split column")
    XCTAssertLessThanOrEqual(composer.frame.maxY, app.frame.maxY, channel + " composer must stay visible")
    XCTAssertFalse(app.buttons["main.menu"].isHittable, channel + " must hide the app header while reading a conversation")
    XCTAssertFalse(app.buttons["conversations.tab.sms"].isHittable)
    if orientation != .portrait && channel == "team" { XCTAssertTrue(app.descendants(matching: .any)["team.compactHeader"].exists) }
    if channel == "live" { XCTAssertFalse(app.staticTexts["Open"].exists, "Do not repeat the Live Chat/Open status row") }
    capture(app, channel + " " + (orientation == .portrait ? "portrait" : "landscape") + " full-width conversation")
   }
   if composer.isEnabled {
    composer.tap()
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
    XCTAssertLessThanOrEqual(composer.frame.maxY, app.keyboards.firstMatch.frame.minY + 2, channel + " composer must be above the native keyboard")
    capture(app, channel + " native keyboard and composer")
   }
  }
 }

 func testLiveComposerAttachmentsEndToEndOnPhone() throws {
  continueAfterFailure = false
  XCUIDevice.shared.orientation = .portrait
  let app = XCUIApplication(); app.terminate(); app.launch()
  let chats = app.buttons["Conversations"].firstMatch
  XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap(); app.buttons["conversations.tab.live"].tap()
  let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "livechat.thread.", "Mango9 composer check")).firstMatch
  XCTAssertTrue(row.waitForExistence(timeout: 15)); row.tap()
  let attach = app.buttons["chat.attach"], mic = app.buttons["Record a voice message"].firstMatch
  let composer = app.descendants(matching: .any).matching(identifier: "chat.composer").firstMatch
  XCTAssertTrue(attach.waitForExistence(timeout: 15)); XCTAssertTrue(mic.exists)
  XCTAssertLessThan(attach.frame.midX, composer.frame.minX); XCTAssertGreaterThan(mic.frame.midX, composer.frame.maxX)
  XCTAssertEqual(attach.frame.midY, mic.frame.midY, accuracy: 1, "Attachment and recorder controls must share one centerline")
  capture(app, "Live composer aligned attachment and microphone controls")
  attach.tap(); XCTAssertTrue(app.buttons["Photo library"].waitForExistence(timeout: 5)); XCTAssertTrue(app.buttons["Choose file"].exists)
  capture(app, "Live composer attachment left and recorder right")
  app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
  for (index, name) in ["Mango9-image-check.png", "Mango9-file-check.pdf"].enumerated() {
   let file = app.buttons["livechat.attachment." + name].firstMatch
   XCTAssertTrue(file.waitForExistence(timeout: 15)); file.tap()
   let close = app.buttons["QLOverlayDoneButtonAccessibilityIdentifier"]
   if !close.waitForExistence(timeout: 3) { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
   XCTAssertTrue(close.waitForExistence(timeout: 15)); capture(app, "Native attachment preview " + name); close.tap()
   let reactions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.react."))
   reactions.element(boundBy: index).tap(); app.buttons["Forward"].tap()
   let destination = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Mango9 composer check · Native composer verification")).firstMatch
   XCTAssertTrue(destination.waitForExistence(timeout: 10)); destination.tap()
   let send = app.buttons["chat.send"].firstMatch
   XCTAssertTrue(send.waitForExistence(timeout: 10)); XCTAssertTrue(send.isEnabled); send.tap()
   let sent = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
    app.buttons.matching(identifier: "Record a voice message").allElementsBoundByIndex.contains { $0.isHittable }
   }, object: nil)
   XCTAssertEqual(XCTWaiter.wait(for: [sent], timeout: 20), .completed)
   capture(app, "Phone attachment upload confirmed " + name)
   app.terminate(); app.launch()
   XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap(); app.buttons["conversations.tab.live"].tap()
   XCTAssertTrue(row.waitForExistence(timeout: 15)); row.tap()
   if index == 0 { app.scrollViews.firstMatch.swipeDown(); app.scrollViews.firstMatch.swipeDown() }
  }
  XCTAssertTrue(mic.waitForExistence(timeout: 10)); mic.tap()
  XCTAssertTrue(app.staticTexts["Recording voice message"].waitForExistence(timeout: 10))
  let duration = app.staticTexts["chat.recording.duration"]
  let recorded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label != %@", "0:00"), object: duration)
  XCTAssertEqual(XCTWaiter.wait(for: [recorded], timeout: 5), .completed)
  app.buttons["Attach voice recording"].tap(); app.buttons["chat.send"].tap()
  let voice = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.attachment.mango9-voice-")).firstMatch
  XCTAssertTrue(voice.waitForExistence(timeout: 20)); voice.tap()
  XCTAssertTrue(app.buttons["QLOverlayDoneButtonAccessibilityIdentifier"].waitForExistence(timeout: 15))
  XCTAssertTrue(app.buttons["Play"].firstMatch.exists)
  capture(app, "Native iPhone voice recorded uploaded and opened for playback")
  app.buttons["QLOverlayDoneButtonAccessibilityIdentifier"].tap()
 }

 func testLandscapeKeyboardOnPhone() throws {
  continueAfterFailure = false
  defer { XCUIDevice.shared.orientation = .portrait }
  let app = XCUIApplication()
  for channel in ["sms", "team", "social", "live"] {
   XCUIDevice.shared.orientation = .portrait
   app.terminate(); app.launch()
   let chats = app.buttons["Conversations"].firstMatch
   XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
   let tab = app.buttons["conversations.tab." + channel]
   XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
   let prefix = channel == "live" ? "livechat.thread." : channel + ".thread."
   let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
   XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
   let composer = app.descendants(matching: .any).matching(identifier: "chat.composer").firstMatch
   XCTAssertTrue(composer.waitForExistence(timeout: 15))
   if composer.isEnabled {
    composer.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
    XCUIDevice.shared.orientation = .landscapeLeft
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
     guard composer.exists, app.keyboards.firstMatch.exists else { return false }
     let input = composer.frame, screen = app.frame, keyboard = app.keyboards.firstMatch.frame
     return screen.width > screen.height && input.width > screen.width * 0.55 && input.maxY <= keyboard.minY + 2 && input.minY > 45
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed, channel + " composer must remain above the landscape keyboard")
    capture(app, channel + " landscape keyboard")
   }
  }
 }

 func testLiveNativeActionsAndTransferOnPhone() throws {
  continueAfterFailure = false
  XCUIDevice.shared.orientation = .portrait
  let app = XCUIApplication(); app.terminate(); app.launch()
  let chats = app.buttons["Conversations"].firstMatch
  XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap(); app.buttons["conversations.tab.live"].tap()
  let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "livechat.thread.", "Mango9 native chat check")).firstMatch
  XCTAssertTrue(row.waitForExistence(timeout: 15)); row.tap()
  let react = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.react.")).firstMatch
  XCTAssertTrue(react.waitForExistence(timeout: 15))
  XCTAssertFalse(app.staticTexts["Assigned to Unassigned"].exists)
  app.buttons["Conversation actions"].tap(); app.buttons["Transfer"].tap()
  let agent = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.transfer.agent.")).firstMatch
  XCTAssertTrue(agent.waitForExistence(timeout: 5)); agent.tap()
  XCTAssertTrue(app.descendants(matching: .any)["livechat.assignee"].waitForExistence(timeout: 10))
  capture(app, "Confirmed assignee after transfer")
  react.tap()
  for title in ["Copy", "Select", "Forward", "Delete for me"] { XCTAssertTrue(app.buttons[title].waitForExistence(timeout: 5)) }
  XCTAssertTrue(app.buttons["Add 👍 reaction"].exists)
  capture(app, "Native Live Chat bubble action panel with full-color emoji")
  app.buttons["Forward"].tap()
  XCTAssertTrue(app.navigationBars["Forward message"].waitForExistence(timeout: 5)); capture(app, "Reviewable forwarding destination picker"); app.buttons["Close"].tap()
  react.tap(); app.buttons["Select"].tap()
  XCTAssertTrue(app.buttons["Copy selected messages"].waitForExistence(timeout: 5)); capture(app, "Message and metadata selected together")
  app.buttons["Done"].tap()
  let attach = app.buttons["chat.attach"]; XCTAssertTrue(attach.exists); attach.tap()
  XCTAssertTrue(app.buttons["Photo library"].waitForExistence(timeout: 5)); XCTAssertTrue(app.buttons["Choose file"].exists)
  capture(app, "Native attachment menu")
  app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()
  XCTAssertTrue(app.buttons["Record a voice message"].waitForExistence(timeout: 5))
 }

 func testLiveNativeVoicePreviewAndPersonalDeleteOnPhone() throws {
  continueAfterFailure = false
  XCUIDevice.shared.orientation = .portrait
  let app = XCUIApplication(); app.terminate(); app.launch()
  let chats = app.buttons["Conversations"].firstMatch
  XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap(); app.buttons["conversations.tab.live"].tap()
  let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "livechat.thread.", "Mango9 native chat check")).firstMatch
  XCTAssertTrue(row.waitForExistence(timeout: 15)); row.tap()
  let voice = app.buttons["voice.m4a"]
  XCTAssertTrue(voice.waitForExistence(timeout: 15)); XCTAssertTrue(voice.isHittable); voice.tap()
  capture(app, "Voice attachment tapped")
  let closePreview = app.buttons["QLOverlayDoneButtonAccessibilityIdentifier"]
  XCTAssertTrue(closePreview.waitForExistence(timeout: 15))
  XCTAssertTrue(app.buttons["QLOverlayDefaultActionButtonAccessibilityIdentifier"].exists, "Quick Look must provide native Share/Save/Open actions")
  XCTAssertTrue(app.buttons["Play"].firstMatch.exists)
  capture(app, "Native voice preview and export controls"); closePreview.tap()
  let react = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.message.react.")).firstMatch
  XCTAssertTrue(react.waitForExistence(timeout: 10)); react.tap(); app.buttons["Delete for me"].tap()
  let confirm = app.buttons["Delete for me"].firstMatch
  XCTAssertTrue(confirm.waitForExistence(timeout: 5)); confirm.tap()
  let text = app.staticTexts["Native chat layout, metadata, and actions check. No visitor was contacted. 🙂"]
  XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: text)], timeout: 15), .completed)
  capture(app, "Confirmed personal deletion on internal test conversation")
 }

	private func capture(_ app: XCUIApplication, _ name: String) {
		let image = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); image.name = name; image.lifetime = .keepAlways; add(image)
	}
}
