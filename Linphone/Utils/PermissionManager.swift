/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of Linphone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import Foundation
import Photos
import Contacts
import UserNotifications
import SwiftUI
import Network
import AVFoundation
import UIKit

/// Checks the live iOS permission at the point of dialing, not the cached onboarding flag.
/// A denied call is never queued for automatic dialing after a visit to Settings.
@MainActor
final class Mango9CallMicrophonePermission {
	static let shared = Mango9CallMicrophonePermission(
		status: { AVAudioSession.sharedInstance().recordPermission },
		requestPermission: { completion in
			AVAudioSession.sharedInstance().requestRecordPermission { granted in
				DispatchQueue.main.async { completion(granted) }
			}
		},
		presentDenied: { completion in presentDeniedAlert(didDismiss: completion) }
	)

	private let status: () -> AVAudioSession.RecordPermission
	private let requestPermission: (@escaping @MainActor (Bool) -> Void) -> Void
	private let presentDenied: (@escaping @MainActor () -> Void) -> Void
	private var pendingRequest: UUID?
	private var warningVisible = false

	init(
		status: @escaping () -> AVAudioSession.RecordPermission,
		requestPermission: @escaping (@escaping @MainActor (Bool) -> Void) -> Void,
		presentDenied: @escaping (@escaping @MainActor () -> Void) -> Void
	) {
		self.status = status
		self.requestPermission = requestPermission
		self.presentDenied = presentDenied
	}

	func requestForOutgoingCall(completion: @escaping @MainActor (Bool) -> Void) {
		// Repeated taps must not queue several calls behind one system permission sheet.
		guard pendingRequest == nil, !warningVisible else {
			completion(false)
			return
		}
		switch status() {
		case .granted:
			completion(true)
		case .undetermined:
			let request = UUID()
			pendingRequest = request
			requestPermission { [weak self] granted in
				guard let self, self.pendingRequest == request else { return }
				self.pendingRequest = nil
				completion(granted)
				if !granted { self.explainDeniedPermission() }
			}
		case .denied:
			completion(false)
			explainDeniedPermission()
		@unknown default:
			completion(false)
			explainDeniedPermission()
		}
	}

	private func explainDeniedPermission() {
		guard !warningVisible else { return }
		warningVisible = true
		presentDenied { [weak self] in self?.warningVisible = false }
	}

	static func makeDeniedAlert(
		openSettings: @escaping @MainActor () -> Void,
		didDismiss: @escaping @MainActor () -> Void
	) -> UIAlertController {
		let alert = UIAlertController(
			title: "Microphone access is off",
			message: "The other person won’t be able to hear you without microphone access. Open iPhone Settings and turn on Microphone for Mango9, then return and tap Call again.",
			preferredStyle: .alert
		)
		alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in didDismiss() })
		alert.addAction(UIAlertAction(title: "Open Settings", style: .default) { _ in
			didDismiss()
			openSettings()
		})
		return alert
	}

	private static func presentDeniedAlert(attempt: Int = 0, didDismiss: @escaping @MainActor () -> Void) {
		let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
			.first { $0.activationState == .foregroundActive }
		var presenter = scene?.windows.first { $0.isKeyWindow }?.rootViewController
		while let current = presenter {
			if let presented = current.presentedViewController {
				presenter = presented
			} else if let navigation = current as? UINavigationController, let visible = navigation.visibleViewController {
				presenter = visible
			} else if let tabs = current as? UITabBarController, let selected = tabs.selectedViewController {
				presenter = selected
			} else {
				break
			}
		}
		guard let presenter, presenter.viewIfLoaded?.window != nil,
			  !presenter.isBeingDismissed, !presenter.isBeingPresented,
			  !(presenter is UIAlertController) else {
			// Allow the system permission sheet or the dialer's sheet to finish dismissing.
			// Do not keep an old call attempt alive indefinitely in the background.
			guard attempt < 12 else { didDismiss(); return }
			DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
				presentDeniedAlert(attempt: attempt + 1, didDismiss: didDismiss)
			}
			return
		}
		let alert = makeDeniedAlert(openSettings: {
			if let url = URL(string: UIApplication.openSettingsURLString) {
				UIApplication.shared.open(url)
			}
		}, didDismiss: didDismiss)
		presenter.present(alert, animated: true)
	}
}

class PermissionManager: ObservableObject {
	
	static let shared = PermissionManager()
	
	@Published var pushPermissionGranted = false
	@Published var photoLibraryPermissionGranted = false
	@Published var cameraPermissionGranted = false
	@Published var contactsPermissionGranted = false
	@Published var microphonePermissionGranted = false
	@Published var allPermissionsHaveBeenDisplayed = false
	
	private init() {}
	
	func getPermissions() {
		pushNotificationRequestPermission {
			let dispatchGroup = DispatchGroup()
			
			dispatchGroup.enter()
			self.microphoneRequestPermission()
			self.photoLibraryRequestPermission()
			self.cameraRequestPermission()
			self.contactsRequestPermission(group: dispatchGroup)
			
			dispatchGroup.notify(queue: .main) {
				// Now request local network authorization last
				self.requestLocalNetworkAuthorization()
			}
		}
	}
	
	func pushNotificationRequestPermission(completion: @escaping () -> Void) {
		let options: UNAuthorizationOptions = [.alert, .sound, .badge]
		UNUserNotificationCenter.current().requestAuthorization(options: options) { (granted, error) in
			if let error = error {
				Log.error("Unexpected error when asking for Push permission : \(error.localizedDescription)")
			}
			DispatchQueue.main.async {
				self.pushPermissionGranted = granted
			}
			completion()
		}
	}
	
	func microphoneRequestPermission() {
		AVAudioSession.sharedInstance().requestRecordPermission({ granted in
			DispatchQueue.main.async {
				self.microphonePermissionGranted = granted
			}
		})
	}
	
	func photoLibraryRequestPermission() {
		PHPhotoLibrary.requestAuthorization(for: .readWrite, handler: {status in
			DispatchQueue.main.async {
				self.photoLibraryPermissionGranted = (status == .authorized || status == .limited || status == .restricted)
			}
		})
	}
	
	func cameraRequestPermission() {
		AVCaptureDevice.requestAccess(for: .video, completionHandler: {accessGranted in
			DispatchQueue.main.async {
				self.cameraPermissionGranted = accessGranted
			}
		})
	}
	
	func contactsRequestPermission(group: DispatchGroup) {
		let store = CNContactStore()
		store.requestAccess(for: .contacts) { success, _ in
			DispatchQueue.main.async {
				self.contactsPermissionGranted = success
			}
			group.leave()
		}
	}
	
	func requestLocalNetworkAuthorization() {
		// Use a general UDP broadcast endpoint to attempt triggering the authorization request
		let host = NWEndpoint.Host("255.255.255.255") // Broadcast on the local network
		let port = NWEndpoint.Port(12345) // Choose an arbitrary port
		
		let params = NWParameters.udp
		let connection = NWConnection(host: host, port: port, using: params)
		
		connection.stateUpdateHandler = { newState in
			switch newState {
			case .ready:
				print("Connection ready")
				connection.cancel() // Close the connection after establishing it
			case .failed(let error):
				print("Connection failed: \(error)")
				connection.cancel()
			default:
				break
			}
		}
		connection.start(queue: .main)
		DispatchQueue.main.async {
			self.allPermissionsHaveBeenDisplayed = true
		}
	}
	
	func havePermissionsAlreadyBeenRequested() {
		let cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
		let micStatus = AVAudioSession.sharedInstance().recordPermission
		let photoStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
		let contactsStatus = CNContactStore.authorizationStatus(for: .contacts)
		
		let notifGroup = DispatchGroup()
		var notifStatus: UNAuthorizationStatus = .notDetermined
		
		notifGroup.enter()
		UNUserNotificationCenter.current().getNotificationSettings { settings in
			notifStatus = settings.authorizationStatus
			notifGroup.leave()
		}
		
		notifGroup.notify(queue: .main) {
			let allAlreadyRequested = cameraStatus != .notDetermined &&
									  micStatus != .undetermined &&
									  photoStatus != .notDetermined &&
									  contactsStatus != .notDetermined &&
									  notifStatus != .notDetermined
			
			if allAlreadyRequested {
				self.allPermissionsHaveBeenDisplayed = true
			}
		}
	}

}
