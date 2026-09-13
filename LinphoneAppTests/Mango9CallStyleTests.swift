import SwiftUI
import XCTest
@testable import LinphoneApp

private final class CallStyleFixtureModel: CallViewModel {
	override func resetCallView() {}
	override func enforceEarpieceIfNeeded() {}
	override func orientationUpdate(orientation: UIDeviceOrientation) {}
}

@MainActor final class Mango9CallStyleTests: XCTestCase {
	func testLightPaletteAndSelectedControlContrast() {
		XCTAssertGreaterThan(luminance(Mango9CallStyle.canvas), 0.95)
		XCTAssertGreaterThan(luminance(Mango9CallStyle.tray), 0.85)
		XCTAssertGreaterThan(contrast(Mango9CallStyle.ink, Mango9CallStyle.canvas), 7)
		XCTAssertGreaterThan(contrast(Mango9CallStyle.controlForeground(active: false), Mango9CallStyle.controlBackground(active: false)), 4.5)
		XCTAssertGreaterThan(contrast(Mango9CallStyle.controlForeground(active: true), Mango9CallStyle.controlBackground(active: true)), 4.5)
		XCTAssertGreaterThan(contrast(Color(red: 1, green: 1, blue: 1), Mango9CallStyle.destructive), 3, "Hang-up symbol must remain legible")
	}

	func testCallScreenRemainsLightForConnectingMutedAndHeldCalls() async throws {
		let telecom = TelecomManager.shared
		let keys: [ReferenceWritableKeyPath<TelecomManager, Bool>] = [\.callStarted, \.callInProgress, \.callDisplayed, \.outgoingCallStarted, \.remoteConfVideo, \.isPausedByRemote, \.remainingCall]
		let previous = keys.map { telecom[keyPath: $0] }
		defer { for (key, value) in zip(keys, previous) { telecom[keyPath: key] = value } }
		telecom.callStarted = true; telecom.callInProgress = true; telecom.callDisplayed = true
		telecom.remoteConfVideo = false; telecom.isPausedByRemote = false; telecom.remainingCall = false
		let model = fixture()
		for state in ["Connecting", "Muted", "Held"] {
			telecom.outgoingCallStarted = state == "Connecting"
			model.micMutted = state == "Muted"
			model.isPaused = state == "Held"
			let view = CallView(fullscreenVideo: .constant(false), isShowStartCallFragment: .constant(false),
				isShowConversationFragment: .constant(false), isShowStartCallGroupPopup: .constant(false),
				isShowEditContactFragment: .constant(false), isShowScheduleMeetingFragment: .constant(false))
				.environmentObject(model).dynamicTypeSize(.xxxLarge)
			let image = try await snapshot(view, name: "Light call - \(state)")
			XCTAssertGreaterThan(try brightness(image, point: CGPoint(x: 8, y: 330)), 0.94)
			XCTAssertGreaterThan(try brightness(image, point: CGPoint(x: 8, y: image.size.height - 65)), 0.85)
		}
		XCTAssertNil(model.currentCall, "Style smoke tests never create a SIP call")
	}

	func testExpandedControlsAndAudioPickerRenderWithLightSurfaces() async throws {
		let model = fixture()
		model.micMutted = true; model.isPaused = true
		let expanded = GeometryReader { geometry in
			BottomSheetContent(geo: geometry, buttonSize: .constant(60), pointingUp: .constant(-1),
				currentOffset: .constant(UIScreen.main.bounds.height * 0.4), minBottomSheetHeight: 0.15, maxBottomSheetHeight: 0.4,
				optionsAudioRoute: .constant(2), optionsChangeLayout: .constant(2), showingDialer: .constant(false),
				audioRouteSheet: .constant(false), changeLayoutSheet: .constant(false), isShowStartCallFragment: .constant(false),
				isShowCallsListFragment: .constant(false), isShowParticipantsListFragment: .constant(false), imageAudioRoute: .constant("speaker-high"))
				.environmentObject(model)
		}.background(Mango9CallStyle.canvas)
		_ = try await snapshot(expanded, name: "Light call - expanded controls")
		let picker = AudioRouteBottomSheet(callViewModel: model, optionsAudioRoute: .constant(2))
		let image = try await snapshot(picker, name: "Light call - audio output", size: CGSize(width: 393, height: 320))
		XCTAssertGreaterThan(try brightness(image, point: CGPoint(x: 5, y: 160)), 0.85)
	}

	private func fixture() -> CallViewModel {
		let model = CallStyleFixtureModel()
		model.displayName = "Alex Morgan"; model.remoteAddressCleanedString = "202-555-0142"
		model.avatarModel = ContactAvatarModel(friend: nil, name: "Alex Morgan", address: "2025550142", withPresence: false)
		model.isOneOneCall = true; model.callsCounter = 1; model.hasAudioRouteRestriction = false
		return model
	}

	private func snapshot<V: View>(_ view: V, name: String, size: CGSize = CGSize(width: 393, height: 852)) async throws -> UIImage {
		let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
		let window = UIWindow(windowScene: scene)
		window.frame = CGRect(origin: .zero, size: size)
		window.rootViewController = UIHostingController(rootView: view)
		window.makeKeyAndVisible()
		defer { window.isHidden = true; window.rootViewController = nil }
		try await Task.sleep(nanoseconds: 500_000_000)
		window.layoutIfNeeded()
		let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
		let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
		return image
	}

	private func brightness(_ image: UIImage, point: CGPoint) throws -> Double {
		let crop = try XCTUnwrap(image.cgImage?.cropping(to: CGRect(x: point.x * image.scale, y: point.y * image.scale, width: 1, height: 1)))
		var pixel = [UInt8](repeating: 0, count: 4)
		let context = try XCTUnwrap(CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
			space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
		context.draw(crop, in: CGRect(x: 0, y: 0, width: 1, height: 1))
		return Double(pixel[0]) / 765 + Double(pixel[1]) / 765 + Double(pixel[2]) / 765
	}

	private func luminance(_ color: Color) -> Double {
		var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
		UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
		func linear(_ value: CGFloat) -> Double { let c = Double(value); return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
		return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
	}

	private func contrast(_ foreground: Color, _ background: Color) -> Double {
		let a = luminance(foreground), b = luminance(background)
		return (max(a, b) + 0.05) / (min(a, b) + 0.05)
	}
}
