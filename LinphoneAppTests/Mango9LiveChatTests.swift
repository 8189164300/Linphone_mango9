import XCTest
import UIKit
@testable import LinphoneApp

final class Mango9LiveChatTests: XCTestCase {
	func testLiveReactionSnapshotsDoNotInterpretStaffMapsOrInventSupport() throws {
		let raw: [String: Any] = ["items": [["emoji": "👍", "count": 2, "mine": true]], "version": 4,
			"supported": true, "recipientVisible": true, "allowed": ["👍", "❤️"]]
		let snapshot = try XCTUnwrap(Mango9LiveReactions.parse(raw))
		XCTAssertEqual(snapshot.items.first?.count, 2); XCTAssertTrue(snapshot.items.first?.mine == true)
		XCTAssertNil(Mango9LiveReactions.parse(["👍": [1, 2]]))
		XCTAssertFalse(try XCTUnwrap(Mango9LiveMessage(["id": 1, "direction": "incoming"])).reactions.supported)
		var malformed = raw; malformed["version"] = -1
		XCTAssertNil(Mango9LiveReactions.parse(malformed))
	}
	func testLiveReactionRetryPreservesRemovalAndOriginalVersion() throws {
		let command = Mango9LiveReactionCommand(messageId: 42, version: 7, emoji: nil)
		let restored = try JSONDecoder().decode(Mango9LiveReactionCommand.self, from: JSONEncoder().encode(command))
		XCTAssertEqual(restored, command)
		XCTAssertTrue(restored.body["emoji"] is NSNull)
		XCTAssertEqual(restored.body["version"] as? Int, 7)
		XCTAssertEqual(restored.body["requestId"] as? String, command.requestId)
	}
	func testConversationSiteUsesTheSameWebsitePolicyAsPublicCapabilities() throws {
		let site = try XCTUnwrap(Mango9LiveSite(["id": "website", "name": "Example", "settings": ["typingIndicators": false, "readReceipts": true, "attachmentsEnabled": true]]))
		XCTAssertEqual(site.capabilities["typing"] as? Bool, false)
		XCTAssertEqual(site.capabilities["readState"] as? Bool, true)
		XCTAssertEqual(site.capabilities["attachments"] as? [String], ["image/png", "image/jpeg", "image/webp", "application/pdf"])
		let advertised = try XCTUnwrap(Mango9LiveSite(["id": "website", "settings": ["attachmentsEnabled": true], "capabilities": ["attachments": [String]()]]))
		XCTAssertEqual(advertised.capabilities["attachments"] as? [String], [], "Explicit server capabilities take priority")
	}
	func testCompactMetadataDoesNotIncludeFullPageOrDeviceDetails() {
		let context = Mango9LiveVisitorContext(["country": ["flag": "🇺🇸", "city": "Example City", "region": "Example Region", "name": "United States"],
			"ipAddress": "192.0.2.1", "entryPage": "https://example.invalid/private/page", "device": ["browser": "Example Browser"]])
		XCTAssertEqual(context.compact, "🇺🇸 Example City · IP 192.0.2.1 · example.invalid")
		XCTAssertFalse(context.compact.contains("/private/page"))
		XCTAssertTrue(context.rows.contains { $0.0 == "Entry page" && $0.1.contains("/private/page") }, "Full visit details stay available through the info button")
	}
	func testProviderDatesWithAndWithoutFractions() {
		XCTAssertEqual(Mango9LiveChatTime.date("2026-01-01T00:00:00.000Z"), Mango9LiveChatTime.date("2026-01-01T00:00:00Z"))
		XCTAssertNotNil(Mango9LiveChatTime.date("2026-01-01T00:00:00.123456+00:00"))
		XCTAssertNil(Mango9LiveChatTime.date("not-a-date"))
	}
	@MainActor func testTabsAreEnabledByDefaultAndDisablingIsAccountScoped() throws {
		let suite = "mango9-livechat-test-" + UUID().uuidString
		let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
		defer { defaults.removePersistentDomain(forName: suite) }
		let first = Mango9ChatTabPreferences(defaults: defaults, account: { "account-a" })
		XCTAssertTrue(first.team && first.social && first.live)
		first.team = false; first.social = false; first.live = false
		XCTAssertTrue(first.isVisible(.sms)); XCTAssertFalse(first.isVisible(.live))
		let restored = Mango9ChatTabPreferences(defaults: defaults, account: { "account-a" })
		XCTAssertFalse(restored.team || restored.social || restored.live)
		let other = Mango9ChatTabPreferences(defaults: defaults, account: { "account-b" })
		XCTAssertTrue(other.team && other.social && other.live)
	}
	func testMobileEndpointUsesProvisionedCRMAndBearer() throws {
		let session = Mango9Session(crmId: "fixture", crmBaseUrl: "https://crm.invalid", crmApiBaseUrl: "https://crm.invalid/app/api/v2",
			userId: "7", parentClientId: "7", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture",
			accessToken: "fixture-token", refreshToken: "fixture-refresh", smsChatApi: "https://messages.invalid/sms_chat_api",
			connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:7@voice.invalid")
		let request = try Mango9CalendarAPI.request(session: session, path: "messages", query: [.init(name: "id", value: "test")], scope: .livechat)
		XCTAssertEqual(request.url?.host, "crm.invalid")
		XCTAssertEqual(request.url?.path, "/app/api/v2/mobile/livechat/messages")
		XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
		XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.name, "id")
	}
	func testVisitorDetailsOnlyDisplaySuppliedMetadata() {
		XCTAssertTrue(Mango9LiveVisitorContext().rows.isEmpty)
		let context = Mango9LiveVisitorContext(["country": ["city": "Example City", "name": "Example Country"], "entryPage": "https://example.invalid/",
			"device": ["browser": "Example Browser"], "timeBeforeFirstMessageSeconds": 42])
		XCTAssertTrue(context.rows.contains { $0.0 == "Approximate location" && $0.1 == "Example City, Example Country" })
		XCTAssertTrue(context.rows.contains { $0.0 == "Time before first message" && $0.1 == "42 seconds" })
		XCTAssertFalse(context.rows.contains { $0.0 == "IP address" || $0.0 == "Referrer" || $0.0 == "Operating system" })
	}
	func testActivityCannotBecomeMessageOrPreview() {
		XCTAssertNil(Mango9LiveMessage(["id": 3, "direction": "activity", "content": "Support joined"]))
		let message = Mango9LiveMessage(["id": 4, "direction": "incoming", "content": "Hello", "created_at": 1000,
			"sender": ["name": "Visitor"], "content_attributes": ["visitorContext": ["language": "en"]]])
		XCTAssertEqual(message?.created, Date(timeIntervalSince1970: 1000))
		XCTAssertEqual(message?.context.rows.first?.0, "Language")
	}
 func testSelectionKeepsMessageAndSuppliedVisitorMetadataTogether() throws {
  let message = try XCTUnwrap(Mango9LiveMessage(["id": 42, "direction": "incoming", "content": "Hello 🙂", "sender": ["name": "Visitor"],
   "hiddenForMe": true, "attachments": [["name": "voice.m4a"]],
   "content_attributes": ["visitorContext": ["timeZone": "America/Los_Angeles", "entryPage": "https://example.invalid/contact", "ipAddress": "192.0.2.1"]]]))
  XCTAssertTrue(message.hiddenForMe)
  for value in ["Hello 🙂", "voice.m4a", "Time zone: America/Los_Angeles", "Entry page: https://example.invalid/contact", "IP address: 192.0.2.1"] {
   XCTAssertTrue(message.selectedText.contains(value))
  }
  XCTAssertFalse(message.selectedText.contains("Country:"), "Never invent missing visitor fields")
  XCTAssertTrue(Mango9LiveVisitorContext(["timezone": "America/Los_Angeles"]).rows.contains { $0.0 == "Time zone" && $0.1 == "America/Los_Angeles" })
  XCTAssertFalse(try XCTUnwrap(Mango9LiveMessage(["id": 43, "direction": "outgoing"])).hiddenForMe)
 }
 func testAssignmentUsesTheConfirmedServerIdentity() throws {
  let data: [String: Any] = ["id": UUID().uuidString, "site_id": UUID().uuidString, "assignee_id": "42"]
  let assigned = try XCTUnwrap(Mango9LiveConversation(data))
  XCTAssertEqual(assigned.assignee, 42)
  var unassigned = data; unassigned["assignee_id"] = NSNull()
  XCTAssertNil(try XCTUnwrap(Mango9LiveConversation(unassigned)).assignee)
 }

	func testPersistedRetryKeepsExactRequestAndAttachment() throws {
		let command = Mango9LivePendingSend(text: "A test message", file: Data([1, 2, 3]), name: "test.png", mime: "image/png")
		let restored = try JSONDecoder().decode(Mango9LivePendingSend.self, from: JSONEncoder().encode(command))
		XCTAssertEqual(restored.id, command.id)
		XCTAssertEqual(restored.text, command.text)
		XCTAssertEqual(restored.file, command.file)
		XCTAssertEqual(restored.body["requestId"] as? String, command.id)
		XCTAssertNotEqual(Mango9LivePendingSend(text: command.text).id, command.id)
	}
 func testStaffAttachmentCapabilitiesDoNotDependOnVisitorUploadSwitch() throws {
  let site = try XCTUnwrap(Mango9LiveSite(["id": "test", "settings": ["attachmentsEnabled": false],
   "capabilities": ["attachments": ["image/png", "application/pdf", "audio/mp4"]]]))
  XCTAssertEqual(site.capabilities["attachments"] as? [String], ["image/png", "application/pdf", "audio/mp4"])
 }
 func testNativeFilePayloadPreservesBytesAndRejectsUnsupportedTypes() throws {
  let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
  try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  defer { try? FileManager.default.removeItem(at: directory) }
  let data = Data("%PDF-1.4 fixture".utf8), url = directory.appendingPathComponent("test.pdf")
  try data.write(to: url)
  let attachment = Attachment(id: "fixture", name: "test.pdf", url: url, type: .fileTransfer)
  let result = try Mango9LiveAttachmentPayload.prepare(attachment, text: "Caption", allowedMIMEs: ["application/pdf"])
  XCTAssertEqual(result.file, data); XCTAssertEqual(result.text, "Caption")
  XCTAssertThrowsError(try Mango9LiveAttachmentPayload.prepare(attachment, text: "", allowedMIMEs: ["image/png"]))
  try Data().write(to: url)
  XCTAssertThrowsError(try Mango9LiveAttachmentPayload.prepare(attachment, text: "", allowedMIMEs: ["application/pdf"]))
  try Data(count: 5 * 1024 * 1024 + 1).write(to: url)
  XCTAssertThrowsError(try Mango9LiveAttachmentPayload.prepare(attachment, text: "", allowedMIMEs: ["application/pdf"]))
 }
 @MainActor func testNativeImagePayloadUsesCompatibleImageAndKeepsCaption() throws {
  let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
  try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
  defer { try? FileManager.default.removeItem(at: directory) }
  let data = UIGraphicsImageRenderer(size: CGSize(width: 20, height: 20)).pngData { UIColor.systemBlue.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 20, height: 20)) }
  let url = directory.appendingPathComponent("image.png"); try data.write(to: url)
  let result = try Mango9LiveAttachmentPayload.prepare(Attachment(id: "fixture", name: "image.png", url: url, type: .image), text: "Image caption", allowedMIMEs: ["image/png"])
  XCTAssertEqual(result.file, data); XCTAssertEqual(result.text, "Image caption")
 }

}
