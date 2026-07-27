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

import linphonesw
import Foundation
import SwiftUI
import AVFoundation

struct QRScanner: UIViewControllerRepresentable {
	
	@Binding var result: String
	
	func makeUIViewController(context: Context) -> QRScannerController {
		let controller = QRScannerController()
		controller.delegate = context.coordinator
		
		return controller
	}
	
	func makeCoordinator() -> Coordinator {
		Coordinator($result)
	}
	
	func updateUIViewController(_ uiViewController: QRScannerController, context: Context) {
	}
}

class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
	
	private var coreContext = CoreContext.shared
	
	@Binding var scanResult: String
	private var lastResult: String = ""
	
	private var mCoreDelegate: CoreDelegate?
	
	init(_ scanResult: Binding<String>) {
		self._scanResult = scanResult
		super.init()
		
		addDelegate()
	}
	
	func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
		
		// Check if the metadataObjects array is not nil and it contains at least one object.
		if metadataObjects.isEmpty {
			scanResult = "Scan a QR code"
			return
		}
		
		// Get the metadata object.
		guard let metadataObj = metadataObjects[0] as? AVMetadataMachineReadableCodeObject else {
			return
		}
		
		if metadataObj.type == AVMetadataObject.ObjectType.qr,
		   let result = metadataObj.stringValue {
			if !result.isEmpty && result != lastResult {
				guard let provisioningURL = Mango9Configuration.verifiedProvisioningURL(from: result) else {
					DispatchQueue.main.async {
						ToastViewModel.shared.show("Invalid Mango9 QR code")
					}
					return
				}

				lastResult = result
				DispatchQueue.main.async {
					self.scanResult = "Connecting to Mango9…"
					self.coreContext.loggingInProgress = true
				}
				coreContext.doOnCoreQueue { core in
					try? core.setProvisioninguri(newValue: provisioningURL.absoluteString)
					core.stop()
					try? core.start()
				}
			}
		}
	}
	
	func addDelegate() {
		mCoreDelegate = CoreDelegateStub(
			onConfiguringStatus: { (_: Core, status: ConfiguringState, _: String) in
				Log.info("New configuration state is \(status)")
				self.handleConfigurationChanged(status: status)
			}
		)
		
		if let delegate = mCoreDelegate {
			coreContext.doOnCoreQueue { core in
				core.addDelegate(delegate: delegate)
			}
		}
	}
	
	func handleConfigurationChanged(status: ConfiguringState) {
		switch status {
		case .Successful:
			DispatchQueue.main.async {
				ToastViewModel.shared.show("Mango9 account connected")
			}
		case .Failed:
			DispatchQueue.main.async {
				self.coreContext.loggingInProgress = false
				ToastViewModel.shared.show("Mango9 provisioning failed")
			}
		default:
			break
		}
	}
}
