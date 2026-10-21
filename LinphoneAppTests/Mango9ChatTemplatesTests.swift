import XCTest
@testable import LinphoneApp

final class Mango9ChatTemplatesTests: XCTestCase {
    func testInsertionPreservesExistingDraftAndEmoji() {
        XCTAssertEqual(Mango9TemplateText.inserting("Hello 👋", into: ""), "Hello 👋")
        XCTAssertEqual(Mango9TemplateText.inserting("Template", into: "My draft"), "My draft\nTemplate")
        XCTAssertEqual(Mango9TemplateText.inserting("Template", into: "My draft\n"), "My draft\nTemplate")
    }
    func testUTF8LimitIsDistinctFromCharacterCounter() {
        XCTAssertEqual("👨‍👩‍👧‍👦".count, 1)
        XCTAssertTrue(Mango9TemplateText.valid(title: "Welcome", content: String(repeating: "😀", count: 2500), titleBytes: 255, contentBytes: 10000))
        XCTAssertFalse(Mango9TemplateText.valid(title: "Welcome", content: String(repeating: "😀", count: 2501), titleBytes: 255, contentBytes: 10000))
        XCTAssertFalse(Mango9TemplateText.valid(title: "  ", content: "Hi", titleBytes: 255, contentBytes: 10000))
    }
    @MainActor func testCRMTemplateRequestUsesCorrectOriginMethodAndExactRetryBody() throws {
        let session = Mango9Session(crmId: "fixture", crmBaseUrl: "https://crm.invalid", crmApiBaseUrl: "https://crm.invalid/app/api/v2",
            userId: "7", parentClientId: "7", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture",
            accessToken: "fixture-token", refreshToken: "fixture-refresh", smsChatApi: "https://messages.invalid/sms_chat_api",
            connectWebsocket: "", enrollmentExpiresAt: .distantFuture, sipIdentity: "sip:7@voice.invalid")
        let body: [String: Any] = ["requestId": "62000000-0000-4000-8000-000000000001", "revision": String(repeating: "a", count: 64), "values": ["title": "Example", "content": "Hello 👋"]]
        let request = try Mango9TemplateAPI.wire(session: session, path: "42", method: "PATCH", body: body)
        XCTAssertEqual(request.url?.absoluteString, "https://crm.invalid/app/api/v2/mobile/messaging/templates/42")
        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertFalse(request.httpShouldHandleCookies)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
        let decoded = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        XCTAssertEqual(decoded["requestId"] as? String, body["requestId"] as? String)
        XCTAssertNil(decoded["user_id"])
    }
    func testServerCapabilitiesDecodeWithoutGrantingMissingPermissions() throws {
        let raw = #"{"permissions":{"can_list":true,"can_use":true,"can_create":false},"limits":{"title":{"max_utf8_bytes":255},"content":{"max_utf8_bytes":10000}},"templates":[],"pagination":{"page":1,"limit":25,"total":0}}"#
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let value = try decoder.decode(Mango9TemplateLibrary.self, from: Data(raw.utf8))
        XCTAssertFalse(value.permissions.canCreate); XCTAssertEqual(value.limits.content.maxUtf8Bytes, 10000)
        XCTAssertEqual(value.templates?.count, 0)
        XCTAssertThrowsError(try decoder.decode(Mango9TemplateLibrary.self, from: Data("{}".utf8)))
    }
    @MainActor func testSavedTemplateRoundTripOnOptedInPhysicalDevice() async throws {
        guard ProcessInfo.processInfo.environment["MANGO9_TEMPLATE_LIVE_CHECK"] == "1" else {
            throw XCTSkip("Requires an explicitly opted-in, signed-in physical device.")
        }
        let session = try XCTUnwrap(Mango9SessionStore.load())
        let model = Mango9TemplatePickerModel(session: session)
        await model.load()
        XCTAssertTrue(model.canCreate)
        model.edit(); model.title = "Phone template check " + UUID().uuidString.prefix(8)
        model.content = "Template draft only 👋"
        await model.save()
        let created = try XCTUnwrap(model.selected)
        do {
            XCTAssertEqual(created.content, "Template draft only 👋")
            await model.select(created)
            model.edit(model.selected); model.content = "Edited template 👋"
            await model.save()
            let saved = try XCTUnwrap(model.selected)
            XCTAssertTrue(model.canInsert)
            XCTAssertEqual(saved.content, "Edited template 👋")
            XCTAssertEqual(Mango9TemplateText.inserting(try XCTUnwrap(saved.content), into: "Existing draft"), "Existing draft\nEdited template 👋")
            let fresh = try await model.api.request(path: String(created.id))
            XCTAssertEqual(fresh.template?.content, saved.content)
        } catch {
            let fresh = try await model.api.request(path: String(created.id))
            _ = try await model.api.request(path: String(created.id), method: "DELETE", body: ["requestId": UUID().uuidString.lowercased(), "revision": try XCTUnwrap(fresh.template?.revision)])
            throw error
        }
        let fresh = try await model.api.request(path: String(created.id))
        _ = try await model.api.request(path: String(created.id), method: "DELETE", body: ["requestId": UUID().uuidString.lowercased(), "revision": try XCTUnwrap(fresh.template?.revision)])
    }

}
