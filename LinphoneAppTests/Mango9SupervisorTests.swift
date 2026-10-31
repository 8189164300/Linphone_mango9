import XCTest
import AVFoundation
import Security
import SwiftUI
import Combine
@testable import LinphoneApp

final class Mango9ChatScrollTests: XCTestCase {
    func testLatestArrowClearsAtBottomAndDuringBottomOverscroll() {
        for bottom in [CGFloat(701), 700, 686, 640, 320, 0, -80] {
            XCTAssertTrue(Mango9ChatScrollMetrics.reachedLatest(bottom: bottom, viewportHeight: 700))
            XCTAssertFalse(Mango9ChatScrollMetrics.hasMoreBelow(bottom: bottom, viewportHeight: 700))
        }
    }
    func testOlderMessagesKeepTheLatestArrowAvailable() {
        for bottom in [CGFloat(702), 750, 1000, 20000] {
            XCTAssertFalse(Mango9ChatScrollMetrics.reachedLatest(bottom: bottom, viewportHeight: 700))
            XCTAssertTrue(Mango9ChatScrollMetrics.hasMoreBelow(bottom: bottom, viewportHeight: 700))
        }
    }
    func testUnmeasuredOrTransientViewportDoesNotFlashArrowOrResumeFollowing() {
        for bottom: CGFloat? in [nil, .infinity, -.infinity, .nan] {
            XCTAssertFalse(Mango9ChatScrollMetrics.hasMoreBelow(bottom: bottom, viewportHeight: 700))
            XCTAssertFalse(Mango9ChatScrollMetrics.reachedLatest(bottom: bottom, viewportHeight: 700))
        }
        for height in [CGFloat(0), -1, .infinity, .nan] {
            XCTAssertFalse(Mango9ChatScrollMetrics.hasMoreBelow(bottom: 900, viewportHeight: height))
            XCTAssertFalse(Mango9ChatScrollMetrics.reachedLatest(bottom: 300, viewportHeight: height))
        }
    }
    func testArrowUsesCurrentViewportAfterKeyboardOrRotation() {
        XCTAssertFalse(Mango9ChatScrollMetrics.hasMoreBelow(bottom: 680, viewportHeight: 700))
        XCTAssertTrue(Mango9ChatScrollMetrics.hasMoreBelow(bottom: 680, viewportHeight: 400))
        XCTAssertFalse(Mango9ChatScrollMetrics.hasMoreBelow(bottom: 390, viewportHeight: 400))
    }
    func testLiveAndSocialShareTheSameScalableMessageFont() {
        XCTAssertEqual(Mango9InboxTypography.message, Font.custom("NotoSans-Regular", size: 14))
        XCTAssertNotEqual(Mango9InboxTypography.message, Font.system(size: 14))
    }
}

final class Mango9SupervisorTests: XCTestCase {
    func testTopOrMiddleLayoutDoesNotReenableFollowingTheLatestReply() {
        for bottom in [CGFloat(-10), 0, 20, 350, 659, 900, .infinity, .nan] {
            XCTAssertFalse(Mango9SupervisorScrollLayout.isNearBottom(bottom, viewportHeight: 700))
        }
        for bottom in [CGFloat(660), 700, 729, 740] {
            XCTAssertTrue(Mango9SupervisorScrollLayout.isNearBottom(bottom, viewportHeight: 700))
        }
        XCTAssertFalse(Mango9SupervisorScrollLayout.isNearBottom(0, viewportHeight: 0))
    }
    private func windowMessages(_ ids: Range<Int>) -> [Mango9SupervisorMessage] {
        ids.compactMap { Mango9SupervisorMessage(["id": "message-\($0)", "role": "assistant", "parts": [["type": "text", "text": "Saved reply \($0)"]]]) }
    }
    func testInitialRenderedPageIsBoundedAndIncludesTheLatestReply() {
        let window = Mango9SupervisorMessageWindow(), messages = windowMessages(0..<200)
        let visible = window.visible(in: messages)
        XCTAssertEqual(visible.count, 40)
        XCTAssertEqual(visible.first?.id, "message-160")
        XCTAssertEqual(visible.last?.id, "message-199")
        XCTAssertTrue(window.visible(in: []).isEmpty)
    }
    func testOlderPagesRemainAccessibleWithoutRenderingAllHistoryAtOpen() {
        var window = Mango9SupervisorMessageWindow()
        let messages = windowMessages(0..<121)
        window.preserve(in: messages)
        window.expand(in: messages)
        XCTAssertEqual(window.visible(in: messages).count, 80)
        window.expand(in: messages)
        XCTAssertEqual(window.visible(in: messages).count, 120)
        window.expand(in: messages)
        XCTAssertEqual(window.visible(in: messages), messages)
    }
    func testRenderedWindowPreservesItsTopAcrossIncomingAndOlderMessages() {
        var window = Mango9SupervisorMessageWindow()
        window.preserve(in: windowMessages(40..<120))
        XCTAssertEqual(window.firstID, "message-80")
        XCTAssertEqual(window.visible(in: windowMessages(0..<121)).first?.id, "message-80")
        XCTAssertEqual(window.visible(in: windowMessages(0..<121)).last?.id, "message-120")
        window.expand(in: windowMessages(0..<121))
        XCTAssertEqual(window.firstID, "message-40")
    }
    func testReplacedOrClearedHistoryDoesNotLeaveAnEmptyRenderedWindow() {
        var window = Mango9SupervisorMessageWindow()
        window.preserve(in: windowMessages(0..<100))
        XCTAssertEqual(window.visible(in: windowMessages(200..<250)).count, 40)
        XCTAssertEqual(window.visible(in: windowMessages(200..<250)).last?.id, "message-249")
        window.preserve(in: [])
        XCTAssertNil(window.firstID)
        XCTAssertEqual(window.visible(in: windowMessages(0..<1)).count, 1)
    }
    func testNativeBottomPaddingDoesNotTriggerRepeatedScrollCorrections() {
        for bottom in [CGFloat(700), 699.5, 685, 671, 660] {
            let layout = Mango9SupervisorScrollLayout(id: "last", frame: CGRect(x: 0, y: bottom - 100, width: 320, height: 100))
            XCTAssertFalse(Mango9SupervisorScrollLayout.needsAlignment(target: "last", layout: layout, viewportHeight: 700))
        }
    }
    func testOffscreenOrUnmeasuredLastMessageStillGetsOneAlignment() {
        XCTAssertTrue(Mango9SupervisorScrollLayout.needsAlignment(target: "last", layout: nil, viewportHeight: 700))
        for bottom in [CGFloat(-20), 40, 500, 702, 900] {
            let layout = Mango9SupervisorScrollLayout(id: "last", frame: CGRect(x: 0, y: bottom - 100, width: 320, height: 100))
            XCTAssertTrue(Mango9SupervisorScrollLayout.needsAlignment(target: "last", layout: layout, viewportHeight: 700))
        }
        let stale = Mango9SupervisorScrollLayout(id: "previous-conversation", frame: CGRect(x: 0, y: 600, width: 320, height: 100))
        XCTAssertTrue(Mango9SupervisorScrollLayout.needsAlignment(target: "last", layout: stale, viewportHeight: 700))
    }
    func testTransientPresentationSizesDoNotMoveTheScrollView() {
        for height in [CGFloat(0), -1, .nan, .infinity] {
            XCTAssertFalse(Mango9SupervisorScrollLayout.needsAlignment(target: "last", layout: nil, viewportHeight: height))
        }
    }
    func testScrollMovementDoesNotFeedBackIntoAnotherScrollRequest() {
        let original = Mango9SupervisorScrollLayout(id: "last", frame: CGRect(x: 0, y: 600, width: 320, height: 100))
        for y in [CGFloat(610), 650, 600, 550, -10] {
            let shifted = Mango9SupervisorScrollLayout(id: "last", frame: CGRect(x: 0, y: y, width: 320, height: 100))
            XCTAssertFalse(Mango9SupervisorScrollLayout.contentChanged(from: original, to: shifted))
        }
        let expandedCard = Mango9SupervisorScrollLayout(id: "last", frame: CGRect(x: 0, y: 600, width: 320, height: 200))
        XCTAssertTrue(Mango9SupervisorScrollLayout.contentChanged(from: original, to: expandedCard))
        let newReply = Mango9SupervisorScrollLayout(id: "new", frame: original.frame)
        XCTAssertTrue(Mango9SupervisorScrollLayout.contentChanged(from: original, to: newReply))
        XCTAssertTrue(Mango9SupervisorScrollLayout.contentChanged(from: nil, to: original))
        XCTAssertFalse(Mango9SupervisorScrollLayout.contentChanged(from: original, to: original))
    }
    func testInitialScrollTargetsVisibleContentRatherThanAnEmptyLazyFooter() throws {
        let last = try XCTUnwrap(Mango9SupervisorMessage(["id": "last-reply", "role": "assistant", "parts": [["type": "text", "text": "Latest reply"]]]))
        let pending = Mango9SupervisorCommand(conversationID: UUID().uuidString.lowercased(), text: "New question")
        XCTAssertEqual(Mango9SupervisorScrollTarget.id(messages: [last], pending: nil, sending: false), last.id)
        XCTAssertEqual(Mango9SupervisorScrollTarget.id(messages: [last], pending: pending, sending: false), pending.messageID)
        XCTAssertEqual(Mango9SupervisorScrollTarget.id(messages: [last], pending: pending, sending: true), Mango9SupervisorScrollTarget.thinking)
        let savedPending = try XCTUnwrap(Mango9SupervisorMessage(["id": pending.messageID, "role": "user", "parts": [["type": "text", "text": pending.text]]]))
        XCTAssertEqual(Mango9SupervisorScrollTarget.id(messages: [savedPending, last], pending: pending, sending: false), last.id)
        XCTAssertEqual(Mango9SupervisorScrollTarget.id(messages: [], pending: nil, sending: false), Mango9SupervisorScrollTarget.empty)
    }
    func testCreationCardsRequireSucceededReceiptAndSameServerPageLinks() throws {
        let proof: [String: Any] = ["version": 1, "statusText": "Task created.", "links": [["label": "View task", "href": "/app/tasks/42"]], "recipients": [["name": "Assigned person", "email": "person@example.invalid"]]]
        var detail: [String: Any] = ["state": "SUCCEEDED", "recap": ["creation": proof]]
        let card = try XCTUnwrap(Mango9SupervisorCreation(detail, baseURL: "https://crm.example.invalid/app/"))
        XCTAssertEqual(card.status, "Task created.")
        XCTAssertEqual(card.recipients, ["Assigned person · person@example.invalid"])
        let message = try XCTUnwrap(Mango9SupervisorMessage(["id": "created", "role": "assistant", "metadata": ["creations": [proof]]]))
        XCTAssertNotNil(message.creationData)
        let user = try XCTUnwrap(Mango9SupervisorMessage(["id": "created", "role": "user", "metadata": ["creations": [proof]]]))
        XCTAssertNil(user.creationData)
        XCTAssertEqual(card.links.first?.url.absoluteString, "https://crm.example.invalid/app/tasks/42")
        XCTAssertEqual(card.links.first.flatMap { Mango9SupervisorCreation.nativeTaskID($0.url) }, 42)
        for state in ["PENDING_APPROVAL", "APPROVED", "EXECUTING", "FAILED", "OUTCOME_UNKNOWN"] {
            detail["state"] = state; XCTAssertNil(Mango9SupervisorCreation(detail, baseURL: "https://crm.example.invalid"))
        }
        detail["state"] = "SUCCEEDED"
        for target in ["https://foreign.invalid/app/tasks/42", "/app/api/v2/tasks/42", "/app/../api/tasks", "javascript:alert(1)"] {
            var invalid = proof; invalid["links"] = [["label": "View task", "href": target]]
            detail["recap"] = ["creation": invalid]
            XCTAssertNil(Mango9SupervisorCreation(detail, baseURL: "https://crm.example.invalid"))
        }
        for target in ["/app/tasks/42/edit", "/app/tasks/new", "/app/workflow/42"] {
            XCTAssertNil(Mango9SupervisorCreation.nativeTaskID(try XCTUnwrap(URL(string: "https://crm.example.invalid" + target))))
        }
    }
    func testHistoryTimestampsUseLocalDaysAndUSDatesAfterAWeek() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let parse = ISO8601DateFormatter()
        let now = try XCTUnwrap(parse.date(from: "2026-10-31T06:45:00Z"))
        func label(_ value: String) throws -> String { Mango9SupervisorHistoryDate.label(try XCTUnwrap(parse.date(from: value)), now: now, calendar: calendar) }
        XCTAssertEqual(try label("2026-10-31T06:30:00Z"), "Today, 11:30 PM")
        XCTAssertEqual(try label("2026-10-30T06:30:00Z"), "Yesterday, 11:30 PM")
        XCTAssertEqual(try label("2026-10-29T06:30:00Z"), "Wednesday, 11:30 PM")
        XCTAssertEqual(try label("2026-10-24T06:30:00Z"), "10/23/2026, 11:30 PM")
        XCTAssertEqual(Mango9SupervisorHistoryDate.label(nil, now: now, calendar: calendar), "")
    }
    func testHistoryParsesFractionalDatesVoiceAndMessageCounts() throws {
        let row = try XCTUnwrap(Mango9SupervisorConversation(["id": UUID().uuidString, "title": "Example", "updatedAt": "2026-10-31T06:30:00.123Z", "messageCount": 4, "hasVoice": true]))
        XCTAssertNotNil(row.updatedAt); XCTAssertEqual(row.messageCount, 4); XCTAssertTrue(row.hasVoice)
    }
    func testMessagesUseNativeCalloutTypographyInsteadOfAFixedPointSize() {
        XCTAssertEqual(Mango9SupervisorTypography.message, Font.callout)
        XCTAssertNotEqual(Mango9SupervisorTypography.message, Font.system(size: 16))
    }
    @MainActor func testMessageTypographyRespondsToAccessibilityTextSizes() throws {
        guard #available(iOS 16.0, *) else { throw XCTSkip("Requires native hosting layout measurement.") }
        let sample = "A message should use your iPhone’s text size and wrap naturally without clipping its contents."
        func height(_ category: ContentSizeCategory) -> CGFloat {
            let host = UIHostingController(rootView: Text(sample)
                .font(Mango9SupervisorTypography.message)
                .environment(\.sizeCategory, category)
                .fixedSize(horizontal: false, vertical: true))
            return host.sizeThatFits(in: CGSize(width: 280, height: 10000)).height
        }
        let normal = height(.large)
        XCTAssertGreaterThan(normal, 0)
        XCTAssertGreaterThan(height(.accessibilityExtraExtraExtraLarge), normal)
    }
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
    let local = SupervisorLocalFixture()
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
private final class SupervisorLocalFixture {
    var chats: [Mango9SupervisorSavedChat] = []
    var store: Mango9SupervisorLocalStore {
        .init(read: { [self] in chats }, write: { [self] in chats = $0 })
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
    private static func settings(_ id: String, state: String = "PENDING_APPROVAL") -> [String: Any] {
        ["id": id, "state": state, "approvalDigest": String(repeating: "a", count: 64),
         "arguments": ["private": "not-for-local-storage"], "recap": ["version": 1, "title": "Create task",
         "fields": [["label": "Owner", "value": "Example owner"], ["label": "Task", "value": "Follow up"]],
         "creation": ["version": 1, "statusText": "Task created.", "links": [["label": "View task", "href": "/app/tasks/42"]],
                      "recipients": [["name": "Example owner", "email": "owner@example.invalid"]]]]]
    }
    private static func settingsMessage(_ id: String) -> Mango9SupervisorMessage {
        Mango9SupervisorMessage(["id": "reply-settings", "role": "assistant", "parts": [["type": "text", "text": "Your settings"]], "metadata": ["actionIds": [id]]])!
    }
    @MainActor func testSettingsAndMessagesRestoreOfflineWithoutPermissionToApply() async throws {
        try await withModel(start: false) { model, client, vault in
            let id = UUID().uuidString.lowercased(), raw = Self.settings(id)
            let saved = try XCTUnwrap(Mango9SupervisorSavedConfiguration(raw))
            let row = try XCTUnwrap(Mango9SupervisorConversation(["id": model.conversationID, "title": "Saved task", "messageCount": 1]))
            vault.local.chats = [.init(id: model.conversationID, savedAt: Date(), conversation: row,
                                      messages: [Self.settingsMessage(id)], configurations: [id: saved])]
            client.handler = { _, _ in throw URLError(.notConnectedToInternet) }
            await model.start()
            XCTAssertFalse(model.authorized); XCTAssertTrue(model.usingSavedHistory)
            XCTAssertEqual(model.messages.first?.text, "Your settings")
            XCTAssertEqual(model.configurationDetails[id]?["state"] as? String, "PENDING_APPROVAL")
            XCTAssertTrue(model.savedConfigurationIDs.contains(id)); XCTAssertFalse(model.canSend)
            XCTAssertNil(model.configurationDetails[id]?["approvalDigest"])
            await model.decide("approve", expected: try XCTUnwrap(Mango9SupervisorApproval(raw)))
            XCTAssertFalse(client.calls.contains { $0.hasSuffix("/decision") })
            try await model.loadConversations(query: "saved", channel: "text")
            XCTAssertEqual(model.conversations.count, 1); XCTAssertTrue(model.canBrowseHistory); XCTAssertFalse(model.canManageHistory)
        }
    }
    @MainActor func testSuccessfulSettingsFetchPersistsAndNetworkFailureDoesNotEraseIt() async throws {
        try await withModel { model, client, vault in
            let id = UUID().uuidString.lowercased(), raw = Self.settings(id, state: "SUCCEEDED")
            model.messages = [Self.settingsMessage(id)]
            client.handler = { _, _ in raw }
            await model.loadConfiguration(id)
            XCTAssertFalse(model.savedConfigurationIDs.contains(id))
            let saved = try XCTUnwrap(vault.local.chats.first?.configurations[id])
            XCTAssertEqual(saved.detail?["state"] as? String, "SUCCEEDED")
            let creation = try XCTUnwrap(Mango9SupervisorCreation(try XCTUnwrap(saved.detail), baseURL: client.original.crmBaseUrl))
            XCTAssertEqual(creation.links.first?.url.path, "/app/tasks/42")
            XCTAssertEqual(creation.recipients, ["Example owner · owner@example.invalid"])
            client.handler = { _, _ in throw URLError(.timedOut) }
            await model.loadConfiguration(id)
            XCTAssertEqual(model.configurationDetails[id]?["state"] as? String, "SUCCEEDED")
            XCTAssertTrue(model.savedConfigurationIDs.contains(id))
            model.close(); await model.start()
            XCTAssertTrue(model.usingSavedHistory)
            XCTAssertEqual(model.configurationDetails[id]?["state"] as? String, "SUCCEEDED")
            XCTAssertEqual(model.messages.first?.text, "Your settings")
        }
    }
    @MainActor func testFreshServerStateReplacesCachedSettingsBeforeApply() async throws {
        try await withModel(start: false) { model, client, vault in
            let id = UUID().uuidString.lowercased(), raw = Self.settings(id)
            vault.local.chats = [.init(id: model.conversationID, savedAt: Date(), conversation: nil,
                                      messages: [Self.settingsMessage(id)], configurations: [id: try XCTUnwrap(.init(raw))])]
            client.handler = { path, body in
                if path == "authorize" { return ["enabled": true] }
                if path == "actions/" + id { return Self.settings(id, state: "SUCCEEDED") }
                if body?["action"] as? String == "list" { return ["items": []] }
                return ["messages": [["id": "reply-settings", "role": "assistant", "parts": [["type": "text", "text": "Your settings"]], "metadata": ["actionIds": [id]]]], "cursor": "1"]
            }
            await model.start()
            for _ in 0..<20 { await Task.yield() }
            await model.loadConfiguration(id)
            XCTAssertFalse(model.savedConfigurationIDs.contains(id))
            XCTAssertEqual(model.configurationDetails[id]?["state"] as? String, "SUCCEEDED")
            await model.decide("approve", expected: try XCTUnwrap(Mango9SupervisorApproval(raw)))
            XCTAssertFalse(client.calls.contains { $0.hasSuffix("/decision") })
        }
    }
    @MainActor func testRevokedAccessPurgesLocalSettingsInsteadOfUsingOfflineFallback() async throws {
        try await withModel(start: false) { model, client, vault in
            let id = UUID().uuidString.lowercased()
            vault.local.chats = [.init(id: model.conversationID, savedAt: Date(), conversation: nil,
                                      messages: [Self.settingsMessage(id)], configurations: [id: try XCTUnwrap(.init(Self.settings(id)))])]
            client.handler = { _, _ in throw Mango9SupervisorFailure(status: 403, code: "forbidden") }
            await model.start()
            XCTAssertTrue(model.messages.isEmpty); XCTAssertTrue(model.configurationDetails.isEmpty)
            XCTAssertTrue(vault.local.chats.isEmpty); XCTAssertFalse(model.usingSavedHistory); XCTAssertFalse(model.authorized)
        }
    }
    @MainActor func testSettingsDeniedOrDeletedOnServerDoNotRemainCached() async throws {
        try await withModel { model, client, vault in
            let id = UUID().uuidString.lowercased()
            model.messages = [Self.settingsMessage(id)]; client.handler = { _, _ in Self.settings(id) }
            await model.loadConfiguration(id)
            client.handler = { _, _ in throw Mango9SupervisorFailure(status: 404, code: "not_found") }
            await model.loadConfiguration(id)
            XCTAssertNil(model.configurationDetails[id]); XCTAssertNil(vault.local.chats.first?.configurations[id])
        }
    }
    @MainActor func testLateSettingsFetchCannotCrossConversationScope() async throws {
        try await withModel { model, client, vault in
            let id = UUID().uuidString.lowercased(), gate = SupervisorDeferredFixture()
            model.messages = [Self.settingsMessage(id)]
            client.handler = { path, _ in
                if path == "actions/" + id { return await gate.wait() }
                return ["messages": [], "cursor": "0"]
            }
            let request = Task { await model.loadConfiguration(id) }
            await fulfillment(of: [gate.entered], timeout: 3)
            await model.select(UUID().uuidString.lowercased())
            gate.resume(Self.settings(id)); await request.value
            XCTAssertNil(model.configurationDetails[id]); XCTAssertTrue(vault.local.chats.isEmpty)
        }
    }
    func testDisplaySnapshotOmitsCommandSecretsAndCannotBecomeAnApproval() throws {
        let id = UUID().uuidString, saved = try XCTUnwrap(Mango9SupervisorSavedConfiguration(Self.settings(id)))
        let detail = try XCTUnwrap(saved.detail)
        XCTAssertNil(detail["arguments"]); XCTAssertNil(detail["approvalDigest"])
        XCTAssertEqual(detail["summaryOnly"] as? Bool, true); XCTAssertNil(Mango9SupervisorApproval(detail))
        XCTAssertEqual((detail["recap"] as? [String: Any])?["title"] as? String, "Create task")
        XCTAssertNil(Mango9SupervisorSavedConfiguration(["id": id, "state": "SUCCEEDED", "recap": ["version": 1, "fields": [["value": "Missing label"]]]]))
    }
    @MainActor func testDeviceCacheRoundTripProtectionAndLoginIsolation() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("supervisor-cache-fixture-" + UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var session = SupervisorClientFixture().original; session.loginGeneration = "login-one"
        let store = Mango9SupervisorLocalStore.device(session, root: root), id = UUID().uuidString
        let saved = Mango9SupervisorSavedChat(id: UUID().uuidString, savedAt: Date(), conversation: nil,
            messages: [Self.settingsMessage(id)], configurations: [id: try XCTUnwrap(.init(Self.settings(id)))])
        try store.write([saved]); XCTAssertEqual(try store.read().first?.id, saved.id)
        let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        let file = try XCTUnwrap(files.first), attributes = try FileManager.default.attributesOfItem(atPath: file.path)
#if !targetEnvironment(simulator)
        // Data Protection is enforced and reported on hardware, not by the
        // simulator's host filesystem. Keep this assertion in device runs.
        XCTAssertEqual(attributes[.protectionKey] as? FileProtectionType, .complete)
#endif
        XCTAssertEqual(try root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        for key in ["loginGeneration", "crmId", "crmApiBaseUrl", "userId", "parentClientId", "role", "sipIdentity"] {
            var raw = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(session)) as? [String: Any])
            raw[key] = "different"
            let different = try JSONDecoder().decode(Mango9Session.self, from: JSONSerialization.data(withJSONObject: raw))
            XCTAssertNotEqual(Mango9SupervisorLocalStore.scope(session), Mango9SupervisorLocalStore.scope(different), key)
            XCTAssertTrue(try Mango9SupervisorLocalStore.device(different, root: root).read().isEmpty, key)
        }
        let expired = Mango9SupervisorSavedChat(id: saved.id, savedAt: Date().addingTimeInterval(-31 * 86400), conversation: nil, messages: saved.messages, configurations: saved.configurations)
        XCTAssertTrue(Mango9SupervisorLocalStore.bounded([expired]).isEmpty)
        try store.write([]); XCTAssertTrue(try store.read().isEmpty)
    }
    @MainActor func testForegroundTailRejectsAnOlderPageFromBeforeBackgrounding() async throws {
        try await withModel(start: false) { model, client, _ in
            let gate = SupervisorDeferredFixture(); var tails = 0
            client.handler = { path, body in
                if path == "authorize" { return ["enabled": true] }
                if body?["action"] as? String == "list" { return ["items": []] }
                if body?["before"] != nil { return await gate.wait() }
                tails += 1
                return ["messages": [Self.reply(tails == 1 ? "old-window" : "fresh-window", "Reply")], "cursor": tails == 1 ? "40" : "100", "olderCursor": tails == 1 ? "10" : "80"]
            }
            await model.start()
            let old = Task { await model.loadOlderHistory() }
            await fulfillment(of: [gate.entered], timeout: 3)
            await model.refreshHistory(latest: true)
            gate.resume(["messages": [Self.reply("stale-page", "Old page")], "olderCursor": "1"])
            await old.value
            XCTAssertEqual(model.messages.map(\.id), ["fresh-window"]); XCTAssertTrue(model.moreMessages)
        }
    }
    @MainActor func testHistoryOpensNewestPageAndOlderPagesPreserveTheLiveCursor() async throws {
        try await withModel(start: false) { model, client, _ in
            client.handler = { path, body in
                if path == "authorize" { return ["enabled": true] }
                if body?["action"] as? String == "list" { return ["items": []] }
                if body?["latest"] as? Bool == true { return ["messages": [Self.reply("newest", "Latest reply")], "cursor": "50", "olderCursor": "10"] }
                if let before = body?["before"] as? String { XCTAssertEqual(before, "10"); return ["messages": [Self.reply("older", "Earlier reply")], "cursor": "9"] }
                XCTAssertEqual(body?["after"] as? String, "50")
                return ["messages": [Self.reply("incoming", "Incoming reply")], "cursor": "51"]
            }
            await model.start(); XCTAssertEqual(model.messages.map(\.id), ["newest"]); XCTAssertTrue(model.moreMessages)
            await model.loadOlderHistory(); XCTAssertEqual(model.messages.map(\.id), ["older", "newest"]); XCTAssertFalse(model.moreMessages)
            await model.refreshHistory(); XCTAssertEqual(model.messages.map(\.id), ["older", "newest", "incoming"])
        }
    }
    @MainActor func testExhaustedHistoryAndForegroundKeepTheOlderMessagesBeingRead() async throws {
        try await withModel(start: false) { model, client, _ in
            var tailReads = 0, olderReads = 0
            client.handler = { path, body in
                if path == "authorize" { return ["enabled": true] }
                if body?["action"] as? String == "list" { return ["items": []] }
                if body?["latest"] as? Bool == true {
                    tailReads += 1
                    return ["messages": [Self.reply("latest", "Latest")], "cursor": "50", "olderCursor": "10"]
                }
                if body?["before"] != nil {
                    olderReads += 1
                    return ["messages": [Self.reply("first", "Oldest")]]
                }
                XCTAssertEqual(body?["after"] as? String, "50")
                return ["messages": [], "cursor": "50"]
            }
            await model.start(); await model.loadOlderHistory()
            XCTAssertFalse(model.moreMessages)
            for _ in 0..<3 { await model.loadOlderHistory() }
            model.background(); await model.resume(latest: false)
            XCTAssertEqual(olderReads, 1)
            XCTAssertEqual(tailReads, 1, "Reading older history must not be replaced with the newest page on foreground")
            XCTAssertEqual(model.messages.map(\.id), ["first", "latest"])
        }
    }
    @MainActor func testOpeningCachedHistoryNeverPublishesAnEmptyIntermediateList() async throws {
        try await withModel(start: false) { model, client, vault in
            let cached = try XCTUnwrap(Mango9SupervisorMessage(Self.reply("cached", "Saved reply")))
            vault.local.chats = [.init(id: model.conversationID, savedAt: Date(), conversation: nil,
                                      messages: [cached], configurations: [:])]
            client.handler = { path, body in
                if path == "authorize" { return ["enabled": true] }
                if body?["action"] as? String == "list" { return ["items": []] }
                return ["messages": [Self.reply("cached", "Saved reply"), Self.reply("newest", "Latest reply")], "cursor": "2"]
            }
            var snapshots: [[String]] = []
            let observer = model.$messages.dropFirst().sink { snapshots.append($0.map(\.id)) }
            defer { observer.cancel() }
            await model.start()
            XCTAssertEqual(model.messages.map(\.id), ["cached", "newest"])
            XCTAssertFalse(snapshots.contains([]), "Refreshing the saved chat must not remove its ScrollView between cached and server history")
            XCTAssertEqual(snapshots, [["cached"], ["cached", "newest"]], "Publish the server page once, not one message at a time")
        }
    }
    @MainActor func testLatestHistoryReplacesVisibleRowsAtomicallyAndKeepsServerOrder() async throws {
        try await withModel { model, client, _ in
            model.messages = [try XCTUnwrap(Mango9SupervisorMessage(Self.reply("old", "Earlier reply")))]
            client.handler = { _, _ in
                ["messages": [Self.reply("first", "First"), Self.reply("last", "Last"), Self.reply("last", "Updated last")], "cursor": "5"]
            }
            var snapshots: [[String]] = []
            let observer = model.$messages.dropFirst().sink { snapshots.append($0.map(\.id)) }
            defer { observer.cancel() }
            await model.refreshHistory(latest: true)
            XCTAssertEqual(snapshots, [["first", "last"]])
            XCTAssertEqual(model.messages.last?.text, "Updated last")
        }
    }
    @MainActor func testAnEarlierConfirmedPendingTurnDoesNotReplayWhenOutsideTheLatestPage() async throws {
        try await withModel(start: false) { model, client, vault in
            let command = Mango9SupervisorCommand(conversationID: UUID().uuidString.lowercased(), text: "Earlier request")
            vault.command = command
            client.handler = { path, body in
                if path == "authorize" { return ["enabled": true] }
                if body?["action"] as? String == "list" { return ["items": []] }
                XCTAssertEqual(body?["latest"] as? Bool, true); XCTAssertEqual(body?["requestId"] as? String, command.requestID)
                return ["messages": [Self.reply("recent", "Recent reply")], "cursor": "100", "confirmedRequestId": command.requestID]
            }
            await model.start(); XCTAssertNil(model.pending); XCTAssertNil(vault.command)
            XCTAssertEqual(client.calls.filter { $0 == "chat" }.count, 0)
        }
    }
    @MainActor func testForegroundRefreshReplacesAStaleWindowWithTheLatestPage() async throws {
        try await withModel { model, client, _ in
            model.messages = [try XCTUnwrap(Mango9SupervisorMessage(Self.reply("stale", "Old window")))]
            client.handler = { _, body in
                XCTAssertEqual(body?["latest"] as? Bool, true)
                return ["messages": [Self.reply("latest", "Current window")], "cursor": "100", "olderCursor": "80"]
            }
            await model.refreshHistory(latest: true)
            XCTAssertEqual(model.messages.map(\.id), ["latest"]); XCTAssertTrue(model.moreMessages)
        }
    }
    @MainActor func testAnOlderPendingReadCannotOverwriteSuccessfulApply() async throws {
        try await withModel { model, client, _ in
            let id = UUID().uuidString.lowercased(), gate = SupervisorDeferredFixture()
            let pending: [String: Any] = ["id": id, "approvalDigest": String(repeating: "a", count: 64), "state": "PENDING_APPROVAL"]
            let done: [String: Any] = ["id": id, "approvalDigest": String(repeating: "a", count: 64), "state": "SUCCEEDED"]
            model.messages = [try XCTUnwrap(Mango9SupervisorMessage(["id": UUID().uuidString, "role": "assistant", "parts": [["type": "text", "text": "Settings"]], "metadata": ["actionIds": [id]]]))]
            model.configurationDetails[id] = pending
            var reads = 0, decisions = 0
            client.handler = { path, _ in
                if path.hasSuffix("/decision") { decisions += 1; return done }
                if path == "actions/" + id { reads += 1; if reads == 1 { return await gate.wait() }; return done }
                return ["messages": [], "items": [], "cursor": "0"]
            }
            let old = Task { await model.loadConfiguration(id) }
            await fulfillment(of: [gate.entered], timeout: 3)
            await model.decide("approve", expected: try XCTUnwrap(Mango9SupervisorApproval(pending)))
            gate.resume(pending); await old.value
            XCTAssertEqual(model.configurationDetails[id]?["state"] as? String, "SUCCEEDED")
            XCTAssertEqual(decisions, 1); XCTAssertTrue(model.configurationFlights.isEmpty)
        }
    }
    @MainActor func testHistorySearchRejectsAnOlderListResponse() async throws {
        try await withModel { model, client, _ in
            let gate = SupervisorDeferredFixture(), fresh = UUID().uuidString
            client.handler = { _, body in
                if body?["query"] as? String == "old" { return await gate.wait() }
                XCTAssertEqual(body?["query"] as? String, "new")
                XCTAssertEqual(body?["channel"] as? String, "voice")
                return ["items": [["id": fresh, "title": "New result"]]]
            }
            let old = Task { try await model.loadConversations(query: "old") }
            await fulfillment(of: [gate.entered], timeout: 3)
            try await model.loadConversations(query: "new", channel: "voice")
            gate.resume(["items": [["id": UUID().uuidString, "title": "Old result"]]])
            try await old.value
            XCTAssertEqual(model.conversations.map(\.id), [fresh]); XCTAssertFalse(model.loadingConversations)
        }
    }
    @MainActor func testDeletingCurrentHistoryStartsAnEmptyChatWithoutRepeatingCommands() async throws {
        try await withModel { model, client, _ in
            let old = model.conversationID
            model.conversations = [try XCTUnwrap(Mango9SupervisorConversation(["id": old, "title": "Example"]))]
            var deletes = 0
            client.handler = { path, body in
                XCTAssertEqual(path, "history")
                if body?["action"] as? String == "delete" {
                    deletes += 1; XCTAssertEqual(body?["conversationId"] as? String, old)
                    XCTAssertNil(body?["userId"]); return ["deleted": true, "id": old]
                }
                return ["items": [], "messages": [], "cursor": "0"]
            }
            await model.deleteConversation(old)
            XCTAssertEqual(deletes, 1); XCTAssertNotEqual(model.conversationID, old)
            XCTAssertTrue(model.conversations.isEmpty); XCTAssertTrue(model.messages.isEmpty)
        }
    }
    @MainActor private func withModel(start: Bool = true, _ body: (Mango9SupervisorModel, SupervisorClientFixture, SupervisorPendingFixture) async throws -> Void) async throws {
        let name = "supervisor-model-fixture-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        let client = SupervisorClientFixture(), vault = SupervisorPendingFixture()
        Mango9SupervisorConsent.allow(client.original, defaults: defaults)
        let model = Mango9SupervisorModel(session: client.original, client: client, defaults: defaults, pendingStore: vault.store, localStore: vault.local.store)
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
