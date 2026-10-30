import XCTest
import AVFoundation
import Security
@testable import LinphoneApp

final class Mango9SupervisorTests: XCTestCase {
    private func session(host: String = "crm.example.invalid", userID: String = "7") -> Mango9Session {
        Mango9Session(crmId: "fixture", crmBaseUrl: "https://" + host, crmApiBaseUrl: "https://" + host + "/app/api/v2",
            userId: userID, parentClientId: "7", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture",
            accessToken: "fixture-token", refreshToken: "fixture-refresh", smsChatApi: "", connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:7@voice.invalid")
    }
    @MainActor func testBothSelectedServersPreserveTheirOriginForChatAndVoice() throws {
        for host in ["2.mango9.com", "us.mango9.com"] {
            let value = session(host: host)
            XCTAssertEqual(try Mango9SupervisorAPI.url(session: value, path: "chat").absoluteString, "https://" + host + "/app/api/v2/assistant/chat")
            XCTAssertEqual(try Mango9SupervisorAPI.voiceURL(session: value, path: "/app/command-center/voice-stream").absoluteString, "wss://" + host + "/app/command-center/voice-stream")
            XCTAssertThrowsError(try Mango9SupervisorAPI.voiceURL(session: value, path: "wss://foreign.invalid/voice"))
            XCTAssertThrowsError(try Mango9SupervisorAPI.url(session: value, path: "../auth"))
        }
    }
    @MainActor func testIdentityMustBeTheAuthenticatedOwnerAndNotMerelyEnabled() {
        var identity: [String: Any] = ["enabled": true, "ownerOnly": true, "userId": 7, "tenantId": 7, "supervisorAuth": "mobile_owner"]
        XCTAssertTrue(Mango9SupervisorAPI.ownerIdentity(identity, session: session()))
        identity["tenantId"] = 8; XCTAssertFalse(Mango9SupervisorAPI.ownerIdentity(identity, session: session()))
        identity["tenantId"] = 7; identity["userId"] = 8; XCTAssertFalse(Mango9SupervisorAPI.ownerIdentity(identity, session: session()))
        identity["userId"] = 7; identity["supervisorAuth"] = "browser_owner"; XCTAssertFalse(Mango9SupervisorAPI.ownerIdentity(identity, session: session()))
        identity["supervisorAuth"] = "mobile_owner"; identity["ownerOnly"] = false; XCTAssertFalse(Mango9SupervisorAPI.ownerIdentity(identity, session: session()))
        XCTAssertFalse(Mango9SupervisorAPI.ownerIdentity(["enabled": true], session: session()))
    }
    func testPendingCommandRetainsExactIdentifiersAcrossRelaunch() throws {
        let command = Mango9SupervisorCommand(conversationID: UUID().uuidString.lowercased(), text: "Read my tasks 👋")
        let restored = try JSONDecoder().decode(Mango9SupervisorCommand.self, from: JSONEncoder().encode(command))
        XCTAssertEqual(restored.requestID, command.requestID); XCTAssertEqual(restored.messageID, command.messageID)
        XCTAssertEqual(restored.conversationID, command.conversationID); XCTAssertEqual(restored.text, command.text)
        let message = try XCTUnwrap(restored.body["message"] as? [String: Any])
        XCTAssertEqual(message["role"] as? String, "user")
        XCTAssertNil(restored.body["userId"]); XCTAssertNil(restored.body["tenantId"])
    }
    func testLimitCountsUTF8RatherThanCharacters() {
        XCTAssertTrue(Mango9SupervisorCommand.valid(String(repeating: "😀", count: 2000)))
        XCTAssertFalse(Mango9SupervisorCommand.valid(String(repeating: "😀", count: 2001)))
        XCTAssertFalse(Mango9SupervisorCommand.valid(" \n "))
    }
    func testHistoryRetainsReceiptIdentityAndVoiceMessageText() throws {
        let message = try XCTUnwrap(Mango9SupervisorMessage(["id": "reply", "role": "assistant", "parts": [["type": "text", "text": "Saved reply"]], "metadata": ["requestId": "original-request", "channel": "voice", "createdAt": "2026-10-29T10:00:00.123Z"]]))
        XCTAssertEqual(message.requestID, "original-request"); XCTAssertEqual(message.text, "Saved reply"); XCTAssertNotNil(message.createdAt)
        XCTAssertNil(Mango9SupervisorMessage(["id": "bad", "role": "system"]))
    }
    func testAccountStorageKeysSeparateServersAndUsers() {
        XCTAssertNotEqual(Mango9SupervisorPersistence.key(session(host: "2.mango9.com")), Mango9SupervisorPersistence.key(session(host: "us.mango9.com")))
    }
    func testSavedRelativeLinksBecomeFullURLsForTheSelectedServer() throws {
        for host in ["2.mango9.com", "us.mango9.com"] {
            let result = Mango9SupervisorReplyFormatting.reply("Saved successfully.\n\n[View task](/app/tasks/view?id=42#details)", baseURL: "https://" + host + "/app/")
            XCTAssertEqual(result.body, "Saved successfully.")
            XCTAssertEqual(try XCTUnwrap(result.links.first).url.absoluteString, "https://" + host + "/app/tasks/view?id=42#details")
            XCTAssertEqual(result.links.first?.title, "View task")
            XCTAssertFalse(result.copyText.contains("](")); XCTAssertTrue(result.copyText.contains("https://" + host))
        }
    }
    func testInlineLinksKeepTheirContextAndDuplicateDestinationsUseOneCard() {
        let result = Mango9SupervisorReplyFormatting.reply("See [your tasks](/app/tasks) for details.\n[Tasks](/app/tasks)", baseURL: "https://crm.example.invalid")
        XCTAssertEqual(result.body, "See your tasks for details."); XCTAssertEqual(result.links.count, 1)
    }
    func testExternalDocumentationIsNotRewrittenToTheCRMServer() {
        let result = Mango9SupervisorReplyFormatting.reply("[Help](https://help.example.invalid/article?id=3)", baseURL: "https://crm.example.invalid")
        XCTAssertEqual(result.links.first?.url.absoluteString, "https://help.example.invalid/article?id=3")
    }
    func testLinksCannotLaunchCustomSchemesOrExposeAPIAndCredentialURLs() {
        for target in ["javascript:alert(1)", "file:///tmp/private", "//foreign.invalid/app/tasks", "https://user:password@crm.example.invalid/app/tasks", "/app/api/v2/tasks", "/app/../api/tasks"] {
            XCTAssertNil(Mango9SupervisorReplyFormatting.webURL(target, baseURL: "https://crm.example.invalid"), target)
        }
    }
    func testPlainFullWebAddressesAlsoHaveATappableDestination() {
        let result = Mango9SupervisorReplyFormatting.reply("See https://crm.example.invalid/app/tasks for details.", baseURL: "https://crm.example.invalid")
        XCTAssertEqual(result.links.first?.url.absoluteString, "https://crm.example.invalid/app/tasks")
    }
#if os(iOS)
    @MainActor func testSelectedPhysicalDeviceOwnerReadinessWithoutSendingOrOpeningMicrophone() async throws {
        guard let session = Mango9SessionStore.load(), session.role == "client", Mango9SessionStore.isActive(session) else { throw XCTSkip("No active owner login on this physical device.") }
        let api = Mango9SupervisorAPI(session: session)
        let status = try await api.authorize()
        XCTAssertEqual(status["enabled"] as? Bool, true)
        let history = try await api.request("history", body: ["action": "list"])
        XCTAssertNotNil(history["items"] as? [[String: Any]])
    }
#endif
    func testAIConsentIsExplicitAccountScopedAndRevocable() throws {
        let name = "supervisor-consent-test-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let account = session(host: "2.mango9.com")
        XCTAssertFalse(Mango9SupervisorConsent.accepted(account, defaults: defaults))
        Mango9SupervisorConsent.allow(account, defaults: defaults)
        XCTAssertTrue(Mango9SupervisorConsent.accepted(account, defaults: defaults))
        XCTAssertTrue(Mango9SupervisorConsent.accepted(account, defaults: try XCTUnwrap(UserDefaults(suiteName: name))))
        XCTAssertFalse(Mango9SupervisorConsent.accepted(session(host: "us.mango9.com"), defaults: defaults))
        let other = session(host: "2.mango9.com", userID: "8")
        XCTAssertFalse(Mango9SupervisorConsent.accepted(other, defaults: defaults))
        Mango9SupervisorConsent.revoke(account, defaults: defaults)
        XCTAssertFalse(Mango9SupervisorConsent.accepted(account, defaults: defaults))
    }
    func testChangedOrCorruptAIDisclosureNeedsNewAcceptance() throws {
        let name = "supervisor-consent-version-test-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let account = session()
        Mango9SupervisorConsent.allow(account, defaults: defaults)
        let key = try XCTUnwrap(defaults.dictionaryRepresentation().keys.first { $0.hasPrefix("mango9.supervisor.aiConsent.") })
        var receipt = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(defaults.data(forKey: key))) as? [String: Any])
        receipt["version"] = "old-disclosure"
        defaults.set(try JSONSerialization.data(withJSONObject: receipt), forKey: key)
        XCTAssertFalse(Mango9SupervisorConsent.accepted(account, defaults: defaults))
        defaults.set(Data("broken".utf8), forKey: key)
        XCTAssertFalse(Mango9SupervisorConsent.accepted(account, defaults: defaults))
    }
    @MainActor func testAIChatVoiceAndApprovalAreBlockedBeforeNetworkWithoutConsent() async throws {
        let api = Mango9SupervisorAPI(session: session(host: "consent-gate.example.invalid"))
        XCTAssertFalse(api.hasAIConsent)
        for (path, body) in [("chat", ["text": "Test"]), ("voice/ticket", ["conversationId": "fixture"]), ("actions/fixture/decision", ["decision": "approve"])] {
            do { _ = try await api.request(path, body: body); XCTFail("Must require explicit consent") }
            catch let failure as Mango9SupervisorFailure { XCTAssertEqual(failure.code, "ai_consent_required") }
        }
#if os(iOS)
        let voice = Mango9SupervisorVoice()
        await voice.start(api: api, conversationID: UUID().uuidString)
        XCTAssertFalse(voice.running); XCTAssertFalse(voice.audioStateForTesting.running)
        XCTAssertEqual(voice.error, Mango9SupervisorFailure(status: 0, code: "ai_consent_required").localizedDescription)
#endif
        XCTAssertFalse(Mango9SupervisorConsent.required(path: "history", body: ["action": "read"]))
        XCTAssertFalse(Mango9SupervisorConsent.required(path: "actions/fixture/decision", body: ["decision": "reject"]))
    }
#if os(iOS)
    @MainActor func testMutedPhysicalAudioStartupRemainsConnected() async throws {
        guard AVAudioSession.sharedInstance().recordPermission == .granted else { throw XCTSkip("Requires previously granted microphone access on the connected phone.") }
        let hasCall = await Mango9SupervisorVoice.hasActiveCall()
        guard !hasCall else { throw XCTSkip("A phone call is active.") }
        let voice = Mango9SupervisorVoice(); defer { voice.stop() }
        for cycle in 1...2 {
            print("Supervisor audio check: starting cycle \(cycle)")
            try voice.startMutedAudioForTesting()
            try await Task.sleep(nanoseconds: 2_000_000_000)
            XCTAssertTrue(voice.connected, (voice.error ?? "Audio stopped") + " " + voice.audioEventsForTesting.joined(separator: "; "))
            XCTAssertTrue(voice.audioStateForTesting.running)
            XCTAssertTrue(voice.audioStateForTesting.processing)
            XCTAssertTrue(voice.audioStateForTesting.muted)
            print("Supervisor audio check: stopping cycle \(cycle)")
            voice.stop()
            XCTAssertFalse(voice.running)
            XCTAssertFalse(voice.audioStateForTesting.running)
            print("Supervisor audio check: completed cycle \(cycle)")
        }
    }
#endif
}

private final class SupervisorPendingFixture {
    var command: Mango9SupervisorCommand?
    var readFails = false
    var deleteFails = false
    var store: Mango9SupervisorPendingStore {
        .init(read: { [self] in
            if readFails { throw Mango9SupervisorFailure(status: 0, code: "storage_unavailable") }; return command
        }, write: { [self] value in
            if value == nil && deleteFails { throw Mango9SupervisorFailure(status: 0, code: "storage_unavailable") }; command = value
        })
    }
}
@MainActor private final class SupervisorClientFixture: Mango9SupervisorClient {
    let original = Mango9Session(crmId: "fixture", crmBaseUrl: "https://supervisor.example.invalid", crmApiBaseUrl: "https://supervisor.example.invalid/app/api/v2", userId: "7", parentClientId: "7", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture", accessToken: "fixture", refreshToken: "fixture", smsChatApi: "", connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:7@voice.invalid")
    var active = true
    var hasAIConsent = true
    var calls: [String] = []
    var handler: ((String, [String: Any]?) async throws -> [String: Any])?
    func requireAIConsent() throws { if !hasAIConsent { throw Mango9SupervisorFailure(status: 0, code: "ai_consent_required") } }
    func cancelAIRequests() {}
    func authorize() async throws -> [String: Any] { try await request("authorize", body: nil) }
    func request(_ path: String, body: [String: Any]?) async throws -> [String: Any] {
        calls.append(path)
        if let handler { return try await handler(path, body) }
        return ["enabled": true, "items": [], "messages": [], "cursor": "0"]
    }
    func ticket(conversationID: String) async throws -> (value: [String: Any], session: Mango9Session) { throw Mango9SupervisorFailure(status: 0, code: "fixture_network_forbidden") }
}
@MainActor private final class SupervisorDeferredFixture {
    let entered = XCTestExpectation(description: "Request reached controlled suspension")
    private var continuation: CheckedContinuation<[String: Any], Never>?
    func wait() async -> [String: Any] {
        await withCheckedContinuation { continuation = $0; entered.fulfill() }
    }
    func resume(_ value: [String: Any]) { continuation?.resume(returning: value); continuation = nil }
}

final class Mango9SupervisorReliabilityTests: XCTestCase {
    @MainActor private func withModel(start: Bool = true, _ body: (Mango9SupervisorModel, SupervisorClientFixture, SupervisorPendingFixture) async throws -> Void) async throws {
        let name = "supervisor-model-fixture-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        let client = SupervisorClientFixture(), vault = SupervisorPendingFixture()
        Mango9SupervisorConsent.allow(client.original, defaults: defaults)
        let model = Mango9SupervisorModel(session: client.original, client: client, defaults: defaults, pendingStore: vault.store)
        defer { model.close(); defaults.removePersistentDomain(forName: name) }
        if start { await model.start(); XCTAssertTrue(model.authorized) }
        try await body(model, client, vault)
    }
    private static func reply(_ id: String, _ text: String) -> [String: Any] {
        ["id": id, "role": "assistant", "parts": [["type": "text", "text": text]]]
    }
    @MainActor func testClosingWhileAuthorizingDoesNotReopenOrLoadHistory() async throws {
        try await withModel(start: false) { model, client, _ in
            let gate = SupervisorDeferredFixture()
            client.handler = { _, _ in await gate.wait() }
            let start = Task { await model.start() }
            await fulfillment(of: [gate.entered], timeout: 3)
            model.close(); gate.resume(["enabled": true]); await start.value
            XCTAssertFalse(model.authorized); XCTAssertFalse(model.loading)
            XCTAssertEqual(client.calls, ["authorize"])
        }
    }
    @MainActor func testSelectingAnotherConversationLoadsImmediatelyAndIgnoresOldResponse() async throws {
        try await withModel { model, client, _ in
            let gate = SupervisorDeferredFixture(), old = model.conversationID, next = UUID().uuidString.lowercased()
            client.handler = { _, body in
                if body?["conversationId"] as? String == old { return await gate.wait() }
                return ["messages": [Self.reply("new-message", "New conversation")], "cursor": "1"]
            }
            let oldRead = Task { await model.refreshHistory() }
            await fulfillment(of: [gate.entered], timeout: 3)
            await model.select(next)
            XCTAssertEqual(model.messages.map(\.id), ["new-message"])
            gate.resume(["messages": [Self.reply("old-message", "Old conversation")], "cursor": "2"])
            await oldRead.value
            XCTAssertEqual(model.conversationID, next); XCTAssertEqual(model.messages.map(\.id), ["new-message"])
        }
    }
    @MainActor func testAccountChangeDuringSendRefreshDoesNotAppendOldReplyOrDropPendingRequest() async throws {
        try await withModel { model, client, vault in
            let gate = SupervisorDeferredFixture()
            client.handler = { path, body in
                if path == "chat" { return Self.reply("reply", "Completed") }
                if body?["action"] as? String == "read" { return await gate.wait() }
                return ["items": []]
            }
            model.draft = "Fixture request"; XCTAssertTrue(model.canSend)
            let send = Task { await model.send() }
            await fulfillment(of: [gate.entered], timeout: 3)
            client.active = false; gate.resume(["messages": [], "cursor": "0"]); await send.value
            XCTAssertTrue(model.messages.isEmpty); XCTAssertNotNil(vault.command)
            XCTAssertEqual(client.calls.filter { $0 == "chat" }.count, 1)
        }
    }
    @MainActor func testUnreadableOutboxBlocksNewRequestsUntilSuccessfulRestore() async throws {
        try await withModel(start: false) { model, client, vault in
            vault.readFails = true; model.draft = "Fixture request"; await model.start()
            XCTAssertFalse(model.canSend); XCTAssertFalse(model.authorized); XCTAssertTrue(client.calls.isEmpty)
            XCTAssertNotNil(model.error)
            vault.readFails = false; await model.start()
            XCTAssertTrue(model.authorized); XCTAssertTrue(model.canSend)
        }
    }
    @MainActor func testFailedKeychainDeletionRetainsPendingAndDoesNotReplayPost() async throws {
        try await withModel { model, client, vault in
            vault.deleteFails = true
            client.handler = { path, _ in path == "chat" ? Self.reply("reply", "Completed") : ["messages": [], "items": []] }
            model.draft = "Fixture request"; await model.send(); await model.send()
            XCTAssertNotNil(vault.command); XCTAssertNotNil(model.pending); XCTAssertFalse(model.canSend)
            XCTAssertEqual(client.calls.filter { $0 == "chat" }.count, 1)
        }
    }
    @MainActor func testApprovalCannotUseADifferentActionThanTheOneReviewed() async throws {
        try await withModel { model, client, _ in
            var detail: [String: Any] = ["id": UUID().uuidString, "approvalDigest": String(repeating: "a", count: 64), "state": "PENDING_APPROVAL"]
            let reviewed = try XCTUnwrap(Mango9SupervisorApproval(detail))
            detail["approvalDigest"] = String(repeating: "b", count: 64); model.actionDetail = detail
            await model.decide("approve", expected: reviewed)
            XCTAssertFalse(client.calls.contains { $0.hasSuffix("/decision") }); XCTAssertNotNil(model.error)
        }
    }
    @MainActor func testLateActionDetailsCannotReappearAfterClosing() async throws {
        try await withModel { model, client, _ in
            let gate = SupervisorDeferredFixture(), id = UUID().uuidString
            client.handler = { _, _ in await gate.wait() }
            let read = Task { await model.reviewAction(id) }
            await fulfillment(of: [gate.entered], timeout: 3)
            model.close(); gate.resume(["id": id, "state": "PENDING_APPROVAL"]); await read.value
            XCTAssertNil(model.actionDetail); XCTAssertFalse(model.actionBusy)
        }
    }
    @MainActor func testRestoredPendingRequestUsesSavedReceiptWithoutReplaying() async throws {
        try await withModel(start: false) { model, client, vault in
            let command = Mango9SupervisorCommand(conversationID: UUID().uuidString.lowercased(), text: "Previously sent")
            vault.command = command
            client.handler = { _, body in
                if body?["action"] as? String == "read" {
                    var reply = Self.reply("saved-reply", "Completed")
                    reply["metadata"] = ["requestId": command.requestID]
                    return ["messages": [reply], "cursor": "1"]
                }
                return ["enabled": true, "items": []]
            }
            await model.start()
            XCTAssertEqual(model.conversationID, command.conversationID)
            XCTAssertNil(vault.command); XCTAssertNil(model.pending)
            XCTAssertEqual(model.messages.map(\.id), ["saved-reply"])
            XCTAssertFalse(client.calls.contains("chat"))
        }
    }
    @MainActor func testRevokingConsentDiscardsLateReplyButPreservesRequestReceipt() async throws {
        try await withModel { model, client, vault in
            let gate = SupervisorDeferredFixture()
            client.handler = { _, _ in await gate.wait() }
            model.draft = "Fixture request"
            let send = Task { await model.send() }
            await fulfillment(of: [gate.entered], timeout: 3)
            model.revokeAIConsent(); gate.resume(Self.reply("reply", "Completed")); await send.value
            XCTAssertFalse(model.hasAIConsent); XCTAssertFalse(model.canSend)
            XCTAssertTrue(model.messages.isEmpty); XCTAssertNotNil(vault.command)
        }
    }
    func testKeychainFailuresAndCorruptionAreNotTreatedAsEmptyOutbox() throws {
        XCTAssertNil(try Mango9SupervisorPersistence.decodePending(status: errSecItemNotFound, data: nil))
        for status in [errSecInteractionNotAllowed, errSecAuthFailed, errSecNotAvailable] {
            XCTAssertThrowsError(try Mango9SupervisorPersistence.decodePending(status: status, data: nil))
            XCTAssertThrowsError(try Mango9SupervisorPersistence.checkDeletion(status))
        }
        XCTAssertThrowsError(try Mango9SupervisorPersistence.decodePending(status: errSecSuccess, data: Data("broken".utf8)))
        XCTAssertNoThrow(try Mango9SupervisorPersistence.checkDeletion(errSecItemNotFound))
        let command = Mango9SupervisorCommand(conversationID: UUID().uuidString, text: "Test")
        XCTAssertEqual(try Mango9SupervisorPersistence.decodePending(status: errSecSuccess, data: JSONEncoder().encode(command))?.requestID, command.requestID)
    }
    func testMuteAndRestartRejectAlreadyQueuedMicrophoneFrames() throws {
        let gate = Mango9SupervisorCaptureGate(limit: 2); gate.reset(enabled: true)
        guard case .buffer(let old) = gate.claim() else { return XCTFail("Expected capture ticket") }
        gate.reset(enabled: false); XCTAssertFalse(gate.permits(old))
        gate.reset(enabled: true)
        guard case .buffer(let current) = gate.claim() else { return XCTFail("Expected fresh ticket") }
        gate.finish(old); XCTAssertFalse(gate.permits(old)); XCTAssertTrue(gate.permits(current))
        gate.reset(enabled: false); XCTAssertFalse(gate.permits(current))
    }
    func testSlowAudioConsumerIsBoundedAndTriggersOnlyOneOverflow() {
        let gate = Mango9SupervisorCaptureGate(limit: 1); gate.reset(enabled: true)
        guard case .buffer(let frame) = gate.claim(), case .overflow(let overflow) = gate.claim() else { return XCTFail("Expected bounded overflow") }
        XCTAssertFalse(gate.permits(frame)); XCTAssertTrue(gate.didOverflow(overflow))
        for _ in 0..<100 { if case .paused = gate.claim() {} else { XCTFail("Overflow must not keep scheduling callbacks") } }
        gate.reset(enabled: true); XCTAssertFalse(gate.didOverflow(overflow))
        gate.finish(frame)
        if case .buffer = gate.claim() {} else { XCTFail("Fresh generation must be usable") }
    }
    @MainActor func testOnlySupportedSupervisorEndpointsAreAllowed() {
        for path in ["/chat", "history?userId=8", "actions//decision", "../auth", "chat#x", "unknown", "actions/not-a-uuid", "actions/" + UUID().uuidString + "/delete"] { XCTAssertFalse(Mango9SupervisorAPI.validEndpoint(path), path) }
        XCTAssertTrue(Mango9SupervisorAPI.validEndpoint("actions/" + UUID().uuidString + "/decision"))
    }
}

private final class SupervisorBoundedResponseFixture: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "bounded.example.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let oversizeHeader = request.url!.path == "/header"
        let size = request.url!.path == "/small" ? 8 : 64
        let headers = oversizeHeader ? ["Content-Length": "1000000"] : [:]
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(repeating: 65, count: size))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
final class Mango9SupervisorResponseLimitTests: XCTestCase {
    func testResponseLimitAppliesWithOrWithoutContentLength() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [SupervisorBoundedResponseFixture.self]
        let transport = URLSession(configuration: config); defer { transport.invalidateAndCancel() }
        for path in ["header", "stream"] {
            do {
                _ = try await Mango9SupervisorAPI.boundedResponse(URLRequest(url: URL(string: "https://bounded.example.invalid/" + path)!), transport: transport, limit: 32)
                XCTFail("Oversized response must stop")
            } catch let failure as Mango9SupervisorFailure { XCTAssertEqual(failure.code, "invalid_response") }
        }
        let result = try await Mango9SupervisorAPI.boundedResponse(URLRequest(url: URL(string: "https://bounded.example.invalid/small")!), transport: transport, limit: 32)
        XCTAssertEqual(result.0.count, 8)
    }
}
