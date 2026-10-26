import XCTest

/// Reads saved templates; edits remain in the composer and no chat message is sent.
final class Mango9TemplatesPhoneUITests: XCTestCase {
    func testTemplateCreateScreenAndMenuOnPhysicalPhone() throws {
        continueAfterFailure = false; XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.terminate(); app.launch()
        let chats = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(chats.waitForExistence(timeout: 30)); chats.tap()
        let live = app.buttons["conversations.tab.live"]; XCTAssertTrue(live.waitForExistence(timeout: 10)); live.tap()
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "livechat.thread.")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 25)); row.tap()
        let plus = app.buttons["chat.attach"]; XCTAssertTrue(plus.waitForExistence(timeout: 20)); plus.tap()
        let templates = app.buttons["Templates ›"]; XCTAssertTrue(templates.waitForExistence(timeout: 5)); templates.tap()
        let create = app.buttons["Create template"].firstMatch
        XCTAssertTrue(create.waitForExistence(timeout: 20))
        let enabled = NSPredicate(format: "enabled == true")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: enabled, object: create)], timeout: 25), .completed)
        capture("Template library", app)
        create.tap()
        let title = app.textFields["templates.title"]; XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["templates.save"].isEnabled)
        let editor = app.textViews["templates.content"]; XCTAssertTrue(editor.exists)
        title.tap(); title.typeText("Phone template check")
        editor.tap(); editor.typeText("Draft only. Nothing sent.")
        XCTAssertTrue(app.buttons["templates.save"].isEnabled)
        XCTAssertTrue(app.staticTexts["25 characters"].exists)
        capture("Template editor portrait", app)
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.width > app.frame.height }, object: app)], timeout: 10), .completed)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertTrue(editor.waitForExistence(timeout: 5)); XCTAssertTrue(app.buttons["templates.save"].isHittable)
        XCTAssertTrue(app.staticTexts["25 characters"].isHittable)
        XCTAssertTrue(app.frame.contains(editor.frame))
        XCTAssertTrue(app.frame.contains(app.buttons["templates.save"].frame))
        let geometry = XCTAttachment(string: "App: \(app.frame); editor: \(editor.frame); save: \(app.buttons["templates.save"].frame); counter: \(app.staticTexts["25 characters"].frame)")
        geometry.name = "Template landscape control bounds"; geometry.lifetime = .keepAlways; add(geometry)
        capture("Template editor landscape", app)
        XCUIDevice.shared.orientation = .portrait
        app.buttons["Back"].firstMatch.tap(); app.buttons["Discard edits"].tap()
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(app.buttons["chat.attach"].waitForExistence(timeout: 10))
    }
    private func capture(_ name: String, _ app: XCUIApplication) { let item = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); item.name = name; item.lifetime = .keepAlways; add(item) }
}

/// Physical-device presentation checks. No messages, credentials or templates are submitted.
final class Mango9ChatDesignPhoneUITests: XCTestCase {
    func testExistingGroupVoiceUsesLoudspeaker() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.activate()
        XCUIDevice.shared.orientation = .portrait
        if !app.buttons["chat.renameGroup"].exists {
            let chats = app.buttons["Conversations"].firstMatch
            guard chats.waitForExistence(timeout: 10) else { throw XCTSkip("Open a signed-in Team group on the phone") }
            chats.tap()
            let team = app.buttons["conversations.tab.team"]
            XCTAssertTrue(team.waitForExistence(timeout: 10)); team.tap()
            let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS[c] %@", "team.thread.", "Test Group")).firstMatch
            guard row.waitForExistence(timeout: 20) else { throw XCTSkip("This account needs an existing Test Group") }
            row.tap()
        }
        let play = app.buttons["voice.playback.control"].firstMatch
        guard play.waitForExistence(timeout: 10), play.isHittable else { throw XCTSkip("Scroll an existing voice message into view") }
        play.tap()
        defer { if play.exists && play.isHittable && play.label == "Pause voice message" { play.tap() } }
        let routed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            play.exists && play.label == "Pause voice message" && (play.value as? String) == "Loudspeaker"
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [routed], timeout: 5), .completed)
    }

    func testLoginControlsAndRotationWithoutSigningOut() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication(); app.terminate(); app.launch()
        XCUIDevice.shared.orientation = .portrait
        let username = app.textFields["Email or username"].firstMatch
        let menu = app.buttons["main.menu"]
        let wasSignedIn = !username.waitForExistence(timeout: 5) && menu.waitForExistence(timeout: 10)
        if wasSignedIn {
            menu.tap()
            let add = app.staticTexts["Add account"].firstMatch
            XCTAssertTrue(add.waitForExistence(timeout: 5)); add.tap()
        }
        XCTAssertTrue(username.waitForExistence(timeout: 10))
        XCTAssertTrue(app.secureTextFields["Password"].exists)
        capture(app, "Sign in portrait")
        app.buttons["Show password"].tap()
        XCTAssertTrue(app.textFields["Password"].exists)
        app.buttons["Hide password"].tap()
        app.buttons["Use an email code"].tap()
        XCTAssertTrue(app.textFields["Email address"].exists)
        app.buttons["Use password instead"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        let rotated = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.frame.width > app.frame.height && app.frame.contains(username.frame)
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [rotated], timeout: 15), .completed)
        username.tap()
        username.typeText("layout-check")
        let next = app.keyboards.buttons.matching(NSPredicate(format: "label ==[c] %@", "Next")).firstMatch
        XCTAssertTrue(next.waitForExistence(timeout: 5)); next.tap()
        let password = app.secureTextFields["Password"].firstMatch
        let reachable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.keyboards.firstMatch.exists && password.isHittable && app.frame.contains(password.frame)
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [reachable], timeout: 10), .completed)
        capture(app, "Sign in landscape with keyboard")
        app.terminate(); app.launch()
        if wasSignedIn { XCTAssertTrue(app.buttons["Conversations"].firstMatch.waitForExistence(timeout: 20)) }
    }

    func testExistingGroupCardsAndRotation() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication(); app.activate()
        XCUIDevice.shared.orientation = .portrait
        let composer = app.descendants(matching: .any)["chat.composer"].firstMatch
        if !composer.exists {
            let chats = app.buttons["Conversations"].firstMatch
            if !chats.waitForExistence(timeout: 10) && app.textFields["Email or username"].exists {
                throw XCTSkip("Sign in on this phone to check existing group conversations")
            }
            XCTAssertTrue(chats.exists); chats.tap()
            let team = app.buttons["conversations.tab.team"]
            XCTAssertTrue(team.waitForExistence(timeout: 10)); team.tap()
            let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS[c] %@", "team.thread.", "Test Group")).firstMatch
            guard row.waitForExistence(timeout: 20) else { throw XCTSkip("This device needs an existing Test Group conversation") }
            row.tap()
        }
        guard app.buttons["chat.renameGroup"].waitForExistence(timeout: 10) else {
            throw XCTSkip("Open a Team group on the phone before running this check")
        }
        XCTAssertTrue(composer.waitForExistence(timeout: 15))
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .landscapeRight] {
            XCUIDevice.shared.orientation = orientation
            let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                composer.exists && (app.frame.width > app.frame.height) == (orientation != .portrait) && app.frame.contains(composer.frame)
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 12), .completed)
            capture(app, "Team Chat orientation \(orientation.rawValue)")
        }
    }

    func testConnectionSettingsPasswordAndBack() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.terminate(); app.launch()
        if app.buttons["main.menu"].waitForExistence(timeout: 5) {
            app.buttons["main.menu"].tap()
            app.staticTexts["Add account"].firstMatch.tap()
        }
        let gear = app.buttons["Connection settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 10)); gear.tap()
        let password = app.secureTextFields["settings.unlockPassword"]
        XCTAssertTrue(password.waitForExistence(timeout: 5)); password.tap(); password.typeText("wrong")
        app.buttons["Unlock"].tap()
        XCTAssertTrue(app.staticTexts["Incorrect settings password"].exists)
        app.buttons["Cancel"].tap()
        XCTAssertTrue(gear.waitForExistence(timeout: 5)); gear.tap()
        XCTAssertTrue(password.waitForExistence(timeout: 5)); password.tap(); password.typeText("6.2.19")
        app.buttons["Unlock"].tap()
        let domain = app.textFields["Server domain"].firstMatch
        XCTAssertTrue(domain.waitForExistence(timeout: 10))
        XCTAssertTrue((domain.value as? String).map { $0.isEmpty || $0 == "Server domain" } ?? true)
        XCTAssertFalse(app.staticTexts["proxy.mango9.com"].exists)
        capture(app, "Connection settings without a prefilled server")
        let back = app.buttons["connectionSettings.back"].firstMatch
        XCTAssertTrue(back.exists); back.tap()
        XCTAssertTrue(gear.waitForExistence(timeout: 5)); gear.tap()
        XCTAssertTrue(password.waitForExistence(timeout: 5), "The gate must lock again after leaving settings")
        app.buttons["Cancel"].tap()
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
