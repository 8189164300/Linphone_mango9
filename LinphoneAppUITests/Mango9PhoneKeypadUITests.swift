import XCTest

/// Physical-device navigation only; never initiates a telephone call or changes a mailbox setting.
final class Mango9PhoneKeypadUITests: XCTestCase {
    func testKeypadDigitsHistoryBackAndRotation() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        defer { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        let calls = app.buttons["Calls"].firstMatch
        XCTAssertTrue(calls.waitForExistence(timeout: 20)); calls.tap()
        let first = app.buttons["dial.key.1"]
        XCTAssertTrue(first.waitForExistence(timeout: 8), "Calls opens directly on keypad")
        let number = app.textFields["dial.number"]
        let delete = app.buttons["dial.delete"]
        delete.press(forDuration: 0.7)
        for digit in ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"] {
            app.buttons["dial.key." + digit].tap()
        }
        XCTAssertEqual(number.value as? String, "123456789*0#")
        delete.tap(); XCTAssertEqual(number.value as? String, "123456789*0")
        delete.press(forDuration: 0.7)
        app.buttons["dial.key.0"].press(forDuration: 0.7)
        XCTAssertEqual(number.value as? String, "+", "Holding zero must not also insert zero")
        delete.press(forDuration: 0.7)
        let call = app.buttons["dial.call"]
        if call.isEnabled {
            call.tap()
            XCTAssertFalse((number.value as? String ?? "").isEmpty, "First tap recalls, without making a call")
            XCTAssertTrue(first.isHittable)
            delete.press(forDuration: 0.7)
        }
        for _ in 0..<3 {
            let start = Date()
            app.buttons["calls.history"].tap()
            let back = app.buttons["calls.back"]
            XCTAssertTrue(back.waitForExistence(timeout: 3)); back.tap()
            XCTAssertTrue(first.waitForExistence(timeout: 3))
            print("KEYPAD_HISTORY_ROUNDTRIP_SECONDS", Date().timeIntervalSince(start))
        }
        XCTAssertTrue(app.buttons["dial.voicemail"].isHittable)
        checkContacts(app)
        capture(app, "Mango9 EPhonix-style keypad portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForOrientation(app, landscape: true)
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        let footerTop = app.buttons["Calls"].firstMatch.frame.minY
        for identifier in ["dial.key.1", "dial.key.#", "dial.call", "dial.voicemail"] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.isHittable)
            XCTAssertLessThan(control.frame.maxY, footerTop, "Whole control must stay above the footer")
        }
        capture(app, "Mango9 EPhonix-style keypad landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForOrientation(app, landscape: false)
        checkContacts(app)
        capture(app, "Mango9 keypad after contact picker")
        app.buttons["main.menu"].tap()
        let settings = app.descendants(matching: .any).matching(identifier: "main.settings").firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 5)); settings.tap()
        let callsSettings = app.descendants(matching: .any).matching(identifier: "settings.callsDisclosure").firstMatch
        XCTAssertTrue(callsSettings.waitForExistence(timeout: 5)); callsSettings.tap()
        XCTAssertTrue(app.textFields["settings.voicemail.number"].waitForExistence(timeout: 5))
        capture(app, "Voicemail number inside Calls settings")
        app.descendants(matching: .any).matching(identifier: "settings.back").firstMatch.tap()
    }
    /// Run after a controlled incoming call is left unanswered on the device.
    func testMissedCallBadgeClearsWhenHistoryOpens() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        XCUIDevice.shared.orientation = .portrait
        let calls = app.buttons["Calls"].firstMatch
        XCTAssertTrue(calls.waitForExistence(timeout: 15)); calls.tap()
        let history = app.buttons["calls.history"]
        XCTAssertTrue(history.waitForExistence(timeout: 5))
        let count = Int((history.value as? String ?? "").split(separator: " ").first ?? "0") ?? 0
        try XCTSkipIf(count == 0, "Requires an actual missed-call fixture; does not place calls or fabricate history")
        XCTAssertGreaterThan(count, 0)
        capture(app, "Missed-call count on history icon")
        app.terminate(); app.launch()
        XCTAssertTrue(calls.waitForExistence(timeout: 15)); calls.tap()
        XCTAssertTrue(history.waitForExistence(timeout: 5))
        XCTAssertEqual(Int((history.value as? String ?? "").split(separator: " ").first ?? "0"), count,
                       "Unseen missed calls must survive restarting the app")
        history.tap()
        XCTAssertTrue(app.buttons["calls.back"].waitForExistence(timeout: 5))
        app.buttons["calls.back"].tap()
        let cleared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            history.exists && history.value as? String == "0 missed calls"
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [cleared], timeout: 5), .completed)
        capture(app, "Missed-call badge cleared after history")
    }
    /// Uses the selected line's real MWI notification; never changes the mailbox.
    func testVoicemailBadgeRestoresAfterRestart() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launch()
        XCUIDevice.shared.orientation = .portrait
        let calls = app.buttons["Calls"].firstMatch
        XCTAssertTrue(calls.waitForExistence(timeout: 20)); calls.tap()
        let voicemail = app.buttons["dial.voicemail"]
        XCTAssertTrue(voicemail.waitForExistence(timeout: 8))
        let notified = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (Int(voicemail.value as? String ?? "0") ?? 0) > 0
        }, object: nil)
        try XCTSkipUnless(XCTWaiter.wait(for: [notified], timeout: 15) == .completed,
                          "Requires a real voicemail count on the selected line")
        let count = voicemail.value as? String
        capture(app, "Voicemail badge before restart")
        app.terminate(); app.launch()
        XCTAssertTrue(calls.waitForExistence(timeout: 20)); calls.tap()
        XCTAssertTrue(voicemail.waitForExistence(timeout: 8))
        XCTAssertEqual(voicemail.value as? String, count,
                       "The last confirmed voicemail count must remain visible after restart")
        capture(app, "Voicemail badge restored after restart")
    }
    private func checkContacts(_ app: XCUIApplication) {
        app.buttons["dial.contacts"].tap()
        let back = app.buttons["dial.contacts.back"]
        XCTAssertTrue(back.waitForExistence(timeout: 8), "Contact picker must actually open")
        capture(app, "Contact picker")
        let search = app.textFields["dial.contacts.search"]
        search.tap(); search.typeText("999999")
        XCTAssertTrue(back.isHittable, "Back stays responsive while contact search runs")
        let beforeBack = Date()
        back.tap()
        XCTAssertTrue(app.buttons["dial.key.1"].waitForExistence(timeout: 3))
        print("KEYPAD_CONTACT_BACK_SECONDS", Date().timeIntervalSince(beforeBack))
    }
    private func waitForOrientation(_ app: XCUIApplication, landscape: Bool) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let frame = app.frame
            return (frame.width > frame.height) == landscape && app.buttons["dial.contacts"].isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    }
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name
        attachment.lifetime = .keepAlways; add(attachment)
    }
}
