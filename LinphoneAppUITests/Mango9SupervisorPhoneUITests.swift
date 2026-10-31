import XCTest

/// Reads the signed-in owner's Supervisor. Does not send messages or record audio.
final class Mango9SupervisorPhoneUITests: XCTestCase {
    func testLatestArrowClearsAfterManualScrollAndBottomBounce() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.launch()
        let conversations = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
        let launcher = app.buttons["supervisor.open"]
        XCTAssertTrue(launcher.waitForExistence(timeout: 10)); launcher.tap()
        try requireUserAIConsent(app)
        let scroll = app.scrollViews["supervisor.messages"].firstMatch
        XCTAssertTrue(scroll.waitForExistence(timeout: 30))
        let arrow = app.buttons["chat.latest"]
        scroll.swipeDown()
        guard arrow.waitForExistence(timeout: 5) else { throw XCTSkip("Requires existing history longer than the viewport; do not create messages.") }
        for _ in 0..<15 { if !arrow.exists { break }; scroll.swipeUp() }
        XCTAssertFalse(arrow.exists, "Reaching the latest message must clear the down arrow")
        for _ in 0..<3 { scroll.swipeUp(); XCTAssertFalse(arrow.exists, "Bottom bounce must not leave a stale arrow") }
        scroll.swipeDown(); XCTAssertTrue(arrow.waitForExistence(timeout: 5))
        arrow.tap()
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !arrow.exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed)
        XCTAssertTrue(app.staticTexts["supervisor.latest.timestamp"].firstMatch.isHittable,
                      "The jump must reveal the latest reply, not only hide its button")
        let capture = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        capture.name = "Supervisor at latest without arrow"; capture.lifetime = .keepAlways; add(capture)
        app.buttons["supervisor.close"].tap()
    }
    func testPullingAtExhaustedHistoryAndForegroundDoNotJumpToLatest() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.launch()
        let conversations = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
        let launcher = app.buttons["supervisor.open"]
        XCTAssertTrue(launcher.waitForExistence(timeout: 10)); launcher.tap()
        try requireUserAIConsent(app)
        let scroll = app.scrollViews["supervisor.messages"].firstMatch
        XCTAssertTrue(scroll.waitForExistence(timeout: 30))
        if app.staticTexts["What can I help you with?"].exists { throw XCTSkip("Requires existing history; do not create a test turn.") }
        func reachTop() {
            for _ in 0..<25 {
                let first = scroll.staticTexts.firstMatch
                if first.exists && first.frame.minY >= scroll.frame.minY - 5 { return }
                scroll.swipeDown(velocity: .fast)
            }
            XCTFail("Could not reach the start of the loaded history")
        }
        for _ in 0..<4 {
            reachTop()
            let older = scroll.buttons["Load older messages"].firstMatch
            if !older.exists { break }
            XCTAssertTrue(older.isHittable); older.tap()
            Thread.sleep(forTimeInterval: 1)
        }
        reachTop()
        if scroll.buttons["Load older messages"].exists { throw XCTSkip("This history exceeds four read-only pages; unit tests cover exhausted pagination.") }
        let anchor = scroll.staticTexts.firstMatch
        XCTAssertTrue(anchor.exists)
        let initialY = anchor.frame.minY
        for _ in 0..<3 {
            scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.22))
                .press(forDuration: 0.1, thenDragTo: scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.72)))
            Thread.sleep(forTimeInterval: 0.7)
            XCTAssertEqual(anchor.frame.minY, initialY, accuracy: 4, "Exhausted history must stay at the top after pull-down")
        }
        XCUIDevice.shared.press(.home); app.activate()
        Thread.sleep(forTimeInterval: 1)
        XCTAssertEqual(anchor.frame.minY, initialY, accuracy: 4, "Returning to the app must preserve the older reply being read")
        let capture = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        capture.name = "Exhausted history remains at top"; capture.lifetime = .keepAlways; add(capture)
        app.buttons["supervisor.close"].tap()
    }
    func testSavedMessagesAreVisibleWithoutDraggingOnOpenAndReopen() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.launch()
        let conversations = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
        let launcher = app.buttons["supervisor.open"]
        XCTAssertTrue(launcher.waitForExistence(timeout: 10))
        for attempt in 1...3 {
            if attempt == 3 {
                app.terminate(); app.launch()
                XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
                XCTAssertTrue(launcher.waitForExistence(timeout: 10))
            }
            let launchFrame = launcher.frame
            // Resolve coordinates before opening, then avoid querying the new
            // message hierarchy until the untouched first-paint captures exist.
            app.coordinate(withNormalizedOffset: .zero)
                .withOffset(CGVector(dx: launchFrame.midX, dy: launchFrame.midY)).tap()
            // Capture before querying message accessibility: querying a lazy
            // container can itself force layout and hide the initial-render bug.
            let immediate = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            immediate.name = "Supervisor before accessibility traversal \(attempt)"; immediate.lifetime = .keepAlways; add(immediate)
            Thread.sleep(forTimeInterval: 1)
            let settled = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            settled.name = "Supervisor untouched after one second \(attempt)"; settled.lifetime = .keepAlways; add(settled)
            try requireUserAIConsent(app)
            let scroll = app.scrollViews["supervisor.messages"].firstMatch
            XCTAssertTrue(scroll.waitForExistence(timeout: 30))
            if app.staticTexts["What can I help you with?"].exists {
                throw XCTSkip("The selected conversation has no messages to verify; do not create a test turn.")
            }
            func visibleTextCount() -> Int {
                scroll.staticTexts.allElementsBoundByIndex.filter {
                    let overlap = $0.frame.intersection(scroll.frame)
                    return $0.isHittable && !overlap.isNull && overlap.height > 8 && overlap.width > 8
                }.count
            }
            let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in visibleTextCount() > 0 }, object: nil)
            let result = XCTWaiter.wait(for: [visible], timeout: 5)
            let capture = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            capture.name = "Supervisor initial viewport \(attempt)"; capture.lifetime = .keepAlways; add(capture)
            if result != .completed {
                // Diagnostic only: distinguish already-loaded, offscreen content
                // from a missing history response without sending anything.
                scroll.swipeDown()
                let after = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                after.name = "Supervisor after diagnostic drag"; after.lifetime = .keepAlways; add(after)
                print("Supervisor visible text after diagnostic drag: \(visibleTextCount())")
            }
            XCTAssertEqual(result, .completed, "Saved chat must be visible before any drag or refresh")
            let timestamp = app.staticTexts["supervisor.latest.timestamp"].firstMatch
            if timestamp.exists && timestamp.isHittable {
                var positions: [CGFloat] = []
                for _ in 0..<5 {
                    positions.append(timestamp.frame.minY)
                    Thread.sleep(forTimeInterval: 0.2)
                }
                let movement = (positions.max() ?? 0) - (positions.min() ?? 0)
                print("Supervisor opening \(attempt) latest-message movement: \(movement) points")
                XCTAssertLessThanOrEqual(movement, 3,
                    "The latest message must settle rather than shake between competing bottom anchors")
            }
            if attempt == 2 {
                XCUIDevice.shared.press(.home); app.activate()
                let resumed = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                resumed.name = "Supervisor foreground before dragging"; resumed.lifetime = .keepAlways; add(resumed)
                let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in visibleTextCount() > 0 }, object: nil)
                XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 5), .completed)
            }
            app.buttons["supervisor.close"].tap()
            XCTAssertTrue(launcher.waitForExistence(timeout: 10))
        }
    }
    func testHistoryHasSearchFiltersAndSwipeDeleteWithoutDeletingCustomerData() throws {
        continueAfterFailure = false
        defer { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.launch()
        let conversations = app.buttons["Conversations"].firstMatch
        XCTAssertTrue(conversations.waitForExistence(timeout: 30)); conversations.tap()
        let launcher = app.buttons["supervisor.open"]
        XCTAssertTrue(launcher.waitForExistence(timeout: 10)); launcher.tap()
        try requireUserAIConsent(app)
        let options = app.buttons["supervisor.options"]
        XCTAssertTrue(options.waitForExistence(timeout: 30)); options.tap()
        app.buttons["History"].firstMatch.tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 15))
        let filters = app.segmentedControls["supervisor.history.filter"]
        XCTAssertTrue(filters.exists)
        XCTAssertTrue(filters.buttons["Text only"].exists); XCTAssertTrue(filters.buttons["With voice"].exists)
        let row = app.buttons.matching(identifier: "supervisor.history.row").firstMatch
        if row.waitForExistence(timeout: 15) {
            XCTAssertLessThan(row.frame.height, 200, "History metadata must not expand rows to fill the screen")
            let originalLabel = row.label
            row.swipeLeft()
            XCTAssertTrue(app.buttons["Delete"].firstMatch.waitForExistence(timeout: 5))
            // The row action only opens confirmation. Never confirm a deletion
            // on customer history; cancellation must retain the selected row.
            app.buttons["Delete"].firstMatch.tap()
            let confirmation = app.alerts["Delete conversation?"]
            XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
            XCTAssertTrue(confirmation.buttons["Cancel"].isHittable)
            XCTAssertTrue(confirmation.buttons["Delete"].isHittable)
            XCTAssertEqual(app.alerts.count, 1)
            let deletionCapture = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            deletionCapture.name = "Compact native delete confirmation"; deletionCapture.lifetime = .keepAlways; add(deletionCapture)
            confirmation.buttons["Cancel"].tap()
            XCTAssertFalse(confirmation.exists)
            XCTAssertTrue(row.exists); XCTAssertEqual(row.label, originalLabel)
            let rowsCapture = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            rowsCapture.name = "Compact History rows after cancelling deletion"; rowsCapture.lifetime = .keepAlways; add(rowsCapture)
        }
        search.tap(); search.typeText("__M9_NO_MATCH_HISTORY_UI_CHECK__")
        let empty = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "No matching")).firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 30))
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "History search with no customer content"; attachment.lifetime = .keepAlways; add(attachment)
        // iOS 26 labels the native search-dismiss button "Close". Scope it
        // to History so an underlying screen's Cancel/Close is never tapped.
        let historyNavigation = app.navigationBars["History"]
        let closeSearch = historyNavigation.buttons["Close"]
        let cancelSearch = historyNavigation.buttons["Cancel"]
        if closeSearch.exists { closeSearch.tap() }
        else if cancelSearch.exists { cancelSearch.tap() }
        let done = historyNavigation.buttons["Done"]
        XCTAssertTrue(done.waitForExistence(timeout: 5)); done.tap()
        XCTAssertTrue(options.waitForExistence(timeout: 5))
    }
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
