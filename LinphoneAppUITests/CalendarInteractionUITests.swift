import XCTest

final class CalendarInteractionUITests: XCTestCase {
	private let app = XCUIApplication()
	override func setUp() { super.setUp(); continueAfterFailure = false }
	private func launch(_ mode: String) {
		app.launchEnvironment["UITEST_CALENDAR"] = mode
		app.launch()
		XCTAssertTrue(app.buttons["appointments.create"].waitForExistence(timeout: 20))
		XCTAssertTrue(app.buttons["appointments.create"].wait(for: \.isEnabled, toEqual: true, timeout: 20))
	}
	private func closeDetails() {
		XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 10))
		app.buttons["Done"].tap()
		XCTAssertTrue(app.buttons["appointments.create"].waitForExistence(timeout: 10))
	}
	private func visibleEvent(prefix: String) -> XCUIElement {
		let matches = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix))
		if !matches.firstMatch.waitForExistence(timeout: 20) {
			let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.lifetime = .keepAlways; add(screenshot)
			let hierarchy = XCTAttachment(string: app.debugDescription); hierarchy.lifetime = .keepAlways; add(hierarchy)
			XCTFail("Missing event button: \(prefix)")
		}
		return matches.allElementsBoundByIndex.first(where: \.isHittable) ?? matches.firstMatch
	}
	private func visibleToday() -> XCUIElement {
		let today = Calendar.current.startOfDay(for: Date())
		let matches = app.buttons.matching(identifier: "calendar.day.\(Int(today.timeIntervalSince1970))")
		XCTAssertTrue(matches.firstMatch.waitForExistence(timeout: 20))
		// Exyte preloads adjacent month pages containing the same boundary date.
		// Interact with the visible page, never its off-screen cached duplicate.
		return matches.allElementsBoundByIndex.first(where: \.isHittable) ?? matches.firstMatch
	}
	private func openTodayFromCount() {
		let day = visibleToday()
		let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '2 events'"), object: day)
		XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 20), .completed)
		XCTAssertFalse(app.staticTexts["Tap"].exists, "Month shows counts, never event names")
		XCTAssertFalse(app.staticTexts["Next"].exists, "Month shows counts, never event names")
		XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'calendar.event.'")).firstMatch.exists)
		// The date cell's blank area must navigate, not only its small count badge.
		day.coordinate(withNormalizedOffset: CGVector(dx: 0.90, dy: 0.85)).tap()
	}
	private func checkMonthCountOpensAllEvents(_ renderer: String) {
		launch(renderer)
		openTodayFromCount()
		for id in [91, 92] {
			let event = visibleEvent(prefix: "calendar.event.\(id)")
			XCTAssertTrue(event.waitForExistence(timeout: 20))
			event.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.8)).tap()
			XCTAssertTrue(app.staticTexts[id == 91 ? "Tap" : "Next"].waitForExistence(timeout: 10))
			closeDetails()
		}
	}
	func testMonthCountOpensDayWithAllEvents() { checkMonthCountOpensAllEvents("month") }
	func testLegacyMonthCountOpensDayWithAllEvents() { checkMonthCountOpensAllEvents("legacy") }
	func testLegacyCrowdedMonthOpensReadableDayAndFirstAndLastEvents() {
		launch("legacy-dense")
		let day = visibleToday()
		let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '30 events'"), object: day)
		XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 20), .completed)
		day.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.85)).tap()
		let agenda = app.scrollViews["calendar.day.agenda"]
		XCTAssertTrue(agenda.waitForExistence(timeout: 20))
		let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.lifetime = .keepAlways; add(screenshot)
		let first = visibleEvent(prefix: "calendar.event.200|")
		XCTAssertGreaterThan(first.frame.width, 250)
		XCTAssertGreaterThanOrEqual(first.frame.height, 44)
		first.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.8)).tap()
		XCTAssertTrue(app.staticTexts["Dense event 01"].waitForExistence(timeout: 10))
		closeDetails()
		let last = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'calendar.event.229|'")).firstMatch
		for _ in 0..<20 {
			if last.exists && last.isHittable { break }
			agenda.swipeUp()
		}
		XCTAssertTrue(last.exists && last.isHittable, "The full day must remain reachable, not truncated to a preview")
		last.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.8)).tap()
		XCTAssertTrue(app.staticTexts["Dense event 30"].waitForExistence(timeout: 10))
		closeDetails()
		// Returning from details retains this day's scroll position.
		XCTAssertTrue(last.isHittable)
		app.buttons["Next day"].tap()
		XCTAssertTrue(agenda.waitForExistence(timeout: 15))
		XCTAssertTrue(visibleEvent(prefix: "calendar.event.200|").isHittable)
		app.buttons["appointments.create"].tap()
		XCTAssertTrue(app.textFields["appointment.title"].waitForExistence(timeout: 10))
		app.buttons["Cancel"].tap()
	}
	func testMonthLongPressStillCreatesOnSelectedDay() {
		for renderer in ["month", "legacy"] {
			launch(renderer)
			let day = visibleToday()
			day.press(forDuration: 0.8)
			XCTAssertTrue(app.textFields["appointment.title"].waitForExistence(timeout: 10))
			XCTAssertTrue(app.navigationBars["New event"].exists)
			app.buttons["Cancel"].tap()
			XCTAssertTrue(day.waitForExistence(timeout: 10), "Holding a day must not also trigger tap navigation")
		}
	}
	func testMonthScrollingDoesNotOpenDayOrCreateEvent() {
		for renderer in ["month", "legacy"] {
			launch(renderer)
			visibleToday().swipeUp()
			XCTAssertEqual(app.buttons["calendar.viewMode"].label, "Calendar view, Month")
			XCTAssertFalse(app.textFields["appointment.title"].exists)
			app.scrollViews.firstMatch.swipeDown()
			XCTAssertEqual(app.buttons["calendar.viewMode"].label, "Calendar view, Month")
		}
	}
	func testRecurringMonthCountOpensDayAndItsServerAppointment() {
		launch("recurring")
		openTodayFromCount()
		let event = visibleEvent(prefix: "calendar.event.91|")
		event.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.8)).tap()
		XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 10))
		XCTAssertTrue(app.staticTexts["Tap"].exists)
		closeDetails()
	}
	func testDayAndWeekEventWhitespaceOpensDetails() {
		launch("month")
		for mode in ["Day", "Week"] {
			app.buttons["calendar.viewMode"].tap()
			app.buttons[mode].tap()
			for id in [91, 92] {
				let event = visibleEvent(prefix: "calendar.event.\(id)")
				event.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.8)).tap()
				closeDetails()
			}
		}
	}
	func testAppointmentRowWhitespaceAndEdgesOpenDetails() {
		launch("list")
		for point in [CGVector(dx: 0.95, dy: 0.85), CGVector(dx: 0.03, dy: 0.05)] {
			let event = app.buttons["appointments.event.91"]
			XCTAssertTrue(event.waitForExistence(timeout: 15))
			event.coordinate(withNormalizedOffset: point).tap()
			closeDetails()
		}
	}
	func testTwoSuccessiveCreatesReturnToListWithFreshForms() {
		launch("list")
		for name in ["First new visit", "Second new visit"] {
			app.buttons["appointments.create"].tap()
			let title = app.textFields["appointment.title"]
			XCTAssertTrue(title.waitForExistence(timeout: 10))
			XCTAssertEqual(title.value as? String, "Title", "A new form must not retain the previous draft")
			title.tap(); title.typeText(name)
			app.buttons["appointment.save"].tap()
			XCTAssertTrue(app.buttons["appointments.create"].waitForExistence(timeout: 15), "Successful save must dismiss the editor")
			XCTAssertTrue(app.staticTexts[name].waitForExistence(timeout: 10))
		}
	}
	func testSaveConflictIsVisibleAndDraftCanBeCorrected() {
		launch("list")
		app.buttons["appointments.create"].tap()
		let title = app.textFields["appointment.title"]
		XCTAssertTrue(title.waitForExistence(timeout: 10))
		title.tap(); title.typeText("Conflict")
		app.buttons["appointment.save"].tap()
		let alert = app.alerts["Event not saved"]
		XCTAssertTrue(alert.waitForExistence(timeout: 10), "Save errors must not be hidden below the visible form")
		alert.buttons["OK"].tap()
		XCTAssertEqual(title.value as? String, "Conflict")
		title.tap(); title.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 8) + "Corrected visit")
		app.buttons["appointment.save"].tap()
		XCTAssertTrue(app.buttons["appointments.create"].waitForExistence(timeout: 15))
		XCTAssertTrue(app.staticTexts["Corrected visit"].waitForExistence(timeout: 10))
	}

	func testExistingEventDateChangePersistsAfterReopening() {
		launch("list")
		app.buttons["appointments.event.91"].tap()
		let edit = app.buttons["appointment.edit"]
		XCTAssertTrue(edit.waitForExistence(timeout: 10))
		XCTAssertTrue(edit.wait(for: \.isEnabled, toEqual: true, timeout: 10)); edit.tap()
		let start = app.descendants(matching: .any).matching(identifier: "appointment.starts").firstMatch
		XCTAssertTrue(start.waitForExistence(timeout: 10))
		start.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.5)).tap()
		let today = Calendar.current.startOfDay(for: Date())
		let moved = Calendar.current.date(byAdding: .day, value: Calendar.current.component(.day, from: today) <= 25 ? 3 : -3, to: today)!
		let dayFormatter = DateFormatter(); dayFormatter.locale = Locale(identifier: "en_US"); dayFormatter.dateFormat = "EEEE, MMMM d"
		let day = dayFormatter.string(from: moved)
		let dayButton = app.buttons.matching(NSPredicate(format: "label == %@", day)).firstMatch
		if !dayButton.waitForExistence(timeout: 5) {
			let tree = XCTAttachment(string: app.debugDescription); tree.lifetime = .keepAlways; add(tree)
			XCTFail("Missing date button \(day)"); return
		}
		dayButton.tap()
		// Dismiss the date popover without changing or cancelling the draft.
		if app.buttons["PopoverDismissRegion"].exists {
			app.buttons["PopoverDismissRegion"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
		}
		XCTAssertTrue(app.buttons["appointment.save"].isEnabled)
		app.buttons["appointment.save"].tap()
		XCTAssertTrue(app.buttons["appointment.edit"].waitForExistence(timeout: 15))
		let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US"); formatter.dateFormat = "MMM d, yyyy"
		let expected = formatter.string(from: moved)
		XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", expected)).firstMatch.waitForExistence(timeout: 10))
		closeDetails()
		app.buttons["appointments.event.91"].tap()
		XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", expected)).firstMatch.waitForExistence(timeout: 10))
	}
}
