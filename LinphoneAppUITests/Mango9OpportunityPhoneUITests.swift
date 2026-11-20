import XCTest

/// Exercises actual server-backed lists and validation without modifying an existing customer.
final class Mango9OpportunityPhoneUITests: XCTestCase {
    /// Explicitly creates two labelled QA Opportunities, verifies their linked record, then archives only those records.
    func testCreateOpportunityFromLeadAndWorkspace() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.activate()
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
        let leads = app.buttons["crm.leads"].firstMatch
        XCTAssertTrue(leads.waitForExistence(timeout: 30)); leads.tap()
        let leadSearch = app.textFields["Search name, phone, or email"]
        XCTAssertTrue(leadSearch.waitForExistence(timeout: 10)); leadSearch.tap(); leadSearch.typeText("QA Opportunity Modal")
        let qaLead = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "QA Opportunity Modal")).firstMatch
        XCTAssertTrue(qaLead.waitForExistence(timeout: 20), "Requires a dedicated QA Lead; never creates against a customer record")
        if app.keyboards.buttons["Search"].exists { app.keyboards.buttons["Search"].tap() }
        let actions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.lead.actions.")).firstMatch
        XCTAssertTrue(actions.waitForExistence(timeout: 30)); actions.tap(); app.buttons["Add Opportunity"].tap()
        let title = app.textFields["crm.opportunity.title"], save = app.buttons["crm.opportunity.save"]
        XCTAssertTrue(title.waitForExistence(timeout: 20))
        let linkedName = app.staticTexts["crm.opportunity.linkedRecord"].label
        XCTAssertFalse(linkedName.isEmpty)
        save.tap()
        XCTAssertTrue(app.staticTexts["Enter a title and choose a valid stage, currency and probability."].waitForExistence(timeout: 5))
        title.tap(); title.typeText("QA cancelled draft")
        capture("Create modal validation and linked Lead")
        app.buttons["crm.opportunity.cancel"].tap()
        XCTAssertTrue(actions.waitForExistence(timeout: 10)); actions.tap(); app.buttons["Add Opportunity"].tap()
        XCTAssertTrue(title.waitForExistence(timeout: 20)); XCTAssertNotEqual(title.value as? String, "QA cancelled draft")
        let stamp = String(Int(Date().timeIntervalSince1970))
        let leadTitle = "QA iOS Lead " + stamp, workspaceTitle = "QA iOS Workspace " + stamp
        title.tap(); title.typeText(leadTitle); save.tap()
        XCTAssertTrue(save.waitForNonExistence(timeout: 30), "Save must confirm and dismiss the modal")
        XCTAssertTrue(actions.waitForExistence(timeout: 30), "Saving from Leads returns to the same list")
        capture("Created from Lead and returned to Leads")
        let leadBack = app.buttons["crm.leads.back"].firstMatch
        if leadBack.isHittable { leadBack.tap() }
        else { app.buttons.matching(NSPredicate(format:"label CONTAINS[c] %@", "back")).firstMatch.tap() }
        let workspace = app.buttons["crm.opportunities"].firstMatch
        XCTAssertTrue(workspace.waitForExistence(timeout: 10)); workspace.tap()
        let add = app.buttons["crm.opportunity.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 20)); add.tap()
        let search = app.textFields["crm.opportunity.recordSearch"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText(linkedName)
        let record = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.record.")).firstMatch
        XCTAssertTrue(record.waitForExistence(timeout: 20)); capture("Lead or Client search in Add Opportunity"); record.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 20)); XCTAssertEqual(app.staticTexts["crm.opportunity.linkedRecord"].label, linkedName)
        title.tap(); title.typeText(workspaceTitle); save.tap()
        XCTAssertTrue(save.waitForNonExistence(timeout: 30), "Workspace save must confirm and dismiss the modal")
        XCTAssertTrue(add.waitForExistence(timeout: 30), "Saving from Opportunities returns to its list")
        for name in [leadTitle, workspaceTitle] {
            let listSearch = app.textFields["crm.opportunity.search"]
            listSearch.tap()
            if let value = listSearch.value as? String, !value.hasPrefix("Search") { listSearch.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count)) }
            listSearch.typeText(name)
            let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row.")).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
            XCTAssertTrue(app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 20))
            XCTAssertTrue(app.staticTexts[linkedName].exists, "The saved Opportunity retains the selected Lead")
            capture("Saved Opportunity linked record " + name)
            app.buttons["crm.opportunity.actions"].tap(); app.buttons["Archive"].firstMatch.tap()
            XCTAssertTrue(app.alerts["Archive Opportunity?"].waitForExistence(timeout: 5)); app.alerts.buttons["Archive"].tap()
            XCTAssertTrue(app.staticTexts["Archived"].waitForExistence(timeout: 20))
            if !app.textFields["crm.opportunity.search"].waitForExistence(timeout: 3) { app.buttons["crm.opportunity.back"].firstMatch.tap() }
        }
        app.buttons["crm.opportunity.back"].firstMatch.tap()
    }

    func testSavedOpportunityFiltersAfterExternalRestart() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.activate()
        let crm=app.buttons["CRM"].firstMatch; XCTAssertTrue(crm.waitForExistence(timeout:30));crm.tap()
        let link=app.buttons["crm.opportunities"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout:30));link.tap()
        let filters=app.buttons["crm.opportunity.filter.more"]
        XCTAssertTrue(filters.waitForExistence(timeout:20));filters.tap()
        let minimum=app.textFields["crm.opportunity.filter.minValue"],maximum=app.textFields["crm.opportunity.filter.maxValue"]
        for _ in 0..<5 {if minimum.isHittable {break};app.swipeUp()}
        XCTAssertEqual(minimum.value as? String,"10");XCTAssertEqual(maximum.value as? String,"20")
        capture("Persistent filters after external process restart")
        let reset=app.buttons["crm.opportunity.filter.reset"]
        for _ in 0..<6 {if reset.isHittable {break};app.swipeUp()}
        reset.tap();app.buttons["crm.opportunity.filter.apply"].tap()
        app.buttons["crm.opportunity.back"].firstMatch.tap()
    }

    func testOpportunityConfigurationFilters() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.activate()
        let crm=app.buttons["CRM"].firstMatch; XCTAssertTrue(crm.waitForExistence(timeout:30)); crm.tap()
        let link=app.buttons["crm.opportunities"].firstMatch; XCTAssertTrue(link.waitForExistence(timeout:20)); link.tap()
        let filters=app.buttons["crm.opportunity.filter.more"]
        XCTAssertTrue(filters.waitForExistence(timeout:20))
        XCTAssertTrue(app.buttons["crm.opportunity.filter.createdBy"].exists)
        XCTAssertFalse(app.buttons["crm.opportunity.filter.currency"].exists)
        filters.tap(); XCTAssertTrue(app.navigationBars["Filters"].waitForExistence(timeout:5)); capture("Opportunity filter configuration")
        let minimum=app.textFields["crm.opportunity.filter.minValue"], maximum=app.textFields["crm.opportunity.filter.maxValue"]
        for _ in 0..<5 {if minimum.isHittable {break};app.swipeUp()}
        minimum.tap(); minimum.typeText("10"); maximum.tap(); maximum.typeText("5")
        let apply=app.buttons["crm.opportunity.filter.apply"]
        XCTAssertFalse(apply.isEnabled)
        maximum.typeText(XCUIKeyboardKey.delete.rawValue+"20"); XCTAssertTrue(apply.isEnabled); capture("Opportunity amount range validation")
        apply.tap(); XCTAssertTrue(filters.waitForExistence(timeout:15))
        app.terminate(); app.launch(); XCTAssertTrue(crm.waitForExistence(timeout:30)); crm.tap()
        XCTAssertTrue(link.waitForExistence(timeout:20)); link.tap(); XCTAssertTrue(filters.waitForExistence(timeout:15)); filters.tap()
        for _ in 0..<5 {if minimum.isHittable {break};app.swipeUp()}
        XCTAssertEqual(minimum.value as? String,"10"); XCTAssertEqual(maximum.value as? String,"20")
        capture("Saved filters restored after app restart")
        let reset=app.buttons["crm.opportunity.filter.reset"]
        for _ in 0..<6 {if reset.isHittable {break};app.swipeUp()}
        XCTAssertTrue(reset.isHittable); reset.tap(); apply.tap()
        XCTAssertTrue(filters.waitForExistence(timeout:10)); filters.tap(); app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(filters.waitForExistence(timeout:10)); app.buttons["crm.opportunity.back"].firstMatch.tap(); XCTAssertTrue(link.waitForExistence(timeout:10))
    }

    func testNativeActivityLinksAndCompactSharing() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.activate()
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
        let workspace = app.buttons["crm.opportunities"].firstMatch
        XCTAssertTrue(workspace.waitForExistence(timeout: 20)); workspace.tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row.")).firstMatch
        guard row.waitForExistence(timeout: 20) else { throw XCTSkip("No accessible Opportunity available for activity checks") }
        row.tap()
        let activity = app.buttons["crm.opportunity.tab.Activity"]
        XCTAssertTrue(activity.waitForExistence(timeout: 20)); if !activity.isHittable { app.swipeUp() }; activity.tap()
        let filter = app.buttons["crm.activity.source"].firstMatch
        XCTAssertTrue(filter.waitForExistence(timeout: 20), "Versioned native activity should replace the legacy timeline")
        XCTAssertTrue(app.buttons["CRM"].firstMatch.isHittable)
        app.swipeUp()
        capture("Native contact activity in Opportunity")
        filter.tap()
        let notes = app.buttons["Notes"].firstMatch
        if notes.waitForExistence(timeout: 5) { notes.tap() }
        capture("Compact activity filter")
        let details = app.buttons["crm.opportunity.tab.Details"]
        if !details.isHittable { app.swipeDown() }
        details.tap()
        app.buttons["crm.opportunity.actions"].tap()
        let share = app.buttons["crm.opportunity.menu.sharing"].firstMatch
        if share.waitForExistence(timeout: 3) { share.tap(); capture("Compact Opportunity sharing sheet"); XCTAssertTrue(app.buttons["crm.opportunity.back"].firstMatch.isHittable); app.buttons["crm.opportunity.back"].firstMatch.tap() }
    }
    func testOpportunityCounterAndRepeatedListNavigation() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.activate()
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
        let workspace = app.buttons["crm.opportunities"].firstMatch
        let count = app.staticTexts["crm.count.opportunities"].firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 20))
        let expected = count.label
        capture("CRM Opportunity counter matches server total")
        let footerY = crm.frame.minY
        var filterY: CGFloat?
        for index in 0..<3 {
            XCTAssertTrue(workspace.waitForExistence(timeout: 10)); workspace.tap()
            let total = app.staticTexts["crm.opportunity.total"]
            XCTAssertTrue(total.waitForExistence(timeout: 5)); XCTAssertEqual(total.label, expected)
            let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row.")).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 5))
            let stage = app.buttons["crm.opportunity.filter.stage"]
            let creator = app.buttons["crm.opportunity.filter.createdBy"]
            XCTAssertEqual(stage.frame.minY, creator.frame.minY, accuracy: 1)
            XCTAssertEqual(stage.frame.width, creator.frame.width, accuracy: 2)
            XCTAssertEqual(crm.frame.minY, footerY, accuracy: 2)
            if let previous = filterY { XCTAssertEqual(stage.frame.minY, previous, accuracy: 2) }
            filterY = stage.frame.minY
            XCTAssertGreaterThan(app.buttons["crm.opportunity.back"].firstMatch.frame.minY, app.buttons["main.menu"].firstMatch.frame.maxY, "Opportunity toolbar must be below the actual native header")
            capture("Stable Opportunity list visit \(index + 1)")
            app.buttons["crm.opportunity.back"].firstMatch.tap()
            if !workspace.waitForExistence(timeout: 3), app.textFields["crm.opportunity.search"].exists {
                // A transient system banner can intercept a header tap.
                app.buttons["crm.opportunity.back"].firstMatch.tap()
            }
            XCTAssertTrue(workspace.waitForExistence(timeout: 10))
        }
    }
    func testFullPageOpportunityWorkspaceAndDetail() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication(); app.activate()
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
        let workspace = app.buttons["crm.opportunities"].firstMatch
        XCTAssertTrue(workspace.waitForExistence(timeout: 30), "Account must have Opportunity access")
        let count = app.staticTexts["crm.count.opportunities"].firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 20), "CRM must show the server Opportunity total")
        let expectedTotal = count.label
        capture("CRM Opportunities link below Clients")
        let footerBefore = app.buttons["CRM"].firstMatch.frame
        workspace.tap()
        XCTAssertTrue(app.textFields["crm.opportunity.search"].waitForExistence(timeout: 30))
        let total = app.staticTexts["crm.opportunity.total"]
        XCTAssertTrue(total.waitForExistence(timeout: 5))
        XCTAssertEqual(total.label, expectedTotal, "CRM counter and full list must count the same Opportunities")
        XCTAssertTrue(app.buttons["crm.opportunity.back"].firstMatch.isHittable)
        XCTAssertTrue(app.buttons["CRM"].firstMatch.isHittable, "Native CRM footer stays visible")
        XCTAssertEqual(app.buttons["CRM"].firstMatch.frame.minY, footerBefore.minY, accuracy: 2, "Footer must not move when entering Opportunities")
        let stageFilter=app.buttons["crm.opportunity.filter.stage"].firstMatch
        let creatorFilter=app.buttons["crm.opportunity.filter.createdBy"].firstMatch
        XCTAssertTrue(stageFilter.waitForExistence(timeout: 10)); XCTAssertTrue(creatorFilter.exists)
        XCTAssertEqual(stageFilter.frame.minY,creatorFilter.frame.minY,accuracy: 1)
        XCTAssertEqual(stageFilter.frame.height,creatorFilter.frame.height,accuracy: 1)
        XCTAssertEqual(stageFilter.frame.width,creatorFilter.frame.width,accuracy: 2)
        capture("Aligned Opportunity filters and stable footer")
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row.")).firstMatch
        guard row.waitForExistence(timeout: 30) else { throw XCTSkip("No accessible Opportunities on this account; detail needs a populated account") }
        if !row.isHittable { app.swipeUp() }
        row.tap()
        XCTAssertTrue(app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Expected close"].waitForExistence(timeout: 30))
        capture("Approved full-page detail portrait")
        app.buttons["crm.opportunity.actions"].tap()
        let edit = app.buttons["crm.opportunity.menu.edit"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 10)); edit.tap()
        for name in ["Stage", "Currency"] {
            let label = app.staticTexts["crm.opportunity.label." + name]
            let choice = app.buttons["crm.opportunity.choice." + name].firstMatch
            XCTAssertTrue(label.waitForExistence(timeout: 10)); XCTAssertTrue(choice.exists)
            XCTAssertEqual(label.frame.midY, choice.frame.midY, accuracy: 3, "Dropdown must sit beside its label")
            XCTAssertGreaterThan(choice.frame.minX, label.frame.maxX)
        }
        capture("Opportunity dropdowns beside labels")
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        if !app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 3), app.staticTexts["crm.opportunity.label.Stage"].exists {
            app.buttons["crm.opportunity.back"].firstMatch.tap()
        }
        XCTAssertTrue(app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 10))
        app.buttons["crm.opportunity.actions"].tap()
        let followup=app.buttons["crm.opportunity.menu.followup"].firstMatch
        XCTAssertTrue(followup.waitForExistence(timeout: 10))
        capture("Three-dot action menu stays over Opportunity detail")
        followup.tap()
        XCTAssertTrue(app.textFields["crm.followup.title"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["crm.followup.save"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.buttons["CRM"].firstMatch.frame.minY,footerBefore.minY,accuracy: 2)
        capture("Follow-up uses calendar start and end times")
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        let notes = app.buttons["crm.opportunity.tab.Notes"]
        if !notes.isHittable { app.swipeUp() }
        XCTAssertTrue(notes.waitForExistence(timeout: 10)); notes.tap()
        capture("Opportunity notes")
        app.buttons["crm.opportunity.tab.Activity"].tap()
        capture("Opportunity activity")
        app.buttons["crm.opportunity.tab.Follow-up"].tap()
        capture("Opportunity follow-up")
        app.buttons["crm.opportunity.tab.Details"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.width > app.frame.height }, object: nil)], timeout: 10), .completed)
        XCTAssertTrue(app.buttons["crm.opportunity.back"].firstMatch.isHittable)
        capture("Approved detail landscape")
        XCUIDevice.shared.orientation = .portrait
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(app.textFields["crm.opportunity.search"].waitForExistence(timeout: 10))
        let filterY = app.buttons["crm.opportunity.filter.stage"].frame.minY
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(workspace.waitForExistence(timeout: 10)); workspace.tap()
        XCTAssertTrue(app.textFields["crm.opportunity.search"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["crm.opportunity.filter.stage"].frame.minY, filterY, accuracy: 2, "Reopening must keep the summary and filter layout stable")
        XCTAssertEqual(app.buttons["CRM"].firstMatch.frame.minY, footerBefore.minY, accuracy: 2)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row.")).firstMatch.waitForExistence(timeout: 5))
        capture("Reopened Opportunity list stays aligned")
    }
    func testOpportunityCardFormAndLeadConversionConfirmation() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        let app = XCUIApplication(); app.activate()
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30), "Sign in to the test account first")
        crm.tap()
        let leads = app.buttons["crm.leads"].firstMatch
        XCTAssertTrue(leads.waitForExistence(timeout: 30)); leads.tap()
        let actions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.lead.actions.")).firstMatch
        XCTAssertTrue(actions.waitForExistence(timeout: 30))
        capture("Lead cards with Opportunity and sharing controls")
        let sharingFilter = app.buttons["crm.sharing.filter"]
        XCTAssertTrue(sharingFilter.exists); sharingFilter.tap()
        XCTAssertTrue(app.buttons["Shared with me"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Shared by me"].exists)
        app.buttons["Shared with me"].tap()
        capture("Incoming sharing filter")
        sharingFilter.tap(); app.buttons["Shared by me"].tap()
        capture("Outgoing sharing filter")
        sharingFilter.tap(); app.buttons["All sharing"].tap()
        XCTAssertTrue(actions.waitForExistence(timeout: 30))
        let sharingBadge = app.buttons["crm.sharing.outgoing"].firstMatch
        if sharingBadge.exists && sharingBadge.isHittable {
            sharingBadge.tap()
            XCTAssertTrue(app.navigationBars["Sharing"].waitForExistence(timeout: 10))
            capture("Sharing names and direction")
            dismissBanner(); app.buttons["Close"].tap()
        }
        dismissBanner(); actions.tap()
        let add = app.buttons["Add Opportunity"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 20)); add.tap()
        let title = app.textFields["crm.opportunity.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        let save = app.buttons["crm.opportunity.save"]
        capture("New Opportunity before validation")
        XCTAssertTrue(save.isHittable); save.tap()
        XCTAssertTrue(app.staticTexts["Enter a title and choose a valid stage, currency and probability."].waitForExistence(timeout: 5))
        capture("New Opportunity after validation")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in title.isHittable }, object: nil)], timeout: 5), .completed)
        title.tap(); title.typeText("Draft UI check — do not save")
        capture("Opportunity form portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.width > app.frame.height && save.isHittable }, object: nil)], timeout: 10), .completed)
        capture("Opportunity form landscape")
        app.buttons["crm.opportunity.cancel"].firstMatch.tap()
        XCUIDevice.shared.orientation = .portrait
        if app.navigationBars["Opportunities"].exists { app.buttons["Back"].firstMatch.tap() }
        XCTAssertTrue(actions.waitForExistence(timeout: 10)); actions.tap()
        app.buttons["Convert to Client"].firstMatch.tap()
        XCTAssertTrue(app.buttons["crm.convert.confirm"].waitForExistence(timeout: 20), "Test lead must be in the test user's conversion scope")
        capture("Lead conversion confirmation")
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(actions.waitForExistence(timeout: 10))
    }
    private func dismissBanner() {
        let banner = XCUIApplication(bundleIdentifier: "com.apple.springboard").descendants(matching: .any).matching(identifier: "NotificationShortLookView").firstMatch
        if banner.exists { banner.swipeUp(); _ = banner.waitForNonExistence(timeout: 5) }
    }
    private func capture(_ name: String) { let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment) }
}
