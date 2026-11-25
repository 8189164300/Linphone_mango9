import XCTest
@testable import LinphoneApp

private final class SocialBulkMock: URLProtocol {
    static var handler: ((URLRequest, [String: Any]) throws -> [String: Any])?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            var data = request.httpBody ?? Data()
            if let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var bytes = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable { let n = stream.read(&bytes, maxLength: bytes.count); if n <= 0 { break }; data.append(bytes, count: n) }
            }
            let body = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            let result = try Self.handler!(request, body)
            let response = try JSONSerialization.data(withJSONObject: ["success": true, "data": result])
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: response); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

@MainActor final class Mango9SocialBulkReadTests: XCTestCase {
    private func withStore(_ body: (Mango9SocialInboxStore) async throws -> Void) async throws {
        let previous = Mango9SessionStore.activeIdentity
        let session = Mango9Session(crmId: "fixture", crmBaseUrl: "https://bulk.example.invalid", crmApiBaseUrl: "https://bulk.example.invalid/api/v2",
            userId: "bulk-fixture", parentClientId: "fixture", role: "client", loginId: "fixture@example.invalid", displayName: "Fixture",
            accessToken: "fixture-token", refreshToken: "fixture-refresh", smsChatApi: "", connectWebsocket: "", enrollmentExpiresAt: .distantFuture,
            sipIdentity: "sip:bulk-fixture@bulk.example.invalid")
        try Mango9SessionStore.save(session, persist: false, makeActive: true)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [SocialBulkMock.self]
        let transport = URLSession(configuration: config)
        defer {
            transport.invalidateAndCancel(); SocialBulkMock.handler = nil
            try? Mango9SessionStore.remove(for: session.sipIdentity!)
            Mango9SessionStore.activate(sipIdentity: previous)
        }
        try await body(Mango9SocialInboxStore(session: session, api: Mango9MessagingAPI(transport: transport)))
    }
    nonisolated private func rows(_ count: Int, unread: Bool) -> [[String: Any]] {
        (0..<count).map { ["id": String(format: "00000000-0000-4000-8000-%012d", $0), "readThrough": $0 + 1, "unread": unread, "markedUnread": false] }
    }
    func testFiveHundredChatsUseOneSelectionAndOneBatchRequest() async throws {
        try await withStore { store in
            let selected = rows(500, unread: true), result = rows(500, unread: false)
            var paths: [String] = []
            SocialBulkMock.handler = { request, body in
                let path = request.url!.lastPathComponent; paths.append(path)
                if path == "inbox-bulk-selection" {
                    XCTAssertEqual(body["mode"] as? String, "unread"); XCTAssertEqual(body["search"] as? String, "sample")
                    XCTAssertNil(body["limit"]); XCTAssertNil(body["kind"])
                    return ["type": "inbox-selection", "selectionId": body["requestId"]!, "items": selected]
                }
                XCTAssertEqual(path, "inbox-bulk-read"); XCTAssertEqual((body["ids"] as? [String])?.count, 500)
                return ["type": "inbox-bulk-read", "requestId": body["requestId"]!, "action": "read", "items": result, "skippedIds": []]
            }
            let ids = try await store.selectChats(mode: "unread", search: "sample")
            XCTAssertEqual(ids.count, 500); XCTAssertTrue(store.threads.isEmpty, "Selection must not append hundreds of full rows")
            try await store.updateSelectedChats(ids, action: .read, search: "sample")
            XCTAssertEqual(paths, ["inbox-bulk-selection", "inbox-bulk-read"])
        }
    }
    func testLostResponseRetriesTheSameSnapshotAndRequest() async throws {
        try await withStore { store in
            let selected = rows(3, unread: false); var selections = 0, requests: [String] = [], snapshots: [String] = []
            SocialBulkMock.handler = { request, body in
                if request.url!.lastPathComponent == "inbox-bulk-selection" {
                    selections += 1
                    return ["type": "inbox-selection", "selectionId": body["requestId"]!, "items": selected]
                }
                requests.append(body["requestId"] as! String); snapshots.append(body["selectionId"] as! String)
                if requests.count == 1 { throw URLError(.networkConnectionLost) }
                return ["type": "inbox-bulk-read", "requestId": body["requestId"]!, "action": "unread", "items": self.rows(3, unread: true), "skippedIds": []]
            }
            let ids = try await store.selectChats(mode: "read", search: "")
            do { try await store.updateSelectedChats(ids, action: .unread, search: ""); XCTFail("Expected lost response") } catch {}
            try await store.updateSelectedChats(ids, action: .unread, search: "")
            XCTAssertEqual(selections, 1); XCTAssertEqual(requests.count, 2)
            XCTAssertEqual(requests.first, requests.last); XCTAssertEqual(snapshots.first, snapshots.last)
        }
    }
    func testReceiptMustCoverExactlyTheSelectedChats() async throws {
        try await withStore { store in
            SocialBulkMock.handler = { request, body in
                if request.url!.lastPathComponent == "inbox-bulk-selection" { return ["type": "inbox-selection", "selectionId": body["requestId"]!, "items": self.rows(2, unread: true)] }
                return ["type": "inbox-bulk-read", "requestId": body["requestId"]!, "action": "read", "items": self.rows(1, unread: false), "skippedIds": []]
            }
            let ids = try await store.selectChats(mode: "unread", search: "")
            do { try await store.updateSelectedChats(ids, action: .read, search: ""); XCTFail("Partial receipt must not report success") } catch {}
        }
    }
    func testSelectionDoesNotCountDownOneChatAtATimeAndFailureKeepsSelection() async throws {
        try await withStore { _ in
            let selection = Mango9ChatListSelection(); selection.bind("fixture:social")
            let ids = (0..<500).map(String.init); await selection.all { ids }
            var calls = 0
            await selection.runBatch(.read) { selected, _ in
                calls += 1; XCTAssertEqual(selected.count, 500); XCTAssertEqual(selection.selected.count, 500)
                await Task.yield(); XCTAssertEqual(selection.selected.count, 500)
                throw URLError(.timedOut)
            }
            XCTAssertEqual(calls, 1); XCTAssertEqual(selection.selected.count, 500); XCTAssertNotNil(selection.error)
            await selection.runBatch(.read) { selected, _ in XCTAssertEqual(selected.count, 500) }
            XCTAssertTrue(selection.selected.isEmpty); XCTAssertFalse(selection.busy); XCTAssertFalse(selection.selecting)
        }
    }
    func testChangingScopeDiscardsOldBatchCompletion() async throws {
        try await withStore { _ in
            let selection = Mango9ChatListSelection(); selection.bind("first")
            await selection.all { ["old"] }
            await selection.runBatch(.read) { _, _ in
                selection.bind("second"); selection.toggle("new")
            }
            XCTAssertEqual(selection.selected, ["new"]); XCTAssertNil(selection.error)
        }
    }
}
