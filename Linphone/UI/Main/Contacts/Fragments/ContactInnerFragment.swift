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
import Contacts
import ContactsUI
import linphonesw

struct ContactInnerFragment: View {
	@Environment(\.dynamicTypeSize) private var dynamicTypeSize
	@ObservedObject var contactsManager = ContactsManager.shared
	@ObservedObject private var mango9ChatStore = Mango9ChatStore.shared
	@EnvironmentObject var contactAvatarModel: ContactAvatarModel
	@EnvironmentObject var contactsListViewModel: ContactsListViewModel

	@State private var orientation = UIDevice.current.orientation
	@State var cnContact: CNContact?
	@State private var presentingEditContact = false
	@State private var loadingEditor = false
	@State private var editorError = false
	@State private var isShowMediaFilesFragment = false
	@State private var isShowDocumentsFilesFragment = false

	@Binding var isShowDeletePopup: Bool
	@Binding var showingSheet: Bool
	@Binding var showShareSheet: Bool
	@Binding var isShowDismissPopup: Bool
	@Binding var isShowSipAddressesPopup: Bool
	@Binding var isShowSipAddressesPopupType: Int
	@Binding var isShowEditContactFragmentInContactDetails: Bool

	private var hasDestination: Bool {
		!contactAvatarModel.addresses.isEmpty || !contactAvatarModel.phoneNumbersWithLabel.isEmpty
	}

	var body: some View {
		NavigationView {
			ZStack {
				VStack(spacing: 0) {
					navigationHeader
					ScrollView {
						VStack(spacing: 0) {
							identityHeader
							quickActions
							ContactInnerActionsFragment(
								showingSheet: $showingSheet, showShareSheet: $showShareSheet,
								isShowDeletePopup: $isShowDeletePopup, isShowDismissPopup: $isShowDismissPopup,
								isShowMediaFilesFragment: $isShowMediaFilesFragment,
								isShowDocumentsFilesFragment: $isShowDocumentsFilesFragment,
								isShowEditContactFragmentInContactDetails: $isShowEditContactFragmentInContactDetails,
								actionEditButton: editNativeContact
							)
							.onAppear { contactsListViewModel.getOneToOneChatRoomWith() }
							.onChange(of: contactAvatarModel.addresses + contactAvatarModel.phoneNumbersWithLabel.map { $0.phoneNumber }) { _ in
								refreshRelatedConversation()
							}
							.onChange(of: SharedMainViewModel.shared.displayedFriend?.id) { _ in
								refreshRelatedConversation()
							}
							.onDisappear { SharedMainViewModel.shared.displayedFriendExistingChatRoom = nil }
						}
						.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
						.frame(maxWidth: .infinity)
					}
				}
				.background(Color(uiColor: .systemGroupedBackground))
				.navigationBarHidden(true)
				.onRotate { orientation = $0 }
				.fullScreenCover(isPresented: $presentingEditContact, onDismiss: {
					contactsManager.refreshContactsAutomatically()
				}) {
					NavigationView {
						EditContactView(contact: $cnContact)
							.navigationBarTitle("contact_edit_title")
							.navigationBarTitleDisplayMode(.inline)
							.edgesIgnoringSafeArea(.vertical)
					}
				}
				.alert("Contact unavailable", isPresented: $editorError) {
					Button("OK", role: .cancel) {}
				} message: {
					Text("This contact may have changed or Contacts access may be limited. Check access in Settings and try again.")
				}

				if isShowMediaFilesFragment {
					ConversationMediaListFragment(isShowMediaFilesFragment: $isShowMediaFilesFragment)
						.zIndex(5).transition(.move(edge: .trailing))
				}
				if isShowDocumentsFilesFragment {
					ConversationDocumentsListFragment(isShowDocumentsFilesFragment: $isShowDocumentsFilesFragment)
						.zIndex(5).transition(.move(edge: .trailing))
				}
			}
		}
		.navigationViewStyle(.stack)
		.tint(Mango9ContactStyle.tint)
	}

	private var navigationHeader: some View {
		HStack {
			if !(orientation == .landscapeLeft || orientation == .landscapeRight
				 || UIScreen.main.bounds.width > UIScreen.main.bounds.height) {
				Button {
					withAnimation { SharedMainViewModel.shared.displayedFriend = nil }
				} label: {
					if dynamicTypeSize.isAccessibilitySize {
						Image(systemName: "chevron.left").font(.title3).frame(minWidth: 44, minHeight: 44)
					} else {
						Label("Contacts", systemImage: "chevron.left").font(.body).frame(minHeight: 44)
					}
				}
				.accessibilityLabel("Contacts")
				.accessibilityIdentifier("contact.back")
			}
			Spacer(minLength: 16)
			if !contactAvatarModel.isReadOnly && !AppServices.corePreferences.hideContactEdition {
				if !contactAvatarModel.editable {
					Button(action: editNativeContact) {
						if loadingEditor { ProgressView() } else { Text("Edit").font(.body) }
					}
					.disabled(loadingEditor)
					.frame(minWidth: 44, minHeight: 44)
					.accessibilityIdentifier("contact.edit")
				} else {
					NavigationLink(destination: EditContactFragment(
						contactAvatarModel: contactAvatarModel,
						isShowEditContactFragment: $isShowEditContactFragmentInContactDetails,
						isShowDismissPopup: $isShowDismissPopup)) {
							Text("Edit").font(.body).frame(minWidth: 44, minHeight: 44)
						}
						.simultaneousGesture(TapGesture().onEnded { isShowEditContactFragmentInContactDetails = true })
						.accessibilityIdentifier("contact.edit")
				}
			}
		}
		.foregroundColor(Mango9ContactStyle.tint)
		.padding(.horizontal, 16)
		.padding(.vertical, 4)
	}

	private var identityHeader: some View {
		VStack(spacing: 12) {
			Avatar(contactAvatarModel: contactAvatarModel, avatarSize: 96)
				.accessibilityHidden(true)
			Text(contactAvatarModel.name)
				.font(.title.weight(.semibold))
				.foregroundColor(.primary)
				.multilineTextAlignment(.center)
				.fixedSize(horizontal: false, vertical: true)
				.accessibilityAddTraits(.isHeader)
			if !mango9PresenceText.isEmpty {
				Text(mango9PresenceText).font(.subheadline)
					.foregroundColor(mango9PresenceText == "Online" ? .green : .secondary)
			}
		}
		.frame(maxWidth: .infinity)
		.padding(.horizontal, 20)
		.padding(.top, 16)
		.padding(.bottom, 24)
	}

	private var quickActions: some View {
		Group {
			if dynamicTypeSize.isAccessibilitySize {
				VStack(spacing: 10) { quickActionButtons }
			} else {
				HStack(alignment: .top, spacing: 10) { quickActionButtons }
			}
		}
		.padding(.horizontal, 16)
	}

	private var quickActionButtons: some View {
		Group {
			quickAction("contact_call_action", icon: "phone.fill", enabled: hasDestination) { performContactAction(0) }
			if !AppServices.corePreferences.disableChatFeature {
				quickAction("contact_message_action", icon: "message.fill",
					enabled: hasDestination || contactsManager.mango9ChatTarget(forNativeUri: contactAvatarModel.nativeUri) != nil) {
					performContactAction(1)
				}
			}
			if !SharedMainViewModel.shared.disableVideoCall {
				quickAction("contact_video_call_action", icon: "video.fill", enabled: hasDestination) { performContactAction(2) }
			}
		}
	}

	private func quickAction(_ title: LocalizedStringKey, icon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
		Button(action: action) {
			Group {
				if dynamicTypeSize.isAccessibilitySize {
					HStack(spacing: 16) {
						Image(systemName: icon).font(.system(size: 22)).accessibilityHidden(true)
						Text(title).font(.body).fixedSize(horizontal: false, vertical: true)
							.frame(maxWidth: .infinity, alignment: .leading)
					}.padding(.horizontal, 16)
				} else {
					VStack(spacing: 7) {
						Image(systemName: icon).font(.system(size: 22)).accessibilityHidden(true)
						Text(title).font(.caption).multilineTextAlignment(.center)
							.fixedSize(horizontal: false, vertical: true)
					}
				}
			}
			.foregroundColor(enabled ? Mango9ContactStyle.tint : .secondary)
			.frame(maxWidth: .infinity, minHeight: 62)
			.padding(.vertical, 8)
			.background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
			.contentShape(Rectangle())
		}
		.buttonStyle(.plain)
		.disabled(!enabled)
	}

	/// Capture the displayed destinations on the UI queue. SDK interpretation and
	/// call/chat operations remain on the core queue, using the existing routes.
	private func performContactAction(_ type: Int) {
		if type == 1, let target = contactsManager.mango9ChatTarget(forNativeUri: contactAvatarModel.nativeUri) {
			NotificationCenter.default.post(name: .mango9OpenChat, object: target)
			return
		}
		let addresses = contactAvatarModel.addresses
		let phones = contactAvatarModel.phoneNumbersWithLabel.map { $0.phoneNumber }
		let target: String?
		if addresses.count == 1 && phones.isEmpty { target = addresses[0] }
		else if addresses.isEmpty && phones.count == 1 { target = phones[0] }
		else {
			guard !addresses.isEmpty || !phones.isEmpty else { return }
			isShowSipAddressesPopupType = type
			isShowSipAddressesPopup = true
			return
		}
		guard let target else { return }
		CoreContext.shared.doOnCoreQueue { core in
			guard let address = core.interpretUrl(url: target, applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core)) else { return }
			if type == 1 { contactsListViewModel.createOneToOneChatRoomWith(remote: address) }
			else { TelecomManager.shared.doCallOrJoinConf(address: address, isVideo: type == 2) }
		}
	}

	private func refreshRelatedConversation() {
		isShowMediaFilesFragment = false
		isShowDocumentsFilesFragment = false
		SharedMainViewModel.shared.displayedFriendExistingChatRoom = nil
		contactsListViewModel.getOneToOneChatRoomWith()
	}

	private var mango9PresenceText: String {
		guard let target = contactsManager.mango9ChatTarget(forNativeUri: contactAvatarModel.nativeUri) else {
			return contactAvatarModel.lastPresenceInfo
		}
		if mango9ChatStore.isTyping(target.userId) { return "Typing…" }
		return mango9ChatStore.isOnline(target.userId) ? "Online" : "Offline"
	}

	func editNativeContact() {
		guard !loadingEditor else { return }
		let identifier = contactAvatarModel.nativeUri
		let selectedID = contactAvatarModel.id
		loadingEditor = true
		// Fetch full details only for this one contact, off the UI thread.
		DispatchQueue.global(qos: .userInitiated).async {
			let result = Result {
				try CNContactStore().unifiedContact(withIdentifier: identifier,
					keysToFetch: [CNContactViewController.descriptorForRequiredKeys()])
			}
			DispatchQueue.main.async {
				loadingEditor = false
				guard SharedMainViewModel.shared.displayedFriend?.id == selectedID else { return }
				switch result {
				case .success(let contact):
					cnContact = contact
					presentingEditContact = true
				case .failure:
					editorError = true
				}
			}
		}
	}
}
