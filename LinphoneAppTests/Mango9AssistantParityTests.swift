import XCTest
@testable import LinphoneApp
final class Mango9AssistantParityTests: XCTestCase {
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
    func testAssistantOnlyUnreadDoesNotLightChatFooter() {
        let counts = ["sms": 0, "team": 0, "social": 0, "live": 0, "assistant": 3]
        XCTAssertEqual(Mango9InboxCountPolicy.total(counts, enabled: ["sms", "team", "social", "live"]), 0)
    }

    func testUnreadCountsConversationsAndRejectsAmbiguousOrInconsistentCounts() {
        let a = UUID().uuidString.lowercased(), b = UUID().uuidString.lowercased()
        XCTAssertEqual(Mango9AssistantUnread(["conversations": [a: 3, b: 1], "unread": 4, "readThrough": "10"])?.counts.count, 2)
        XCTAssertNil(Mango9AssistantUnread(["conversations": [a: 3], "unread": 2, "readThrough": "10"]))
        XCTAssertNil(Mango9AssistantUnread(["conversations": [a: 1, a.uppercased(): 1], "unread": 2, "readThrough": "10"]))
    }
    func testHistoryDedupeAndDeletedRowsAreIndependentOfMessageCount() throws {
        let id = UUID().uuidString.lowercased()
        let a = try XCTUnwrap(Mango9SupervisorConversation(["id": id, "messageCount": 50, "title": "Old", "updatedAt": "2026-11-10T10:00:00Z"]))
        let b = try XCTUnwrap(Mango9SupervisorConversation(["id": id.uppercased(), "messageCount": 52, "title": "New", "updatedAt": "2026-11-11T10:00:00Z"]))
        XCTAssertEqual(Mango9AssistantState.unique([a, b], excluding: []).map(\.title), ["New"])
        XCTAssertTrue(Mango9AssistantState.unique([a, b], excluding: [id]).isEmpty)
    }
    func testMultipartImmutableSnapshotAndFinalShortPart() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: dir) }
        let input = dir.appendingPathComponent("input"), output = dir.appendingPathComponent("part")
        try Data(repeating: 87, count: Mango9AssistantMediaFile.partSize + 123).write(to: input)
        let read = try FileHandle(forReadingFrom: input); defer { try? read.close() }
        let manifest = try Mango9AssistantMediaFile.inspect(read); XCTAssertEqual(manifest.partMD5.count, 2)
        FileManager.default.createFile(atPath: output.path, contents: nil)
        let write = try FileHandle(forWritingTo: output); defer { try? write.close() }
        XCTAssertEqual(try Mango9AssistantMediaFile.copyPart(read, to: write, size: manifest.size, number: 2, md5: manifest.partMD5[1]), 123)
        XCTAssertThrowsError(try Mango9AssistantMediaFile.copyPart(read, to: write, size: manifest.size, number: 2, md5: String(repeating: "A", count: 22) + "=="))
        XCTAssertThrowsError(try Mango9AssistantMediaFile.requireSpace(bytes: 500_000_001, available: 1_000_000_000))
    }
    func testAttachmentsSurviveLocalCacheAndUnsupportedPartsDoNotBecomeText() throws {
        let fileID = UUID().uuidString.lowercased()
        let message = try XCTUnwrap(Mango9SupervisorMessage(["id": UUID().uuidString, "role": "user", "parts": [["type": "attachment", "id": fileID, "mime": "audio/wav", "name": "Voice.wav", "size": 48044], ["type": "tool", "text": "private"]]]))
        let restored = try JSONDecoder().decode(Mango9SupervisorMessage.self, from: JSONEncoder().encode(message))
        XCTAssertEqual(restored.attachments?.first?.id, fileID); XCTAssertEqual(restored.attachments?.first?.sizeLabel, "48.0 KB"); XCTAssertTrue(restored.text.isEmpty)
    }
    func testStorageTicketsRejectCrossServerForeignHostAndPaths() {
        let id = UUID().uuidString.lowercased(), prefix = "https://s3.us-central-1.wasabisys.com/newdev-chatmedia/ephonix-supervisor/v1/"
        XCTAssertNotNil(Mango9AssistantMediaContract.objectURL(prefix + "2.mango9.com/7/7/" + id, id: id, origin: "https://2.mango9.com"))
        for raw in [prefix + "us.mango9.com/7/7/" + id, prefix + "2.mango9.com/7/7/../" + id, "https://foreign.invalid/" + id, prefix + "2.mango9.com/7/7/" + UUID().uuidString.lowercased()] {
            XCTAssertNil(Mango9AssistantMediaContract.objectURL(raw, id: id, origin: "https://2.mango9.com"))
        }
    }
    func testAssistantPushRequiresRecipientAndAccountScope() {
        let payload: [String: Any] = ["event": "supervisor.reply", "conversation_id": UUID().uuidString, "crm_id": "fixture", "sip_identity": "sip:100@voice.invalid", "recipient_user_id": "7"]
        XCTAssertNotNil(Mango9AssistantPushTarget(userInfo: payload))
        for field in ["conversation_id", "crm_id", "sip_identity", "recipient_user_id"] { var bad = payload; bad.removeValue(forKey: field); XCTAssertNil(Mango9AssistantPushTarget(userInfo: bad)) }
    }
    func testFooterCountsChatsAndClearsOnlyTheReadChannel() {
        XCTAssertEqual(Mango9InboxCountPolicy.ids([("+15555550100", 3), ("15555550100", 7), ("15555550101", 0)], sms: true).count, 1)
        XCTAssertEqual(Mango9InboxCountPolicy.ids([("room", 20), ("room", 1), ("read", 0)]).count, 1)
        var counts = ["sms": 1, "team": 2, "social": 3, "live": 1, "assistant": 1]
        let enabled = Set(counts.keys)
        XCTAssertEqual(Mango9InboxCountPolicy.total(counts, enabled: enabled), 8)
        counts["social"] = 0
        XCTAssertEqual(Mango9InboxCountPolicy.total(counts, enabled: enabled), 5)
        XCTAssertEqual(Mango9InboxCountPolicy.total(counts, enabled: ["sms", "assistant"]), 2)
    }
    func testWorkflowMetadataRequiresItsOwnerAndStructure() throws {
        let raw: [String: Any] = ["version": 1, "ownerId": 7, "actorId": 8, "plan": ["kind": "workflow", "definition": ["name": "Draft"]], "steps": [], "blockers": [], "missingTopics": [], "canBuild": true]
        let design = try XCTUnwrap(Mango9AssistantWorkflow(try JSONSerialization.data(withJSONObject: raw)))
        // Validation rejects missing required structure, independently of any web session.
        XCTAssertNil(Mango9AssistantWorkflow(Data("{}".utf8)))
        XCTAssertEqual(design.owner, 7); XCTAssertEqual(design.actor, 8)
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
