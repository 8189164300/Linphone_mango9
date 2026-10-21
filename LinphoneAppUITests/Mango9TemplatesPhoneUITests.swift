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
