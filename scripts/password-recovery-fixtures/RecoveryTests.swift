import Foundation

private final class StubProtocol: URLProtocol {
    static var status = 200
    static var body = #"{"sent":true}"#
    static var retry: String?
    static var failure: URLError?
    static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        if let failure = Self.failure { client?.urlProtocol(self, didFailWithError: failure); return }
        var headers = ["Content-Type": "application/json"]
        if let retry = Self.retry { headers["Retry-After"] = retry }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: Self.status,
                                                             httpVersion: "HTTP/1.1", headerFields: headers)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(Self.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@main struct RecoveryTests {
    static func main() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        precondition(Mango9PasswordRecoveryService.normalizedEmail("  FIXTURE@Example.test \n") == "fixture@example.test")
        for value in ["", "a@b", "bad", "a\nb@example.test", String(repeating: "a", count: 250) + "@example.test"] {
            precondition(Mango9PasswordRecoveryService.normalizedEmail(value) == nil)
            do { try await Mango9PasswordRecoveryService.request(email: value, session: session); fatalError("invalid email sent") }
            catch { precondition(error as? Mango9PasswordRecoveryError == .invalidEmail) }
        }
        precondition(StubProtocol.requests.isEmpty)
        try await Mango9PasswordRecoveryService.request(email: " FIXTURE@Example.test ", session: session)
        let request = StubProtocol.requests.last!
        precondition(request.url?.absoluteString == "https://account.mango9.com/m9/auth/mobile/password/reset/request")
        precondition(request.httpMethod == "POST" && request.value(forHTTPHeaderField: "Content-Type") == "application/json")
        precondition(request.value(forHTTPHeaderField: "Authorization") == nil && request.value(forHTTPHeaderField: "Cookie") == nil)
        let stream = request.httpBodyStream!
        stream.open(); defer { stream.close() }
        var bytes = [UInt8](repeating: 0, count: 1024)
        let length = stream.read(&bytes, maxLength: bytes.count)
        let payload = try JSONSerialization.jsonObject(with: Data(bytes.prefix(length))) as? [String: String]
        precondition(payload == ["email": "fixture@example.test"])
        for (status, body) in [(200, "{}"), (200, #"{"sent":false}"#), (200, "<html>login</html>"), (204, ""), (302, ""), (400, "{}"), (401, "{}"), (404, "{}"), (503, "{}"), (500, #"{"sent":true}"#)] {
            StubProtocol.status = status; StubProtocol.body = body
            do { try await Mango9PasswordRecoveryService.request(email: "fixture@example.test", session: session); fatalError("false success") }
            catch { precondition(error as? Mango9PasswordRecoveryError == .unavailable) }
        }
        StubProtocol.status = 429
        for (header, expected) in [("120", 120), ("invalid", 60), ("-1", 1)] {
            StubProtocol.retry = header
            do { try await Mango9PasswordRecoveryService.request(email: "fixture@example.test", session: session); fatalError("rate limit ignored") }
            catch { precondition(error as? Mango9PasswordRecoveryError == .rateLimited(seconds: expected)) }
        }
        StubProtocol.failure = URLError(.notConnectedToInternet)
        do { try await Mango9PasswordRecoveryService.request(email: "fixture@example.test", session: session); fatalError("offline success") }
        catch { precondition((error as? URLError)?.code == .notConnectedToInternet) }
        StubProtocol.failure = nil
        let count = StubProtocol.requests.count
        let cancelled = Task { () throws -> Void in
            try await Task.sleep(nanoseconds: 100_000_000)
            try await Mango9PasswordRecoveryService.request(email: "fixture@example.test", session: session)
        }
        cancelled.cancel()
        do { try await cancelled.value; fatalError("cancellation ignored") } catch is CancellationError {}
        precondition(StubProtocol.requests.count == count)
        print("PASS: native recovery validation, Central routing, email-only payload, success contract, errors, limits, offline and cancellation")
    }
}
