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

final class Mango9PushCallerIdentityTests: XCTestCase {
	func testParsesFlexisipCallerIdentityFromPush() {
		let payload = #"{"aps":{"loc-key":"IC_MSG","loc-args":["8189164300"],"call-id":"call-123"},"from-uri":"sip:8189164300@manushak.mango9.com","display-name":"8189164300"}"#

		XCTAssertEqual(
			Mango9PushCallerIdentity.parse(payload: payload),
			Mango9PushCallerIdentity(
				callId: "call-123",
				handle: "+18189164300",
				displayName: "8189164300"
			)
		)
	}

	func testParsesCallerIdentityFromAlertLocArgs() {
		let payload = #"{"aps":{"alert":{"loc-key":"IC_MSG","loc-args":["sip:+18184885588@manushak.mango9.com"]},"call-id":"call-456"}}"#

		XCTAssertEqual(
			Mango9PushCallerIdentity.parse(payload: payload),
			Mango9PushCallerIdentity(
				callId: "call-456",
				handle: "+18184885588",
				displayName: "818-488-5588"
			)
		)
	}

	func testRejectsPushWithoutCallerIdentity() {
		let payload = #"{"aps":{"call-id":"call-789"}}"#

		XCTAssertNil(Mango9PushCallerIdentity.parse(payload: payload))
	}

	func testRejectsAnonymousPlaceholderIdentity() {
		let invalidPayload = #"{"aps":{"call-id":"call-999"},"from-uri":"sip:anonymous@anonymous.invalid"}"#
		let invitePayload = #"{"aps":{"call-id":"call-998"},"from-uri":"sip:anonymous@anonymous.invite"}"#
		let misspelledPayload = #"{"aps":{"call-id":"call-997"},"from-uri":"anonimous@anonimous.invite"}"#

		XCTAssertNil(Mango9PushCallerIdentity.parse(payload: invalidPayload))
		XCTAssertNil(Mango9PushCallerIdentity.parse(payload: invitePayload))
		XCTAssertNil(Mango9PushCallerIdentity.parse(payload: misspelledPayload))
	}
}
