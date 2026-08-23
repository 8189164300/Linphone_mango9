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
