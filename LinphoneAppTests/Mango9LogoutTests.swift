/* SPDX-License-Identifier: GPL-3.0-or-later */
import XCTest
@testable import LinphoneApp

@MainActor
final class Mango9LogoutTests: XCTestCase {
	private let identity = "sip:101@fixture.example.com"
	private let token = String(repeating: "a", count: 64)

	private final class Memory {
		var records: [Mango9LogoutRecord] = []
		var requests: [URLRequest] = []
		var fails = false
		var refusesWrite = false
		var accountPresent = false
	}
	private func record(identity: String? = nil, token: String? = nil, pending: Bool = false) -> Mango9LogoutRecord {
		Mango9LogoutRecord(identity: identity ?? self.identity, voipToken: token ?? self.token,
			provider: Mango9Configuration.applePushProvider, topic: Mango9Configuration.applePushParam,
			cleanupToken: "fixture-cleanup-capability", pendingLogout: pending)
	}
	private func session() -> Mango9Session {
		Mango9Session(crmId: "fixture", crmBaseUrl: "https://crm.example.com", crmApiBaseUrl: "https://crm.example.com/api",
			userId: "1", parentClientId: "1", role: "agent", loginId: "fixture", displayName: nil,
			accessToken: "fixture-access-token", refreshToken: "fixture-refresh-token", smsChatApi: "https://chat.example.com",
			connectWebsocket: "wss://chat.example.com", enrollmentExpiresAt: .distantFuture, sipIdentity: identity)
	}
	private func coordinator(_ memory: Memory) -> Mango9LogoutCoordinator {
		Mango9LogoutCoordinator(read: { memory.records }, write: {
			if memory.refusesWrite { throw Mango9LogoutError.secureStorage }
			memory.records = $0
		}, send: { request in
			memory.requests.append(request)
			if memory.fails { throw URLError(.notConnectedToInternet) }
			let body = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: String]
			var data: [String: Any] = ["completed": true]
			if request.url!.path.hasSuffix("push-lease") {
				XCTAssertTrue(memory.records.contains { $0.id.uuidString.lowercased() == body["generation"] },
					"The retry generation must be durable before network I/O")
				data = ["cleanup_token": "fixture-issued-capability", "generation": body["generation"]!]
			}
			return (try JSONSerialization.data(withJSONObject: ["success": true, "data": data]),
				HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
		}, accountExists: { _ in memory.accountPresent })
	}

	func testOfflineLogoutPersistsWithoutNeedingPasswordOrJWT() async throws {
		let memory = Memory(); memory.records = [record()]; memory.fails = true
		try await coordinator(memory).prepareLogout(identity: identity, token: token, session: nil)
		XCTAssertTrue(memory.records[0].pendingLogout)
		XCTAssertTrue(memory.requests.isEmpty)
		let json = String(data: try JSONEncoder().encode(memory.records), encoding: .utf8)!
		XCTAssertFalse(json.contains("accessToken"))
		XCTAssertFalse(json.contains("refreshToken"))
	}

	func testRetrySurvivesCoordinatorRestart() async throws {
		let memory = Memory(); memory.records = [record(pending: true)]; memory.fails = true
		await coordinator(memory).retryPending()
		XCTAssertEqual(memory.records.count, 1)
		memory.fails = false
		await coordinator(memory).retryPending()
		XCTAssertTrue(memory.records.isEmpty)
		XCTAssertEqual(memory.requests.count, 2)
	}

	func testOtherAccountIsNotRemoved() async throws {
		let memory = Memory(); let other = record(identity: "sip:202@other.example.com")
		memory.records = [record(), other]
		let coordinator = coordinator(memory)
		try await coordinator.prepareLogout(identity: identity, token: token, session: nil)
		await coordinator.retryPending()
		XCTAssertEqual(memory.records, [other])
	}

	func testRotatedTokensAreAllQueuedForThisAccount() async throws {
		let memory = Memory(); memory.records = [record(), record(token: String(repeating: "b", count: 64))]
		let coordinator = coordinator(memory)
		try await coordinator.prepareLogout(identity: identity, token: token, session: nil)
		XCTAssertTrue(memory.records.allSatisfy(\.pendingLogout))
		await coordinator.retryPending()
		XCTAssertTrue(memory.records.isEmpty)
		XCTAssertEqual(memory.requests.count, 2)
	}

	func testSecureStorageFailurePreventsLogoutIntent() async {
		let memory = Memory(); memory.records = [record()]; memory.refusesWrite = true
		do {
			try await coordinator(memory).prepareLogout(identity: identity, token: token, session: nil)
			XCTFail("Must not continue local logout without durable intent")
		} catch {}
		XCTAssertFalse(memory.records[0].pendingLogout)
	}

	func testGracePeriodCannotEraseDurableIntent() async {
		let memory = Memory(); memory.records = [record(pending: true)]; memory.accountPresent = true
		await coordinator(memory).retryPending()
		XCTAssertEqual(memory.records.count, 1)
		XCTAssertTrue(memory.requests.isEmpty)
	}

	func testFreshLoginRevokesOldLeaseBeforeActivatingNewGeneration() async throws {
		let memory = Memory(); let old = record(pending: true); memory.records = [old]
		try await coordinator(memory).prepareLogin(identity: identity, token: token, session: session())
		XCTAssertEqual(memory.requests.map { $0.url!.lastPathComponent }, ["logout", "push-lease"])
		XCTAssertEqual(memory.records.count, 1)
		XCTAssertNotEqual(memory.records[0].id, old.id)
		XCTAssertFalse(memory.records[0].pendingLogout)
	}

	func testLoginDoesNotBypassOfflineCleanup() async {
		let memory = Memory(); memory.records = [record(pending: true)]; memory.fails = true
		do {
			try await coordinator(memory).prepareLogin(identity: identity, token: token, session: session())
			XCTFail("A previous cleanup must not race new registration")
		} catch {}
		XCTAssertEqual(memory.requests.map { $0.url!.lastPathComponent }, ["logout"])
		XCTAssertTrue(memory.records[0].pendingLogout)
	}

	func testUnknownLeaseFailsClosedUntilOnlineBootstrap() async {
		let memory = Memory()
		do {
			try await coordinator(memory).prepareLogout(identity: identity, token: token, session: nil)
			XCTFail("No device-scoped cleanup authority")
		} catch {}
		XCTAssertTrue(memory.records.isEmpty)
	}

	func testLeaseCreationIsIdempotentAndNeverSendsSIPCredentials() async throws {
		let memory = Memory(); let coordinator = coordinator(memory)
		try await coordinator.prepareLogin(identity: identity, token: token, session: session())
		try await coordinator.prepareLogin(identity: identity, token: token, session: session())
		XCTAssertEqual(memory.requests.count, 1)
		let body = String(data: memory.requests[0].httpBody!, encoding: .utf8)!
		XCTAssertFalse(body.contains("password"))
		XCTAssertFalse(body.contains("ha1"))
		XCTAssertFalse(body.contains("refresh"))
	}

	func testLostActivationResponseReusesPersistedGeneration() async throws {
		let memory = Memory(); memory.fails = true
		do { try await coordinator(memory).prepareLogin(identity: identity, token: token, session: session()) }
		catch {}
		let generation = try XCTUnwrap(memory.records.first?.id)
		memory.fails = false
		try await coordinator(memory).prepareLogin(identity: identity, token: token, session: session())
		XCTAssertEqual(memory.records[0].id, generation)
		XCTAssertNotNil(memory.records[0].cleanupToken)
	}

	func testInterruptedOlderTokenActivationDoesNotBlockLogoutForever() async throws {
		let memory = Memory()
		var old = record(token: String(repeating: "b", count: 64))
		old.cleanupToken = nil
		memory.records = [old, record()]
		let coordinator = coordinator(memory)
		try await coordinator.prepareLogout(identity: identity, token: token, session: session())
		XCTAssertEqual(memory.requests.count, 1)
		XCTAssertTrue(memory.records.allSatisfy { $0.pendingLogout && $0.cleanupToken != nil })
		let body = try JSONSerialization.jsonObject(with: memory.requests[0].httpBody!) as! [String: String]
		XCTAssertEqual(body["voip_token"], old.voipToken)
		await coordinator.retryPending()
		XCTAssertTrue(memory.records.isEmpty)
	}

	func testFailedAcknowledgementPersistenceKeepsRetryWork() async {
		let memory = Memory(); memory.records = [record(pending: true)]; memory.refusesWrite = true
		await coordinator(memory).retryPending()
		XCTAssertEqual(memory.records.count, 1)
		memory.refusesWrite = false
		await coordinator(memory).retryPending()
		XCTAssertTrue(memory.records.isEmpty)
	}

	func testCleanupUsesOnlyCapabilityAndFixedHTTPSHost() async {
		let memory = Memory(); memory.records = [record(pending: true)]
		await coordinator(memory).retryPending()
		let request = memory.requests.first!
		XCTAssertEqual(request.url?.host, Mango9Configuration.provisioningHost)
		XCTAssertEqual(request.url?.scheme, "https")
		XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-cleanup-capability")
		XCTAssertEqual(String(data: request.httpBody!, encoding: .utf8), "{}")
	}

	func testTokenNormalizationRejectsMalformedTokens() {
		XCTAssertEqual(Mango9LogoutCoordinator.normalizedVoIPToken(token.uppercased() + ":voip"), token)
		for raw in ["", token + ":voip:voip", token + "&remote", token + "/", String(repeating: "z", count: 64)] {
			XCTAssertNil(Mango9LogoutCoordinator.normalizedVoIPToken(raw))
		}
	}
}
