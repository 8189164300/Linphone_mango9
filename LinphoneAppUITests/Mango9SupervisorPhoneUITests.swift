import XCTest

/// Reads the signed-in owner's Supervisor. Does not send messages or record audio.
final class Mango9SupervisorPhoneUITests: XCTestCase {
    func testAIPrivacyCanBeDeclinedWithoutEnablingChatOrVoice() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.launch()
        let conversations = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
        let launcher = app.buttons["supervisor.open"]
        XCTAssertTrue(launcher.waitForExistence(timeout: 10)); launcher.tap()
        let allow = app.buttons["supervisor.consent.allow"], decline = app.buttons["supervisor.consent.decline"]
        guard allow.waitForExistence(timeout: 35) else { throw XCTSkip("This account already has a user-selected consent choice; do not change it automatically.") }
        XCTAssertTrue(app.links["supervisor.consent.policy"].exists || app.buttons["supervisor.consent.policy"].exists)
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .portrait] {
            XCUIDevice.shared.orientation = orientation
            let ready = NSPredicate { _, _ in (app.frame.height > app.frame.width) == (orientation == .portrait) && allow.isHittable && decline.isHittable }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: ready, object: app)], timeout: 15), .completed)
            Thread.sleep(forTimeInterval: 1)
            XCTAssertTrue(app.frame.contains(allow.frame)); XCTAssertTrue(app.frame.contains(decline.frame))
            let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); screenshot.name = "AI privacy " + String(orientation.rawValue); screenshot.lifetime = .keepAlways; add(screenshot)
        }
        decline.tap()
        XCTAssertTrue(app.buttons["supervisor.consent.review"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["supervisor.send"].exists)
        XCTAssertFalse(app.buttons["supervisor.voice.connect"].exists)
        app.buttons["supervisor.mode"].tap()
        XCTAssertTrue(allow.waitForExistence(timeout: 5)); decline.tap()
        app.buttons["supervisor.close"].tap()
        XCTAssertTrue(launcher.waitForExistence(timeout: 5))
        launcher.tap()
        XCTAssertTrue(allow.waitForExistence(timeout: 20), "Declining must never save acceptance.")
        decline.tap(); app.buttons["supervisor.close"].tap()
    }
    private func requireUserAIConsent(_ app: XCUIApplication) throws {
        let ready = NSPredicate { _, _ in
            app.buttons["supervisor.consent.allow"].exists || app.descendants(matching: .any).matching(identifier: "supervisor.composer").firstMatch.exists
        }
        _ = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: ready, object: app)], timeout: 35)
        if app.buttons["supervisor.consent.allow"].exists {
            throw XCTSkip("Requires the user's own AI privacy acceptance; this test does not accept for them.")
        }
    }
    func testVoiceWaitsForTapAndEndRemainsVisible() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication(); XCUIDevice.shared.orientation = .portrait; app.launch()
        let conversations = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
        let launcher = app.buttons["supervisor.open"]; XCTAssertTrue(launcher.waitForExistence(timeout: 10)); launcher.tap()
        try requireUserAIConsent(app)
        let mode = app.buttons["supervisor.mode"]; XCTAssertTrue(mode.waitForExistence(timeout: 35)); mode.tap()
        let connect = app.buttons["supervisor.voice.connect"], end = app.buttons["supervisor.voice.end"], mute = app.buttons["supervisor.voice.mute"]
        XCTAssertTrue(connect.waitForExistence(timeout: 10))
        Thread.sleep(forTimeInterval: 3)
        XCTAssertTrue(app.staticTexts["Ready to connect"].exists)
        XCTAssertFalse(mute.isEnabled)
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .portrait] {
            XCUIDevice.shared.orientation = orientation
            let ready = NSPredicate { _, _ in (app.frame.height > app.frame.width) == (orientation == .portrait) && end.isHittable }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: ready, object: app)], timeout: 15), .completed)
            Thread.sleep(forTimeInterval: 1.5)
            XCTAssertTrue(app.frame.contains(end.frame)); XCTAssertTrue(app.frame.contains(mute.frame))
            XCTAssertGreaterThanOrEqual(end.frame.width, 140); XCTAssertGreaterThanOrEqual(end.frame.height, 44)
            let capture = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); capture.name = "Voice ready " + String(orientation.rawValue); capture.lifetime = .keepAlways; add(capture)
        }
        end.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "supervisor.composer").firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(connect.exists)
    }
    func testSupervisorOpensInLightUIAndFitsBothOrientations() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.launch()
        let conversations = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
        let launcher = app.buttons["supervisor.open"]
        XCTAssertTrue(launcher.waitForExistence(timeout: 10)); XCTAssertTrue(launcher.isHittable); launcher.tap()
        try requireUserAIConsent(app)
        let mode = app.buttons["supervisor.mode"]
        XCTAssertTrue(mode.waitForExistence(timeout: 45), "The server must authorize the selected account before Voice becomes available.")
        let composer = app.descendants(matching: .any).matching(identifier: "supervisor.composer").firstMatch
        XCTAssertTrue(composer.exists); XCTAssertTrue(composer.isEnabled)
        XCTAssertFalse(app.buttons["supervisor.send"].isEnabled)
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .portrait] {
            XCUIDevice.shared.orientation = orientation
            let rotated = NSPredicate { _, _ in orientation == .portrait ? app.frame.height > app.frame.width : app.frame.width > app.frame.height }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: rotated, object: app)], timeout: 10), .completed)
            // Accessibility frames update before UIKit's rotation snapshot finishes.
            // Let the presentation animation settle before visual evidence is taken.
            Thread.sleep(forTimeInterval: 1.5)
            XCTAssertTrue(mode.isHittable); XCTAssertTrue(composer.isHittable)
            XCTAssertTrue(app.frame.contains(composer.frame))
            let link = app.descendants(matching: .any).matching(identifier: "supervisor.link").firstMatch
            if link.exists {
                XCTAssertTrue(link.label.contains("https://"))
                XCTAssertGreaterThanOrEqual(link.frame.minX, app.frame.minX)
                XCTAssertLessThanOrEqual(link.frame.maxX, app.frame.maxX)
            }
            let capture = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); capture.name = "Supervisor " + (orientation == .portrait ? "portrait" : "landscape"); capture.lifetime = .keepAlways; add(capture)
        }
        app.buttons["supervisor.close"].tap()
        XCTAssertTrue(launcher.waitForExistence(timeout: 10))
    }
}
