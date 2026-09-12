/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
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

enum Mango9ContactStyle {
	/// Retain the app's indigo in light mode, with readable contrast in dark mode.
	static let tint = Color(uiColor: UIColor { traits in
		traits.userInterfaceStyle == .dark
			? UIColor(red: 0.58, green: 0.63, blue: 1, alpha: 1)
			: UIColor(Color.mango9Primary)
	})
}

struct ContactInnerActionsFragment: View {
	@ObservedObject private var sharedMainViewModel = SharedMainViewModel.shared
	@EnvironmentObject var contactAvatarModel: ContactAvatarModel
	@EnvironmentObject var contactsListViewModel: ContactsListViewModel

	@Binding var showingSheet: Bool
	@Binding var showShareSheet: Bool
	@Binding var isShowDeletePopup: Bool
	@Binding var isShowDismissPopup: Bool
	@Binding var isShowMediaFilesFragment: Bool
	@Binding var isShowDocumentsFilesFragment: Bool
	@Binding var isShowEditContactFragmentInContactDetails: Bool
	var actionEditButton: () -> Void

	private var canEdit: Bool {
		!contactAvatarModel.isReadOnly && !AppServices.corePreferences.hideContactEdition
	}
	private var visibleAddresses: [String] {
		AppServices.corePreferences.hideSipAddresses ? [] : contactAvatarModel.addresses
	}

	var body: some View {
		VStack(spacing: 20) {
			if !visibleAddresses.isEmpty || !contactAvatarModel.phoneNumbersWithLabel.isEmpty || !contactAvatarModel.emails.isEmpty {
				VStack(spacing: 0) {
					ForEach(Array(contactAvatarModel.phoneNumbersWithLabel.enumerated()), id: \.offset) { index, entry in
						contactValue(label: entry.label.isEmpty ? String(localized: "phone_number") : Mango9ContactLabel.localized(entry.label),
							value: entry.phoneNumber, icon: "phone", accessibilityID: "contact.phone.\(index)") {
							call(entry.phoneNumber)
						}
						.contextMenu { copyButton(entry.phoneNumber) }
						if index < contactAvatarModel.phoneNumbersWithLabel.count - 1 || !visibleAddresses.isEmpty || !contactAvatarModel.emails.isEmpty {
							insetDivider
						}
					}
					ForEach(Array(visibleAddresses.enumerated()), id: \.offset) { index, address in
						contactValue(label: String(localized: "sip_address"), value: displayAddress(address),
							icon: "phone", accessibilityID: "contact.sip.\(index)") { call(address) }
							.contextMenu { copyButton(address) }
						if index < visibleAddresses.count - 1 || !contactAvatarModel.emails.isEmpty { insetDivider }
					}
					ForEach(Array(contactAvatarModel.emails.enumerated()), id: \.offset) { index, email in
						contactValue(label: String(localized: "contact_email"), value: email,
							icon: "envelope", accessibilityID: "contact.email.\(index)") {
							var url = URLComponents()
							url.scheme = "mailto"; url.path = email
							if let target = url.url { UIApplication.shared.open(target) }
						}
						.contextMenu { copyButton(email) }
						if index < contactAvatarModel.emails.count - 1 { insetDivider }
					}
				}
				.contactDetailCard()
			}

			if !contactAvatarModel.organization.isEmpty || !contactAvatarModel.jobTitle.isEmpty {
				VStack(spacing: 0) {
					if !contactAvatarModel.organization.isEmpty {
						informationRow(String(localized: "contact_editor_company"), value: contactAvatarModel.organization)
					}
					if !contactAvatarModel.organization.isEmpty && !contactAvatarModel.jobTitle.isEmpty { insetDivider }
					if !contactAvatarModel.jobTitle.isEmpty {
						informationRow(String(localized: "contact_editor_job_title"), value: contactAvatarModel.jobTitle)
					}
				}
				.contactDetailCard()
			}

			if sharedMainViewModel.displayedFriendExistingChatRoom != nil {
				VStack(spacing: 0) {
					Button { isShowMediaFilesFragment = true } label: {
						actionRow("conversation_menu_media_files", icon: "photo.on.rectangle")
					}
					insetDivider
					Button { isShowDocumentsFilesFragment = true } label: {
						actionRow("conversation_menu_documents_files", icon: "doc")
					}
				}
				.contactDetailCard()
			}

			VStack(spacing: 0) {
				if canEdit {
					Button { contactsListViewModel.toggleStarredSelectedFriend() } label: {
						actionRow(contactAvatarModel.starred ? "contact_details_remove_from_favourites" : "contact_details_add_to_favourites",
							icon: contactAvatarModel.starred ? "star.fill" : "star")
					}
					.accessibilityIdentifier("contact.favorite")
					insetDivider
				}
				Button { showShareSheet = true } label: {
					actionRow("contact_details_share", icon: "square.and.arrow.up")
				}
				.accessibilityIdentifier("contact.share")
			}
			.contactDetailCard()

			// iPhone contacts are deleted in Apple's editor. Keep the existing
			// deletion route for contacts belonging to other address books.
			if canEdit && contactAvatarModel.removalSource != .iPhone {
				Button(role: .destructive) { isShowDeletePopup = true } label: {
					Text("Delete Contact")
						.font(.body)
						.foregroundColor(.red)
						.frame(maxWidth: .infinity, minHeight: 48)
						.padding(.vertical, 4)
						.contentShape(Rectangle())
				}
				.accessibilityIdentifier("contact.remove")
				.contactDetailCard()
			}
		}
		.buttonStyle(.plain)
		.padding(.horizontal, 16)
		.padding(.top, 24)
		.padding(.bottom, 28)
	}

	private var insetDivider: some View { Divider().padding(.leading, 16) }

	private func informationRow(_ label: String, value: String) -> some View {
		VStack(alignment: .leading, spacing: 4) {
			Text(label).font(.subheadline).foregroundColor(.secondary)
			Text(value).font(.body).foregroundColor(.primary)
				.fixedSize(horizontal: false, vertical: true)
		}
		.frame(maxWidth: .infinity, alignment: .leading)
		.padding(16)
	}

	private func contactValue(label: String, value: String, icon: String, accessibilityID: String,
							  action: @escaping () -> Void) -> some View {
		Button(action: action) {
			HStack(spacing: 12) {
				VStack(alignment: .leading, spacing: 5) {
					Text(label).font(.subheadline).foregroundColor(.primary)
					Text(value).font(.body).foregroundColor(Mango9ContactStyle.tint)
						.fixedSize(horizontal: false, vertical: true)
						.environment(\.layoutDirection, .leftToRight)
				}
				.frame(maxWidth: .infinity, alignment: .leading)
				Image(systemName: icon).font(.system(size: 20)).foregroundColor(Mango9ContactStyle.tint)
					.accessibilityHidden(true)
			}
			.padding(16)
			.frame(minHeight: 64)
			.contentShape(Rectangle())
		}
		.accessibilityIdentifier(accessibilityID)
	}

	private func actionRow(_ title: LocalizedStringKey, icon: String) -> some View {
		HStack(spacing: 12) {
			Text(title).font(.body).fixedSize(horizontal: false, vertical: true)
				.frame(maxWidth: .infinity, alignment: .leading)
			Image(systemName: icon).font(.system(size: 20)).accessibilityHidden(true)
		}
		.foregroundColor(Mango9ContactStyle.tint)
		.padding(.horizontal, 16)
		.padding(.vertical, 14)
		.frame(minHeight: 48)
		.contentShape(Rectangle())
	}

	private func copyButton(_ value: String) -> some View {
		Button {
			UIPasteboard.general.string = value
			ToastViewModel.shared.show("Success_address_copied_into_clipboard")
		} label: { Label("Copy", systemImage: "doc.on.doc") }
	}

	private func displayAddress(_ value: String) -> String {
		if value.lowercased().hasPrefix("sips:") { return String(value.dropFirst(5)) }
		if value.lowercased().hasPrefix("sip:") { return String(value.dropFirst(4)) }
		return value
	}

	private func call(_ value: String) {
		CoreContext.shared.doOnCoreQueue { core in
			guard let address = core.interpretUrl(url: value, applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core)) else { return }
			TelecomManager.shared.doCallOrJoinConf(address: address)
		}
	}
}

private extension View {
	func contactDetailCard() -> some View {
		background(Color(uiColor: .secondarySystemGroupedBackground))
			.clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
	}
}
