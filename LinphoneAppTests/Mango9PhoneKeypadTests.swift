import XCTest
import linphonesw
@testable import LinphoneApp

final class Mango9PhoneKeypadTests: XCTestCase {
    func testCallKitRingingRepairsSavedEarlyMediaWithoutChangingOutgoingMedia() throws {
        for outgoing in [false, true] {
            let config = try Factory.Instance.createConfigFromString(data: "[sip]\nincoming_calls_early_media=1\nauto_send_ringing=0\nreal_early_media=\(outgoing ? 1 : 0)\n")
            CorePreferences.configureIncomingCallKitRinging(config)
            CorePreferences.configureIncomingCallKitRinging(config)
            XCTAssertFalse(config.getBool(section: "sip", key: "incoming_calls_early_media", defaultValue: true))
            XCTAssertTrue(config.getBool(section: "sip", key: "auto_send_ringing", defaultValue: false))
            XCTAssertEqual(config.getBool(section: "sip", key: "real_early_media", defaultValue: !outgoing), outgoing)
        }
    }
    func testMissedReceiptsSurviveRestartAndClearOnlyWhenRead() throws {
        var state = Mango9MissedCallState(current: ["old"], legacyUnreadCount: 0)
        XCTAssertEqual(state.reconcile(current: ["new", "old"]), 1)
        var restarted = try JSONDecoder().decode(Mango9MissedCallState.self, from: JSONEncoder().encode(state))
        XCTAssertEqual(restarted.reconcile(current: ["new", "old"]), 1)
        XCTAssertEqual(restarted.reconcile(current: ["new", "old"], markSeen: true), 0)
        var restartedAgain = try JSONDecoder().decode(Mango9MissedCallState.self, from: JSONEncoder().encode(restarted))
        XCTAssertEqual(restartedAgain.reconcile(current: ["new", "old"]), 0)
        XCTAssertEqual(restartedAgain.reconcile(current: ["next", "new", "old"]), 1)
    }
    func testMissedReceiptsIgnoreHistoricCallsDeduplicateAndPruneDeletedCalls() {
        var state = Mango9MissedCallState(current: ["recent", "older"], legacyUnreadCount: 1)
        XCTAssertEqual(state.reconcile(current: ["recent", "recent", "older"]), 1)
        XCTAssertEqual(state.reconcile(current: ["older"]), 0)
        XCTAssertEqual(state.reconcile(current: []), 0)
        XCTAssertTrue(state.known.isEmpty)
        XCTAssertEqual(state.reconcile(current: ["next"]), 1)
    }
    func testReadingOneLinesMissedCallsDoesNotClearAnotherLine() throws {
        var first = Mango9MissedCallState(current: [], legacyUnreadCount: 0)
        var second = Mango9MissedCallState(current: [], legacyUnreadCount: 0)
        XCTAssertEqual(first.reconcile(current: ["first"]), 1)
        XCTAssertEqual(second.reconcile(current: ["second"]), 1)
        XCTAssertEqual(first.reconcile(current: ["first"], markSeen: true), 0)
        second = try JSONDecoder().decode(Mango9MissedCallState.self, from: JSONEncoder().encode(second))
        XCTAssertEqual(second.reconcile(current: ["second"]), 1)
    }
    func testVoicemailBadgeRestoresByLineAndFreshServerClearPersists() throws {
        let suite = "phone-voicemail-test-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = "sip:100@first.example", second = "sip:100@second.example"
        Mango9VoicemailReceipts.save(owner: first, waiting: true, count: 4, defaults: defaults)
        let restarted = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertEqual(Mango9VoicemailReceipts.read(owner: first, defaults: restarted), Mango9VoicemailReceipt(waiting: true, count: 4))
        XCTAssertNil(Mango9VoicemailReceipts.read(owner: second, defaults: restarted))
        Mango9VoicemailReceipts.save(owner: second, waiting: true, count: 2, defaults: restarted)
        Mango9VoicemailReceipts.save(owner: first, waiting: false, count: 4, defaults: restarted)
        XCTAssertEqual(Mango9VoicemailReceipts.read(owner: first, defaults: defaults), Mango9VoicemailReceipt(waiting: false, count: 0))
        XCTAssertEqual(Mango9VoicemailReceipts.read(owner: second, defaults: defaults)?.count, 2)
        Mango9VoicemailReceipts.save(owner: first, waiting: true, count: 0, defaults: defaults)
        XCTAssertEqual(Mango9VoicemailReceipts.read(owner: first, defaults: restarted), Mango9VoicemailReceipt(waiting: true, count: 0))
    }
    func testMailboxDefaultsAndTenantIsolation() {
        XCTAssertEqual(Mango9PhonePolicy.mailboxNumber("", domain: "team.example"), "*97")
        XCTAssertEqual(Mango9PhonePolicy.mailboxNumber(" *98 ", domain: "team.example"), "*98")
        XCTAssertEqual(Mango9PhonePolicy.mailboxNumber("sip:*97@team.example", domain: "TEAM.example"), "*97")
        for invalid in ["sip:*97@other.example", "sip:*97@team.example;transport=udp", "hello", "1234567890123", "*97\r\nINVITE", "١٢٣"] {
            XCTAssertNil(Mango9PhonePolicy.mailboxNumber(invalid, domain: "team.example"), invalid)
        }
    }
    func testVoicemailClearsOnZeroAndDoesNotInventACount() {
        XCTAssertTrue(Mango9PhonePolicy.voicemailStatus(waiting: true, voiceCount: 2).waiting)
        XCTAssertFalse(Mango9PhonePolicy.voicemailStatus(waiting: true, voiceCount: 0).waiting)
        XCTAssertEqual(Mango9PhonePolicy.voicemailStatus(waiting: true, voiceCount: nil).count, 0)
        XCTAssertFalse(Mango9PhonePolicy.voicemailStatus(waiting: false, voiceCount: nil).waiting)
        XCTAssertEqual(Mango9PhonePolicy.voicemailStatus(waiting: false, voiceCount: 3).count, 0)
    }
    func testRecallUsesOnlyOutgoingCallsOfCurrentLine() {
        let owner = "sip:100@team.example"
        XCTAssertEqual(Mango9PhonePolicy.recallNumber("123", local: owner, owner: owner, outgoing: true), "123")
        XCTAssertNil(Mango9PhonePolicy.recallNumber("123", local: owner, owner: owner, outgoing: false))
        XCTAssertNil(Mango9PhonePolicy.recallNumber("123", local: "sip:100@other.example", owner: owner, outgoing: true))
        XCTAssertNil(Mango9PhonePolicy.recallNumber("", local: owner, owner: owner, outgoing: true))
    }
    func testRecallRetainsExternalSIPDomain() {
        XCTAssertEqual(Mango9PhonePolicy.recallDestination(username: "200", uri: "sip:200@team.example", domain: "team.example", ownerDomain: "team.example"), "200")
        XCTAssertEqual(Mango9PhonePolicy.recallDestination(username: "alice", uri: "sip:alice@other.example", domain: "other.example", ownerDomain: "team.example"), "sip:alice@other.example")
    }
    func testLandscapeKeepsKeysBesideCallControlsAboveFooter() {
        for width: CGFloat in [280, 340, 420] {
            for height: CGFloat in [230, 280, 350] {
                let d = Mango9KeypadLayout(width: width, height: height, landscape: true)
                XCTAssertGreaterThanOrEqual(d.key, 44)
                XCTAssertLessThanOrEqual(d.key * 3 + d.columnGap * 2, width)
                XCTAssertLessThanOrEqual(d.key * 4 + d.rowGap * 3 + 24, height)
            }
        }
    }
    func testGeometryKeepsTouchTargetsAndUsesScrollForShortLandscape() {
        for width: CGFloat in [280, 320, 393, 440, 852, 1024] {
            for height: CGFloat in [180, 240, 450, 655, 900] {
                let d = Mango9KeypadLayout(width: width, height: height)
                XCTAssertGreaterThanOrEqual(d.key, 44)
                XCTAssertLessThanOrEqual(d.key * 3 + d.columnGap * 2, width)
                if height >= 400 { XCTAssertLessThanOrEqual(d.minimumHeight, height + 1) }
            }
        }
    }
}
