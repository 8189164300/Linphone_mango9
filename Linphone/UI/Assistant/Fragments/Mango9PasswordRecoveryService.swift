import Foundation

enum Mango9PasswordRecoveryError: Error, Equatable {
    case invalidEmail
    case rateLimited(seconds: Int)
    case unavailable
}

enum Mango9PasswordRecoveryService {
    // Recovery belongs to the central identity, including when its CRM is offline.
    static let endpoint = URL(string: "https://account.mango9.com/m9/auth/mobile/password/reset/request")!

    static func normalizedEmail(_ value: String) -> String? {
        let email = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard email.utf8.count <= 254,
              email.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil else { return nil }
        return email
    }

    private static let recoverySession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 25
        return URLSession(configuration: configuration, delegate: NoRecoveryRedirects(), delegateQueue: nil)
    }()

    static func request(email input: String, session: URLSession? = nil) async throws {
        guard let email = normalizedEmail(input) else { throw Mango9PasswordRecoveryError.invalidEmail }
        try Task.checkCancellation()
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(RecoveryRequest(email: email))
        let (data, response) = try await (session ?? recoverySession).data(for: request)
        try Task.checkCancellation()
        guard let response = response as? HTTPURLResponse else { throw Mango9PasswordRecoveryError.unavailable }
        if response.statusCode == 429 {
            let seconds = min(86_400, max(1, Int(response.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 60))
            throw Mango9PasswordRecoveryError.rateLimited(seconds: seconds)
        }
        guard (200..<300).contains(response.statusCode),
              (try? JSONDecoder().decode(RecoveryResponse.self, from: data))?.sent == true else {
            throw Mango9PasswordRecoveryError.unavailable
        }
    }

    private struct RecoveryRequest: Encodable { let email: String }
    private struct RecoveryResponse: Decodable { let sent: Bool }

    private final class NoRecoveryRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }
}
