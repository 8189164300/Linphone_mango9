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
	private let now = Date(timeIntervalSince1970: 1_000)
	private let pushed = Mango9PushCallerIdentity(callId: "incoming-a", handle: "+12025550123", displayName: "Caller A")
	private let sip = Mango9IncomingCallerPresentation(handle: "+12025550124", displayName: "SIP caller", source: .sip)

	private func resolve(_ store: Mango9IncomingCallerStore, callId: String = "incoming-a", token: String = "native-a",
		account: String? = nil, placeholder: Bool = true, withheld: Bool = false,
		at: Date? = nil) -> Mango9IncomingCallerPresentation {
		store.resolve(callId: callId, token: token, account: account, placeholder: placeholder,
			sip: placeholder ? .unknown : sip, withheld: withheld, now: at ?? now)
	}

	func testPushSurvivesForegroundRefreshAndPendingTTLWhileCallIsActive() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		let initial = resolve(store)
		XCTAssertEqual(initial.displayName, "Caller A")
		XCTAssertEqual(initial.subtitle, "202-555-0123")
		XCTAssertEqual(resolve(store, at: now.addingTimeInterval(180)), initial)
	}

	func testPendingPushExpiresBeforeItCanAttachToAnUnrelatedLateCall() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		XCTAssertEqual(resolve(store, at: now.addingTimeInterval(121)), .unknown)
	}

	func testPushArrivingAfterPlaceholderUpdatesSameCall() {
		let store = Mango9IncomingCallerStore()
		XCTAssertEqual(resolve(store), .unknown)
		XCTAssertTrue(store.cache(pushed, now: now))
		XCTAssertEqual(resolve(store).source, .push)
	}

	func testRealSIPReplacesPushAndLatePushCannotOverwriteIt() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		_ = resolve(store)
		XCTAssertEqual(resolve(store, account: "sip:agent@example.test", placeholder: false), sip)
		XCTAssertFalse(store.cache(pushed, now: now))
		XCTAssertEqual(resolve(store), sip, "A stale placeholder callback cannot downgrade real SIP identity")
	}

	func testActualAnonymousINVITEMustNotFallbackToVisiblePushIdentity() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		_ = resolve(store)
		XCTAssertEqual(resolve(store, placeholder: false, withheld: true), .withheld)
		XCTAssertFalse(store.cache(pushed, now: now))
		XCTAssertEqual(resolve(store), .withheld)
		XCTAssertEqual(Mango9IncomingCallerPresentation.withheld.subtitle, "")
	}

	func testRealINVITEWithoutUsableIdentityDoesNotRevealPushAsFallback() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		_ = resolve(store)
		XCTAssertEqual(store.resolve(callId: pushed.callId, token: "native-a", account: nil, placeholder: false,
			sip: .unknown, withheld: false, now: now), .unknown)
		XCTAssertFalse(store.cache(pushed, now: now))
	}

	func testSameCallIDCannotMoveIdentityToAnotherNativeCall() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		_ = resolve(store)
		XCTAssertEqual(resolve(store, token: "native-b"), .unknown)
		XCTAssertEqual(resolve(store).displayName, "Caller A")
		store.finish(callId: pushed.callId, token: "native-b", now: now)
		XCTAssertEqual(resolve(store).displayName, "Caller A")
	}

	func testTwoAccountsAndTwoCallsRemainIndependent() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		store.cache(.init(callId: "incoming-b", handle: "+12025550125", displayName: "Caller B"), now: now)
		XCTAssertEqual(resolve(store, account: "sip:agent@one.example.test").displayName, "Caller A")
		XCTAssertEqual(resolve(store, callId: "incoming-b", token: "native-b", account: "sip:agent@two.example.test").displayName, "Caller B")
		XCTAssertEqual(resolve(store, account: "sip:agent@two.example.test"), .unknown)
		XCTAssertNil(store.finalPresentation(callId: pushed.callId, token: "native-a", account: "sip:agent@two.example.test"))
	}

	func testUnknownRecipientDoesNotBypassAccountOwnership() {
		let store = Mango9IncomingCallerStore()
		var identity = pushed
		identity.recipient = "sip:agent@one.example.test"
		store.cache(identity, now: now)
		XCTAssertEqual(resolve(store), .unknown)
	}

	func testEmptyCallIDNeverCorrelatesTwoCalls() {
		let store = Mango9IncomingCallerStore()
		XCTAssertFalse(store.cache(.init(callId: "", handle: "+12025550123", displayName: "Caller"), now: now))
		XCTAssertEqual(resolve(store, callId: ""), .unknown)
	}

	func testExplicitRecipientMustMatchKnownAccount() {
		let store = Mango9IncomingCallerStore()
		var identity = pushed
		identity.recipient = "sip:agent@one.example.test"
		store.cache(identity, now: now)
		XCTAssertEqual(resolve(store, account: "sip:agent@two.example.test"), .unknown)
		let correct = Mango9IncomingCallerStore()
		correct.cache(identity, now: now)
		XCTAssertEqual(resolve(correct, account: "sip:agent@ONE.example.test").source, .push)
	}

	func testEndRemovesIdentityAndRejectsDuplicatePush() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		_ = resolve(store)
		XCTAssertEqual(store.finalPresentation(callId: pushed.callId, token: "native-a")?.displayName, "Caller A")
		store.finish(callId: pushed.callId, token: "native-a", now: now)
		XCTAssertNil(store.finalPresentation(callId: pushed.callId, token: "native-a"))
		XCTAssertFalse(store.cache(pushed, now: now.addingTimeInterval(10)))
		XCTAssertEqual(resolve(store, token: "new-native-call"), .unknown)
	}

	func testConflictingDuplicatePushCannotChangeFirstIdentity() {
		let store = Mango9IncomingCallerStore()
		store.cache(pushed, now: now)
		let duplicate = Mango9PushCallerIdentity(callId: pushed.callId, handle: "+12025550199", displayName: "Different caller")
		XCTAssertFalse(store.cache(duplicate, now: now))
		XCTAssertEqual(resolve(store).displayName, "Caller A")
		XCTAssertFalse(store.cache(duplicate, now: now))
	}

	func testPrivacyMaskHeaderAndAnonymousFromAreRespected() {
		for mask: UInt in [1, 8, 9] {
			XCTAssertTrue(Mango9IncomingCallerStore.hasCallerPrivacy(mask: mask, header: "", user: "2025550123", name: nil))
		}
		XCTAssertTrue(Mango9IncomingCallerStore.hasCallerPrivacy(mask: 0, header: "id;critical", user: "2025550123", name: nil))
		XCTAssertTrue(Mango9IncomingCallerStore.hasCallerPrivacy(mask: 0, header: "", user: "anonymous", name: nil))
		XCTAssertFalse(Mango9IncomingCallerStore.hasCallerPrivacy(mask: 0, header: "none", user: "2025550123", name: "Caller"))
		XCTAssertFalse(Mango9IncomingCallerStore.hasCallerPrivacy(mask: 4, header: "session", user: "2025550123", name: nil))
	}

	func testWithheldFromCannotBeOverriddenByPushDisplayName() {
		XCTAssertNil(Mango9PushCallerIdentity.parse(payload: #"{"aps":{"call-id":"private-call"},"from-uri":"sip:anonymous@anonymous.invalid","display-name":"Visible name"}"#))
		XCTAssertNil(Mango9PushCallerIdentity.parse(payload: #"{"aps":{"call-id":"private-call"},"from-uri":"\"Hidden\" <sips:anonymous@anonymous.invalid>","display-name":"Visible name"}"#))
	}

	func testAccountKeysDoNotCollapseCaseSensitiveSIPUsers() {
		XCTAssertEqual(Mango9IncomingCallerStore.accountKey("Agent <sip:Agent@EXAMPLE.test;transport=tls>"), "Agent@example.test")
		XCTAssertNotEqual(Mango9IncomingCallerStore.accountKey("sip:Agent@example.test"), Mango9IncomingCallerStore.accountKey("sip:agent@example.test"))
	}
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
