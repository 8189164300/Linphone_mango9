import XCTest
@testable import LinphoneApp

private final class OpportunityURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try XCTUnwrap(Self.handler)(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

@MainActor final class Mango9OpportunityTests: XCTestCase {
    func testNavigationBlocksSaveAndOffersDiscardOnlyForDirtyForm() {
        let navigation = Mango9CRMNavigationGuard(), owner = UUID()
        var left = 0, pending: (() -> Void)?
        navigation.update(owner: owner, busy: true, dirty: true) { pending = $0 }
        navigation.request { left += 1 }
        XCTAssertEqual(left, 0); XCTAssertNil(pending)
        navigation.update(owner: owner, busy: false, dirty: true) { pending = $0 }
        navigation.request { left += 1 }
        XCTAssertEqual(left, 0); XCTAssertNotNil(pending)
        pending = nil // Keep editing.
        XCTAssertEqual(left, 0)
        navigation.request { left += 1 }; pending?(); pending = nil
        XCTAssertEqual(left, 1)
        navigation.update(owner: owner, busy: false, dirty: false) { pending = $0 }
        navigation.request { left += 1 }
        XCTAssertEqual(left, 2); XCTAssertNil(pending)
        navigation.remove(owner); navigation.request { left += 1 }
        XCTAssertEqual(left, 3)
    }

    func testTemporarySMSNavigationKeepsDraftButCannotInterruptSave() {
        let navigation = Mango9CRMNavigationGuard(), owner = UUID()
        var visits = 0, confirmations = 0
        navigation.update(owner: owner, busy: false, dirty: true) { _ in confirmations += 1 }
        navigation.request({ visits += 1 }, preservingDraft: true)
        XCTAssertEqual(visits, 1); XCTAssertEqual(confirmations, 0)
        navigation.request { visits += 1 }
        XCTAssertEqual(visits, 1); XCTAssertEqual(confirmations, 1, "The retained draft still needs confirmation for a destructive exit")
        navigation.update(owner: owner, busy: true, dirty: true) { _ in confirmations += 1 }
        navigation.request({ visits += 1 }, preservingDraft: true)
        XCTAssertEqual(visits, 1)
    }

    func testStaleFormCannotUnlockNewerSavingForm() {
        let navigation = Mango9CRMNavigationGuard(), older = UUID(), newer = UUID()
        var left = false
        navigation.update(owner: older, busy: false, dirty: false) { _ in XCTFail() }
        navigation.update(owner: newer, busy: true, dirty: false) { _ in XCTFail() }
        navigation.update(owner: older, busy: false, dirty: false) { _ in XCTFail() }
        navigation.remove(older); navigation.request { left = true }
        XCTAssertFalse(left)
        navigation.remove(newer); navigation.request { left = true }
        XCTAssertTrue(left)
    }

    func testCalendarLinkOnlyUsesCurrentUsersValidFollowupEvent() throws {
        for (mine, id, expected) in [(true, 91, Optional(91)), (false, 91, nil), (true, 0, nil)] {
            let row = try Mango9Opportunity(["id": 12, "title": "Fixture", "stage": "new", "followUp": ["mine": mine, "eventId": id]])
            XCTAssertEqual(row.followupEventID, expected)
        }
        let row = try Mango9Opportunity(["id": 12, "title": "Fixture", "stage": "new"])
        XCTAssertNil(row.followupEventID)
    }

    func testDashboardPreservesCurrencyNullZeroAndRejectsBrokenTotals() throws {
        var data: [String: Any] = ["open": 3, "won": 3, "due": 2, "overdue": 1, "today": 1,
            "unknownOpen": 1, "unknownWon": 1, "estimatedWon": 1,
            "openCurrencies": [["currency": "USD", "value": "1234.50"], ["currency": "EUR", "value": NSNull()]],
            "currencies": [["currency": "USD", "value": "50.00"], ["currency": "EUR", "value": "0.00"]]]
        let totals = try Mango9OpportunityDashboardData(data)
        XCTAssertEqual(totals.open, 3); XCTAssertEqual(totals.won, 3)
        XCTAssertEqual(totals.openAmounts[0].value, "1234.50"); XCTAssertNil(totals.openAmounts[1].value)
        XCTAssertEqual(totals.wonAmounts[1].value, "0.00"); XCTAssertEqual(totals.estimatedWon, 1)
        data["due"] = 99; XCTAssertThrowsError(try Mango9OpportunityDashboardData(data))
        data["due"] = 2; data["won"] = true; XCTAssertThrowsError(try Mango9OpportunityDashboardData(data))
        data["won"] = 3; data["currencies"] = [["currency": "USD", "value": "NaN"]]
        XCTAssertThrowsError(try Mango9OpportunityDashboardData(data))
        data["currencies"] = [["currency": "USD", "value": "1"], ["currency": "USD", "value": "2"]]
        XCTAssertThrowsError(try Mango9OpportunityDashboardData(data))
        let empty: [String: Any] = ["open": 0, "won": 0, "due": 0, "overdue": 0, "today": 0,
            "unknownOpen": 0, "unknownWon": 0, "estimatedWon": 0, "openCurrencies": [], "currencies": []]
        XCTAssertEqual(try Mango9OpportunityDashboardData(empty).openAmounts.count, 0)
    }

    func testStageColorsPreserveConfigurationAndUseSafeFallbacks() throws {
        let metadata = try Mango9OpportunityMetadata(["userId": 1, "permissions": ["access": true], "statusSpecs": [
            ["id": "custom", "name": "Custom", "classification": "open", "probability": 10, "color": "#123456"],
            ["id": "won", "name": "Won", "classification": "won", "probability": 100, "color": "invalid"]]])
        XCTAssertEqual(metadata.stages[0].colorHex, "#123456")
        XCTAssertEqual(metadata.stages[1].colorHex, "#15803D")
        XCTAssertEqual(Mango9CRMStatusStyle.hex(nil, fallback: "New"), "#718096")
        XCTAssertEqual(Mango9CRMStatusStyle.hex(nil, fallback: "Custom status"), Mango9CRMStatusStyle.hex(nil, fallback: "CUSTOM STATUS"))
    }

    func testConfigurationFilterRangesUseServerKeys() throws {
        var f = Mango9OpportunityFilters(); f.createdBy = "11"; f.owner = "12"; f.minValue = "0"; f.maxValue = "1000.50"; f.closeFrom = "2026-11-20"; f.closeTo = "2026-11-30"; f.currency = "USD"
        let q = try f.query(); XCTAssertEqual(q["createdBy"], "11"); XCTAssertEqual(q["owner"], "12"); XCTAssertEqual(q["minValue"], "0"); XCTAssertEqual(q["closeTo"], "2026-11-30")
        var draft = f; draft.minValue = "2000"; XCTAssertThrowsError(try draft.query()); XCTAssertEqual(f.minValue, "0")
        draft = f; draft.closeFrom = "2026-02-30"; XCTAssertThrowsError(try draft.query())
        draft = f; draft.createdFrom = "2026-11-21"; draft.createdTo = "2026-11-20"; XCTAssertThrowsError(try draft.query())
        draft = f; draft.maxValue = "1.999"; XCTAssertThrowsError(try draft.query())
        XCTAssertEqual(try Mango9OpportunityFilters().query(), [:]); XCTAssertEqual(Mango9OpportunityFilters().activeCount, 0)
    }

    func testOpportunityFiltersPersistPerAccountAndListAcrossReload() throws {
        let name = "opportunity-preferences-test-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        var value = Mango9OpportunityPreference(); value.stage = "qualified"; value.filters.currency = "USD"; value.filters.closeFrom = "2026-11-20"; value.filters.minValue = "10"
        let actor = session()
        value.save(actor, contact: nil, defaults: defaults)
        let reopened = try XCTUnwrap(UserDefaults(suiteName: name))
        XCTAssertEqual(Mango9OpportunityPreference.load(actor, contact: nil, defaults: reopened), value)
        XCTAssertEqual(Mango9OpportunityPreference.load(session("other"), contact: nil, defaults: reopened), .init())
        XCTAssertEqual(Mango9OpportunityPreference.load(session(base: "https://other.example.invalid/app/api/v2"), contact: nil, defaults: reopened), .init())
        XCTAssertEqual(Mango9OpportunityPreference.load(actor, contact: 42, defaults: reopened), .init())
        var draft = value; draft.filters.currency = "EUR"
        XCTAssertEqual(Mango9OpportunityPreference.load(actor, contact: nil, defaults: reopened), value)
        Mango9OpportunityPreference().save(actor, contact: nil, defaults: defaults)
        XCTAssertEqual(Mango9OpportunityPreference.load(actor, contact: nil, defaults: reopened), .init())
        defaults.set(Data("broken".utf8), forKey: Mango9OpportunityPreference.key(actor, contact: nil))
        XCTAssertEqual(Mango9OpportunityPreference.load(actor, contact: nil, defaults: reopened), .init())
    }

    private func session(_ user: String = "opportunity-fixture", base: String = "https://crm.example.invalid/app/api/v2") -> Mango9Session {
        .init(crmId: "fixture", crmBaseUrl: "https://crm.example.invalid/app", crmApiBaseUrl: base,
              userId: user, parentClientId: "fixture", role: "buyer", loginId: "fixture@example.invalid", displayName: "Fixture",
              accessToken: "fixture-token", refreshToken: "fixture-refresh", smsChatApi: "", connectWebsocket: "",
              enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:\(user)@crm.example.invalid")
    }

    func testVisibilityRestrictionsAndRestorationKeepStoredSharesSeparate() throws {
        func choices(_ ids: [Int]) throws -> Mango9OpportunityMetadata {
            try .init(["userId": 7, "permissions": ["access": true, "share": true], "statusSpecs": [], "users": ids.map { ["id": $0, "name": "Member \($0)"] as [String: Any] }])
        }
        let all = try choices([7, 8, 9]), limited = try choices([7, 9]), empty = try choices([])
        let prior = [7, 8, 9, 9, 999]
        XCTAssertEqual(all.visibleRecipients(prior, ownerID: 7), [8, 9])
        XCTAssertEqual(limited.visibleRecipients(prior, ownerID: 7), [9])
        XCTAssertEqual(limited.visibleOwnerFilter("8"), ""); XCTAssertEqual(limited.visibleOwnerFilter("9"), "9")
        XCTAssertEqual(empty.visibleRecipients(prior, ownerID: 7), [])
        XCTAssertEqual(all.visibleRecipients(prior, ownerID: 7), [8, 9]); XCTAssertEqual(all.visibleOwnerFilter("8"), "8")
        XCTAssertEqual(prior, [7, 8, 9, 9, 999])
    }
    func testFailedVisibilityRefreshCannotSendStaleSharingChoices() async throws {
        let previous = Mango9SessionStore.activeIdentity
        let identity = try Mango9SessionStore.save(session("offline-visibility"), persist: false, makeActive: true)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [OpportunityURLProtocol.self]
        let transport = URLSession(configuration: config)
        defer {
            OpportunityURLProtocol.handler = nil; transport.invalidateAndCancel()
            try? Mango9OpportunityCommands.save(nil, session: identity, contact: 12, operation: "mutate")
            try? Mango9SessionStore.remove(for: identity.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous)
        }
        let meta = try Mango9OpportunityMetadata(["userId": 101, "permissions": ["access": true, "share": true], "statusSpecs": [], "users": [["id": 102, "name": "Fixture member"]]])
        let detail = Mango9OpportunityDetailModel(id: 12, session: identity, metadata: meta, transport: transport)
        detail.row = try Mango9Opportunity(["id": 12, "title": "Fixture", "stage": "new", "revision": "1", "owner_id": 101, "canManage": true])
        var mutations = 0
        OpportunityURLProtocol.handler = { request in
            if request.httpMethod != "GET" { mutations += 1 }
            throw URLError(.notConnectedToInternet)
        }
        let saved = await detail.mutate("share", values: ["userIds": [102]])
        XCTAssertFalse(saved); XCTAssertEqual(mutations, 0)
        XCTAssertFalse(detail.busy); XCTAssertNotNil(detail.error); XCTAssertNil(detail.pending)
        XCTAssertNil(try Mango9OpportunityCommands.load(identity, contact: 12, operation: "mutate"))
    }
    func testSharingRefreshesChoicesButNeverRewritesAnUncertainRequest() async throws {
        let previous = Mango9SessionStore.activeIdentity
        let identity = try Mango9SessionStore.save(session("visibility-fixture"), persist: false, makeActive: true)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [OpportunityURLProtocol.self]
        let transport = URLSession(configuration: config)
        defer {
            OpportunityURLProtocol.handler = nil; transport.invalidateAndCancel()
            try? Mango9OpportunityCommands.save(nil, session: identity, contact: 12, operation: "mutate")
            try? Mango9SessionStore.remove(for: identity.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous)
        }
        var visible = [101, 103], failFirst = true, sends = [[String: Any]](), metadataReads = 0
        let row: [String: Any] = ["id": 12, "title": "Fixture", "stage": "new", "revision": "1", "owner_id": 101, "canManage": true]
        OpportunityURLProtocol.handler = { request in
            var bytes = request.httpBody ?? Data()
            if bytes.isEmpty, let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }; var buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; bytes.append(buffer, count: count) }
            }
            let path = request.url!.path
            var data: [String: Any]
            if path.hasSuffix("/metadata") {
                metadataReads += 1
                data = ["userId": 101, "permissions": ["access": true, "share": true], "statusSpecs": [], "users": visible.map { ["id": $0, "name": "Member \($0)"] as [String: Any] }]
            } else if path.hasSuffix("/share") {
                sends.append(try JSONSerialization.jsonObject(with: bytes) as! [String: Any])
                if failFirst { failFirst = false; throw URLError(.timedOut) }
                data = ["id": 12, "action": "share", "saved": true]
            } else { data = row }
            return (200, try JSONSerialization.data(withJSONObject: ["success": true, "data": data]))
        }
        let detail = Mango9OpportunityDetailModel(id: 12, session: identity, metadata: nil, transport: transport)
        await detail.load()
        let first = await detail.mutate("share", values: ["userIds": [101, 102, 103]])
        XCTAssertFalse(first); XCTAssertEqual(sends.first?["userIds"] as? [Int], [103])
        XCTAssertEqual(metadataReads, 2, "Refresh before a fresh save, even when details were already loaded")
        visible = [101, 102]
        let retry = await detail.mutate("share", values: ["userIds": [102]])
        XCTAssertTrue(retry, detail.error ?? "")
        XCTAssertEqual(sends.count, 2); XCTAssertEqual(sends[0]["requestId"] as? String, sends[1]["requestId"] as? String)
        XCTAssertEqual(sends[1]["userIds"] as? [Int], [103], "A timeout retry retains its original body and idempotency key")
        XCTAssertEqual(detail.metadata?.owners.map(\.id), [101, 102])
    }
    func testStatusSaveKeepsRevisionAndRetriesOriginalChoiceAfterTimeout() async throws {
        let previous = Mango9SessionStore.activeIdentity
        let identity = try Mango9SessionStore.save(session("status-fixture"), persist: false, makeActive: true)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [OpportunityURLProtocol.self]
        let transport = URLSession(configuration: config)
        defer {
            OpportunityURLProtocol.handler = nil; transport.invalidateAndCancel()
            try? Mango9OpportunityCommands.save(nil, session: identity, contact: 12, operation: "mutate")
            try? Mango9SessionStore.remove(for: identity.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous)
        }
        var failFirst = true, sends = [[String: Any]]()
        var row: [String: Any] = ["id": 12, "title": "Fixture", "stage": "new", "revision": "7", "owner_id": 101, "canManage": true]
        OpportunityURLProtocol.handler = { request in
            var bytes = request.httpBody ?? Data()
            if bytes.isEmpty, let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }; var buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; bytes.append(buffer, count: count) }
            }
            let path = request.url!.path
            var data: [String: Any]
            if path.hasSuffix("/metadata") {
                data = ["userId": 101, "permissions": ["access": true, "edit": true], "statusSpecs": []]
            } else if path.hasSuffix("/stage") {
                sends.append(try JSONSerialization.jsonObject(with: bytes) as! [String: Any])
                if failFirst { failFirst = false; throw URLError(.timedOut) }
                row["stage"] = "qualified"; row["revision"] = "8"
                data = ["id": 12, "action": "stage", "saved": true]
            } else { data = row }
            return (200, try JSONSerialization.data(withJSONObject: ["success": true, "data": data]))
        }
        let detail = Mango9OpportunityDetailModel(id: 12, session: identity, metadata: nil, transport: transport)
        await detail.load()
        let first = await detail.mutate("stage", values: ["stage": "qualified"])
        XCTAssertFalse(first); XCTAssertNotNil(detail.pending); XCTAssertFalse(detail.can("update"))
        let retry = await detail.mutate("stage", values: ["stage": "proposal"])
        XCTAssertTrue(retry, detail.error ?? "")
        XCTAssertEqual(sends.count, 2)
        XCTAssertEqual(sends[0]["revision"] as? String, "7")
        XCTAssertEqual(sends[0]["requestId"] as? String, sends[1]["requestId"] as? String)
        XCTAssertEqual(sends[1]["stage"] as? String, "qualified")
        XCTAssertEqual(detail.row?.stage, "qualified"); XCTAssertEqual(detail.row?.revision, "8"); XCTAssertNil(detail.pending)
        detail.metadata = try Mango9OpportunityMetadata(["userId": 101, "permissions": ["access": true, "edit": false], "statusSpecs": []])
        let denied = await detail.mutate("stage", values: ["stage": "proposal"])
        XCTAssertFalse(denied); XCTAssertEqual(sends.count, 2)
    }
    private func metadata() throws -> Mango9OpportunityMetadata {
        try .init(["userId": "101", "permissions": ["access": true, "create": true, "assign": false],
            "users": [["id": "101", "name": "Fixture"]], "sources": ["Website"],
            "statusSpecs": [
                ["id": "custom-qualify", "name": "Assessment", "classification": "open", "probability": 37, "default": true, "retired": false],
                ["id": "custom-won", "name": "Confirmed", "classification": "won", "probability": 100, "default": false, "retired": false],
                ["id": "custom-lost", "name": "Declined", "classification": "lost", "probability": 0, "default": false, "retired": false],
                ["id": "retired", "name": "Old", "classification": "open", "probability": 10, "default": false, "retired": true]]])
    }
    func testNativeActivityTargetsAndReadPathsAreStrict() throws {
        let target = try XCTUnwrap(Mango9CRMActivityTarget(["screen": "quote", "id": 42]))
        XCTAssertEqual(target.mobilePath, "documents/quotes/42")
        for raw: [String: Any] in [["screen": "quote", "id": "../1"], ["screen": "web", "id": "https://other.invalid"], ["screen": "activity", "contactId": 1, "key": "sms:1"], ["screen": "sms", "conversationId": String(repeating: "a", count: 64), "phone": "../1"]] { XCTAssertNil(Mango9CRMActivityTarget(raw)) }
        let request = try Mango9OpportunityAPI.request(session(), path: "", mobilePath: "contacts/7/activity")
        XCTAssertEqual(request.url?.path, "/app/api/v2/mobile/contacts/7/activity")
        XCTAssertThrowsError(try Mango9OpportunityAPI.request(session(), path: "", body: [:], mobilePath: "contacts/7/activity"))
        XCTAssertThrowsError(try Mango9OpportunityAPI.request(session(), path: "", mobilePath: "contacts/7/../8/activity"))
    }
    func testDuplicateCreationMatchesActualSavedRecord() throws {
        XCTAssertEqual(try Mango9OpportunityAPI.creationID(["id": 42, "action": "create", "saved": false, "duplicate": true, "unchanged": true]), 42)
        XCTAssertThrowsError(try Mango9OpportunityAPI.creationID(["id": 42, "action": "create", "saved": false, "duplicate": true]))
        let command: [String: Any] = ["contactId": 7, "values": ["title": "Deal", "value": "1.00", "next_due_at": "2026-11-20T15:00:00Z"]]
        var row: [String: Any] = ["contact_id": 7, "title": "Deal", "value": "1.0", "next_due_at": "2026-11-20T07:00:00-08:00"]
        XCTAssertTrue(Mango9OpportunityAPI.duplicateMatches(row, command: command))
        row["value"] = "2.00"; XCTAssertFalse(Mango9OpportunityAPI.duplicateMatches(row, command: command))
        row["value"] = "1.0"; row["contact_id"] = 8; XCTAssertFalse(Mango9OpportunityAPI.duplicateMatches(row, command: command))
    }
    func testMetadataPreservesCustomStagesAndFiltersRetired() throws {
        let metadata = try metadata()
        XCTAssertEqual(metadata.userID, 101)
        XCTAssertEqual(metadata.owners.first?.id, 101)
        XCTAssertEqual(metadata.stages.map(\.id), ["custom-qualify", "custom-won", "custom-lost"])
        XCTAssertEqual(metadata.stages.first?.probability, 37)
        XCTAssertTrue(metadata.stages.first?.isDefault == true)
        XCTAssertFalse(metadata.permissions["assign"] == true)
    }
    func testOverviewCountsOpportunitiesNotContactsAndDoesNotCrossAccountsOrExpireSilently() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let data: [String: Any] = ["items": [
            ["id": 7, "contact_id": 100, "title": "First deal", "stage": "custom-qualify"],
            ["id": 8, "contact_id": 100, "title": "Second deal", "stage": "custom-qualify"]],
            "summary": [[String: Any]](), "pagination": ["page": 1, "total": 12, "hasMore": true]]
        let overview = try Mango9OpportunityOverview(session: session(), metadata: metadata(), data: data, now: now)
        XCTAssertEqual(overview.total, 12, "The CRM counter uses the server total, not this page's contacts or rows")
        XCTAssertEqual(overview.rows.map(\.id), [7, 8], "Two Opportunities for one lead stay separate")
        XCTAssertTrue(overview.matches(session(), now: now.addingTimeInterval(29)))
        XCTAssertFalse(overview.matches(session(), now: now.addingTimeInterval(30)))
        XCTAssertFalse(overview.matches(session("other-account"), now: now))
        XCTAssertFalse(overview.matches(session(base: "https://other.example.invalid/app/api/v2"), now: now))
        var malformed = data
        malformed["pagination"] = ["page": 1, "total": -1, "hasMore": false]
        XCTAssertThrowsError(try Mango9OpportunityOverview(session: session(), metadata: metadata(), data: malformed))
    }
    func testCreatePayloadMatchesServerAndKeepsMoneyExact() throws {
        var draft = Mango9OpportunityDraft(); draft.title = "  Consulting  "; draft.stage = "custom-qualify"
        draft.value = "999999999999.99"; draft.hasNextDue = true; draft.nextAction = "Call back"
        let values = try draft.values(metadata: metadata())
        XCTAssertEqual(Set(values.keys), ["title", "stage", "value", "currency", "probability", "probability_mode", "close_date", "source", "description", "next_action", "next_due_at", "actual_value", "actual_close_date", "loss_reason"])
        XCTAssertEqual(values["title"] as? String, "Consulting")
        XCTAssertEqual(values["value"] as? String, "999999999999.99")
        XCTAssertEqual(values["probability"] as? Int, 37)
        XCTAssertEqual(values["probability_mode"] as? String, "stage")
        XCTAssertTrue(values["close_date"] is NSNull)
        XCTAssertNotNil(ISO8601DateFormatter().date(from: values["next_due_at"] as! String))
        XCTAssertNoThrow(try JSONSerialization.data(withJSONObject: values))
    }
    func testUnknownStageAndInvalidAmountsRejected() throws {
        var draft = Mango9OpportunityDraft(); draft.title = "Fixture"; draft.stage = "retired"
        XCTAssertThrowsError(try draft.values(metadata: metadata()))
        draft.stage = "custom-qualify"
        for amount in ["-1", "1.234", "1,000", "NaN", "1000000000000", "1e9"] {
            draft.value = amount; XCTAssertThrowsError(try draft.values(metadata: metadata()), amount)
        }
        draft.value = ""; XCTAssertTrue(try draft.values(metadata: metadata())["value"] is NSNull)
    }
    func testLostOutcomeRequiresReasonAndOpenClearsOutcome() throws {
        var draft = Mango9OpportunityDraft(); draft.title = "Fixture"; draft.stage = "custom-lost"
        XCTAssertThrowsError(try draft.values(metadata: metadata()))
        draft.lossReason = "Timing"; draft.actualValue = "0.00"
        let lost = try draft.values(metadata: metadata())
        XCTAssertEqual(lost["loss_reason"] as? String, "Timing")
        XCTAssertNotNil(lost["actual_close_date"] as? String)
        draft.stage = "custom-qualify"
        let open = try draft.values(metadata: metadata())
        XCTAssertTrue(open["actual_close_date"] is NSNull); XCTAssertTrue(open["actual_value"] is NSNull)
        XCTAssertEqual(open["loss_reason"] as? String, "")
    }
    func testFollowupRequiresActionAndManualProbabilityIsUsed() throws {
        var draft = Mango9OpportunityDraft(); draft.title = "Fixture"; draft.stage = "custom-qualify"; draft.hasNextDue = true
        XCTAssertThrowsError(try draft.values(metadata: metadata()))
        draft.nextAction = "Email proposal"; draft.manualProbability = true; draft.probability = 62
        XCTAssertEqual(try draft.values(metadata: metadata())["probability"] as? Int, 62)
    }
    func testRequestsKeepOriginAndUseCorrectLegacyAndMobilePaths() throws {
        let session = session()
        let request = try Mango9OpportunityAPI.request(session, path: "metadata")
        XCTAssertEqual(request.url?.absoluteString, "https://crm.example.invalid/app/api/opportunities/metadata")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
        let conversion = try Mango9OpportunityAPI.request(session, path: "123/convert-to-client", contact: true, body: ["revision": String(repeating: "a", count: 64), "requestId": UUID().uuidString])
        XCTAssertEqual(conversion.url?.path, "/app/api/v2/mobile/contacts/123/convert-to-client")
        XCTAssertEqual(conversion.httpMethod, "POST")
        XCTAssertThrowsError(try Mango9OpportunityAPI.request(self.session(base: "http://crm.example.invalid/app/api/v2"), path: "metadata"))
        XCTAssertThrowsError(try Mango9OpportunityAPI.request(self.session(base: "https://user:pass@crm.example.invalid/app/api/v2"), path: "metadata"))
        XCTAssertThrowsError(try Mango9OpportunityAPI.request(session, path: "../other"))
    }
    func testSuccessEnvelopeDoesNotRequireMessageAndErrorsAreNotEmptyLists() throws {
        let payload = Data(#"{"success":true,"data":{"counts":[{"id":"12","count":"3"}]}}"#.utf8)
        let result = try Mango9OpportunityAPI.decode(payload, status: 200)
        XCTAssertNotNil(result["counts"])
        XCTAssertThrowsError(try Mango9OpportunityAPI.decode(Data(#"{"success":false,"message":"Permission denied"}"#.utf8), status: 403))
        XCTAssertThrowsError(try Mango9OpportunityAPI.decode(Data(#"{"success":true}"#.utf8), status: 200))
        XCTAssertNil(Mango9OpportunityAPI.integer(true))
        XCTAssertNil(Mango9OpportunityAPI.integer(1.5))
    }
    func testPendingCommandSurvivesReadWithExactRequestIDAndBody() throws {
        let session = session(), operation = "fixture-create"
        defer { try? Mango9OpportunityCommands.save(nil, session: session, contact: 123, operation: operation) }
        let body: [String: Any] = ["requestId": UUID().uuidString, "contactId": 123, "ownerId": 101, "values": ["title": "Fixture", "value": "0.01"]]
        try Mango9OpportunityCommands.save(body, session: session, contact: 123, operation: operation)
        let restored = try XCTUnwrap(Mango9OpportunityCommands.load(session, contact: 123, operation: operation))
        XCTAssertEqual(restored["requestId"] as? String, body["requestId"] as? String)
        XCTAssertEqual(restored["values"] as? [String: String], body["values"] as? [String: String])
        XCTAssertNil(try Mango9OpportunityCommands.load(self.session("different"), contact: 123, operation: operation))
    }
    func testNetworkResponseCannotCrossAccountSwitch() async throws {
        let previous = Mango9SessionStore.activeIdentity, current = session(), replacement = session("replacement")
        try Mango9SessionStore.save(current, persist: false, makeActive: true)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [OpportunityURLProtocol.self]
        let transport = URLSession(configuration: config)
        defer {
            transport.invalidateAndCancel(); OpportunityURLProtocol.handler = nil
            try? Mango9SessionStore.remove(for: current.sipIdentity!); try? Mango9SessionStore.remove(for: replacement.sipIdentity!)
            Mango9SessionStore.activate(sipIdentity: previous)
        }
        OpportunityURLProtocol.handler = { _ in
            try Mango9SessionStore.save(replacement, persist: false, makeActive: true)
            return (200, Data(#"{"success":true,"data":{"items":[]}}"#.utf8))
        }
        do { _ = try await Mango9OpportunityAPI.send(current, transport: transport); XCTFail("Stale account accepted") }
        catch { XCTAssertEqual((error as? Mango9OpportunityFailure)?.message, Mango9OpportunityFailure.accountChanged.message) }
    }
    func testSharingDirectionsAndReadOnlyCapabilitiesDecode() throws {
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let sharing = try decoder.decode(Mango9ContactSharing.self, from: Data(#"{"shared_with_me":true,"shared_by_me":false,"entries":[{"id":17,"shared_by":101,"shared_by_name":"Alex","target_user_id":102,"shared_with_name":"Jordan","via":"appointment"}]}"#.utf8))
        XCTAssertTrue(sharing.sharedWithMe); XCTAssertFalse(sharing.sharedByMe)
        XCTAssertEqual(sharing.entries.first?.sharedByName, "Alex")
        XCTAssertEqual(sharing.entries.first?.sharedWithName, "Jordan")
        XCTAssertEqual(sharing.entries.first?.via, "appointment")
        let permissions = try decoder.decode(Mango9ContactPermissions.self, from: Data(#"{"can_update":false,"can_delete":false}"#.utf8))
        XCTAssertFalse(permissions.canUpdate); XCTAssertFalse(permissions.canDelete)
        XCTAssertEqual(Mango9CRMSharingFilter.allCases.map(\.rawValue), ["", "with_me", "by_me"])
    }

    func testRestrictedOpportunityCannotRevealLinkedContact() throws {
        let row = try Mango9Opportunity(["id": 12, "title": "Fixture", "stage": "new", "contact_id": 33,
            "contact_state": "restricted", "canOpenContact": false, "contact": ["id": 33, "name": "Must not display"]])
        XCTAssertNil(row.contactID)
        XCTAssertEqual(row.contactName, "Restricted contact")
        XCTAssertTrue(row.contact.isEmpty)
    }

    func testMoneyKeepsUnknownDistinctFromZeroAndWeightsExactDecimal() throws {
        let row = try Mango9Opportunity(["id": 12, "title": "Fixture", "stage": "new", "value": "999999999999.99", "probability": 37, "currency": "USD"])
        XCTAssertEqual(row.weightedValue, "369999999999.9963")
        XCTAssertEqual(Mango9OpportunityStyle.money(nil, currency: "USD"), "Not entered")
        XCTAssertEqual(Mango9OpportunityStyle.money("0.00", currency: "USD"), "$0")
    }

    func testRetiredStageCanOnlyBeRetainedByItsExistingRecord() throws {
        let metadata = try metadata()
        XCTAssertTrue(metadata.displayStages.contains { $0.id == "retired" })
        var draft = Mango9OpportunityDraft(); draft.title = "Fixture"; draft.stage = "retired"
        XCTAssertThrowsError(try draft.values(metadata: metadata))
        XCTAssertThrowsError(try draft.values(metadata: metadata, existingStage: "custom-qualify"))
        XCTAssertNoThrow(try draft.values(metadata: metadata, existingStage: "retired"))
    }

    func testServerDatesRoundTripWithoutShiftingDateOnlyFields() throws {
        XCTAssertNotNil(Mango9OpportunityStyle.parseDate("2026-11-19 15:30:00+00"))
        XCTAssertNotNil(Mango9OpportunityStyle.parseDate("2026-11-19T15:30:00.123Z"))
        XCTAssertEqual(Mango9OpportunityStyle.date("2026-12-05"), "Dec 5, 2026")
        let row = try Mango9Opportunity(["id": 12, "title": "Fixture", "stage": "new", "next_due_at": "2026-11-19T15:30:00Z", "close_date": "2026-12-05"])
        XCTAssertEqual(Mango9OpportunityDraft.date(row.draft.closeDate), "2026-12-05")
        XCTAssertTrue(row.draft.hasNextDue)
    }

    func testFollowupUsesOneOpportunityUpdateAndRetryKeepsRequestID() async throws {
        let previous = Mango9SessionStore.activeIdentity
        let identity = try Mango9SessionStore.save(session("followup-fixture"), persist: false, makeActive: true)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [OpportunityURLProtocol.self]
        let transport = URLSession(configuration: config)
        defer {
            OpportunityURLProtocol.handler = nil; transport.invalidateAndCancel()
            for operation in ["followup", "mutate"] { try? Mango9OpportunityCommands.save(nil, session: identity, contact: 12, operation: operation) }
            try? Mango9SessionStore.remove(for: identity.sipIdentity!); Mango9SessionStore.activate(sipIdentity: previous)
        }
        var row: [String: Any] = ["id": 12, "title": "Fixture", "stage": "custom-qualify", "revision": "1", "currency": "USD", "probability": 37,
            "value": "123.45", "description": "Keep", "contact_id": 33, "canOpenContact": true, "contact_state": "available", "contact": ["id": 33]]
        let meta: [String: Any] = ["userId": "101", "permissions": ["access": true, "edit": true], "users": [], "sources": [],
            "statusSpecs": [["id": "custom-qualify", "name": "Assessment", "classification": "open", "probability": 37, "default": true, "retired": false]]]
        var requests: [String] = [], applied = Set<String>(), calendarRequests = 0
        OpportunityURLProtocol.handler = { request in
            let path = request.url!.path
            var bytes = request.httpBody ?? Data()
            if bytes.isEmpty, let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; bytes.append(buffer, count: count) }
            }
            let body = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any] ?? [:]
            let data: [String: Any]
            if path.contains("/calendar/") { calendarRequests += 1; throw URLError(.badURL) }
            else if path.hasSuffix("/opportunities/metadata") { data = meta }
            else if path.hasSuffix("/12/update") {
                let requestID = try XCTUnwrap(body["requestId"] as? String); requests.append(requestID)
                let values = try XCTUnwrap(body["values"] as? [String: Any])
                XCTAssertEqual(values["value"] as? String, "123.45"); XCTAssertEqual(values["description"] as? String, "Keep")
                if applied.insert(requestID).inserted { row.merge(values) { _, new in new }; row["revision"] = String(applied.count + 1) }
                row["onCalendar"] = !(row["next_due_at"] is NSNull)
                row["followUp"] = ["eventId": 91, "holderName": "Owner", "mine": true]
                if requests.count == 1 { throw URLError(.timedOut) }
                data = ["id": 12, "action": "update", "saved": true]
            } else if path.hasSuffix("/opportunities/12") { data = row }
            else { throw URLError(.badURL) }
            return (200, try JSONSerialization.data(withJSONObject: ["success": true, "data": data]))
        }
        let detail = Mango9OpportunityDetailModel(id: 12, session: identity, metadata: nil, transport: transport)
        let schedule = Mango9OpportunityFollowupModel(detail); await schedule.load()
        XCTAssertTrue(schedule.writable, schedule.error ?? "")
        schedule.title = "Test follow-up"; schedule.start = Date(timeIntervalSince1970: 1796148000)
        let first = await schedule.save(); XCTAssertFalse(first); XCTAssertEqual(requests.count, 1)
        let reopened = Mango9OpportunityFollowupModel(detail); await reopened.load()
        let retry = await reopened.save(); XCTAssertTrue(retry, reopened.error ?? "")
        XCTAssertEqual(requests[0], requests[1]); XCTAssertEqual(applied.count, 1); XCTAssertEqual(calendarRequests, 0)
        XCTAssertEqual(detail.row?.followupCalendarStatus, "On your Calendar")
        let reschedule = Mango9OpportunityFollowupModel(detail); await reschedule.load()
        reschedule.start = reschedule.start.addingTimeInterval(3600)
        let moved = await reschedule.save(); XCTAssertTrue(moved); XCTAssertEqual(applied.count, 2)
        let clear = Mango9OpportunityFollowupModel(detail); await clear.load(); clear.scheduled = false
        let cleared = await clear.save(); XCTAssertTrue(cleared); XCTAssertEqual(detail.row?.followupCalendarStatus, "Not on Calendar")
        XCTAssertEqual(calendarRequests, 0)
        try Mango9OpportunityCommands.save(["phase": "sending"], session: identity, contact: 12, operation: "followup")
        let legacy = Mango9OpportunityFollowupModel(detail); await legacy.load()
        XCTAssertFalse(legacy.writable); let blocked = await legacy.save(); XCTAssertFalse(blocked)
        XCTAssertNotNil(legacy.journal); XCTAssertEqual(calendarRequests, 0)
    }
}
