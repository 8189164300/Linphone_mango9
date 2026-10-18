import XCTest
import ImageIO
import UIKit
@testable import LinphoneApp

final class Mango9ReactionTests: XCTestCase {
	@MainActor func testConversationDeletionAcrossDevicesAndLostResponseRetryPreservesNewArrivals() async throws {
		let active = session(), prior = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		let suite = "conversation-visibility-" + UUID().uuidString, secondSuite = "conversation-second-" + UUID().uuidString
		let defaults = UserDefaults(suiteName: suite)!, secondDefaults = UserDefaults(suiteName: secondSuite)!
		defer {
			defaults.removePersistentDomain(forName: suite); secondDefaults.removePersistentDomain(forName: secondSuite)
			Mango9SessionStore.remove(for: active.sipIdentity!); if let prior { try? Mango9SessionStore.save(prior, makeActive: true) }
		}
		for channel in [Mango9PersonalMessageVisibility.Channel.sms, .team, .social] {
			let id = channel == .sms ? "15555550101" : "thread"
			var cleared = false, newerMessage = false, loseResponse = true
			var receipts: [String: [String: Any]] = [:], requests: [String] = []
			ReactionMockProtocol.handler = { request in
				if request.url!.path.hasSuffix("bootstrap") { return (200, Data(#"{"success":true,"message":"","data":{"websocketUrl":"wss://messages.example.invalid","token":"fixture","expiresIn":900,"userId":42,"transport":"websocket"}}"#.utf8)) }
				XCTAssertTrue(request.url!.path.contains(channel == .social ? "inbox-conversation-visibility" : "conversation-visibility/" + channel.rawValue))
				let body = try ReactionMockProtocol.body(request)
				XCTAssertEqual(body["conversationIds"] as? [String], [id])
				var receipt: [String: Any] = ["scope": "user", "channel": channel.rawValue, "state": "succeeded", "conversationIds": [id],
					"clearedConversationIds": cleared ? [id] : [], "hiddenConversationIds": cleared && !newerMessage ? [id] : []]
				if body["hide"] as? Bool == true {
					let key = try XCTUnwrap(body["requestId"] as? String); requests.append(key)
					if let saved = receipts[key] { return Self.envelope(saved) }
					cleared = true; newerMessage = false
					receipt["requestId"] = key; receipt["clearedConversationIds"] = [id]; receipt["hiddenConversationIds"] = [id]; receipts[key] = receipt
					if loseResponse { loseResponse = false; throw URLError(.networkConnectionLost) }
				}
				return Self.envelope(receipt)
			}
			let first = Mango9PersonalConversationVisibility(api: mockAPI(), defaults: defaults)
			do { try await first.delete(session: active, channel: channel, id: id); XCTFail("Lost receipt") } catch {}
			XCTAssertFalse(first.isHidden(id, channel: channel), "No optimistic hide")
			let otherDevice = Mango9PersonalConversationVisibility(api: mockAPI(), defaults: secondDefaults)
			try await otherDevice.refresh(session: active, channel: channel, ids: [id])
			XCTAssertTrue(otherDevice.isHidden(id, channel: channel))
			newerMessage = true
			let restarted = Mango9PersonalConversationVisibility(api: mockAPI(), defaults: defaults)
			try await restarted.delete(session: active, channel: channel, id: id)
			XCTAssertEqual(requests.count, 2); XCTAssertEqual(requests[0], requests[1]); XCTAssertEqual(receipts.count, 1)
			XCTAssertFalse(restarted.isHidden(id, channel: channel)); XCTAssertTrue(restarted.wasCleared(id, channel: channel))
			try await otherDevice.refresh(session: active, channel: channel, ids: [id])
			XCTAssertFalse(otherDevice.isHidden(id, channel: channel), "New arrival restores conversation")
			try await restarted.delete(session: active, channel: channel, id: id)
			XCTAssertNotEqual(requests.last, requests.first); XCTAssertTrue(restarted.isHidden(id, channel: channel))
		}
	}
	@MainActor func testConversationDeletionRejectsWrongReceiptsAndAccountScope() async throws {
		let active = session(), prior = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		let suite = "conversation-invalid-" + UUID().uuidString, defaults = UserDefaults(suiteName: suite)!
		defer { defaults.removePersistentDomain(forName: suite); Mango9SessionStore.remove(for: active.sipIdentity!); if let prior { try? Mango9SessionStore.save(prior, makeActive: true) } }
		let store = Mango9PersonalConversationVisibility(api: mockAPI(), defaults: defaults)
		for failure in ["scope", "channel", "state", "requestId", "conversationIds", "hiddenConversationIds"] {
			ReactionMockProtocol.handler = { request in
				let body = try ReactionMockProtocol.body(request)
				var receipt: [String: Any] = ["scope": "user", "channel": "social", "state": "succeeded", "requestId": body["requestId"]!,
					"conversationIds": ["thread"], "clearedConversationIds": ["thread"], "hiddenConversationIds": ["thread"]]
				receipt[failure] = failure.hasSuffix("Ids") ? ["another"] : "wrong"
				return Self.envelope(receipt)
			}
			do { try await store.delete(session: active, channel: .social, id: "thread"); XCTFail(failure) } catch {}
			XCTAssertFalse(store.isHidden("thread", channel: .social))
		}
		let other = session(user: "43"); try Mango9SessionStore.save(other, persist: false, makeActive: true)
		ReactionMockProtocol.handler = { _ in XCTFail("Cross-account request"); throw URLError(.badURL) }
		do { try await store.delete(session: active, channel: .social, id: "thread"); XCTFail("Scope changed") } catch {}
		XCTAssertFalse(store.isHidden("thread", channel: .social)); Mango9SessionStore.remove(for: other.sipIdentity!)
	}
	@MainActor func testConversationVisibilityRefreshBatchesLargeDirectories() async throws {
		let active = session(), prior = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: active.sipIdentity!); if let prior { try? Mango9SessionStore.save(prior, makeActive: true) } }
		var calls = 0
		ReactionMockProtocol.handler = { request in
			let body = try ReactionMockProtocol.body(request), ids = try XCTUnwrap(body["conversationIds"] as? [String]); calls += 1
			XCTAssertLessThanOrEqual(ids.count, 200); XCTAssertEqual(body["hide"] as? Bool, false); XCTAssertNil(body["requestId"])
			return Self.envelope(["scope": "user", "channel": "social", "state": "succeeded", "conversationIds": ids, "clearedConversationIds": [], "hiddenConversationIds": []])
		}
		try await Mango9PersonalConversationVisibility(api: mockAPI()).refresh(session: active, channel: .social, ids: (0..<451).map { "thread-\($0)" })
		XCTAssertEqual(calls, 3)
	}
	@MainActor func testSocialThumbnailDecodesBoundedImageAndRejectsInvalidContent() async throws {
		let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
		defer { try? FileManager.default.removeItem(at: url) }
		let original = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 800)).image { context in
			UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: 1200, height: 800))
		}
		try XCTUnwrap(original.pngData()).write(to: url)
		let image = try await Mango9SocialThumbnailCache.decode(url, video: false)
		XCTAssertLessThanOrEqual(image.size.width, 512)
		XCTAssertLessThanOrEqual(image.size.height, 512)
		XCTAssertEqual(image.size.width / image.size.height, 1.5, accuracy: 0.01)
		try Data("not an image".utf8).write(to: url)
		do { _ = try await Mango9SocialThumbnailCache.decode(url, video: false); XCTFail("Malformed content must not be shown") } catch {}
	}
	@MainActor func testPopoverAnchorStaysInsideVisibleScrolledBubble() {
		let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
		let container = UIView(frame: CGRect(x: 0, y: 80, width: 320, height: 300)); container.clipsToBounds = true
		let bubble = UIView(frame: CGRect(x: 16, y: -200, width: 240, height: 800))
		window.addSubview(container); container.addSubview(bubble); window.layoutIfNeeded()
		let anchor = bubble.convert(Mango9PopoverAnchor.visibleBounds(of: bubble, in: window), to: window)
		XCTAssertFalse(anchor.isEmpty)
		XCTAssertEqual(anchor.minX, 16)
		XCTAssertGreaterThanOrEqual(anchor.minY, 80)
		XCTAssertLessThanOrEqual(anchor.maxY, 380)
	}
	@MainActor func testSocialListMuteUsesExactThreadAndServerConfirmedState() async throws {
		let active = session(), prior = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: active.sipIdentity!); if let prior { try? Mango9SessionStore.save(prior, makeActive: true) } }
		var muted = false
		ReactionMockProtocol.handler = { request in
			if request.url!.lastPathComponent == "inbox-notification-set" {
				let body = try ReactionMockProtocol.body(request); XCTAssertEqual(body["id"] as? String, "thread")
				muted = try XCTUnwrap(body["muted"] as? Bool)
			} else { XCTAssertEqual(request.url!.lastPathComponent, "inbox-notification-get") }
			return Self.envelope(["id": "thread", "muted": muted])
		}
		let api = mockAPI()
		let initial = try await api.socialNotificationPreference(session: active, thread: "thread")
		XCTAssertFalse(initial)
		let changed = try await api.socialNotificationPreference(session: active, thread: "thread", muted: true)
		XCTAssertTrue(changed)
		let reread = try await api.socialNotificationPreference(session: active, thread: "thread")
		XCTAssertTrue(reread)
		ReactionMockProtocol.handler = { _ in Self.envelope(["id": "wrong-thread", "muted": false]) }
		do { _ = try await api.socialNotificationPreference(session: active, thread: "thread", muted: false); XCTFail("Mismatched receipt") } catch {}
	}
	@MainActor func testChatInboxDatesMatchSMSAndUseLocalDay() throws {
		let zone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-10-17T08:00:00Z"))
		XCTAssertEqual(Mango9ChatDateText.display("2026-10-17T07:05:00.123Z", now: now, timeZone: zone), "12:05AM")
		// UTC's 17th is still the 16th on this device; do not label it as today.
		XCTAssertEqual(Mango9ChatDateText.display("2026-10-17T06:59:00Z", now: now, timeZone: zone), "10-16-2026")
		XCTAssertEqual(Mango9ChatDateText.display("2025-10-17T08:00:00Z", now: now, timeZone: zone), "10-17-2025")
	}
	@MainActor func testChatMessageDatesRetainDateAndTimeWithOffsetsAndDST() throws {
		let zone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
		XCTAssertEqual(Mango9ChatDateText.display("2026-10-17T10:05:00+03:00", inConversation: true, timeZone: zone), "10-17-2026 12:05AM")
		XCTAssertEqual(Mango9ChatDateText.display("2026-03-08T09:30:00Z", inConversation: true, timeZone: zone), "03-08-2026 1:30AM")
		XCTAssertEqual(Mango9ChatDateText.display("2026-03-08T10:30:00Z", inConversation: true, timeZone: zone), "03-08-2026 3:30AM")
	}
	@MainActor func testChatDatesNeverInventActivityForMissingOrMalformedValues() {
		for value in [nil, "", "not a date"] as [String?] {
			XCTAssertEqual(Mango9ChatDateText.display(value), "")
			XCTAssertEqual(Mango9ChatDateText.display(value, inConversation: true), "")
		}
	}
	@MainActor func testSocialThreadDecodesActivityDateAndAllBrandAssetsAreBundled() throws {
		var json = Self.socialThreadJSON; json["activityAt"] = "2026-10-17T12:34:00Z"
		let thread = try JSONDecoder().decode(Mango9SocialThread.self, from: JSONSerialization.data(withJSONObject: json))
		XCTAssertEqual(thread.activityAt, "2026-10-17T12:34:00Z")
		for platform in Mango9SocialPlatform.all { XCTAssertNotNil(UIImage(named: "social-" + platform), platform) }
	}

	@MainActor func testPersonalDeletionSyncsAcrossDevicesWithoutProviderDeleteOrSend() async throws {
		let active = session(); let prior = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		let suite = "visibility-tests-" + UUID().uuidString
		let defaults = UserDefaults(suiteName: suite)!
		defer {
			defaults.removePersistentDomain(forName: suite); Mango9SessionStore.remove(for: active.sipIdentity!)
			if let prior { try? Mango9SessionStore.save(prior, makeActive: true) }
		}
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ReactionMockProtocol.self]
		let api = Mango9MessagingAPI(transport: URLSession(configuration: config))
		var saved: [String: Set<String>] = [:]
		ReactionMockProtocol.handler = { request in
			if request.url!.path.hasSuffix("bootstrap") { return (200, Data(#"{"success":true,"message":"","data":{"websocketUrl":"wss://messages.example.invalid","token":"fixture","expiresIn":900,"userId":42,"transport":"websocket"}}"#.utf8)) }
			let social = request.url!.lastPathComponent == "inbox-message-visibility"
			let channel = social ? "social" : request.url!.lastPathComponent
			XCTAssertTrue(["sms", "team", "social"].contains(channel))
			let body = try ReactionMockProtocol.body(request)
			let ids = social ? (body["messageIds"] as! [Int]).map(String.init) : body["messageIds"] as! [String]
			if social { XCTAssertEqual(body["id"] as? String, "thread") }
			if body["hide"] as? Bool == true { saved[channel, default: []].formUnion(ids) }
			return Self.envelope(["scope": "user", "channel": channel, "state": "succeeded", "hiddenIds": Array(saved[channel, default: []].intersection(ids))])
		}
		for channel in [Mango9PersonalMessageVisibility.Channel.sms, .team, .social] {
			let first = Mango9PersonalMessageVisibility(api: api, defaults: defaults)
			let thread: String? = channel == .social ? "thread" : nil
			try await first.update(session: active, channel: channel, thread: thread, ids: ["1", "2"], hide: true)
			XCTAssertTrue(first.isHidden("1", channel: channel, thread: thread))
			// A fresh device/cache gets the saved server markers, not an optimistic local hide.
			defaults.removePersistentDomain(forName: suite)
			let second = Mango9PersonalMessageVisibility(api: api, defaults: defaults)
			XCTAssertFalse(second.isHidden("1", channel: channel, thread: thread))
			try await second.update(session: active, channel: channel, thread: thread, ids: ["1", "2", "3"], hide: false)
			XCTAssertTrue(second.isHidden("1", channel: channel, thread: thread))
			XCTAssertFalse(second.isHidden("3", channel: channel, thread: thread))
			if channel == .social { XCTAssertFalse(second.isHidden("1", channel: channel, thread: "other")) }
		}
	}
	@MainActor func testPersonalDeletionFailureAndMalformedReceiptNeverHideLocally() async throws {
		let active = session(); let prior = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		let suite = "visibility-tests-" + UUID().uuidString; let defaults = UserDefaults(suiteName: suite)!
		defer { defaults.removePersistentDomain(forName: suite); Mango9SessionStore.remove(for: active.sipIdentity!); if let prior { try? Mango9SessionStore.save(prior, makeActive: true) } }
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ReactionMockProtocol.self]
		let visibility = Mango9PersonalMessageVisibility(api: Mango9MessagingAPI(transport: URLSession(configuration: config)), defaults: defaults)
		for failure in ["offline", "denied", "unknown", "wrong-scope", "wrong-target"] {
			ReactionMockProtocol.handler = { _ in
				if failure == "offline" { throw URLError(.notConnectedToInternet) }
				if failure == "denied" { return (403, Data("{}".utf8)) }
				return Self.envelope(["scope": failure == "wrong-scope" ? "workspace" : "user", "channel": "social",
					"state": failure == "unknown" ? "unknown" : "succeeded", "hiddenIds": failure == "wrong-target" ? ["2"] : ["1"]])
			}
			do { try await visibility.update(session: active, channel: .social, thread: "thread", ids: ["1"], hide: true); XCTFail(failure) }
			catch { XCTAssertFalse(visibility.isHidden("1", channel: .social, thread: "thread")) }
		}
		let other = session(user: "43"); try Mango9SessionStore.save(other, persist: false, makeActive: true)
		ReactionMockProtocol.handler = { _ in XCTFail("No cross-account request"); throw URLError(.badURL) }
		do { try await visibility.update(session: active, channel: .social, thread: "thread", ids: ["1"], hide: true); XCTFail("Scope changed") } catch {}
		Mango9SessionStore.remove(for: other.sipIdentity!)
	}
	func testNativeEmojiInputPreservesOneCompleteGrapheme() {
		for emoji in ["👍", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇺🇸", "🥳", "1️⃣"] {
			XCTAssertTrue(Mango9EmojiInput.isSingleEmoji(emoji), emoji)
		}
		for value in ["", "hello", "1", "#", "a", "👍👍", "❤️ text", "\u{FFFC}"] {
			XCTAssertFalse(Mango9EmojiInput.isSingleEmoji(value), value)
		}
	}
	@MainActor func testHEICPreparationPreservesOriginalAndProducesBoundedJPEG() async throws {
		let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".heic")
		defer { try? FileManager.default.removeItem(at: source) }
		let image = UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16)).image { context in
			UIColor.blue.setFill(); context.fill(CGRect(x: 0, y: 0, width: 16, height: 16))
		}.cgImage!
		guard let destination = CGImageDestinationCreateWithURL(source as CFURL, "public.heic" as CFString, 1, nil) else {
			throw XCTSkip("HEIC encoder unavailable on this test runtime")
		}
		CGImageDestinationAddImage(destination, image, nil)
		guard CGImageDestinationFinalize(destination) else { throw XCTSkip("HEIC encoder unavailable on this test runtime") }
		let original = try Data(contentsOf: source)
		let prepared = try await Mango9SocialAttachmentPolicy.providerAttachment(Attachment(id: "photo", name: "Photo.heic", url: source, type: .image))
		defer { try? FileManager.default.removeItem(at: prepared.full) }
		XCTAssertEqual(try Data(contentsOf: source), original)
		XCTAssertEqual(prepared.id, "photo"); XCTAssertEqual(prepared.name, "Photo.jpg")
		let decoded = try XCTUnwrap(CGImageSourceCreateWithURL(prepared.full as CFURL, nil))
		XCTAssertEqual(CGImageSourceGetType(decoded) as String?, "public.jpeg")
		let output = try XCTUnwrap(CGImageSourceCreateImageAtIndex(decoded, 0, nil))
		XCTAssertLessThanOrEqual(max(output.width, output.height), 2048)
	}
	func testCorruptHEICIsRejectedWithoutChangingOriginal() async throws {
		let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".heic")
		let original = Data([1, 2, 3]); try original.write(to: source)
		defer { try? FileManager.default.removeItem(at: source) }
		do {
			_ = try await Mango9SocialAttachmentPolicy.providerAttachment(Attachment(id: "photo", name: "Photo.heic", url: source, type: .image))
			XCTFail("Corrupt photo should not reach upload")
		} catch { XCTAssertEqual(try Data(contentsOf: source), original) }
	}
	func testAttachmentPolicyMatchesProviderLimits() throws {
		XCTAssertEqual(try Mango9SocialAttachmentPolicy.mime(extension: "JPG", bytes: 25 * 1048576, platform: "instagram"), "image/jpeg")
		XCTAssertEqual(try Mango9SocialAttachmentPolicy.mime(extension: "mp4", bytes: 100, platform: "facebook"), "video/mp4")
		for ext in ["pdf", "gif", "mov", "heic", "txt"] { XCTAssertThrowsError(try Mango9SocialAttachmentPolicy.mime(extension: ext, bytes: 100, platform: "instagram")) }
		for bytes in [0, -1, 25 * 1048576 + 1] { XCTAssertThrowsError(try Mango9SocialAttachmentPolicy.mime(extension: "png", bytes: bytes, platform: "facebook")) }
		XCTAssertThrowsError(try Mango9SocialAttachmentPolicy.mime(extension: "mp4", bytes: 1, platform: "tiktok"))
		XCTAssertThrowsError(try Mango9SocialAttachmentPolicy.mime(extension: "png", bytes: 3 * 1048576 + 1, platform: "tiktok"))
	}
	func testReplyPreservesGraphemesAndBoundsQuotedText() {
		let emoji = "👨‍👩‍👧‍👦"
		let quote = String(repeating: emoji, count: 600)
		XCTAssertEqual(Mango9MessageText.reply("👍🏽", to: quote), "> " + String(repeating: emoji, count: 500) + "\n\n👍🏽")
		XCTAssertEqual(Mango9MessageText.reply("answer", to: "first\nsecond"), "> first\n> second\n\nanswer")
		XCTAssertEqual(Mango9MessageText.reply("normal", to: nil), "normal")
	}
	@MainActor func testForwardPayloadCannotCrossAccount() throws {
		let active = session(); let previous = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		let payload = Mango9ForwardPayload(text: "Private draft")
		XCTAssertTrue(payload.isCurrent)
		let other = session(user: "43")
		try Mango9SessionStore.save(other, persist: false, makeActive: true)
		XCTAssertFalse(payload.isCurrent)
		Mango9SessionStore.remove(for: active.sipIdentity!); Mango9SessionStore.remove(for: other.sipIdentity!)
		if let previous { try Mango9SessionStore.save(previous, makeActive: true) }
	}
	@MainActor func testSocialAttachmentFailureNeverInvokesSendOrCreatesUnknownIntent() async throws {
		try await withSocialStore { store, journal in
			var sends = 0
			ReactionMockProtocol.handler = { request in
				if request.url!.lastPathComponent == "media-prepare" { return (503, Data("{}".utf8)) }
				if request.url!.lastPathComponent == "inbox-send" { sends += 1 }
				return Self.socialRead(request)
			}
			let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
			try Data([1, 2, 3]).write(to: url); defer { try? FileManager.default.removeItem(at: url) }
			await store.loadThread(id: "thread"); store.draft = "Photo"
			store.selectedMedia = [Attachment(id: "photo", name: "Photo.png", url: url, type: .image)]
			await store.send()
			XCTAssertEqual(sends, 0); XCTAssertFalse(store.unresolvedSend)
			XCTAssertNil(journal.pending(account: Mango9ReactionTarget.accountKey(store.session), thread: "thread"))
			XCTAssertEqual(store.selectedMedia.count, 1); XCTAssertEqual(store.draft, "Photo")
		}
	}
	@MainActor func testSocialMediaUploadUsesSignedPutWithoutBearerAndSendsReadyID() async throws {
		try await withSocialStore { store, _ in
			var phases: [String] = []; var assetID = ""
			ReactionMockProtocol.handler = { request in
				let path = request.url!.lastPathComponent
				switch path {
				case "media-prepare":
					phases.append(path); let body = try ReactionMockProtocol.body(request); assetID = body["id"] as! String
					XCTAssertEqual(body["mime"] as? String, "image/png"); XCTAssertEqual(body["size"] as? Int, 3)
					return Self.envelope(["asset": ["id": assetID, "state": "pending"], "upload": ["url": "https://storage.example.invalid/upload", "method": "PUT", "headers": ["Content-Type": "image/png"]]])
				case "upload":
					phases.append(path); XCTAssertEqual(request.httpMethod, "PUT")
					XCTAssertNil(request.value(forHTTPHeaderField: "Authorization")); XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
					XCTAssertFalse(request.httpShouldHandleCookies); return (204, Data())
				case "media-complete": phases.append(path); return Self.envelope(["id": assetID, "state": "ready"])
				case "inbox-send":
					phases.append(path); let body = try ReactionMockProtocol.body(request)
					XCTAssertEqual(body["mediaId"] as? String, assetID)
					return Self.envelope(["id": "thread", "sendId": "send", "requestId": body["requestId"]!, "state": "sent", "message": "Photo"])
				default: return Self.socialRead(request)
				}
			}
			let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
			try Data([1, 2, 3]).write(to: url); defer { try? FileManager.default.removeItem(at: url) }
			await store.loadThread(id: "thread"); store.draft = "Photo"
			store.selectedMedia = [Attachment(id: "photo", name: "Photo.png", url: url, type: .image)]
			await store.send()
			XCTAssertEqual(phases, ["media-prepare", "upload", "media-complete", "inbox-send"])
			XCTAssertTrue(store.selectedMedia.isEmpty); XCTAssertEqual(store.draft, "")
		}
	}
	private func session(host: String = "messages.example.invalid", user: String = "42") -> Mango9Session {
		Mango9Session(crmId: "fixture", crmBaseUrl: "https://\(host)", crmApiBaseUrl: "https://\(host)/app/api/v2",
			userId: user, parentClientId: "1", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture",
			accessToken: "fixture-access", refreshToken: "fixture-refresh", smsChatApi: "https://\(host)/sms_chat_api",
			connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:\(user)@\(host)")
	}
	private func target(_ channel: Mango9ReactionChannel = .sms, account: String = "fixture") -> Mango9ReactionTarget {
		.init(account: account, channel: channel, conversation: "conversation", message: "message")
	}
	func testSocialRemovedTombstoneIsNotActiveAndStaffShapeIsNotCoerced() throws {
		let state = try JSONDecoder().decode(Mango9SocialReactions.self, from: Data(#"{"self":{"emoji":"❤️","action":"removed","fromMe":true,"at":"2026-01-01T00:00:00Z"},"participant":{"emoji":"👍🏽","action":"added","fromMe":false,"at":"2026-01-01T00:00:00Z"}}"#.utf8))
		XCTAssertNil(state.activeAccount)
		XCTAssertEqual(state.activeParticipant?.emoji, "👍🏽")
		let staff = Mango9StaffReactions.history(["reactions": ["self": ["emoji": "❤️", "action": "added", "fromMe": true]]])
		XCTAssertTrue(staff.reactions.isEmpty)
		XCTAssertNil(try JSONDecoder().decode(Mango9SocialReactions.self, from: Data("[]".utf8)).activeAccount)
	}
	func testSocialSelfMustBelongToCurrentAccount() throws {
		let data = Data(#"{"self":{"emoji":"❤️","action":"added","fromMe":false,"at":"2026-01-01"},"participant":{"emoji":"❤️","action":"removed","fromMe":false,"at":"2026-01-02"}}"#.utf8)
		let state = try JSONDecoder().decode(Mango9SocialReactions.self, from: data)
		XCTAssertNil(state.activeAccount); XCTAssertNil(state.activeParticipant)
	}
	func testStaffHistoryDeduplicatesNumericActorsAndDoesNotUseNumericStrings() {
		let result = Mango9StaffReactions.history(["reactions": ["👍": [42, 7, 42, -1], "❤️": ["42"], "bad": NSNull()], "reactionVersion": 8, "reactionCapabilities": NSNull()])
		XCTAssertEqual(result.reactions, ["👍": [7, 42]])
		XCTAssertEqual(result.version, 8)
	}
	func testOlderResponsesCannotRestoreRemovedReaction() {
		let added = Mango9StaffReactions(reactions: ["❤️": [42]], version: 1)
		let removed = Mango9StaffReactions(reactions: [:], version: 2)
		XCTAssertEqual(removed.merging(added), removed)
		XCTAssertEqual(added.merging(removed), removed)
	}
	func testCapabilitySeparatesTeamSMSAndProviderVisibility() {
		let sms = Mango9ReactionCapabilities(supported: true, scope: "workspace", recipientVisible: false, textFallback: false, providerReactions: false)
		XCTAssertTrue(sms.permits(.sms)); XCTAssertFalse(sms.permits(.team)); XCTAssertFalse(sms.permits(.social))
		let dm = Mango9ReactionCapabilities(supported: true, scope: "provider", recipientVisible: true, textFallback: false, canAdd: true, canRemove: false)
		XCTAssertTrue(dm.permits(.social)); XCTAssertFalse(dm.permits(.social, removing: true)); XCTAssertFalse(dm.permits(.sms))
		var unsupported = dm; unsupported.supported = false
		XCTAssertFalse(unsupported.permits(.social))
	}
	func testUnsupportedDisconnectedDeletedOrUnavailableSocialMessagesCannotReact() throws {
		let caps: [String: Any] = ["supported": true, "scope": "provider", "recipientVisible": true, "canAdd": true, "canRemove": true]
		var thread: [String: Any] = ["id": "thread", "sourceId": "source", "version": 1, "platform": "instagram", "unread": false, "connectionCurrent": true, "reactionCapabilities": caps]
		var message: [String: Any] = ["id": 1, "text": "Hello", "direction": "incoming", "deleted": false, "reactionCapabilities": caps]
		func allowed() throws -> Bool {
			let t = try JSONDecoder().decode(Mango9SocialThread.self, from: JSONSerialization.data(withJSONObject: thread))
			let m = try JSONDecoder().decode(Mango9SocialMessage.self, from: JSONSerialization.data(withJSONObject: message))
			return t.permitsReaction(on: m)
		}
		XCTAssertTrue(try allowed())
		thread["platform"] = "tiktok"; XCTAssertFalse(try allowed())
		thread["platform"] = "instagram"; thread["connectionCurrent"] = false; XCTAssertFalse(try allowed())
		thread["connectionCurrent"] = true; message["deleted"] = true; XCTAssertFalse(try allowed())
		message["deleted"] = false; message["unavailable"] = true; XCTAssertFalse(try allowed())
		message["unavailable"] = false; thread["reactionCapabilities"] = NSNull(); XCTAssertFalse(try allowed())
	}
	func testUnknownReceiptStatesStayUnknown() throws {
		for text in ["unknown", "checking", "sending", "unexpected"] {
			XCTAssertEqual(try JSONDecoder().decode(Mango9ReactionOutcome.self, from: Data("\"\(text)\"".utf8)), .unknown)
		}
	}
	func testSocialReactionMenuKeepsRemovalWhenAddingIsUnavailable() throws {
		func menu(add: Bool, remove: Bool, action: String = "added", platform: String = "instagram", connected: Bool = true, deleted: Bool = false) throws -> Mango9MessageActionSet {
			let caps: [String: Any] = ["supported": true, "scope": "provider", "recipientVisible": true, "canAdd": add, "canRemove": remove]
			let thread: [String: Any] = ["id": "thread", "sourceId": "source", "version": 1, "platform": platform, "unread": false, "connectionCurrent": connected, "reactionCapabilities": caps]
			let message: [String: Any] = ["id": 1, "text": "Hello", "direction": "incoming", "deleted": deleted, "reactionCapabilities": caps,
				"reactions": ["self": ["emoji": "🦋", "action": action, "fromMe": true, "at": "2026-01-01T00:00:00Z"]]]
			return .social(thread: try JSONDecoder().decode(Mango9SocialThread.self, from: JSONSerialization.data(withJSONObject: thread)),
				message: try JSONDecoder().decode(Mango9SocialMessage.self, from: JSONSerialization.data(withJSONObject: message)))
		}
		let removalOnly = try menu(add: false, remove: true)
		XCTAssertEqual(removalOnly.reactions, ["🦋"])
		XCTAssertEqual(removalOnly.selectedReactions, ["🦋"])
		XCTAssertFalse(removalOnly.allowsMoreReactions)
		let both = try menu(add: true, remove: true)
		XCTAssertTrue(both.reactions.contains("🦋")); XCTAssertTrue(both.allowsMoreReactions)
		XCTAssertFalse(try menu(add: true, remove: false).reactions.contains("🦋"))
		XCTAssertTrue(try menu(add: false, remove: true, action: "removed").reactions.isEmpty)
		XCTAssertTrue(try menu(add: false, remove: false).reactions.isEmpty)
		XCTAssertTrue(try menu(add: true, remove: true, platform: "tiktok").reactions.isEmpty)
		XCTAssertTrue(try menu(add: true, remove: true, connected: false).reactions.isEmpty)
		XCTAssertTrue(try menu(add: true, remove: true, deleted: true).reactions.isEmpty)
	}
	func testDeniedReceiptLookupDoesNotEraseUnknownProviderOutcome() {
		let denied = Mango9MessagingError.rejected(403, "posting_session_required")
		XCTAssertEqual(Mango9ReactionOutcome.transportFailure(denied, previouslyUnconfirmed: true), .unknown)
		XCTAssertEqual(Mango9ReactionOutcome.transportFailure(denied, previouslyUnconfirmed: false), .failed)
		for error: Error in [URLError(.notConnectedToInternet), Mango9MessagingError.rejected(429, "rate_limited"), Mango9MessagingError.rejected(503, "unavailable")] {
			XCTAssertEqual(Mango9ReactionOutcome.transportFailure(error, previouslyUnconfirmed: false), .unknown)
		}
	}
	func testEmojiRoundTripsAndSocialUnreactOmitsEmoji() throws {
		for emoji in ["👍", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇺🇸"] {
			let command = Mango9ReactionCommand(target: target(), version: 4, emoji: emoji, remove: false)
			let json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: command.body)) as? [String: Any])
			XCTAssertEqual(json["emoji"] as? String, emoji)
			XCTAssertEqual(json["scope"] as? String, "workspace")
			XCTAssertNil(json["userId"]); XCTAssertNil(json["ownerId"])
			XCTAssertEqual(try JSONDecoder().decode(Mango9ReactionCommand.self, from: JSONEncoder().encode(command)), command)
		}
		let unreact = Mango9ReactionCommand(target: target(.social), version: 3, emoji: "", remove: true)
		XCTAssertNil(unreact.body["emoji"]); XCTAssertEqual(unreact.body["action"] as? String, "unreact")
	}
	@MainActor func testUnknownJournalSurvivesRestartAndOnlyAllowsExactRetry() throws {
		let name = "reactions-test-" + UUID().uuidString; let defaults = UserDefaults(suiteName: name)!
		defer { defaults.removePersistentDomain(forName: name) }
		let command = Mango9ReactionCommand(target: target(), version: 0, emoji: "👍", remove: false)
		let journal = Mango9ReactionJournal(defaults: defaults)
		try journal.begin(command)
		XCTAssertThrowsError(try journal.begin(command)) // Rapid duplicate tap while in flight.
		journal.finish(command, outcome: .unknown)
		let reopened = Mango9ReactionJournal(defaults: defaults)
		XCTAssertEqual(reopened.pending(command.target), command)
		XCTAssertThrowsError(try reopened.begin(.init(target: command.target, version: 0, emoji: "👍", remove: false)))
		try reopened.begin(command)
		reopened.finish(command, outcome: .succeeded)
		XCTAssertNil(reopened.pending(command.target))
	}
	@MainActor func testFailedVersionCheckCanCreateNewConfirmedIntent() throws {
		let name = "reactions-test-" + UUID().uuidString; let defaults = UserDefaults(suiteName: name)!
		defer { defaults.removePersistentDomain(forName: name) }
		let journal = Mango9ReactionJournal(defaults: defaults)
		let old = Mango9ReactionCommand(target: target(), version: 1, emoji: "❤️", remove: false)
		try journal.begin(old); journal.finish(old, outcome: .failed)
		let confirmed = Mango9ReactionCommand(target: old.target, version: 2, emoji: "❤️", remove: false)
		XCTAssertNotEqual(confirmed.requestId, old.requestId); try journal.begin(confirmed)
	}
	func testScopeCannotCrossChannelAccountOrHost() {
		let first = session(), otherHost = session(host: "other.example.invalid"), otherActor = session(user: "43")
		let scoped = target(account: Mango9ReactionTarget.accountKey(first))
		XCTAssertTrue(scoped.matches(first)); XCTAssertFalse(scoped.matches(otherHost)); XCTAssertFalse(scoped.matches(otherActor))
		XCTAssertNotEqual(target(.sms).id, target(.team).id)
		XCTAssertNotEqual(target(.social).id, target(.team).id)
	}
	@MainActor func testSMSHistoryMergeKeepsNewReactionsAndNormalDeliveryStatus() {
		var old = Mango9ServerSMSMessage(id: "test", phone: "15555550100", text: "unchanged", time: "2026-01-01", senderID: "15555550200", status: 2, isIncoming: false, files: "")
		old.reactionState = .init(reactions: ["👍": [42]], version: 1)
		var new = old; new.reactionState = .init(reactions: [:], version: 2); new = new.withStatus(1)
		let result = Mango9ChatStore.mergeSMSMessages(server: [new], live: [old])
		XCTAssertEqual(result.first?.reactionState.version, 2); XCTAssertTrue(result.first?.reactionState.reactions.isEmpty == true)
		XCTAssertEqual(result.first?.status, 2); XCTAssertEqual(result.first?.text, "unchanged")
	}
	@MainActor func testSocial403DoesNotTryCookiesRefreshOrImpersonation() async throws {
		let active = session(); let previous = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: active.sipIdentity!); if let previous { try? Mango9SessionStore.save(previous, makeActive: true) } }
		var calls = 0
		ReactionMockProtocol.handler = { request in
			calls += 1
			XCTAssertEqual(request.url?.path, "/app/ad-manager/api/social-posting/inbox")
			XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-access")
			XCTAssertNil(request.value(forHTTPHeaderField: "Cookie")); XCTAssertFalse(request.httpShouldHandleCookies)
			return (403, Data(#"{"success":false,"error":{"code":"posting_session_required"}}"#.utf8))
		}
		let api = mockAPI()
		do {
			let _: Mango9SocialPage<Mango9SocialThread, String> = try await api.request(Mango9SocialPage<Mango9SocialThread, String>.self,
				session: active, social: true, path: ["inbox"], query: [.init(name: "kind", value: "dms")])
			XCTFail("Access must be denied")
		} catch { XCTAssertTrue(error.localizedDescription.contains("supervisor")) }
		XCTAssertEqual(calls, 1)
	}
	@MainActor func testExpiredCRMTokenRefreshesAndRetriesIdenticalSocialCommand() async throws {
		let active = session(); let previous = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: active.sipIdentity!); if let previous { try? Mango9SessionStore.save(previous, makeActive: true) } }
		let target = Mango9ReactionTarget(account: Mango9ReactionTarget.accountKey(active), channel: .social, conversation: "thread", message: "1")
		let command = Mango9ReactionCommand(target: target, version: 3, emoji: "👍🏽", remove: false)
		var bodies: [[String: Any]] = []
		ReactionMockProtocol.handler = { request in
			if request.url?.path.hasSuffix("auth/refresh") == true {
				return (200, Data(#"{"success":true,"message":"","data":{"tokens":{"accessToken":"new-access","refreshToken":"new-refresh"}}}"#.utf8))
			}
			bodies.append(try ReactionMockProtocol.body(request))
			if bodies.count == 1 { return (401, Data("{}".utf8)) }
			XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer new-access")
			return (200, Data(#"{"success":true,"data":{"id":"thread","messageId":1,"action":"react","state":"unknown"}}"#.utf8))
		}
		let result = try await mockAPI().request(Mango9SocialReactionReceipt.self, session: active, social: true, path: ["inbox-moderate"], body: command.body)
		XCTAssertEqual(result.state, .unknown); XCTAssertEqual(bodies.count, 2)
		XCTAssertEqual(bodies[0]["requestId"] as? String, bodies[1]["requestId"] as? String)
		XCTAssertEqual(bodies[0]["emoji"] as? String, "👍🏽")
		XCTAssertEqual(bodies[0]["version"] as? Int, bodies[1]["version"] as? Int)
	}
	@MainActor private func mockAPI() -> Mango9MessagingAPI {
		let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ReactionMockProtocol.self]
		return Mango9MessagingAPI(transport: URLSession(configuration: config))
	}
	@MainActor func testExpiredChatTokenRefreshUsesChatActorAndNeverSMSSend() async throws {
		let active = session(); let previous = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: active.sipIdentity!); if let previous { try? Mango9SessionStore.save(previous, makeActive: true) } }
		var commands: [[String: Any]] = []; var bootstraps = 0; var refreshes = 0
		ReactionMockProtocol.handler = { request in
			switch request.url!.lastPathComponent {
			case "bootstrap":
				bootstraps += 1
				return (200, Data(#"{"success":true,"message":"","data":{"websocketUrl":"wss://messages.example.invalid","token":"chat-only","expiresIn":900,"userId":77,"transport":"websocket"}}"#.utf8))
			case "refresh":
				refreshes += 1
				return (200, Data(#"{"success":true,"message":"","data":{"tokens":{"accessToken":"renewed","refreshToken":"renewed-refresh"}}}"#.utf8))
			case "message":
				XCTAssertEqual(request.url!.path, "/sms_chat_api/reactions/sms/message")
				XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer chat-only")
				commands.append(try ReactionMockProtocol.body(request))
				if commands.count == 1 { return (401, Data("{}".utf8)) }
				return (200, Data(#"{"success":true,"data":{"channel":"sms","messageId":"message","reactions":{"👍":[77]},"version":1,"state":"succeeded","capabilities":{"supported":true,"scope":"workspace","recipientVisible":false,"providerReactions":false}}}"#.utf8))
			default: XCTFail("No SMS send or other RPC is allowed"); throw URLError(.unsupportedURL)
			}
		}
		let api = mockAPI()
		let command = Mango9ReactionCommand(target: target(account: Mango9ReactionTarget.accountKey(active)), version: 0, emoji: "👍", remove: false)
		let result = try await api.request(Mango9StaffReactionReceipt.self, session: active, social: false, path: ["reactions", "sms", "message"], body: command.body)
		XCTAssertEqual(result.state, .succeeded); XCTAssertEqual(api.actorID(for: active), 77)
		XCTAssertEqual(bootstraps, 2); XCTAssertEqual(refreshes, 1); XCTAssertEqual(commands.count, 2)
		XCTAssertEqual(commands[0]["requestId"] as? String, commands[1]["requestId"] as? String)
		XCTAssertEqual(commands[1]["scope"] as? String, "workspace")
	}
	@MainActor func testChangedAccountPreventsRequestBeforeSending() async throws {
		let previous = Mango9SessionStore.load(); let active = session(user: "43")
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: active.sipIdentity!); if let previous { try? Mango9SessionStore.save(previous, makeActive: true) } }
		ReactionMockProtocol.handler = { _ in XCTFail("No request may cross accounts"); throw URLError(.badURL) }
		do {
			let _: Mango9StaffReactionReceipt = try await mockAPI().request(Mango9StaffReactionReceipt.self, session: session(), social: false, path: ["reactions", "sms", "message"])
			XCTFail("Should reject a stale target")
		} catch { guard case Mango9MessagingError.scopeChanged = error else { return XCTFail("Unexpected error") } }
	}
	@MainActor func testSocialProviderFailureStillLoadsSavedHistoryAndRevocationClearsIt() async throws {
		let active = session(); let previous = Mango9SessionStore.load()
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer { Mango9SessionStore.remove(for: active.sipIdentity!); if let previous { try? Mango9SessionStore.save(previous, makeActive: true) } }
		var revoked = false
		ReactionMockProtocol.handler = { request in
			if revoked { return (403, Data(#"{"error":{"code":"posting_session_required"}}"#.utf8)) }
			switch request.url!.lastPathComponent {
			case "inbox-detail": return (200, Data(#"{"success":true,"data":{"id":"thread","version":1,"sourceId":"source","platform":"instagram","name":"Fixture","unread":false,"connectionCurrent":true}}"#.utf8))
			case "inbox-refresh-messages":
				let body = try ReactionMockProtocol.body(request)
				XCTAssertEqual(body["id"] as? String, "source"); XCTAssertNil(body["threadId"])
				return (503, Data("{}".utf8))
			case "inbox-messages": return (200, Data(#"{"success":true,"data":{"items":[{"id":1,"text":"Saved content","direction":"incoming","deleted":false,"reactions":[]}],"next":null}}"#.utf8))
			default: throw URLError(.unsupportedURL)
			}
		}
		let store = Mango9SocialInboxStore(session: active, api: mockAPI())
		await store.loadThread(id: "thread", refreshProvider: true)
		XCTAssertEqual(store.messages.first?.text, "Saved content")
		XCTAssertTrue(store.error?.contains("saved history") == true)
		revoked = true
		await store.loadThread(id: "thread")
		XCTAssertTrue(store.messages.isEmpty); XCTAssertNil(store.thread)
	}
	@MainActor func testSocialNavigationCacheKeepsDraftAndSeparatesAccounts() {
		let cache = Mango9SocialChatCache()
		let first = cache.store(session: session(), thread: "one")
		first.draft = "Unsent draft"
		XCTAssertTrue(first === cache.store(session: session(), thread: "one"))
		XCTAssertEqual(cache.store(session: session(), thread: "one").draft, "Unsent draft")
		XCTAssertEqual(cache.store(session: session(), thread: "two").draft, "")
		XCTAssertFalse(first === cache.store(session: session(user: "43"), thread: "one"))
		cache.clear()
		XCTAssertEqual(cache.store(session: session(), thread: "one").draft, "")
	}
	@MainActor func testSocialInboxTransientFailureDoesNotEraseHistory() async throws {
		try await withSocialStore { store, _ in
			var offline = false
			ReactionMockProtocol.handler = { _ in
				if offline { throw URLError(.notConnectedToInternet) }
				return Self.envelope(["items": [Self.socialThreadJSON], "next": NSNull()])
			}
			await store.loadInbox()
			XCTAssertEqual(store.threads.count, 1)
			offline = true; await store.loadInbox()
			XCTAssertEqual(store.threads.count, 1); XCTAssertNotNil(store.error)
		}
	}
	func testSocialPushRequiresExactConversationAndAccountScope() throws {
		let current = session()
		var payload: [String: Any] = ["event": "social.message", "thread_id": "00000000-0000-4000-8000-000000000001", "crm_id": current.crmId, "sip_identity": current.sipIdentity!]
		let target = try XCTUnwrap(Mango9SocialPushTarget(userInfo: ["mango9": payload]))
		XCTAssertTrue(target.matches(current)); XCTAssertFalse(target.matches(session(host: "other.example.invalid")))
		payload["thread_id"] = "invalid"; XCTAssertNil(Mango9SocialPushTarget(userInfo: payload))
		payload["thread_id"] = target.id; payload["event"] = "sms.received"; XCTAssertNil(Mango9SocialPushTarget(userInfo: payload))
		payload["event"] = "social.message"; payload.removeValue(forKey: "sip_identity"); XCTAssertNil(Mango9SocialPushTarget(userInfo: payload))
	}
	@MainActor func testSocialMuteIsServerAcknowledgedAndScoped() async throws {
		try await withSocialStore { store, _ in
			defer {
				let name = Mango9SocialMutePreferences.key(store.session, "thread")
				UserDefaults.standard.removeObject(forKey: name); UserDefaults.standard.removeObject(forKey: name + ".confirmed")
			}
			var muted = false
			ReactionMockProtocol.handler = { request in
				switch request.url!.lastPathComponent {
				case "inbox-notification-set":
					let body = try ReactionMockProtocol.body(request); XCTAssertNil(body["actorId"]); XCTAssertNil(body["ownerId"])
					muted = body["muted"] as! Bool
					return Self.envelope(["id": "thread", "muted": muted])
				case "inbox-notification-get": return Self.envelope(["id": "thread", "muted": muted])
				default: return Self.socialRead(request)
				}
			}
			await store.loadThread(id: "thread"); XCTAssertEqual(store.muted, false)
			try await store.toggleMute(); XCTAssertEqual(store.muted, true)
			XCTAssertTrue(Mango9SocialMutePreferences.isMuted(session: store.session, thread: "thread"))
			XCTAssertFalse(Mango9SocialMutePreferences.isMuted(session: store.session, thread: "other"))
			try await store.toggleMute(); XCTAssertEqual(store.muted, false)
		}
	}
	func testSocialMuteCacheExpiresSoOtherDevicesCanUnmute() {
		let suite = "social-mute-test-" + UUID().uuidString, now = Date()
		let defaults = UserDefaults(suiteName: suite)!; defer { defaults.removePersistentDomain(forName: suite) }
		let current = session()
		Mango9SocialMutePreferences.save(true, session: current, thread: "thread", defaults: defaults, now: now)
		XCTAssertTrue(Mango9SocialMutePreferences.isMuted(session: current, thread: "thread", defaults: defaults, now: now))
		XCTAssertFalse(Mango9SocialMutePreferences.isMuted(session: current, thread: "thread", defaults: defaults, now: now.addingTimeInterval(31)))
		XCTAssertFalse(Mango9SocialMutePreferences.isMuted(session: session(host: "other.example.invalid"), thread: "thread", defaults: defaults, now: now))
	}
	@MainActor func testSocialMissingNotificationEndpointKeepsAuthorizedHistory() async throws {
		try await withSocialStore { store, _ in
			ReactionMockProtocol.handler = { request in
				if request.url!.lastPathComponent == "inbox-notification-get" { return (404, Data("{}".utf8)) }
				return Self.socialRead(request)
			}
			await store.loadThread(id: "thread")
			XCTAssertEqual(store.thread?.id, "thread"); XCTAssertEqual(store.messages.count, 1)
			XCTAssertNil(store.muted)
		}
	}
	@MainActor func testSocialFiltersAreServerScopedAndClearIncompatibleAccountAndCursor() async throws {
		try await withSocialStore { store, _ in
			let instagram = String(repeating: "a", count: 24), facebook = String(repeating: "b", count: 24)
			var requests: [[String: String]] = []
			ReactionMockProtocol.handler = { request in
				if request.url!.lastPathComponent == "accounts" {
					return Self.envelope(["items": [["id": instagram, "platform": "instagram", "name": "Studio"], ["id": facebook, "platform": "facebook", "name": "Page"]]])
				}
				requests.append(Dictionary(uniqueKeysWithValues: URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!.map { ($0.name, $0.value ?? "") }))
				return Self.envelope(["items": [Self.socialThreadJSON], "next": "cursor"])
			}
			await store.loadAccounts(); XCTAssertEqual(store.accounts.count, 2)
			store.selectPlatform("instagram"); store.selectAccount(instagram)
			await store.loadInbox(search: "  hello  ")
			XCTAssertEqual(requests.last?["platform"], "instagram"); XCTAssertEqual(requests.last?["accountId"], instagram)
			XCTAssertEqual(requests.last?["search"], "hello"); XCTAssertEqual(requests.last?["kind"], "dms")
			await store.loadInbox(search: "hello", more: true)
			XCTAssertEqual(requests.last?["before"], "cursor"); XCTAssertNil(store.nextThread, "Repeated cursors must stop pagination")
			XCTAssertEqual(store.threads.count, 1, "Overlapping pages must not duplicate rows")
			store.selectPlatform("facebook")
			XCTAssertEqual(store.selectedAccount, ""); XCTAssertTrue(store.threads.isEmpty); XCTAssertNil(store.nextThread)
			store.selectAccount(instagram); XCTAssertEqual(store.selectedAccount, "")
			await store.loadInbox(search: "hello")
			XCTAssertEqual(requests.last?["platform"], "facebook"); XCTAssertNil(requests.last?["accountId"]); XCTAssertNil(requests.last?["before"])
			store.clearPrivateState(); XCTAssertTrue(store.accounts.isEmpty); XCTAssertEqual(store.selectedPlatform, "")
		}
	}
	@MainActor func testSocialRefreshRemovesStaleRowsAndRejectsInFlightFilterResponse() async throws {
		try await withSocialStore { store, _ in
			var mode = 0
			ReactionMockProtocol.handler = { _ in
				if mode == 2 { Task { @MainActor in store.selectPlatform("facebook") }; Thread.sleep(forTimeInterval: 0.1) }
				return Self.envelope(["items": mode == 1 ? [] : [Self.socialThreadJSON], "next": NSNull()])
			}
			await store.loadInbox(); XCTAssertEqual(store.threads.count, 1)
			mode = 1; await store.loadInbox(); XCTAssertTrue(store.threads.isEmpty)
			mode = 2; await store.loadInbox(); XCTAssertTrue(store.threads.isEmpty)
			XCTAssertEqual(store.selectedPlatform, "facebook"); XCTAssertTrue(store.busy, "The next filter refresh is pending, not an empty inbox")
		}
	}
	@MainActor func testSocialReadMarksOnlyDisplayedSequenceAndClearingRejectsLateMessages() async throws {
		try await withSocialStore { store, _ in
			var read = false, clear = false
			ReactionMockProtocol.handler = { request in
				switch request.url!.lastPathComponent {
				case "inbox-detail":
					var row = Self.socialThreadJSON; row["unread"] = true; row["readThrough"] = 12
					return Self.envelope(row)
				case "inbox-read":
					let body = try ReactionMockProtocol.body(request)
					XCTAssertEqual(body["readThrough"] as? Int, 12); XCTAssertNil(body["actorId"]); XCTAssertNil(body["ownerId"])
					read = true; return Self.envelope(Self.socialThreadJSON)
				case "inbox-messages" where clear:
					Task { @MainActor in store.clearPrivateState() }; Thread.sleep(forTimeInterval: 0.1)
					return Self.socialRead(request)
				default: return Self.socialRead(request)
				}
			}
			await store.loadThread(id: "thread", markRead: true)
			XCTAssertTrue(read); XCTAssertFalse(store.thread?.unread ?? true)
			clear = true; await store.loadThread(id: "thread")
			XCTAssertNil(store.thread); XCTAssertTrue(store.messages.isEmpty); XCTAssertFalse(store.busy)
		}
	}
	@MainActor func testSocialSavedHistoryAppearsBeforeProviderRefresh() async throws {
		try await withSocialStore { store, _ in
			ReactionMockProtocol.handler = { request in
				switch request.url!.lastPathComponent {
				case "inbox-refresh-messages":
					XCTAssertEqual(store.messages.first?.text, "Saved content")
					return (503, Data("{}".utf8))
				default: return Self.socialRead(request)
				}
			}
			await store.loadThread(id: "thread", refreshProvider: true)
			XCTAssertEqual(store.messages.count, 1)
		}
	}
	@MainActor func testSocialUnknownSendCannotResendOrLoseDraft() async throws {
		try await withSocialStore { store, journal in
			var sendCount = 0
			ReactionMockProtocol.handler = { request in
				if request.url!.lastPathComponent == "inbox-send" {
					sendCount += 1
					let body = try ReactionMockProtocol.body(request)
					XCTAssertEqual(body["message"] as? String, "Hello 👨‍👩‍👧‍👦")
					XCTAssertNotNil(UUID(uuidString: body["requestId"] as? String ?? ""))
					throw URLError(.timedOut)
				}
				return Self.socialRead(request)
			}
			await store.loadThread(id: "thread"); store.draft = "Hello 👨‍👩‍👧‍👦"
			await store.send(); await store.send(); await store.checkSend()
			XCTAssertEqual(sendCount, 1); XCTAssertTrue(store.unresolvedSend)
			XCTAssertEqual(store.draft, "Hello 👨‍👩‍👧‍👦")
			XCTAssertNotNil(journal.pending(account: Mango9ReactionTarget.accountKey(store.session), thread: "thread"))
			XCTAssertFalse(store.canSend)
		}
	}
	@MainActor func testSocialSentReceiptClearsOnlySentDraftAndUsesServerContract() async throws {
		try await withSocialStore { store, _ in
			var receipt: [String: Any]?
			ReactionMockProtocol.handler = { request in
				switch request.url!.lastPathComponent {
				case "inbox-send":
					let body = try ReactionMockProtocol.body(request)
					XCTAssertEqual(body["id"] as? String, "thread"); XCTAssertEqual(body["version"] as? Int, 3)
					XCTAssertNil(body["ownerId"]); XCTAssertNil(body["actorId"])
					receipt = ["id": "thread", "sendId": "send", "requestId": body["requestId"]!, "state": "sent", "message": "Reply", "providerId": "external"]
					return Self.envelope(receipt!)
				case "inbox-sends": return Self.envelope(["items": receipt.map { [$0] } ?? [], "open": NSNull()])
				default: return Self.socialRead(request)
				}
			}
			await store.loadThread(id: "thread"); store.draft = "Reply"; await store.send()
			XCTAssertEqual(store.draft, ""); XCTAssertFalse(store.unresolvedSend)
			XCTAssertEqual(store.sends.first?.statusLabel, "Sent")
		}
	}
	@MainActor func testSocialDisconnectedConversationHasNoSendAction() async throws {
		try await withSocialStore { store, _ in
			ReactionMockProtocol.handler = { request in
				if request.url!.lastPathComponent == "inbox-detail" {
					var thread = Self.socialThreadJSON; thread["connectionCurrent"] = false
					return Self.envelope(thread)
				}
				XCTAssertNotEqual(request.url!.lastPathComponent, "inbox-send")
				return Self.socialRead(request)
			}
			await store.loadThread(id: "thread"); store.draft = "No send"; await store.send()
			XCTAssertFalse(store.canSend); XCTAssertEqual(store.draft, "No send")
		}
	}
	@MainActor func testSocialPendingSendSurvivesRestartWithoutSavingMessageBody() throws {
		let name = "social-send-test-" + UUID().uuidString; let defaults = UserDefaults(suiteName: name)!
		defer { defaults.removePersistentDomain(forName: name) }
		let first = Mango9SocialSendJournal(defaults: defaults)
		let pending = try first.begin(account: "account", thread: "thread")
		let reopened = Mango9SocialSendJournal(defaults: defaults)
		XCTAssertEqual(reopened.pending(account: "account", thread: "thread"), pending)
		XCTAssertThrowsError(try reopened.begin(account: "account", thread: "thread"))
		XCTAssertNil(reopened.pending(account: "other", thread: "thread"))
		reopened.finish(pending); XCTAssertNil(reopened.pending(account: "account", thread: "thread"))
	}
	@MainActor private func withSocialStore(_ body: (Mango9SocialInboxStore, Mango9SocialSendJournal) async throws -> Void) async throws {
		let active = session(); let previous = Mango9SessionStore.load()
		let name = "social-send-test-" + UUID().uuidString; let defaults = UserDefaults(suiteName: name)!
		try Mango9SessionStore.save(active, persist: false, makeActive: true)
		defer {
			defaults.removePersistentDomain(forName: name)
			Mango9SessionStore.remove(for: active.sipIdentity!)
			if let previous { try? Mango9SessionStore.save(previous, makeActive: true) }
		}
		let journal = Mango9SocialSendJournal(defaults: defaults)
		try await body(Mango9SocialInboxStore(session: active, api: mockAPI(), journal: journal), journal)
	}
	private static var socialThreadJSON: [String: Any] {
		["id": "thread", "version": 3, "sourceId": "source", "platform": "instagram", "name": "Fixture",
		 "unread": false, "connectionCurrent": true, "canReply": true]
	}
	private static func envelope(_ value: [String: Any]) -> (Int, Data) {
		(200, try! JSONSerialization.data(withJSONObject: ["success": true, "data": value]))
	}
	private static func socialRead(_ request: URLRequest) -> (Int, Data) {
		switch request.url!.lastPathComponent {
		case "inbox-detail": return envelope(socialThreadJSON)
		case "inbox-notification-get": return envelope(["id": "thread", "muted": false])
		case "inbox-sends": return envelope(["items": [], "open": NSNull()])
		case "inbox-refresh-messages": return envelope([:])
		case "inbox-messages": return envelope(["items": [["id": 1, "text": "Saved content", "direction": "incoming", "deleted": false]], "next": NSNull()])
		default: XCTFail("Unexpected endpoint"); return (400, Data("{}".utf8))
		}
	}
}

private final class ReactionMockProtocol: URLProtocol {
	static var handler: ((URLRequest) throws -> (Int, Data))?
	override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix(".invalid") == true }
	override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
	override func startLoading() {
		do {
			let (status, data) = try Self.handler!(request)
			client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
			client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
		} catch { client?.urlProtocol(self, didFailWithError: error) }
	}
	override func stopLoading() {}
	static func body(_ request: URLRequest) throws -> [String: Any] {
		var data = request.httpBody ?? Data()
		if let stream = request.httpBodyStream {
			stream.open(); defer { stream.close() }
			var buffer = [UInt8](repeating: 0, count: 4096)
			while stream.hasBytesAvailable { let n = stream.read(&buffer, maxLength: buffer.count); if n <= 0 { break }; data.append(buffer, count: n) }
		}
		return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
	}
}
