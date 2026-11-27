import XCTest

/// Exercises actual server-backed lists and validation without modifying an existing customer.
final class Mango9OpportunityPhoneUITests: XCTestCase {
    func testLiveLeadClientPickerFiltersAndTransitions() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["CRM"].firstMatch.waitForExistence(timeout: 30)); app.buttons["CRM"].firstMatch.tap()
        let workspace = app.buttons["crm.opportunities"].firstMatch
        XCTAssertTrue(workspace.waitForExistence(timeout: 20))
        for pass in 0..<3 {
            workspace.tap()
            capture("Opportunity list transition \(pass + 1)")
            XCTAssertTrue(app.buttons["crm.opportunity.add"].waitForExistence(timeout: 20)); app.buttons["crm.opportunity.add"].tap()
            capture("Lead or Client transition \(pass + 1)")
            XCTAssertTrue(app.textFields["crm.opportunity.recordSearch"].waitForExistence(timeout: 10))
            if pass == 0 {
                for (filter, expected) in [("Clients", "Client"), ("Leads", "Lead"), ("All", "")] {
                    app.buttons["crm.opportunity.recordType"].tap(); app.buttons[filter].firstMatch.tap()
                    let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.record."))
                    let empty = app.staticTexts["No matching leads or clients. Use Add new to create one."]
                    let ready = NSPredicate { _, _ in rows.firstMatch.exists || empty.exists }
                    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: ready, object: nil)], timeout: 25), .completed)
                    XCTAssertFalse(app.buttons["Retry"].exists)
                    if !expected.isEmpty {
                        for row in rows.allElementsBoundByIndex where row.isHittable {
                            XCTAssertTrue(row.staticTexts[expected].exists || row.label.components(separatedBy: ", ").contains(expected), "Wrong kind under \(filter): \(row.label)")
                        }
                    }
                    capture("Live picker filtered to \(filter)")
                }
            }
            app.buttons["crm.opportunity.back"].firstMatch.tap()
            XCTAssertTrue(app.buttons["crm.opportunity.add"].waitForExistence(timeout: 10))
            app.buttons["crm.opportunity.back"].firstMatch.tap()
            XCTAssertTrue(workspace.waitForExistence(timeout: 10))
        }
    }

    func testRepeatedColdLaunchAndAccountMenuReadOnly() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        for index in 0..<12 {
            app.terminate(); app.launch()
            XCTAssertTrue(app.buttons["CRM"].firstMatch.waitForExistence(timeout: 30), "Cold launch \(index + 1) failed")
            let menu = app.buttons["main.menu"].firstMatch
            XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
            let account = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Account ")).firstMatch
            XCTAssertTrue(account.waitForExistence(timeout: 10), "Account rendering failed on launch \(index + 1)")
            XCTAssertEqual(app.state, .runningForeground)
            if index == 11 { capture("Account menu after twelve cold launches") }
        }
    }

    /// Find an owned scheduled follow-up without editing any customer record.
    func testExistingOpportunityCalendarLinkReadOnly() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["CRM"].firstMatch.waitForExistence(timeout: 30)); app.buttons["CRM"].firstMatch.tap()
        let workspace = app.buttons["crm.opportunities"].firstMatch
        XCTAssertTrue(workspace.waitForExistence(timeout: 20)); workspace.tap()
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row."))
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 30))
        var visited = Set<String>()
        for _ in 0..<8 {
            let ids = rows.allElementsBoundByIndex.filter { $0.isHittable && !visited.contains($0.identifier) }.map(\.identifier)
            for id in ids {
                visited.insert(id)
                let row = app.buttons[id]
                guard row.isHittable else { continue }
                row.tap()
                let followup = app.buttons["Follow-up"].firstMatch
                XCTAssertTrue(followup.waitForExistence(timeout: 20)); followup.tap()
                let link = app.buttons["crm.followup.calendar"].firstMatch
                if link.waitForExistence(timeout: 2) {
                    capture("Owned Opportunity follow-up before Calendar")
                    link.tap()
                    let starts = app.otherElements["appointment.startSummary"].firstMatch
                    let startText = app.staticTexts["appointment.startSummary"].firstMatch
                    let loaded = NSPredicate { _, _ in starts.exists || startText.exists }
                    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: loaded, object: nil)], timeout: 30), .completed, app.debugDescription)
                    XCTAssertFalse(app.staticTexts["This linked appointment could not be opened. You can still browse its date in Calendar."].exists)
                    capture("Calendar opens the linked appointment")
                    app.buttons["Done"].firstMatch.tap()
                    XCTAssertTrue(app.buttons["appointments.back"].waitForExistence(timeout: 10))
                    capture("Calendar selected follow-up date")
                    app.buttons["appointments.back"].tap()
                    XCTAssertTrue(link.waitForExistence(timeout: 10))
                    app.buttons["crm.opportunity.back"].firstMatch.tap()
                    XCTAssertTrue(app.buttons[id].waitForExistence(timeout: 10))
                    return
                }
                app.buttons["crm.opportunity.back"].firstMatch.tap()
                XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 10))
            }
            app.swipeUp()
        }
        throw XCTSkip("No owned scheduled follow-up is available; run the dedicated QA round-trip instead")
    }

    /// Creates a disposable Opportunity on an existing dedicated QA lead, checks
    /// its server-managed Calendar event, removes the follow-up, then archives it.
    func testTemporaryQAFollowupCalendarRoundTrip() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["CRM"].firstMatch.waitForExistence(timeout: 30)); app.buttons["CRM"].firstMatch.tap()
        let workspace = app.buttons["crm.opportunities"].firstMatch
        XCTAssertTrue(workspace.waitForExistence(timeout: 20)); workspace.tap()
        XCTAssertTrue(app.buttons["crm.opportunity.add"].waitForExistence(timeout: 20)); app.buttons["crm.opportunity.add"].tap()
        let search = app.textFields["crm.opportunity.recordSearch"]
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("QA Opportunity Modal")
        let record = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.record.")).firstMatch
        XCTAssertTrue(record.waitForExistence(timeout: 20), "Requires an existing dedicated QA lead")
        XCTAssertTrue(record.label.contains("QA Opportunity Modal"), "Never attach the test to a customer")
        record.tap()
        let title = "QA Calendar navigation " + String(Int(Date().timeIntervalSince1970))
        let field = app.textFields["crm.opportunity.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 20)); field.tap(); field.typeText(title)
        app.buttons["crm.opportunity.save"].tap()
        XCTAssertTrue(app.buttons["crm.opportunity.add"].waitForExistence(timeout: 30))
        let listSearch = app.textFields["crm.opportunity.search"]
        listSearch.tap()
        if let value = listSearch.value as? String, !value.hasPrefix("Search") { listSearch.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count)) }
        listSearch.typeText(title + "\n")
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row.")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20)); XCTAssertTrue(row.label.contains(title)); row.tap()
        XCTAssertTrue(app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 20)); app.buttons["crm.opportunity.actions"].tap()
        app.buttons["Schedule"].firstMatch.tap()
        let action = app.textFields["crm.followup.title"]
        XCTAssertTrue(action.waitForExistence(timeout: 20)); action.tap(); action.typeText("QA navigation check\n")
        capture("QA follow-up due time before saving")
        app.buttons["crm.followup.save"].tap()
        XCTAssertTrue(app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 30))
        app.buttons["Follow-up"].firstMatch.tap()
        let link = app.buttons["crm.followup.calendar"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 20), app.debugDescription)
        capture("QA scheduled Opportunity links to Calendar"); link.tap()
        let start = app.descendants(matching: .any).matching(identifier: "appointment.startSummary").firstMatch
        XCTAssertTrue(start.waitForExistence(timeout: 30), app.debugDescription)
        let editEvent = app.buttons["appointment.edit"]
        if editEvent.exists {
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: editEvent)], timeout: 20), .completed)
        }
        XCTAssertFalse(app.buttons["Refresh appointment"].exists)
        capture("QA linked Calendar appointment title and time")
        app.buttons["Done"].firstMatch.tap()
        XCTAssertTrue(app.buttons["appointments.back"].waitForExistence(timeout: 10))
        capture("QA Calendar selected date after closing appointment")
        app.buttons["appointments.back"].tap()
        XCTAssertTrue(link.waitForExistence(timeout: 10)); XCTAssertTrue(app.staticTexts[title].exists)
        capture("Calendar Back returns to the same QA Opportunity")
        app.buttons["crm.opportunity.actions"].tap(); app.buttons["Schedule"].firstMatch.tap()
        XCTAssertTrue(action.waitForExistence(timeout: 20))
        app.switches["Schedule in Calendar"].firstMatch.tap()
        app.buttons["crm.followup.save"].tap()
        XCTAssertTrue(app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 30))
        XCTAssertTrue(link.waitForNonExistence(timeout: 15)); XCTAssertTrue(app.staticTexts["Not on Calendar"].firstMatch.exists)
        capture("QA follow-up removed from Calendar")
        app.buttons["crm.opportunity.actions"].tap(); app.buttons["Archive"].firstMatch.tap()
        XCTAssertTrue(app.alerts["Archive Opportunity?"].waitForExistence(timeout: 5)); app.alerts.buttons["Archive"].tap()
        XCTAssertTrue(app.staticTexts["Archived"].waitForExistence(timeout: 20))
        capture("QA Opportunity archived after Calendar cleanup")
        // Archiving removes the selected row and may already return to the list.
        if !listSearch.waitForExistence(timeout: 3) { app.buttons["crm.opportunity.back"].firstMatch.tap() }
        XCTAssertTrue(listSearch.waitForExistence(timeout: 10)); listSearch.tap()
        listSearch.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: title.count) + "\n")
        app.buttons["crm.opportunity.back"].firstMatch.tap()
    }

    func testDashboardAndStatusMenuReadOnly() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch(); XCUIDevice.shared.orientation = .portrait
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
        let metric = app.buttons["crm.opportunity.dashboard.Open opportunities"].firstMatch
        XCTAssertTrue(metric.waitForExistence(timeout: 30), app.debugDescription)
        capture("CRM Opportunity dashboard and footer")
        metric.tap()
        XCTAssertTrue(app.buttons["crm.opportunity.dashboard.clear"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["crm.opportunity.add"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.textFields["crm.opportunity.search"].exists)
        XCTAssertFalse(app.staticTexts["Weighted value"].exists)
        XCTAssertFalse(app.staticTexts["Won / Lost"].exists)
        XCTAssertFalse(app.staticTexts["Open value"].exists)
        capture("Search filters and Opportunities list without duplicate dashboard")
        app.buttons["crm.opportunity.add"].tap()
        XCTAssertTrue(app.textFields["crm.opportunity.recordSearch"].waitForExistence(timeout: 10), "Adding an Opportunity starts with Lead or Client selection")
        capture("Add Opportunity linked Lead or Client selection")
        XCTAssertTrue(app.buttons["CRM"].firstMatch.isHittable, "The existing footer remains usable")
        app.buttons["crm.opportunity.recordType"].tap()
        XCTAssertTrue(app.buttons["Leads"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Clients"].firstMatch.exists)
        app.buttons["All"].firstMatch.tap()
        for kind in ["Lead", "Client"] {
            app.buttons["crm.opportunity.newRecord"].tap()
            app.buttons["Add " + kind].firstMatch.tap()
            XCTAssertTrue(app.textFields["First name"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.buttons["CRM"].firstMatch.isHittable, "Creation remains within the app footer")
            XCTAssertFalse(app.buttons["Cancel"].exists, "Creation uses an inner page with Back")
            capture("Add " + kind + " inside header and footer")
            let name = app.textFields["First name"]
            name.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5)); name.typeText("Unsaved navigation check")
            capture("Draft text entered before Back")
            XCTAssertEqual(name.value as? String, "Unsaved navigation check", "Typing must reach the visible form before testing discard")
            app.buttons["crm.opportunity.back"].firstMatch.tap()
            XCTAssertTrue(app.buttons["Keep editing"].waitForExistence(timeout: 5)); app.buttons["Keep editing"].tap()
            XCTAssertEqual(name.value as? String, "Unsaved navigation check")
            name.typeText("\n") // Dismiss the keyboard before using the footer.
            XCTAssertTrue(app.buttons["crm.footer.crm"].isHittable)
            app.buttons["crm.footer.crm"].tap()
            XCTAssertTrue(app.buttons["Keep editing"].waitForExistence(timeout: 5)); app.buttons["Keep editing"].tap()
            XCTAssertTrue(name.exists, "Footer navigation must not silently lose the draft")
            app.buttons["crm.opportunity.back"].firstMatch.tap()
            XCTAssertTrue(app.buttons["Discard changes"].waitForExistence(timeout: 5)); app.buttons["Discard changes"].tap()
            XCTAssertTrue(app.textFields["crm.opportunity.recordSearch"].waitForExistence(timeout: 10))
        }
        let retainedSearch = app.textFields["crm.opportunity.recordSearch"]
        retainedSearch.tap(); retainedSearch.typeText("a")
        app.buttons["crm.opportunity.recordType"].tap(); app.buttons["Leads"].firstMatch.tap()
        let linkedRecord = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.record.")).firstMatch
        XCTAssertTrue(linkedRecord.waitForExistence(timeout: 20)); linkedRecord.tap()
        XCTAssertTrue(app.textFields["crm.opportunity.title"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["CRM"].firstMatch.isHittable)
        capture("Add Opportunity inner page with linked record")
        let newTitle = app.textFields["crm.opportunity.title"]
        newTitle.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5)); newTitle.typeText("Navigation draft")
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Discard changes"].waitForExistence(timeout: 5)); app.buttons["Discard changes"].tap()
        XCTAssertTrue(app.textFields["crm.opportunity.recordSearch"].waitForExistence(timeout: 10))
        XCTAssertEqual(retainedSearch.value as? String, "a")
        XCTAssertTrue(app.buttons["crm.opportunity.recordType"].label.contains("Leads"))
        capture("Picker retains search and selected record type")
        app.buttons["crm.opportunity.back"].tap()
        XCTAssertTrue(app.buttons["crm.opportunity.add"].waitForExistence(timeout: 10))
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "crm.opportunity.row."))
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 20))
        let row = try XCTUnwrap(rows.allElementsBoundByIndex.first { $0.isHittable && $0.frame.minY > 180 && $0.frame.maxY < app.frame.height - 120 }, "Choose a card fully above the footer")
        row.tap()
        let status = app.buttons["crm.opportunity.status"].firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 30)); XCTAssertTrue(status.isEnabled)
        capture("Opportunity top status badge")
        status.tap(); capture("Opportunity configured status menu")
        if app.buttons["Won"].firstMatch.exists {
            app.buttons["Won"].firstMatch.tap()
            XCTAssertTrue(app.buttons["Save"].waitForExistence(timeout: 10))
            capture("Compact closing details")
            app.buttons["Cancel"].firstMatch.tap()
        }
        app.buttons["crm.opportunity.actions"].tap()
        let edit = app.buttons["crm.opportunity.menu.edit"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 10)); edit.tap()
        XCTAssertTrue(app.staticTexts["Opportunity details"].waitForExistence(timeout: 10))
        capture("Styled Opportunity edit cards")
        let editTitle = app.textFields["Opportunity title"]
        XCTAssertTrue(editTitle.waitForExistence(timeout: 5)); editTitle.tap(); editTitle.typeText(" unsaved")
        let draftTitle = editTitle.value as? String
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Keep editing"].waitForExistence(timeout: 5)); app.buttons["Keep editing"].tap()
        XCTAssertEqual(editTitle.value as? String, draftTitle)
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Discard changes"].waitForExistence(timeout: 5)); app.buttons["Discard changes"].tap()
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.94)).tap() // Outside the menu; never select a status.
    }

    func testCRMToSMSReturnsToSameLeadReadOnly() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch()
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
        let leads = app.buttons["crm.leads"].firstMatch
        XCTAssertTrue(leads.waitForExistence(timeout: 20)); leads.tap()
        let record = app.buttons["crm.record.lead"].firstMatch
        XCTAssertTrue(record.waitForExistence(timeout: 30)); record.tap()
        let name = app.staticTexts["crm.record.name"].firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 20)); let original = name.label
        let phone = app.buttons["crm.record.phone"].firstMatch
        guard phone.exists else { throw XCTSkip("The first available Lead has no phone; no records were changed") }
        phone.tap(); app.buttons["SMS with Mango9"].firstMatch.tap()
        let back = app.buttons["chat.back"].firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 20)); capture("SMS opened from CRM without sending")
        for kind in ["media", "documents"] {
            app.buttons["chat.actions"].tap()
            let fileMenu = app.buttons[kind == "media" ? "Media" : "Documents"].firstMatch
            XCTAssertTrue(fileMenu.waitForExistence(timeout: 5)); fileMenu.tap()
            let fileBack = app.buttons["chat." + kind + ".back"]
            XCTAssertTrue(fileBack.waitForExistence(timeout: 10))
            let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: fileBack)
            XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 10), .completed, app.debugDescription)
            XCTAssertGreaterThanOrEqual(fileBack.frame.width, 44)
            capture("Native " + kind + " Back control")
            fileBack.tap(); XCTAssertTrue(back.waitForExistence(timeout: 10))
        }
        back.tap()
        XCTAssertTrue(name.waitForExistence(timeout: 15)); XCTAssertEqual(name.label, original)
        XCTAssertTrue(app.buttons["crm.record.back"].exists)
        capture("SMS Back restores the same Lead detail")
        app.buttons["crm.record.back"].tap()
        XCTAssertTrue(record.waitForExistence(timeout: 15))
    }

    func testRecordingsBackReadOnly() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch()
        let menu = app.buttons["main.menu"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 30)); menu.tap()
        let recordings = app.staticTexts["Recordings"].firstMatch
        guard recordings.waitForExistence(timeout: 5) else { throw XCTSkip("Recordings disabled by account settings") }
        recordings.tap()
        let back = app.buttons["recordings.back"]
        XCTAssertTrue(back.waitForExistence(timeout: 10)); XCTAssertTrue(back.isHittable)
        XCTAssertGreaterThanOrEqual(back.frame.width, 44)
        capture("Native Recordings Back control"); back.tap()
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
    }

    func testLeadAndClientStatusLayoutReadOnly() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch()
        XCUIDevice.shared.orientation = .portrait
        let crm = app.buttons["CRM"].firstMatch
        XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
        let leads = app.buttons["crm.leads"].firstMatch
        XCTAssertTrue(leads.waitForExistence(timeout: 20)); leads.tap()
        XCTAssertTrue(app.buttons["crm.record.lead"].firstMatch.waitForExistence(timeout: 30))
        capture("Larger colored Lead filters and aligned badges")
        app.buttons["crm.leads.back"].firstMatch.tap()
        let clients = app.buttons["crm.clients"].firstMatch
        if !clients.isHittable { app.swipeUp() }
        XCTAssertTrue(clients.waitForExistence(timeout: 10)); clients.tap()
        XCTAssertTrue(app.buttons["crm.record.client"].firstMatch.waitForExistence(timeout: 30))
        capture("Larger colored Client filters and aligned badges")
    }

    /// Reads existing records only; does not save, delete, or change sharing.
    func testPreviousNotesInExistingLeadAndClient() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.terminate(); app.launch()
        XCUIDevice.shared.orientation = .portrait
        for kind in ["lead", "client"] {
            let crm = app.buttons["CRM"].firstMatch
            XCTAssertTrue(crm.waitForExistence(timeout: 30)); crm.tap()
            let link = app.buttons[kind == "lead" ? "crm.leads" : "crm.clients"].firstMatch
            XCTAssertTrue(link.waitForExistence(timeout: 20)); link.tap()
            let row = app.buttons["crm.record." + kind].firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 30), "An existing record is required for this read-only check")
            row.tap()
            let heading = app.staticTexts["Previous notes"].firstMatch
            for _ in 0..<12 {
                if heading.exists && heading.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(heading.waitForExistence(timeout: 20))
            XCTAssertFalse(app.buttons["Retry notes"].exists, "History must load successfully, not show an empty success after an API failure")
            XCTAssertFalse(app.textViews["note_history"].exists, "Previous notes are not an editable field")
            capture("Inline previous notes " + kind)
            app.buttons["crm.record.back"].tap()
            if kind == "lead" { app.buttons["crm.leads.back"].firstMatch.tap() }
        }
    }

    /// Explicitly creates two labelled QA Opportunities, verifies their linked record, then archives only those records.
    func testCreateOpportunityFromLeadAndWorkspace() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication(); app.terminate(); app.launch()
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
        XCTAssertTrue(app.staticTexts["Enter a title and choose a valid status, currency and probability."].waitForExistence(timeout: 5))
        title.tap(); title.typeText("QA cancelled draft")
        capture("Create modal validation and linked Lead")
        app.buttons["crm.opportunity.back"].tap()
        XCTAssertTrue(app.buttons["Discard changes"].waitForExistence(timeout: 5)); app.buttons["Discard changes"].tap()
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
        let app = XCUIApplication(); app.terminate(); app.launch()
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
        let app = XCUIApplication(); app.terminate(); app.launch()
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
        let app = XCUIApplication(); app.terminate(); app.launch()
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
        let app = XCUIApplication(); app.terminate(); app.launch()
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
        let app = XCUIApplication(); app.terminate(); app.launch()
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
        for name in ["Status", "Currency"] {
            let label = app.staticTexts["crm.opportunity.label." + name]
            let choice = app.buttons["crm.opportunity.choice." + name].firstMatch
            XCTAssertTrue(label.waitForExistence(timeout: 10)); XCTAssertTrue(choice.exists)
            XCTAssertEqual(label.frame.midY, choice.frame.midY, accuracy: 3, "Dropdown must sit beside its label")
            XCTAssertGreaterThan(choice.frame.minX, label.frame.maxX)
        }
        capture("Opportunity dropdowns beside labels")
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        if !app.buttons["crm.opportunity.actions"].waitForExistence(timeout: 3), app.staticTexts["crm.opportunity.label.Status"].exists {
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
        XCTAssertFalse(app.datePickers["crm.followup.ends"].exists)
        capture("Follow-up uses server-managed Calendar due time")
        let nextAction = app.textFields["crm.followup.title"]
        nextAction.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5)); nextAction.typeText(" unsaved navigation check")
        let nextActionDraft = nextAction.value as? String
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Keep editing"].waitForExistence(timeout: 5)); app.buttons["Keep editing"].tap()
        XCTAssertEqual(nextAction.value as? String, nextActionDraft)
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Discard changes"].waitForExistence(timeout: 5)); app.buttons["Discard changes"].tap()
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
        let app = XCUIApplication(); app.terminate(); app.launch()
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
        XCTAssertTrue(app.staticTexts["Enter a title and choose a valid status, currency and probability."].waitForExistence(timeout: 5))
        capture("New Opportunity after validation")
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in title.isHittable }, object: nil)], timeout: 5), .completed)
        title.tap(); title.typeText("Draft UI check — do not save")
        capture("Opportunity form portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.frame.width > app.frame.height && save.isHittable }, object: nil)], timeout: 10), .completed)
        capture("Opportunity form landscape")
        app.buttons["crm.opportunity.back"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Discard changes"].waitForExistence(timeout: 5)); app.buttons["Discard changes"].tap()
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
