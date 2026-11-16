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
    func testDocumentSelectionKeepsSameNamesIndependentAndPreservesOrder() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let a = root.appendingPathComponent("a"), b = root.appendingPathComponent("b")
        for directory in [a, b] { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
        let first = a.appendingPathComponent("same.txt"), second = b.appendingPathComponent("same.txt")
        try Data("first".utf8).write(to: first); try Data("second".utf8).write(to: second)
        let complete = expectation(description: "Both snapshots")
        FilePicker.convertToAttachmentArray(fromResults: [first, second]) { values, error in
            defer { complete.fulfill(); for value in values ?? [] { try? FileManager.default.removeItem(at: value.full) } }
            XCTAssertNil(error); XCTAssertEqual(values?.count, 2)
            guard let values, values.count == 2 else { return }
            XCTAssertNotEqual(values[0].full, values[1].full)
            XCTAssertEqual(try? String(contentsOf: values[0].full, encoding: .utf8), "first")
            XCTAssertEqual(try? String(contentsOf: values[1].full, encoding: .utf8), "second")
            XCTAssertEqual(values.map(\.name), ["same.txt", "same.txt"])
        }
        wait(for: [complete], timeout: 10)
        let failed = expectation(description: "No partial selection")
        FilePicker.convertToAttachmentArray(fromResults: [first, root.appendingPathComponent("missing.txt")]) { values, error in
            XCTAssertNil(values); XCTAssertNotNil(error); failed.fulfill()
        }
        wait(for: [failed], timeout: 10)
    }
    func testBatchDraftMigrationRetainsOrderAndRejectsWrongScopeOrDuplicates() throws {
        let chat = UUID().uuidString.lowercased()
        func draft(_ name: String) -> Mango9AssistantUpload {
            Mango9AssistantUpload(id: UUID().uuidString.lowercased(), account: "fixture", origin: "https://2.mango9.com", conversationId: chat,
                file: Mango9AssistantFile(uri: "file:///tmp/" + name, name: name, size: 100, mime: "text/plain", sha256: String(repeating: "a", count: 64), partMD5: [Data(repeating: 0, count: 16).base64EncodedString()]))
        }
        let first = draft("a.txt"), second = draft("b.txt")
        func restore(_ data: Data, account: String = "fixture", origin: String = "https://2.mango9.com", conversation: String? = nil) throws -> Mango9AssistantUploadBatch {
            try Mango9AssistantUploadBatch.restore(data, account: account, origin: origin, conversation: conversation ?? chat)
        }
        let legacy = try restore(JSONEncoder().encode(first))
        XCTAssertEqual(legacy.drafts.map(\.id), [first.id]); XCTAssertFalse(legacy.sealed)
        let batch = Mango9AssistantUploadBatch(version: 2, drafts: [second, first], sealed: false)
        let bytes = try JSONEncoder().encode(batch)
        XCTAssertEqual(try restore(bytes).drafts.map(\.id), [second.id, first.id])
        XCTAssertThrowsError(try restore(bytes, account: "different"))
        XCTAssertThrowsError(try restore(bytes, origin: "https://us.mango9.com"))
        XCTAssertThrowsError(try restore(bytes, conversation: UUID().uuidString))
        for invalid in [[], [first, first], [first, second, draft("c.txt"), draft("d.txt")]] {
            XCTAssertThrowsError(try restore(JSONEncoder().encode(Mango9AssistantUploadBatch(version: 2, drafts: invalid, sealed: false))))
        }
        var frozenA = first, frozenB = second; frozenA.caption = "One caption"; frozenB.caption = "One caption"
        let frozen = try restore(JSONEncoder().encode(Mango9AssistantUploadBatch(version: 2, drafts: [frozenA, frozenB], sealed: true)))
        XCTAssertTrue(frozen.sealed); XCTAssertEqual(frozen.drafts[1].caption, "One caption")
        frozenB.caption = "Different"
        XCTAssertThrowsError(try restore(JSONEncoder().encode(Mango9AssistantUploadBatch(version: 2, drafts: [frozenA, frozenB], sealed: true))))
    }
    func testDeletedChatMigratesWholeBatchAndReusesDestinationAfterInterruptedCleanup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let chat = UUID().uuidString.lowercased(), account = "fixture", origin = "https://2.mango9.com"
        let source = root.appendingPathComponent(account + "." + chat + ".json")
        let drafts = (1...3).map { n in Mango9AssistantUpload(id: UUID().uuidString.lowercased(), account: account, origin: origin, conversationId: chat,
            file: Mango9AssistantFile(uri: "file:///tmp/fixture\(n).wav", name: "voice\(n).wav", size: 100, mime: "audio/wav", sha256: String(repeating: "a", count: 64), partMD5: [Data(repeating: 0, count: 16).base64EncodedString()]), caption: "Original caption") }
        let original = try JSONEncoder().encode(Mango9AssistantUploadBatch(version: 2, drafts: drafts, sealed: true))
        try original.write(to: source)
        func recover() throws -> String { try Mango9AssistantDraftMigration.recover(source, source: chat, account: account, origin: origin) }
        let destination = try recover(), target = root.appendingPathComponent(account + "." + destination + ".json")
        let saved = try Data(contentsOf: target)
        let moved = try Mango9AssistantUploadBatch.restore(saved, account: account, origin: origin, conversation: destination)
        XCTAssertEqual(moved.drafts.map(\.file), drafts.map(\.file)); XCTAssertFalse(moved.sealed)
        XCTAssertTrue(moved.drafts.allSatisfy { $0.caption == nil }); XCTAssertTrue(Set(moved.drafts.map(\.id)).isDisjoint(with: Set(drafts.map(\.id))))
        XCTAssertFalse(FileManager.default.fileExists(atPath: source.path))
        try original.write(to: source) // Simulate interruption after destination commit but before source removal.
        XCTAssertEqual(try recover(), destination); XCTAssertEqual(try Data(contentsOf: target), saved)
        try original.write(to: source); try Data("bad journal".utf8).write(to: target)
        XCTAssertThrowsError(try recover()); XCTAssertEqual(try Data(contentsOf: source), original)
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
