/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
 *
 * This file is part of linphone-iphone
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
import linphonesw

struct SideMenuAccountRow: View {
	@ObservedObject var model: AccountModel
	let accountNumber: Int
	@EnvironmentObject var accountProfileViewModel: AccountProfileViewModel
	
	@Binding var isOpen: Bool
	@Binding var isShowAccountProfileFragment: Bool
	@State private var mango9Extension = ""
	@State private var mango9ActiveNumber = ""
	
	private let avatarSize = 45.0
	
	var body: some View {
		HStack(spacing: 0) {
			Button {
				selectAccount()
			} label: {
				HStack {
					ZStack {
						Circle()
							.fill(Color.grayMain2c200)
						Text(String(accountNumber))
							.default_text_style_800(styleSize: 17)
							.foregroundStyle(Color.grayMain2c600)
					}
					.frame(width: avatarSize, height: avatarSize)
					.padding(.leading, 6)

					VStack(alignment: .leading, spacing: 3) {
						Text(accountLineLabel)
							.default_text_style_grey_400(styleSize: 14)
							.lineLimit(1)
							.minimumScaleFactor(0.72)
							.frame(maxWidth: .infinity, alignment: .leading)

						HStack(spacing: 5) {
							ZStack {
								if model.isDefaultAccount {
									Image(systemName: "checkmark.circle.fill")
										.resizable()
										.scaledToFit()
										.foregroundStyle(Color.orangeMain500)
								}
							}
							.frame(width: 12, height: 12)
							.accessibilityLabel(
								model.isDefaultAccount
									? "Selected account"
									: ""
							)

							Text(pbxCompanyName)
								.default_text_style_uncolored(styleSize: 11)
								.foregroundStyle(Color.grayMain2c500)
								.lineLimit(1)
								.minimumScaleFactor(0.75)

							Circle()
								.fill(model.registrationStateAssociatedUIColor)
								.frame(width: 8, height: 8)
								.accessibilityLabel(
									model.humanReadableRegistrationState
								)
						}
						.frame(maxWidth: .infinity, alignment: .leading)
						.onChange(
							of: model.registrationStateAssociatedUIColor
						) { _ in
							accountProfileViewModel.refreshAccountError()
						}
					}
					.padding(.leading, 4)

					Spacer(minLength: 4)
				}
				.contentShape(Rectangle())
			}
			.frame(maxWidth: .infinity)
			.buttonStyle(.plain)
			.accessibilityLabel(
				"Account \(accountNumber), \(pbxCompanyName), \(accountLineLabel)"
			)
			.accessibilityValue(
				model.isDefaultAccount
					? "Selected, \(model.humanReadableRegistrationState)"
					: model.humanReadableRegistrationState
			)
			.accessibilityHint(
				model.isDefaultAccount
					? "Current Mango9 account"
					: "Double tap to use this Mango9 account"
			)
			
			HStack(spacing: 4) {
				if model.voicemailCount > 0 {
					Button {
						model.callVoicemailUri()
					} label: {
						ZStack(alignment: .top) {
							VStack {
								Spacer()
								
								Image("voicemail")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.grayMain2c500)
									.frame(width: 22, height: 22)
								
								Spacer()
							}
							
							Text(String(model.voicemailCount))
								.foregroundStyle(Color.redDanger500)
								.default_text_style_600(styleSize: 12)
								.lineLimit(1)
								.frame(maxWidth: .infinity, alignment: .trailing)
								.padding(.top, 1)
						}
						.frame(width: 30, height: 30)
					}
					.highPriorityGesture(
						TapGesture().onEnded {
							model.callVoicemailUri()
						}
					)
					.buttonStyle(.plain)
				}
				
				if model.notificationsCount > 0 && !AppServices.corePreferences.disableChatFeature {
					VStack {
						Text(String(model.notificationsCount))
							.foregroundStyle(.white)
							.default_text_style(styleSize: 12)
							.lineLimit(1)
							.frame(width: 20, height: 20)
							.background(Color.redDanger500)
							.cornerRadius(50)
					}
					.frame(width: 30, height: 30)
					.padding(.trailing, -8)
				}
				
				Button {
					openAccountProfile()
				} label: {
					Image("user-circle-gear")
						.renderingMode(.template)
						.resizable()
						.foregroundColor(Color.grayMain2c600)
						.scaledToFit()
						.frame(height: 25)
				}
				.frame(width: 30, height: 30)
				.buttonStyle(.plain)
				.accessibilityLabel("View account \(accountNumber)")
			}
			.frame(alignment: .trailing)
			.padding(.top, 12)
			.padding(.bottom, 12)
		}
		.frame(height: 61)
		.padding(.horizontal, 16)
		.background(model.isDefaultAccount ? Color.grayMain2c100 : .white)
		.onAppear {
			refreshLineIdentity()
		}
		.onReceive(
			NotificationCenter.default.publisher(for: .mango9LineIdentityChanged)
		) { _ in
			refreshLineIdentity()
		}
	}

	private func selectAccount() {
		guard !model.isDefaultAccount else { return }
		model.setAsDefault()
		withAnimation {
			isOpen = false
		}
	}

	private var accountLineLabel: String {
		Self.accountLineLabel(
			extensionNumber: mango9Extension,
			activeNumber: mango9ActiveNumber,
			fallback: model.displayName
		)
	}

	static func accountLineLabel(
		extensionNumber: String,
		activeNumber: String,
		fallback: String
	) -> String {
		let extensionNumber = extensionNumber.trimmingCharacters(
			in: .whitespacesAndNewlines
		)
		let activeNumber = activeNumber.trimmingCharacters(
			in: .whitespacesAndNewlines
		)
		if !extensionNumber.isEmpty, !activeNumber.isEmpty {
			return "\(activeNumber) · Ext \(extensionNumber)"
		}
		if !extensionNumber.isEmpty {
			return "Ext \(extensionNumber)"
		}
		if !activeNumber.isEmpty {
			return activeNumber
		}
		return fallback
	}

	private var pbxCompanyName: String {
		let identity = model.account.params?.identityAddress
		let sipIdentity = identity?.asStringUriOnly() ?? ""
		let sessionName = Mango9SessionStore.load(for: sipIdentity)?
			.displayName?
			.trimmingCharacters(in: .whitespacesAndNewlines)
		let domain = identity?.domain?
			.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		let tenant = domain.split(separator: ".").first.map(String.init) ?? ""

		if !tenant.isEmpty,
		   let firstNamePart = sessionName?.split(separator: " ").first.map(String.init),
		   normalizedCompanyToken(firstNamePart) == normalizedCompanyToken(tenant) {
			return firstNamePart
		}

		let formattedTenant = tenant
			.replacingOccurrences(of: "-", with: " ")
			.replacingOccurrences(of: "_", with: " ")
			.trimmingCharacters(in: .whitespacesAndNewlines)
		if !formattedTenant.isEmpty {
			return formattedTenant.capitalized
		}
		return domain.isEmpty ? "Mango9 PBX" : domain
	}

	private func normalizedCompanyToken(_ value: String) -> String {
		value
			.lowercased()
			.unicodeScalars
			.filter(CharacterSet.alphanumerics.contains)
			.map(String.init)
			.joined()
	}

	private func openAccountProfile() {
		guard let accountIndex = CoreContext.shared.accounts.firstIndex(where: {
			$0 === model
		}) else {
			Log.error("[Mango9] Could not open account settings because the account was not found.")
			return
		}
		accountProfileViewModel.accountModelIndex = accountIndex
		withAnimation {
			isOpen = false
			isShowAccountProfileFragment = true
		}
	}

	private func refreshLineIdentity() {
		let sipIdentity =
			model.account.params?.identityAddress?.asStringUriOnly() ?? ""
		if let identity = Mango9LineIdentityStore.load(
			sipIdentity: sipIdentity
		) {
			mango9Extension = identity.extensionNumber
			mango9ActiveNumber = identity.activeNumber ?? ""
		} else {
			mango9Extension =
				model.account.params?.identityAddress?.username ?? ""
			mango9ActiveNumber = ""
		}
	}
}
