import XCTest
@testable import LinphoneApp
final class Mango9InboxReadLedgerTests: XCTestCase {
    func testAcknowledgedReadsRejectStaleSnapshotsButKeepNewArrivals() {
        var reads = Mango9InboxReadLedger()
        reads.read("chat", channel: "social", version: 100)
        XCTAssertFalse(reads.unread(true, id: "chat", channel: "social", version: 99))
        XCTAssertFalse(reads.unread(true, id: "chat", channel: "social", version: 100))
        XCTAssertTrue(reads.unread(true, id: "chat", channel: "social", version: 101))
        reads.read("chat", channel: "social", version: 90)
        XCTAssertFalse(reads.unread(true, id: "chat", channel: "social", version: 100))
        XCTAssertTrue(reads.unread(true, id: "chat", channel: "live", version: 100))
        XCTAssertTrue(reads.unread(true, id: "other", channel: "social", version: 100))
        XCTAssertTrue(reads.unread(true, id: "chat", channel: "social", version: nil))
        XCTAssertFalse(reads.unread(false, id: "chat", channel: "social", version: 101))
        XCTAssertTrue(Mango9InboxReadLedger().unread(true, id: "chat", channel: "social", version: 100))
    }
}
final class Mango9CRMNewRecordTests: XCTestCase {
    func testInitialHistoryDoesNotBecomeNewAndArrivalsCountOnce() {
        var state = Mango9CRMNewRecords(); state.reconcile([1, 2])
        XCTAssertTrue(state.unread.isEmpty)
        state.reconcile([1, 2, 3, 4]); state.reconcile([1, 2, 3, 4])
        XCTAssertEqual(state.unread, [3, 4])
    }
    func testSimultaneousDeletionAndArrivalWithSameTotalIsDetected() {
        var state = Mango9CRMNewRecords(); state.reconcile([1, 2]); state.reconcile([2, 3])
        XCTAssertEqual(state.unread, [3]); state.reconcile([2, 4]); XCTAssertEqual(state.unread, [4])
    }
    func testReadDuringRefreshDoesNotResurrectBadgeOrClearOtherRecords() {
        var state = Mango9CRMNewRecords(); state.reconcile([1]); state.reconcile([1, 2, 3])
        state.read(2); state.reconcile([1, 2, 3, 4]); XCTAssertEqual(state.unread, [3, 4])
        state.read(4); state.read(3); state.reconcile([1, 2, 3, 4]); XCTAssertTrue(state.unread.isEmpty)
    }
    func testStateSurvivesRestartAndAccountsRemainIndependent() throws {
        var first = Mango9CRMNewRecords(); first.reconcile([1]); first.reconcile([1, 2])
        let stored = try JSONEncoder().encode(first)
        var restored = try JSONDecoder().decode(Mango9CRMNewRecords.self, from: stored)
        var second = Mango9CRMNewRecords(); second.reconcile([1, 2, 3]); second.read(2)
        XCTAssertTrue(second.unread.isEmpty); XCTAssertEqual(restored.unread, [2])
        restored.read(2); restored.reconcile([1, 2]); XCTAssertTrue(restored.unread.isEmpty)
    }
    func testEmptySuccessRemovesDeletedRecordsAndNextArrivalIsNew() {
        var state = Mango9CRMNewRecords(); state.reconcile([1]); state.reconcile([1, 2])
        state.reconcile([]); XCTAssertTrue(state.unread.isEmpty)
        state.reconcile([3]); XCTAssertEqual(state.unread, [3])
    }
}
