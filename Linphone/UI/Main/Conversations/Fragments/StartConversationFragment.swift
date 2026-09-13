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

// swiftlint:disable type_body_length
struct StartConversationFragment: View {
	
	@ObservedObject var contactsManager = ContactsManager.shared
	@ObservedObject var magicSearch = MagicSearchSingleton.shared
	
	@StateObject private var startConversationViewModel = StartConversationViewModel()
	@StateObject private var contactSearch = Mango9ConversationContactSearch()
	@State private var contactPage = Mango9ConversationContactPage()
	@State private var suggestionPage = Mango9ConversationContactPage()
	
	@EnvironmentObject var conversationsListViewModel: ConversationsListViewModel
	
	@Binding var isShowStartConversationFragment: Bool
	
	@State private var contactAvatarModel: ContactAvatarModel? = nil
	@State private var isShowSipAddressesPopup: Bool = false
	
	@FocusState var isSearchFieldFocused: Bool
	@State private var delayedColor = Color.white
	
	@State var operationInProgress: Bool = false
	
	var body: some View {
		NavigationView {
			ZStack {
				VStack(spacing: 1) {
					
					Rectangle()
						.foregroundColor(delayedColor)
						.edgesIgnoringSafeArea(.top)
						.frame(height: 0)
						.task(delayColor)
					
					HStack {
						Image("caret-left")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.orangeMain500)
							.frame(width: 25, height: 25, alignment: .leading)
							.padding(.all, 10)
							.padding(.top, 2)
							.padding(.leading, -10)
							.onTapGesture {
								contactSearch.cancel()
								delayColorDismiss()
								withAnimation {
									isShowStartConversationFragment = false
								}
							}
						
						Text("new_conversation_title")
							.multilineTextAlignment(.leading)
							.default_text_style_orange_800(styleSize: 16)
						
						Spacer()
						
					}
					.frame(maxWidth: .infinity)
					.frame(height: 50)
					.padding(.horizontal)
					.padding(.bottom, 4)
					.background(.white)
					
					VStack(spacing: 0) {
						ZStack(alignment: .trailing) {
							TextField("history_call_start_search_bar_filter_hint", text: $startConversationViewModel.searchField)
								.default_text_style(styleSize: 15)
								.frame(height: 25)
								.focused($isSearchFieldFocused)
								.padding(.horizontal, 30)
								.onChange(of: startConversationViewModel.searchField) { newValue in
									contactSearch.update(newValue)
								}
							
							HStack {
								Button(action: {
								}, label: {
									Image("magnifying-glass")
										.renderingMode(.template)
										.resizable()
										.foregroundStyle(Color.grayMain2c500)
										.frame(width: 25, height: 25)
								})
								
								Spacer()
								
								if !startConversationViewModel.searchField.isEmpty {
									Button(action: {
										startConversationViewModel.searchField = ""
									}, label: {
										Image("x")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.grayMain2c500)
											.frame(width: 25, height: 25)
									})
								}
							}
						}
						.padding(.horizontal, 15)
						.padding(.vertical, 10)
						.cornerRadius(60)
						.overlay(
							RoundedRectangle(cornerRadius: 60)
								.inset(by: 0.5)
								.stroke(isSearchFieldFocused ? Color.orangeMain500 : Color.gray200, lineWidth: 1)
						)
						.padding(.vertical)
						.padding(.horizontal)
						
						if !startConversationViewModel.hideGroupChatButton {
							NavigationLink(destination: {
								StartGroupConversationFragment(isShowStartConversationFragment: $isShowStartConversationFragment)
									.environmentObject(startConversationViewModel)
							}, label: {
								HStack {
									HStack(alignment: .center) {
										Image("users-three")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(.white)
											.frame(width: 28, height: 28)
									}
									.padding(10)
									.background(Color.orangeMain500)
									.cornerRadius(40)
									
									Text("new_conversation_create_group")
										.foregroundStyle(.black)
										.default_text_style_800(styleSize: 16)
										.lineLimit(1)
									
									Spacer()
									
									Image("caret-right")
										.renderingMode(.template)
										.resizable()
										.foregroundStyle(Color.grayMain2c500)
										.frame(width: 25, height: 25, alignment: .leading)
								}
							})
							.padding(.vertical, 10)
							.padding(.horizontal, 20)
							.background(
								LinearGradient(gradient: Gradient(colors: [.grayMain2c100, .white]), startPoint: .leading, endPoint: .trailing)
									.padding(.vertical, 10)
									.padding(.horizontal, 40)
							)
						}
						
						ZStack {
							// List recycles off-screen rows. A plain ScrollView eagerly
							// mounted every avatar and queued thousands of photo reads.
							List {
								if !contactsManager.avatarListModel.isEmpty {
									HStack(alignment: .center) {
										Text("contacts_list_all_contacts_title")
											.default_text_style_800(styleSize: 16)
										
										Spacer()
									}
									.padding(.vertical, 10)
									.padding(.horizontal, 16)
								}
								
								ContactsListFragment(showingSheet: .constant(false), startCallFunc: { _ in },
									selectContact: selectRecipient, rowLimit: contactPage.limit, onRowAppear: { index in
										contactPage.reveal(near: index, total: contactsManager.avatarListModel.count)
									})
								
								if !contactsManager.lastSearchSuggestions.isEmpty {
									HStack(alignment: .center) {
										Text("generic_address_picker_suggestions_list_title")
											.default_text_style_800(styleSize: 16)
										
										Spacer()
									}
									.padding(.vertical, 10)
									.padding(.horizontal, 16)
									
									suggestionsList
								}
							}
							.listStyle(.plain)
							.accessibilityIdentifier("new-conversation-contacts")
							
							if magicSearch.isLoading {
								ProgressView()
									.controlSize(.large)
									.progressViewStyle(CircularProgressViewStyle(tint: .orangeMain500))
							}
						}
					}
					.frame(maxWidth: .infinity)
				}
				.background(.white)
				
				if isShowSipAddressesPopup && contactAvatarModel != nil {
					VStack(alignment: .leading) {
						HStack {
							Text("contact_dialog_pick_phone_number_or_sip_address_title")
								.default_text_style_800(styleSize: 16)
								.padding(.bottom, 2)
							
							Spacer()
							
							Image("x")
								.renderingMode(.template)
								.resizable()
								.foregroundStyle(Color.grayMain2c600)
								.frame(width: 25, height: 25)
								.padding(.all, 10)
						}
						.frame(maxWidth: .infinity)
						
						ForEach(contactAvatarModel!.addresses, id: \.self) { destination in
							HStack {
								HStack {
									VStack {
										Text(String(localized: "sip_address") + ":")
											.default_text_style_700(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
										Text(destination.hasPrefix("sip:") ? String(destination.dropFirst(4)) : destination)
											.default_text_style(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.lineLimit(1)
											.fixedSize(horizontal: false, vertical: true)
									}
									Spacer()
								}
								.padding(.vertical, 15)
								.padding(.horizontal, 10)
							}
							.background(.white)
							.onTapGesture {
								createConversation(to: destination)
							}
						}
						
						ForEach(Array(contactAvatarModel!.phoneNumbersWithLabel.enumerated()), id: \.offset) { _, entry in
							HStack {
								HStack {
									VStack {
										Text(String(localized: "phone_number") + ":")
											.default_text_style_700(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
										Text(entry.phoneNumber)
											.default_text_style(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.lineLimit(1)
											.fixedSize(horizontal: false, vertical: true)
									}
									Spacer()
								}
								.padding(.vertical, 15)
								.padding(.horizontal, 10)
							}
							.background(.white)
							.onTapGesture {
								createConversation(to: entry.phoneNumber)
							}
						}
					}
					.padding(.horizontal, 20)
					.padding(.vertical, 20)
					.background(.white)
					.cornerRadius(20)
					.frame(maxHeight: .infinity)
					.shadow(color: Color.orangeMain500, radius: 0, x: 0, y: 2)
					.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
					.padding(.horizontal, 20)
					.background(.black.opacity(0.65))
					.zIndex(3)
					.onTapGesture {
						isShowSipAddressesPopup.toggle()
					}
				}
				
				if startConversationViewModel.operationOneToOneInProgress {
					PopupLoadingView()
						.background(.black.opacity(0.65))
						.onDisappear {
							contactSearch.cancel()
							delayColorDismiss()
							
							isShowStartConversationFragment = false
							
							if let displayedConversation = startConversationViewModel.displayedConversation {
								self.conversationsListViewModel.changeDisplayedChatRoom(conversationModel: displayedConversation)
								startConversationViewModel.displayedConversation = nil
							}
						}
				}
			}
			.onAppear {
				if magicSearch.currentFilter != startConversationViewModel.searchField || (contactsManager.avatarListModel.isEmpty && contactsManager.lastSearchSuggestions.isEmpty) {
					contactSearch.update(startConversationViewModel.searchField, immediately: true)
				}
			}
			.onDisappear { contactSearch.cancel() }
			.onReceive(contactsManager.$avatarListModel) { _ in contactPage.reset() }
			.onReceive(contactsManager.$lastSearchSuggestions) { _ in suggestionPage.reset() }
			.navigationTitle("")
			.navigationBarHidden(true)
		}
		.navigationViewStyle(StackNavigationViewStyle())
	}

	static func recipientDestinations(_ contact: ContactAvatarModel) -> [String] {
		contact.addresses + contact.phoneNumbersWithLabel.map(\.phoneNumber)
	}

	private func selectRecipient(_ contact: ContactAvatarModel) {
		// The tapped row already owns the contact. Don't scan the whole SDK
		// address book again to rediscover it (or match a namesake by accident).
		let destinations = Self.recipientDestinations(contact)
		if destinations.count == 1, let destination = destinations.first {
			createConversation(to: destination)
		} else if !destinations.isEmpty {
			contactAvatarModel = contact
			isShowSipAddressesPopup = true
		}
	}

	private func createConversation(to destination: String) {
		CoreContext.shared.doOnCoreQueue { core in
			let address = core.interpretUrl(url: destination, applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core))
			DispatchQueue.main.async {
				guard let address else {
					ToastViewModel.shared.show("Failed_to_create_conversation_error")
					return
				}
				isShowSipAddressesPopup = false
				startConversationViewModel.createOneToOneChatRoomWith(remote: address)
			}
		}
	}
	
	@Sendable private func delayColor() async {
		try? await Task.sleep(nanoseconds: 250_000_000)
		delayedColor = Color.orangeMain500
	}
	
	func delayColorDismiss() {
		Task {
			try? await Task.sleep(nanoseconds: 80_000_000)
			delayedColor = .white
		}
	}
	
	var suggestionsList: some View {
		// Capture each result, not an index into a list that may shrink while
		// SwiftUI is still displaying the previous search results.
		let results = contactsManager.lastSearchSuggestions
		let limit = min(results.count, suggestionPage.limit)
		return ForEach(results.prefix(limit), id: \.getCobject) { suggestion in
			Button {
				CoreContext.shared.doOnCoreQueue { _ in
					if let address = suggestion.address {
						DispatchQueue.main.async { startConversationViewModel.createOneToOneChatRoomWith(remote: address) }
					}
				}
			} label: {
				HStack {
					if suggestion.address != nil {
						let name = contactsManager.suggestionDisplayName(for: suggestion)
						Mango9ContactInitials(name: name, size: 45)
						
						Text(name)
							.default_text_style(styleSize: 16)
							.lineLimit(1)
							.frame(maxWidth: .infinity, alignment: .leading)
							.foregroundStyle(Color.orangeMain500)
					} else {
						Image("profil-picture-default")
							.resizable()
							.frame(width: 45, height: 45)
							.clipShape(Circle())
						
						Text("username_error")
							.default_text_style(styleSize: 16)
							.frame(maxWidth: .infinity, alignment: .leading)
							.foregroundStyle(Color.orangeMain500)
					}
				}
				.padding(.horizontal)
			}
			.buttonStyle(.borderless)
			.listRowSeparator(.hidden)
			.onAppear {
				guard limit > 0, suggestion.getCobject == results[max(0, limit - 10)].getCobject else { return }
				suggestionPage.reveal(near: limit - 1, total: results.count)
			}
		}
	}
}

#Preview {
    StartConversationFragment(
		isShowStartConversationFragment: .constant(true)
	)
}

// swiftlint:enable type_body_length
