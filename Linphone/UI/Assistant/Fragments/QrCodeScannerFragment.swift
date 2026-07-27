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

import SwiftUI

struct QrCodeScannerFragment: View {
	
	@ObservedObject private var coreContext = CoreContext.shared
	
	@Environment(\.dismiss) var dismiss
	
	@State var scanResult = "Scan a QR code"
	
	var body: some View {
		ZStack(alignment: .top) {
			QRScanner(result: $scanResult)
			
			Text(scanResult)
				.default_text_style_white_800(styleSize: 20)
				.padding(.top, 175)
			
			VStack {
				HStack {
					Button {
						dismiss()
					} label: {
						HStack(spacing: 6) {
							Image("caret-left")
								.renderingMode(.template)
								.resizable()
								.frame(width: 18, height: 18)

							Text("Back")
								.default_text_style_white_600(styleSize: 16)
						}
						.foregroundStyle(Color.white)
						.padding(.horizontal, 14)
						.padding(.vertical, 10)
						.background(Color.orangeMain500)
						.clipShape(Capsule())
						.shadow(color: Color.black.opacity(0.25), radius: 5, y: 2)
					}
					.accessibilityLabel("Back to Mango9 login")

					Spacer()
				}
				.padding(.horizontal, 16)
				.padding(.top, 56)

				Spacer()
			}
			.zIndex(2)
		}
		.edgesIgnoringSafeArea(.all)
		.navigationBarHidden(true)
		.onAppear {
			coreContext.codeScannerIsOpen = true
		}
		.onDisappear {
			coreContext.codeScannerIsOpen = false
		}
		
		/*
		if $isShowToast {
			ZStack {
				
			}.onAppear {
				dismiss()
			}
		}
		 */
	}
}

#Preview {
	QrCodeScannerFragment()
}
