import XCTest

final class MessageReactionUITests: XCTestCase {
	func testConversationDeleteConfirmsOnPressedRowAndPreservesOtherChats() {
		continueAfterFailure = false
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social-list"; app.launch()
		let row = app.buttons["social.thread.fixture-thread"]
		XCTAssertTrue(row.waitForExistence(timeout: 15)); row.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Delete conversation"])
		XCTAssertTrue(app.buttons["Delete for me"].waitForExistence(timeout: 5))
		let confirmation = XCTAttachment(screenshot: app.screenshot()); confirmation.name = "Conversation-specific delete confirmation"; confirmation.lifetime = .keepAlways; add(confirmation)
		tapWhenEnabled(app.buttons["Cancel"]); XCTAssertTrue(row.exists)
		row.press(forDuration: 1); tapWhenEnabled(app.buttons["Delete conversation"]); tapWhenEnabled(app.buttons["Delete for me"])
		expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: row); waitForExpectations(timeout: 10)
		XCTAssertTrue(app.buttons["social.thread.facebook-thread"].exists); XCTAssertTrue(app.buttons["social.thread.tiktok-thread"].exists)
	}
	func testSocialListMuteAndUnmuteUsePressedConversation() {
		continueAfterFailure = false
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social-list"; app.launch()
		let row = app.buttons["social.thread.fixture-thread"]
		XCTAssertTrue(row.waitForExistence(timeout: 15)); row.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Mute conversation"])
		let badge = app.images["chat.muted.social:fixture-thread"]
		XCTAssertTrue(badge.waitForExistence(timeout: 5))
		XCTAssertFalse(app.images["chat.muted.social:facebook-thread"].exists)
		row.press(forDuration: 1); tapWhenEnabled(app.buttons["Unmute conversation"])
		expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: badge); waitForExpectations(timeout: 5)
	}
	func testSocialAttachmentShowsPreviewAndAnchorsHoldActions() {
		continueAfterFailure = false
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social"
		app.launchEnvironment["UITEST_SOCIAL_ATTACHMENT"] = "1"; app.launch()
		let attachment = app.buttons["social.attachment.1.0"]
		XCTAssertTrue(attachment.waitForExistence(timeout: 15))
		expectation(for: NSPredicate(format: "value == %@", "Preview loaded"), evaluatedWith: attachment); waitForExpectations(timeout: 15)
		XCTAssertFalse(app.staticTexts["Attachment or unavailable content"].exists)
		XCTAssertGreaterThan(attachment.frame.height, 100)
		tapWhenEnabled(attachment)
		XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 10), "Opening the preview before selection. State: \(app.staticTexts["fixture.previewState"].label)")
		tapWhenEnabled(app.buttons["Done"])
		attachment.press(forDuration: 1)
		XCTAssertTrue(app.buttons["chat.moreReactions"].waitForExistence(timeout: 5))
		let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Attachment preview and anchored actions"; screenshot.lifetime = .keepAlways; add(screenshot)
		tapWhenEnabled(app.buttons["Select"])
		XCTAssertTrue(app.buttons["Delete selected messages for me"].waitForExistence(timeout: 5))
		tapWhenEnabled(app.buttons["Done"])
		XCTAssertFalse(app.buttons["Delete selected messages for me"].exists)
		tapWhenEnabled(attachment)
		XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 10), "A normal tap must still open the full attachment viewer. State: \(app.staticTexts["fixture.previewState"].label)")
	}

	func testSocialInboxAndMessagesShowDatesWithBrandLogos() {
		continueAfterFailure = false
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social-list"; app.launch()
		let row = app.buttons["social.thread.fixture-thread"]
		XCTAssertTrue(row.waitForExistence(timeout: 15))
		for id in ["fixture-thread", "facebook-thread", "tiktok-thread"] {
			let item = app.buttons["social.thread." + id]
			XCTAssertTrue(item.waitForExistence(timeout: 5))
			XCTAssertTrue(item.label.contains("01-01-2026"), item.label)
			// AX measures row content; List's ten-point top/bottom insets are outside it.
			XCTAssertGreaterThanOrEqual(item.frame.height, 56)
		}
		let inbox = XCTAttachment(screenshot: app.screenshot()); inbox.name = "Social inbox dates and platform logos"; inbox.lifetime = .keepAlways; add(inbox)
		app.terminate()
		app.launchEnvironment["UITEST_REACTIONS"] = "social"; app.launchEnvironment["UITEST_MULTI_MESSAGE"] = "1"; app.launch()
		XCTAssertTrue(app.staticTexts["Our reply"].waitForExistence(timeout: 15))
		for id in 1...3 {
			let date = app.staticTexts["social.message.date.\(id)"]
			XCTAssertTrue(date.waitForExistence(timeout: 5))
			XCTAssertTrue(date.label.contains("01-01-2026"), date.label)
		}
		let messages = XCTAttachment(screenshot: app.screenshot()); messages.name = "Social message sizing and timestamps"; messages.lifetime = .keepAlways; add(messages)
	}

	func testSocialCompactFiltersNarrowOnlySocialInbox() {
		continueAfterFailure = false
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social-list"; app.launch()
		XCTAssertTrue(app.staticTexts["Facebook customer"].waitForExistence(timeout: 10))
		tapWhenEnabled(app.buttons["social.filter.platform"])
		let instagram = app.buttons["social.platform.instagram"]
		XCTAssertTrue(instagram.waitForExistence(timeout: 5)); instagram.press(forDuration: 0.15)
		expectation(for: NSPredicate(format: "label CONTAINS %@", "Instagram"), evaluatedWith: app.buttons["social.filter.platform"])
		expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.staticTexts["Facebook customer"])
		waitForExpectations(timeout: 10)
		let customer = app.staticTexts["Customer conversation"]
		XCTAssertTrue(customer.waitForExistence(timeout: 5))
		XCTAssertFalse(app.staticTexts["Facebook customer"].exists)
		tapWhenEnabled(app.buttons["social.filter.account"])
		let studio = app.buttons["social.account." + String(repeating: "a", count: 24)]
		XCTAssertTrue(studio.waitForExistence(timeout: 5)); studio.press(forDuration: 0.15)
		expectation(for: NSPredicate(format: "label CONTAINS %@", "Studio"), evaluatedWith: app.buttons["social.filter.account"])
		waitForExpectations(timeout: 10)
		XCTAssertTrue(app.buttons["social.filter.account"].label.contains("Studio"))
		XCTAssertTrue(customer.waitForExistence(timeout: 5))
		expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: app.buttons["social.thread.fixture-thread"])
		waitForExpectations(timeout: 10)
		XCTAssertFalse(app.buttons["conversations.compose"].exists)
		// Native menu dismissal and List snapshot transitions can outlast AX updates.
		RunLoop.current.run(until: Date().addingTimeInterval(0.6))
		let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Compact Social inbox filters"; screenshot.lifetime = .keepAlways; add(screenshot)
	}
	func testNewChatButtonFollowsSelectedChannelAndOpensTeamPicker() {
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "new-chat"; app.launch()
		tapWhenEnabled(app.buttons["New message"])
		XCTAssertTrue(app.staticTexts["SMS composer"].exists)
		tapWhenEnabled(app.buttons["Team Chat"])
		tapWhenEnabled(app.buttons["New Team chat"])
		let start = app.buttons["Start chat"]
		XCTAssertTrue(start.waitForExistence(timeout: 5)); XCTAssertFalse(start.isEnabled)
		tapWhenEnabled(app.buttons["team.member.11"])
		tapWhenEnabled(start)
		XCTAssertTrue(app.staticTexts["Team direct: 11"].waitForExistence(timeout: 5))
		tapWhenEnabled(app.buttons["New Team chat"])
		tapWhenEnabled(app.buttons["team.member.11"])
		tapWhenEnabled(app.buttons["team.member.12"])
		let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "New Team group picker"; screenshot.lifetime = .keepAlways; add(screenshot)
		tapWhenEnabled(app.buttons["Start chat"])
		XCTAssertTrue(app.staticTexts["Team group: 11,12"].waitForExistence(timeout: 5))
		tapWhenEnabled(app.buttons["Social"])
		XCTAssertFalse(app.buttons["conversations.compose"].exists, "Social must not offer an SMS composer")
		tapWhenEnabled(app.buttons["SMS"])
		tapWhenEnabled(app.buttons["New message"])
		XCTAssertTrue(app.staticTexts["SMS composer"].exists)
	}

	func testFailedTeamCreationKeepsSelectionAndShowsError() {
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "new-chat"
		app.launchEnvironment["UITEST_TEAM_CREATE_FAIL"] = "1"; app.launch()
		tapWhenEnabled(app.buttons["Team Chat"])
		tapWhenEnabled(app.buttons["New Team chat"])
		tapWhenEnabled(app.buttons["team.member.11"])
		tapWhenEnabled(app.buttons["Start chat"])
		XCTAssertTrue(app.staticTexts["Fixture connection failed"].waitForExistence(timeout: 5))
		tapWhenEnabled(app.buttons["OK"])
		XCTAssertEqual(app.buttons["team.member.11"].value as? String, "Selected")
		tapWhenEnabled(app.buttons["Cancel"])
		XCTAssertFalse(app.staticTexts["SMS composer"].exists)
		XCTAssertTrue(app.buttons["New Team chat"].exists)
	}

	func testEmojiKeyboardChoiceSavesOneCompleteReaction() {
		for channel in ["sms", "social"] {
			let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = channel; app.launch()
			let message = app.staticTexts[channel == "sms" ? "Hold this message to react" : "Hello from the customer"]
			XCTAssertTrue(message.waitForExistence(timeout: 15))
			message.press(forDuration: 1); tapWhenEnabled(app.buttons["chat.moreReactions"])
			let input = app.textFields["chat.emojiInput"]
			XCTAssertTrue(input.waitForExistence(timeout: 5)); input.typeText("👍🏽")
			XCTAssertTrue(app.staticTexts[channel == "sms" ? "👍🏽 1" : "👍🏽 Your account"].waitForExistence(timeout: 10))
			XCTAssertFalse(input.exists)
			app.terminate()
		}
	}
	func testDeleteForMeRemovesOnlySelectedBubbleAfterServerReceipt() {
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social"
		app.launchEnvironment["UITEST_MULTI_MESSAGE"] = "1"; app.launch()
		let message = app.staticTexts["Hello from the customer"]
		XCTAssertTrue(message.waitForExistence(timeout: 15))
		message.press(forDuration: 1); tapWhenEnabled(app.buttons["Select"])
		tapWhenEnabled(app.buttons["Delete selected messages for me"])
		tapWhenEnabled(app.buttons["Delete for me"])
		expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: message)
		waitForExpectations(timeout: 10)
		XCTAssertTrue(app.staticTexts["Our reply"].exists)
		XCTAssertTrue(app.staticTexts["Another incoming message"].exists)
		XCTAssertTrue(app.buttons["chat.attach"].exists)
	}
	func testSelectionExposesEveryIncomingAndOutgoingBubble() {
		let app = XCUIApplication()
		app.launchEnvironment["UITEST_REACTIONS"] = "social"
		app.launchEnvironment["UITEST_MULTI_MESSAGE"] = "1"
		app.launch()
		let message = app.staticTexts["Hello from the customer"]
		XCTAssertTrue(message.waitForExistence(timeout: 15))
		XCTAssertTrue(app.staticTexts["Our reply"].exists)
		XCTAssertTrue(app.staticTexts["Another incoming message"].exists)
		message.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Select"])
		XCTAssertTrue(app.buttons["Deselect message"].waitForExistence(timeout: 5))
		XCTAssertEqual(app.buttons.matching(identifier: "Select message").count, 2)
		tapWhenEnabled(app.buttons.matching(identifier: "Select message").firstMatch)
		XCTAssertEqual(app.buttons.matching(identifier: "Deselect message").count, 2)
		XCTAssertEqual(app.buttons.matching(identifier: "Select message").count, 1)
		let screenshot = XCTAttachment(screenshot: app.screenshot())
		screenshot.name = "Incoming and outgoing selection"; screenshot.lifetime = .keepAlways; add(screenshot)
		tapWhenEnabled(app.buttons.matching(identifier: "Select message").firstMatch)
		XCTAssertEqual(app.buttons.matching(identifier: "Deselect message").count, 3)
		tapWhenEnabled(app.buttons["Done"])
		XCTAssertTrue(message.waitForExistence(timeout: 5))
		XCTAssertFalse(app.buttons["Select message"].exists)
	}
	func testMoreReactionsOpensSystemEmojiInputAndCancelDoesNotReact() {
		for channel in ["sms", "social"] {
			let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = channel; app.launch()
			let message = app.staticTexts[channel == "sms" ? "Hold this message to react" : "Hello from the customer"]
			XCTAssertTrue(message.waitForExistence(timeout: 15))
			XCTAssertFalse(app.buttons["chat.moreReactions"].exists)
			message.press(forDuration: 1)
			tapWhenEnabled(app.buttons["chat.moreReactions"])
			XCTAssertTrue(app.textFields["chat.emojiInput"].waitForExistence(timeout: 5))
			XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
			let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "System emoji keyboard - \(channel)"; screenshot.lifetime = .keepAlways; add(screenshot)
			tapWhenEnabled(app.buttons["Cancel"])
			XCTAssertTrue(message.waitForExistence(timeout: 5))
			XCTAssertFalse(app.staticTexts["👍 1"].exists)
			app.terminate()
		}
	}
	func testAttachmentMenuAnchorsToPlusWithAndWithoutKeyboard() {
		for keyboardOpen in [false, true] {
			let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social"; app.launch()
			XCTAssertTrue(app.staticTexts["Hello from the customer"].waitForExistence(timeout: 15))
			if keyboardOpen {
				let input = app.descendants(matching: .any).matching(identifier: "chat.composer").firstMatch
				input.tap(); input.typeText("Keep this draft")
				XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
			}
			let plus = app.buttons["chat.attach"]
			XCTAssertTrue(plus.waitForExistence(timeout: 5))
			let anchor = plus.frame
			tapWhenEnabled(plus)
			let library = app.buttons["Photo library"], files = app.buttons["Choose file"]
			XCTAssertTrue(library.waitForExistence(timeout: 5)); XCTAssertTrue(files.exists)
			let menu = library.frame.union(files.frame)
			XCTAssertLessThanOrEqual(menu.minX, anchor.midX + 16)
			XCTAssertGreaterThan(menu.maxX, anchor.midX)
			XCTAssertLessThanOrEqual(menu.maxY, anchor.maxY + 8)
			XCTAssertLessThan(abs(anchor.minY - menu.maxY), 60, "Menu must open beside the +, not at the screen edge")
			let screenshot = XCTAttachment(screenshot: app.screenshot())
			screenshot.name = keyboardOpen ? "Attachment menu with keyboard" : "Attachment menu without keyboard"
			screenshot.lifetime = .keepAlways; add(screenshot)
			library.tap()
			XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 5), "The anchored menu must still open the native library")
			tapWhenEnabled(app.buttons["Cancel"])
			XCTAssertTrue(plus.waitForExistence(timeout: 5))
			app.terminate()
		}
	}
	func testComposerTracksKeyboardWithoutBottomGap() {
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social"
		app.launchEnvironment["UITEST_CONVERSATION_SHELL"] = "1"; app.launch()
		XCTAssertTrue(app.staticTexts["Hello from the customer"].waitForExistence(timeout: 15))
		let restingScreenshot = XCTAttachment(screenshot: app.screenshot())
		restingScreenshot.name = "Conversation shell without keyboard"; restingScreenshot.lifetime = .keepAlways; add(restingScreenshot)
		let input = app.descendants(matching: .any).matching(identifier: "chat.composer").firstMatch
		XCTAssertTrue(input.waitForExistence(timeout: 5))
		input.tap(); input.typeText("Composer layout check")
		let keyboard = app.keyboards.firstMatch
		XCTAssertTrue(keyboard.waitForExistence(timeout: 5))
		let frameProbe = app.descendants(matching: .any).matching(identifier: "fixture.keyboardTop").firstMatch
		XCTAssertTrue(frameProbe.waitForExistence(timeout: 5))
		guard let rawTop = frameProbe.value as? String, let top = Double(rawTop), top > 0 else {
			XCTFail("Missing native keyboard frame"); return
		}
		XCTAssertLessThanOrEqual(input.frame.maxY, top)
		XCTAssertLessThan(top - input.frame.maxY, 32)
		let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "Composer keyboard alignment"; screenshot.lifetime = .keepAlways; add(screenshot)
	}
	func testSocialComposerActionsAndSelectionParity() {
		continueAfterFailure = false
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social"; app.launch()
		let message = app.staticTexts["Hello from the customer"]
		XCTAssertTrue(message.waitForExistence(timeout: 15))
		message.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Reply"])
		XCTAssertTrue(app.staticTexts["Reply · Sent as quoted text"].waitForExistence(timeout: 5))
		tapWhenEnabled(app.buttons["Cancel reply"])
		message.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Select"])
		XCTAssertTrue(app.buttons["Delete selected messages for me"].waitForExistence(timeout: 5))
		XCTAssertTrue(app.buttons["Forward selected messages"].exists)
		tapWhenEnabled(app.buttons["Done"])
		tapWhenEnabled(app.buttons["Add attachment"])
		XCTAssertTrue(app.buttons["Photo library"].waitForExistence(timeout: 5))
		XCTAssertTrue(app.buttons["Choose file"].exists)
	}
	func testSMSReactionsAppearOnlyOnHoldAndPersistAsBadges() {
		let app = XCUIApplication()
		app.launchEnvironment["UITEST_REACTIONS"] = "sms"
		app.launch()
		let message = app.staticTexts["Hold this message to react"]
		XCTAssertTrue(message.waitForExistence(timeout: 15))
		XCTAssertFalse(app.buttons["Add 👍 reaction"].exists)
		XCTAssertFalse(app.buttons["Mango9-only SMS reactions"].exists)
		message.press(forDuration: 1)
		XCTAssertTrue(app.staticTexts["Mango9 only · No SMS or tapback sent"].waitForExistence(timeout: 5))
		tapWhenEnabled(app.buttons["Add 👍 reaction"])
		XCTAssertTrue(app.staticTexts["👍 1"].waitForExistence(timeout: 10))
		message.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Remove 👍 reaction"])
		expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.staticTexts["👍 1"])
		waitForExpectations(timeout: 10)
	}
	func testSocialHoldMenuRemovesSharedAccountReaction() {
		let app = XCUIApplication(); app.launchEnvironment["UITEST_REACTIONS"] = "social"; app.launch()
		let message = app.staticTexts["Hello from the customer"]
		XCTAssertTrue(message.waitForExistence(timeout: 15))
		XCTAssertFalse(app.buttons["React"].exists)
		message.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Add ❤️ reaction"])
		XCTAssertTrue(app.staticTexts["❤️ Your account"].waitForExistence(timeout: 10))
		message.press(forDuration: 1)
		tapWhenEnabled(app.buttons["Remove ❤️ reaction"])
		expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.staticTexts["❤️ Your account"])
		waitForExpectations(timeout: 10)
	}
	private func tapWhenEnabled(_ element: XCUIElement) {
		XCTAssertTrue(element.waitForExistence(timeout: 10))
		expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: element); waitForExpectations(timeout: 10)
		expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: element); waitForExpectations(timeout: 10)
		element.tap()
	}
}
