/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
 *
 * This file is part of linphone-iphone.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import XCTest
@testable import LinphoneApp

final class Mango9MultiAccountTests: XCTestCase {
	func testSMSMutePreferencePersistsAndSeparatesAccounts() {
		let phone = "8185550199"
		let firstIdentity = "sip:700@tenant-a.example.com"
		let secondIdentity = "sip:700@tenant-b.example.com"
		defer {
			Mango9SMSMutePreferences.setMuted(false, phone: phone, identity: firstIdentity)
			Mango9SMSMutePreferences.setMuted(false, phone: phone, identity: secondIdentity)
		}

		Mango9SMSMutePreferences.setMuted(false, phone: phone, identity: firstIdentity)
		Mango9SMSMutePreferences.setMuted(false, phone: phone, identity: secondIdentity)
		Mango9SMSMutePreferences.setMuted(true, phone: phone, identity: firstIdentity)

		XCTAssertTrue(
			Mango9SMSMutePreferences.isMuted(phone: phone, identity: firstIdentity)
		)
		XCTAssertFalse(
			Mango9SMSMutePreferences.isMuted(phone: phone, identity: secondIdentity)
		)
	}

	func testSMSMutePreferenceNormalizesPhoneNumbers() {
		let identity = "sip:701@tenant.example.com"
		defer {
			Mango9SMSMutePreferences.setMuted(
				false,
				phone: "+1 (818) 555-0188",
				identity: identity
			)
		}

		Mango9SMSMutePreferences.setMuted(
			true,
			phone: "818-555-0188",
			identity: identity
		)
		XCTAssertTrue(
			Mango9SMSMutePreferences.isMuted(
				phone: "+1 (818) 555-0188",
				identity: identity
			)
		)
	}

	func testRegistrationErrorDistinguishesPushFromCredentials() {
		let pushFailure = Mango9RegistrationFailure(
			sipMessage: "555 Push Notification Service Not Supported"
		)
		let credentialFailure = Mango9RegistrationFailure(
			sipMessage: "403 Forbidden"
		)

		XCTAssertEqual(pushFailure.kind, .pushConfiguration)
		XCTAssertEqual(pushFailure.sipCode, 555)
		XCTAssertTrue(
			pushFailure.userMessage(line: "700").contains("call notifications")
		)
		XCTAssertEqual(credentialFailure.kind, .credentials)
		XCTAssertTrue(
			credentialFailure.userMessage(line: "700").contains("authenticate")
		)
	}

	func testRegistrationErrorUsesFriendlyRetryWording() {
		let networkFailure = Mango9RegistrationFailure(
			sipMessage: "408 Request Timeout"
		)
		let serverFailure = Mango9RegistrationFailure(
			sipMessage: "503 Service Unavailable"
		)

		XCTAssertEqual(networkFailure.kind, .network)
		XCTAssertTrue(
			networkFailure.userMessage(line: "100").contains("keep retrying")
		)
		XCTAssertEqual(serverFailure.kind, .serviceUnavailable)
		XCTAssertTrue(
			serverFailure.userMessage(line: nil).contains("retry automatically")
		)
	}

	func testEnrollmentXMLIsParsedWithoutApplyingProxyZero() async throws {
		let xml = """
		<?xml version="1.0" encoding="UTF-8"?>
		<config>
		  <section name="proxy_0">
		    <entry name="reg_identity" overwrite="true">sip:200@tenant.example.com</entry>
		  </section>
		  <section name="auth_info_0">
		    <entry name="username" overwrite="true">200</entry>
		    <entry name="domain" overwrite="true">tenant.example.com</entry>
		    <entry name="ha1" overwrite="true">example-digest</entry>
		    <entry name="realm" overwrite="true">tenant.example.com</entry>
		  </section>
		</config>
		"""

		let enrollment = try await Mango9AccountProvisioner.parseEnrollment(
			Data(xml.utf8)
		)

		XCTAssertEqual(enrollment.identity, "sip:200@tenant.example.com")
		XCTAssertEqual(enrollment.username, "200")
		XCTAssertEqual(enrollment.domain, "tenant.example.com")
		XCTAssertEqual(enrollment.realm, "tenant.example.com")
		XCTAssertEqual(enrollment.ha1, "example-digest")
	}

	func testSIPIdentityNormalizationSeparatesTenantAccounts() {
		XCTAssertEqual(
			Mango9SessionStore.normalizedIdentity(
				"<SIP:100@TENANT-A.EXAMPLE.COM>;transport=tls"
			),
			"sip:100@tenant-a.example.com"
		)
		XCTAssertNotEqual(
			Mango9SessionStore.normalizedIdentity(
				"sip:100@tenant-a.example.com"
			),
			Mango9SessionStore.normalizedIdentity(
				"sip:100@tenant-b.example.com"
			)
		)
		XCTAssertEqual(
			Mango9SessionStore.normalizedIdentity(
				"\"Tenant User\" <SIP:100@TENANT-A.EXAMPLE.COM>;transport=tls"
			),
			"sip:100@tenant-a.example.com"
		)
	}

	func testActiveIdentityDoesNotMatchAnotherTenant() {
		let previousIdentity = Mango9SessionStore.activeIdentity
		defer {
			Mango9SessionStore.activate(sipIdentity: previousIdentity)
		}

		Mango9SessionStore.activate(
			sipIdentity: "sip:100@tenant-a.example.com"
		)

		XCTAssertTrue(
			Mango9SessionStore.isActive(
				sipIdentity: "sip:100@tenant-a.example.com"
			)
		)
		XCTAssertFalse(
			Mango9SessionStore.isActive(
				sipIdentity: "sip:100@tenant-b.example.com"
			)
		)
	}

	func testRemovedAccountCannotBeReactivatedByStalePush() throws {
		let identity = "sip:700@\(UUID().uuidString.lowercased()).example.com"
		let session = Mango9Session(
			crmId: "test-crm",
			crmBaseUrl: "https://example.com",
			crmApiBaseUrl: "https://example.com/api",
			userId: "test-user",
			parentClientId: "test-parent",
			role: "user",
			loginId: "test@example.com",
			displayName: "Test User",
			accessToken: "test-access-token",
			refreshToken: "test-refresh-token",
			smsChatApi: "https://example.com/sms_chat_api",
			connectWebsocket: "wss://example.com/connect",
			enrollmentExpiresAt: Date().addingTimeInterval(300),
			sipIdentity: identity
		)
		defer {
			Mango9SessionStore.remove(for: identity)
		}

		try Mango9SessionStore.save(
			session,
			for: identity,
			persist: false
		)
		XCTAssertTrue(Mango9SessionStore.hasSession(for: identity))

		Mango9SessionStore.remove(for: identity)

		XCTAssertFalse(Mango9SessionStore.hasSession(for: identity))
	}

	func testTeamChatM4AAttachmentUsesAudioPlayer() throws {
		let media = try XCTUnwrap(
			Mango9ChatMedia.parse(
				"https://cdn.example.com/messages/voice.m4a"
				+ "?signature=test&ffName=Team%20voice.m4a"
				+ "&ffType=audio%2Fmp4&ffExt=m4a"
			).first
		)

		XCTAssertEqual(media.kind, .audio)
		XCTAssertEqual(media.name, "Team voice.m4a")
		XCTAssertEqual(media.mimeType, "audio/mp4")
		XCTAssertEqual(media.url.pathExtension, "m4a")
	}

	func testLatestLeadFilterRequestWinsWhilePreviousRequestLoads() async throws {
		let previousIdentity = Mango9SessionStore.activeIdentity
		let identity = "sip:filter-test@\(UUID().uuidString.lowercased()).example.com"
		let session = Mango9Session(
			crmId: "test-crm",
			crmBaseUrl: "https://example.com",
			crmApiBaseUrl: "https://example.com/api",
			userId: "test-user",
			parentClientId: "test-parent",
			role: "user",
			loginId: "test@example.com",
			displayName: "Test User",
			accessToken: "test-access-token",
			refreshToken: "test-refresh-token",
			smsChatApi: "https://example.com/sms_chat_api",
			connectWebsocket: "wss://example.com/connect",
			enrollmentExpiresAt: Date().addingTimeInterval(300),
			sipIdentity: identity
		)
		defer {
			Mango9SessionStore.remove(for: identity)
			Mango9SessionStore.activate(sipIdentity: previousIdentity)
		}

		try Mango9SessionStore.save(session, for: identity, persist: false)
		Mango9SessionStore.activate(sipIdentity: identity)
		let probe = Mango9LeadFilterProbe()
		let viewModel = await Mango9LeadsViewModel {
			_, _, status, _, _, _ in
			await probe.load(status: status)
		}

		let firstReload = Task {
			await viewModel.setStatus("New")
		}
		await probe.waitForCallCount(1)

		await MainActor.run {
			viewModel.selectedDateFilter = .today
		}
		let latestReload = Task {
			await viewModel.setStatus("Qualified")
		}
		await Task.yield()
		await probe.releaseFirstCall()

		await firstReload.value
		await latestReload.value

		let recordedStatuses = await probe.recordedStatuses()
		XCTAssertEqual(recordedStatuses, ["New", "Qualified"])
		let applied = await MainActor.run {
			(
				viewModel.leads.first?.status,
				viewModel.filteredLeads.count,
				viewModel.total
			)
		}
		XCTAssertEqual(applied.0, "Qualified")
		XCTAssertEqual(applied.1, 1)
		XCTAssertEqual(applied.2, 1)
	}

	func testLineIdentityCacheIsSeparatedBySIPAccount() {
		let suffix = UUID().uuidString.lowercased()
		let firstAccount = "sip:100@\(suffix)-a.example.com"
		let secondAccount = "sip:100@\(suffix)-b.example.com"
		defer {
			Mango9LineIdentityStore.clear(sipIdentity: firstAccount)
			Mango9LineIdentityStore.clear(sipIdentity: secondAccount)
		}

		Mango9LineIdentityStore.save(
			Mango9LineIdentity(
				extensionNumber: "100",
				activeNumber: "2025550101"
			),
			sipIdentity: firstAccount
		)
		Mango9LineIdentityStore.save(
			Mango9LineIdentity(
				extensionNumber: "200",
				activeNumber: "2025550199"
			),
			sipIdentity: secondAccount
		)

		XCTAssertEqual(
			Mango9LineIdentityStore.load(
				sipIdentity: firstAccount
			)?.extensionNumber,
			"100"
		)
		XCTAssertEqual(
			Mango9LineIdentityStore.load(
				sipIdentity: firstAccount
			)?.activeNumber,
			"202-555-0101"
		)
		XCTAssertEqual(
			Mango9LineIdentityStore.load(
				sipIdentity: secondAccount
			)?.extensionNumber,
			"200"
		)
		XCTAssertEqual(
			Mango9LineIdentityStore.load(
				sipIdentity: secondAccount
			)?.activeNumber,
			"202-555-0199"
		)
	}

	func testSideMenuKeepsExtensionVisibleWithoutActiveNumber() {
		XCTAssertEqual(
			SideMenuAccountRow.accountLineLabel(
				extensionNumber: "700",
				activeNumber: "",
				fallback: "Gevorg Stepanyan"
			),
			"Ext 700"
		)
		XCTAssertEqual(
			SideMenuAccountRow.accountLineLabel(
				extensionNumber: "700",
				activeNumber: "818-900-7897",
				fallback: "Gevorg Stepanyan"
			),
			"818-900-7897 · Ext 700"
		)
	}

	func testLegacyLineIdentityMigratesToDefaultSIPAccount() {
		let defaults = UserDefaults.standard
		let migrationKey = "mango9_line_identity_legacy_migrated_v1"
		let account = "sip:700@\(UUID().uuidString.lowercased()).example.com"
		let previousExtension = defaults.object(
			forKey: Mango9LineIdentityStore.extensionKey
		)
		let previousActiveNumber = defaults.object(
			forKey: Mango9LineIdentityStore.activeNumberKey
		)
		let previousMigration = defaults.object(forKey: migrationKey)
		defer {
			Mango9LineIdentityStore.clear(sipIdentity: account)
			restore(
				previousExtension,
				forKey: Mango9LineIdentityStore.extensionKey,
				in: defaults
			)
			restore(
				previousActiveNumber,
				forKey: Mango9LineIdentityStore.activeNumberKey,
				in: defaults
			)
			restore(previousMigration, forKey: migrationKey, in: defaults)
		}

		defaults.removeObject(forKey: migrationKey)
		defaults.set("700", forKey: Mango9LineIdentityStore.extensionKey)
		defaults.set("8189007897", forKey: Mango9LineIdentityStore.activeNumberKey)

		Mango9LineIdentityStore.migrateLegacyActiveValuesIfNeeded(
			sipIdentity: account
		)

		let migrated = Mango9LineIdentityStore.load(sipIdentity: account)
		XCTAssertEqual(migrated?.extensionNumber, "700")
		XCTAssertEqual(migrated?.activeNumber, "818-900-7897")
		XCTAssertTrue(defaults.bool(forKey: migrationKey))
	}

	private func restore(
		_ value: Any?,
		forKey key: String,
		in defaults: UserDefaults
	) {
		if let value {
			defaults.set(value, forKey: key)
		} else {
			defaults.removeObject(forKey: key)
		}
	}

	func testOnlyEnrollmentURLsAreMarkedOneTime() {
		let enrollmentURL = Mango9Configuration.provisioningBaseURL
			.appendingPathComponent("v1/enroll/example-token")
			.absoluteString
		let reusableConfigurationURL = Mango9Configuration.provisioningBaseURL
			.appendingPathComponent("config/device.xml")
			.absoluteString

		XCTAssertTrue(
			Mango9Configuration.isOneTimeEnrollmentURL(
				enrollmentURL
			)
		)
		XCTAssertFalse(
			Mango9Configuration.isOneTimeEnrollmentURL(
				reusableConfigurationURL
			)
		)
	}

	func testApplePushProviderUsesSignedAPSEnvironment() {
		XCTAssertEqual(
			Mango9Configuration.applePushProvider(forAPSEnvironment: "development"),
			"apns.dev"
		)
		XCTAssertEqual(
			Mango9Configuration.applePushProvider(forAPSEnvironment: "production"),
			"apns"
		)
		XCTAssertEqual(
			Mango9Configuration.applePushProvider(forAPSEnvironment: nil),
			"apns"
		)
	}
}

private actor Mango9LeadFilterProbe {
	private var statuses: [String] = []
	private var firstCallRelease: CheckedContinuation<Void, Never>?

	func load(status: String) async -> Mango9LeadListPayload {
		statuses.append(status)
		if statuses.count == 1 {
			await withCheckedContinuation { continuation in
				firstCallRelease = continuation
			}
		}

		return Mango9LeadListPayload(
			leads: [
				Mango9Lead(
					id: statuses.count,
					ownerUserId: 1,
					ownerName: "Owner",
					name: "(status) Lead",
					phone: "2025550101",
					email: "lead@example.com",
					status: status,
					source: "Test",
					createdAt: "server-filtered-timestamp"
				)
			],
			pagination: .init(total: 1, page: 1, limit: 50, pages: 1)
		)
	}

	func waitForCallCount(_ expectedCount: Int) async {
		while statuses.count < expectedCount {
			await Task.yield()
		}
	}

	func releaseFirstCall() {
		firstCallRelease?.resume()
		firstCallRelease = nil
	}

	func recordedStatuses() -> [String] {
		statuses
	}
}
