/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import Foundation
import Security
import Network
import UIKit
import linphonesw

struct Mango9LogoutRecord: Codable, Equatable, Identifiable {
	var id = UUID()
	let identity: String
	let voipToken: String
	let provider: String
	let topic: String
	var cleanupToken: String?
	var pendingLogout = false

	func matches(identity: String, token: String, provider: String, topic: String) -> Bool {
		self.identity == identity && voipToken == token && self.provider == provider && self.topic == topic
	}
}

enum Mango9LogoutError: LocalizedError {
	case secureStorage, unavailable, missingDeviceScope
	var errorDescription: String? {
		switch self {
		case .secureStorage:
			return "Mango9 couldn’t securely save the sign-out request. Please unlock your iPhone and try again."
		case .unavailable:
			return "Please connect to the internet and try again so Mango9 can safely stop calls for this account."
		case .missingDeviceScope:
			return "Please sign in to this account online once more so Mango9 can securely remove its call notifications."
		}
	}
}

enum Mango9LogoutKeychain {
	private static var key: [String: Any] {
		[kSecClass as String: kSecClassGenericPassword,
		 kSecAttrService as String: Mango9Configuration.bundleIdentifier + ".logout-cleanup",
		 kSecAttrAccount as String: "device-leases-v1"]
	}
	static func load() throws -> [Mango9LogoutRecord] {
		var query = key
		query[kSecReturnData as String] = true
		query[kSecMatchLimit as String] = kSecMatchLimitOne
		var result: CFTypeRef?
		let status = SecItemCopyMatching(query as CFDictionary, &result)
		if status == errSecItemNotFound { return [] }
		guard status == errSecSuccess, let data = result as? Data,
			  let records = try? JSONDecoder().decode([Mango9LogoutRecord].self, from: data) else {
			throw Mango9LogoutError.secureStorage
		}
		return records
	}
	static func save(_ records: [Mango9LogoutRecord]) throws {
		let attributes: [String: Any] = [
			kSecValueData as String: try JSONEncoder().encode(records),
			kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
		]
		let status = SecItemUpdate(key as CFDictionary, attributes as CFDictionary)
		if status == errSecSuccess { return }
		guard status == errSecItemNotFound else { throw Mango9LogoutError.secureStorage }
		var item = key
		attributes.forEach { item[$0.key] = $0.value }
		guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
			throw Mango9LogoutError.secureStorage
		}
	}
}

private final class Mango9LogoutTransport: NSObject, URLSessionTaskDelegate {
	static let delegate = Mango9LogoutTransport()
	static let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
	func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
					newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
		completionHandler(nil)
	}
}

/// A single serialized queue for lease activation and cleanup. Only revocation
/// capabilities survive logout, not SIP passwords or CRM login credentials.
@MainActor
final class Mango9LogoutCoordinator {
	static let shared = Mango9LogoutCoordinator()
	private let read: () throws -> [Mango9LogoutRecord]
	private let write: ([Mango9LogoutRecord]) throws -> Void
	private let send: (URLRequest) async throws -> (Data, URLResponse)
	private let accountExists: (String) async -> Bool
	private var locked = false
	private var waiters: [CheckedContinuation<Void, Never>] = []
	private var monitor: NWPathMonitor?
	private var retryTask: Task<Void, Never>?
	private var retryTimer: Timer?

	init(read: @escaping () throws -> [Mango9LogoutRecord] = Mango9LogoutKeychain.load,
		 write: @escaping ([Mango9LogoutRecord]) throws -> Void = Mango9LogoutKeychain.save,
		 send: @escaping (URLRequest) async throws -> (Data, URLResponse) = {
			try await Mango9LogoutTransport.session.data(for: $0)
		 }, accountExists: @escaping (String) async -> Bool = { identity in
			await withCheckedContinuation { continuation in
				CoreContext.shared.doOnCoreQueue { core in
					continuation.resume(returning: core.accountList.contains {
						$0.params?.identityAddress?.asStringUriOnly() == identity
					})
				}
			}
		 }) {
		self.read = read
		self.write = write
		self.send = send
		self.accountExists = accountExists
	}

	private func acquire() async {
		if locked { await withCheckedContinuation { waiters.append($0) } }
		else { locked = true }
	}
	private func release() {
		if waiters.isEmpty { locked = false }
		else { waiters.removeFirst().resume() }
	}

	static func normalizedVoIPToken(_ raw: String?) -> String? {
		guard let raw else { return nil }
		let value = (raw.hasSuffix(":voip") ? String(raw.dropLast(5)) : raw).lowercased()
		guard (32...256).contains(value.count), value.allSatisfy({ $0.isASCII && $0.isHexDigit }) else { return nil }
		return value
	}

	private func request(path: String, bearer: String, body: [String: String]) async throws -> [String: Any] {
		var request = URLRequest(url: Mango9Configuration.provisioningBaseURL.appendingPathComponent(path))
		request.httpMethod = "POST"
		request.timeoutInterval = 40
		request.setValue("Bearer " + bearer, forHTTPHeaderField: "Authorization")
		request.setValue("application/json", forHTTPHeaderField: "Content-Type")
		request.httpBody = try JSONSerialization.data(withJSONObject: body)
		let (data, response) = try await send(request)
		guard let http = response as? HTTPURLResponse else { throw Mango9LogoutError.unavailable }
		if http.statusCode == 401 { throw Mango9CRMAPIError.unauthorized }
		guard (200..<300).contains(http.statusCode),
			  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
			  json["success"] as? Bool == true, let payload = json["data"] as? [String: Any] else {
			throw Mango9LogoutError.unavailable
		}
		return payload
	}

	/// Must be called under the operation lock. Persist the generation before
	/// sending it, so retrying after a lost response is idempotent.
	private func ensureLease(identity: String, token: String, session: Mango9Session) async throws {
		var records = try read()
		let provider = Mango9Configuration.applePushProvider
		let topic = Mango9Configuration.applePushParam
		let index: Int
		if let existing = records.firstIndex(where: {
			!$0.pendingLogout && $0.matches(identity: identity, token: token, provider: provider, topic: topic)
		}) {
			index = existing
			if records[index].cleanupToken != nil { return }
		} else {
			records.append(Mango9LogoutRecord(identity: identity, voipToken: token, provider: provider, topic: topic))
			index = records.count - 1
			try write(records)
		}
		let record = records[index]
		records[index].cleanupToken = try await authorizeLease(record, session: session)
		try write(records)
	}

	private func authorizeLease(_ record: Mango9LogoutRecord, session: Mango9Session) async throws -> String {
		let body = ["crm_id": session.crmId, "sip_identity": record.identity, "voip_token": record.voipToken,
					"provider": record.provider, "topic": record.topic, "generation": record.id.uuidString.lowercased()]
		let payload: [String: Any]
		do {
			payload = try await request(path: "v1/mobile/push-lease", bearer: session.accessToken, body: body)
		} catch Mango9CRMAPIError.unauthorized {
			let refreshed = try await Mango9CRMAPI.refresh(session: session)
			payload = try await request(path: "v1/mobile/push-lease", bearer: refreshed.accessToken, body: body)
		}
		guard let capability = payload["cleanup_token"] as? String, !capability.isEmpty,
			  payload["generation"] as? String == record.id.uuidString.lowercased() else {
			throw Mango9LogoutError.unavailable
		}
		return capability
	}

	func prepareLogout(identity: String, token: String?, session: Mango9Session?) async throws {
		await acquire()
		defer { release() }
		if let token = Self.normalizedVoIPToken(token), let session {
			try await ensureLease(identity: identity, token: token, session: session)
		}
		// A token may rotate after an interrupted activation. Finish those
		// older exact scopes too; otherwise a missing capability could leave
		// the account permanently unable to complete a safe logout.
		for record in try read() where record.identity == identity && record.cleanupToken == nil {
			guard let session else { throw Mango9LogoutError.missingDeviceScope }
			let capability = try await authorizeLease(record, session: session)
			var updated = try read()
			if let index = updated.firstIndex(where: { $0.id == record.id }) {
				updated[index].cleanupToken = capability
				try write(updated)
			}
		}
		var records = try read()
		let targets = records.indices.filter { records[$0].identity == identity }
		guard !targets.isEmpty, targets.allSatisfy({ records[$0].cleanupToken != nil }) else {
			throw Mango9LogoutError.missingDeviceScope
		}
		for index in targets { records[index].pendingLogout = true }
		try write(records) // Never remove the local account if durable intent fails.
	}

	func prepareLogin(identity: String, token: String?, session: Mango9Session?) async throws {
		await acquire()
		defer { release() }
		// A queued previous logout must finish before this line can register
		// again. Activation then cancels the server's older cleanup generation.
		try await drain(identity: identity)
		guard let token = Self.normalizedVoIPToken(token), let session else { return }
		try await ensureLease(identity: identity, token: token, session: session)
	}

	private func drain(identity: String? = nil) async throws {
		for record in try read() where record.pendingLogout && (identity == nil || record.identity == identity) {
			// Do not acknowledge/delete intent during the SIP unregister grace
			// period. A process kill must still recover the local account removal.
			guard !(await accountExists(record.identity)) else { throw Mango9LogoutError.unavailable }
			guard let capability = record.cleanupToken else { throw Mango9LogoutError.missingDeviceScope }
			let result = try await request(path: "v1/mobile/logout", bearer: capability, body: [:])
			guard result["completed"] as? Bool == true else { throw Mango9LogoutError.unavailable }
			try write(try read().filter { $0.id != record.id })
		}
	}

	func retryPending() async {
		await acquire()
		defer { release() }
		// One failed account must not prevent cleanup of another account.
		let identities = Set(((try? read()) ?? []).filter(\.pendingLogout).map(\.identity))
		for identity in identities {
			do { try await drain(identity: identity) }
			catch { Log.warn("[Logout] Device registration cleanup remains queued") }
		}
	}

	func hasPendingLogout(identity: String) -> Bool {
		((try? read()) ?? []).contains { $0.identity == identity && $0.pendingLogout }
	}

	func start() {
		guard monitor == nil else { return }
		let monitor = NWPathMonitor()
		monitor.pathUpdateHandler = { path in
			if path.status == .satisfied { Task { @MainActor in await Self.shared.refresh() } }
		}
		monitor.start(queue: DispatchQueue(label: "mango9.logout.network"))
		self.monitor = monitor
		retryTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { _ in
			Task { @MainActor in
				if UIApplication.shared.applicationState == .active { await Self.shared.refresh() }
			}
		}
	}

	func prepareLogin(identity: String, session: Mango9Session) async throws {
		let token: String? = await withCheckedContinuation { continuation in
			CoreContext.shared.doOnCoreQueue { core in
				continuation.resume(returning: core.pushNotificationConfig?.voipToken)
			}
		}
		try await prepareLogin(identity: identity, token: token, session: session)
	}

	func refresh() async {
		guard retryTask == nil else { return }
		retryTask = Task { @MainActor in
			defer { retryTask = nil }
			await retryPending()
			let snapshots: [(String, String?)] = await withCheckedContinuation { continuation in
				CoreContext.shared.doOnCoreQueue { core in
					continuation.resume(returning: core.accountList.compactMap { account in
						guard account.params?.registerEnabled == true,
							  let identity = account.params?.identityAddress?.asStringUriOnly() else { return nil }
						return (identity, account.params?.pushNotificationConfig?.voipToken)
					})
				}
			}
			for (identity, token) in snapshots {
				// Bootstrap cleanup capabilities for accounts from older builds.
				await acquire()
				defer { release() }
				guard !((try? read()) ?? []).contains(where: { $0.identity == identity && $0.pendingLogout }),
					  let token = Self.normalizedVoIPToken(token),
					  let session = Mango9SessionStore.load(for: identity) else { continue }
				do { try await ensureLease(identity: identity, token: token, session: session) }
				catch { Log.warn("[Logout] Device cleanup setup will retry") }
			}
		}
		await retryTask?.value
	}

	/// Core-queue only. Recover a process kill between saving logout intent and
	/// the original five-second SIP unregister completion callback.
	nonisolated static func recoverLocalRemovals(core: Core) {
		guard let records = try? Mango9LogoutKeychain.load() else { return }
		let pending = Set(records.filter(\.pendingLogout).map(\.identity))
		for account in core.accountList {
			guard let identity = account.params?.identityAddress?.asStringUriOnly(), pending.contains(identity) else { continue }
			if let params = account.params?.clone() { params.registerEnabled = false; account.params = params }
			let auth = account.findAuthInfo()
			core.removeAccount(account: account)
			if let auth, !core.accountList.contains(where: { $0.findAuthInfo() === auth }) { core.removeAuthInfo(info: auth) }
			Mango9SessionStore.remove(for: identity)
		}
	}
}
