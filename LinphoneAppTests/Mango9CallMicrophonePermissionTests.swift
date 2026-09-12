import AVFoundation
import UIKit
import XCTest
@testable import LinphoneApp

@MainActor
final class Mango9CallMicrophonePermissionTests: XCTestCase {
	func testGrantedPermissionContinuesWithoutPrompt() {
		let gate = Mango9CallMicrophonePermission(
			status: { .granted },
			requestPermission: { _ in XCTFail("Must not prompt again") },
			presentDenied: { _ in XCTFail("Must not warn when granted") }
		)
		var results: [Bool] = []
		gate.requestForOutgoingCall { results.append($0) }
		XCTAssertEqual(results, [true])
	}

	func testDeniedPermissionBlocksDialingAndOffersOneWarning() {
		var warnings = 0
		let gate = Mango9CallMicrophonePermission(
			status: { .denied },
			requestPermission: { _ in XCTFail("iOS cannot ask again after denial") },
			presentDenied: { _ in warnings += 1 }
		)
		var results: [Bool] = []
		gate.requestForOutgoingCall { results.append($0) }
		gate.requestForOutgoingCall { results.append($0) }
		XCTAssertEqual(results, [false, false])
		XCTAssertEqual(warnings, 1)
	}

	func testFirstRequestWaitsForNativePermissionAndContinuesOnlyOnce() {
		var reply: (@MainActor (Bool) -> Void)?
		var prompts = 0
		let gate = Mango9CallMicrophonePermission(
			status: { .undetermined },
			requestPermission: { prompts += 1; reply = $0 },
			presentDenied: { _ in XCTFail("Granted request must not warn") }
		)
		var results: [Bool] = []
		gate.requestForOutgoingCall { results.append($0) }
		XCTAssertTrue(results.isEmpty, "No outgoing call until the user answers")
		reply?(true)
		reply?(true)
		XCTAssertEqual(prompts, 1)
		XCTAssertEqual(results, [true])
	}

	func testDenyingNativePromptDoesNotPlaceCall() {
		var reply: (@MainActor (Bool) -> Void)?
		var warnings = 0
		let gate = Mango9CallMicrophonePermission(
			status: { .undetermined }, requestPermission: { reply = $0 },
			presentDenied: { _ in warnings += 1 }
		)
		var results: [Bool] = []
		gate.requestForOutgoingCall { results.append($0) }
		reply?(false)
		XCTAssertEqual(results, [false])
		XCTAssertEqual(warnings, 1)
	}

	func testRepeatedTapsDoNotQueueMultipleCallsDuringPermissionRequest() {
		var reply: (@MainActor (Bool) -> Void)?
		var prompts = 0
		let gate = Mango9CallMicrophonePermission(
			status: { .undetermined },
			requestPermission: { prompts += 1; reply = $0 },
			presentDenied: { _ in XCTFail("No warning after grant") }
		)
		var first: [Bool] = []
		var second: [Bool] = []
		gate.requestForOutgoingCall { first.append($0) }
		gate.requestForOutgoingCall { second.append($0) }
		reply?(true)
		XCTAssertEqual(first, [true])
		XCTAssertEqual(second, [false])
		XCTAssertEqual(prompts, 1)
	}

	func testSettingsChangesAreReadFreshButNeverAutomaticallyDial() {
		var permission: AVAudioSession.RecordPermission = .denied
		var dismiss: (@MainActor () -> Void)?
		let gate = Mango9CallMicrophonePermission(
			status: { permission },
			requestPermission: { _ in XCTFail("Settings grants do not need another prompt") },
			presentDenied: { dismiss = $0 }
		)
		var results: [Bool] = []
		gate.requestForOutgoingCall { results.append($0) }
		dismiss?()
		permission = .granted
		XCTAssertEqual(results, [false], "Returning from Settings must not auto-dial")
		gate.requestForOutgoingCall { results.append($0) }
		XCTAssertEqual(results, [false, true])
	}

	func testCancelAllowsAnotherExplanationOnNextAttempt() {
		var warnings = 0
		var dismiss: (@MainActor () -> Void)?
		let gate = Mango9CallMicrophonePermission(
			status: { .denied }, requestPermission: { _ in XCTFail("Already denied") },
			presentDenied: { warnings += 1; dismiss = $0 }
		)
		var results: [Bool] = []
		gate.requestForOutgoingCall { results.append($0) }
		dismiss?()
		gate.requestForOutgoingCall { results.append($0) }
		XCTAssertEqual(results, [false, false])
		XCTAssertEqual(warnings, 2)
	}

	func testNativeAlertExplainsAudibilityAndRenders() async throws {
		let alert = Mango9CallMicrophonePermission.makeDeniedAlert(
			openSettings: { XCTFail("Rendering must not open Settings") }, didDismiss: {}
		)
		XCTAssertEqual(alert.preferredStyle, .alert)
		XCTAssertEqual(alert.title, "Microphone access is off")
		XCTAssertTrue(alert.message?.contains("won’t be able to hear you") == true)
		XCTAssertEqual(alert.actions.map(\.title), ["Cancel", "Open Settings"])
		XCTAssertEqual(alert.actions.first?.style, .cancel)
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let previousWindow = scene.windows.first { $0.isKeyWindow }
		let window = UIWindow(windowScene: scene)
		window.frame = scene.coordinateSpace.bounds
		let host = UIViewController()
		host.view.backgroundColor = .systemBackground
		window.rootViewController = host
		window.makeKeyAndVisible()
		defer { window.isHidden = true; previousWindow?.makeKey() }
		try await Task.sleep(nanoseconds: 250_000_000)
		let presented = expectation(description: "Native alert finished presenting")
		host.present(alert, animated: true) { presented.fulfill() }
		await fulfillment(of: [presented], timeout: 5)
		XCTAssertTrue(host.presentedViewController === alert)
		XCTAssertNotNil(alert.view.window)
		XCTAssertGreaterThan(alert.view.alpha, 0)
		// Capture the alert itself: UIKit may use another presentation window, and
		// full-screen XCUIScreen capture requires a UI-test runner, not a unit-test host.
		alert.view.layoutIfNeeded()
		XCTAssertGreaterThan(alert.view.bounds.width, 0)
		XCTAssertGreaterThan(alert.view.bounds.height, 0)
		let image = UIGraphicsImageRenderer(bounds: alert.view.bounds).image { _ in
			alert.view.drawHierarchy(in: alert.view.bounds, afterScreenUpdates: true)
		}
		let attachment = XCTAttachment(image: image)
		attachment.name = "Native microphone permission explanation"
		attachment.lifetime = .keepAlways
		add(attachment)
		host.dismiss(animated: false)
	}
}
