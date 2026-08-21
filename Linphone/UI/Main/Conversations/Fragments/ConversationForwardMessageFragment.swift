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

struct ConversationForwardMessageFragment: View {
	
	@ObservedObject var contactsManager = ContactsManager.shared
	@ObservedObject var magicSearch = MagicSearchSingleton.shared
	@ObservedObject private var mango9ChatStore = Mango9ChatStore.shared
	
	@EnvironmentObject var conversationViewModel: ConversationViewModel
	@EnvironmentObject var conversationsListViewModel: ConversationsListViewModel
	
	@StateObject private var conversationForwardMessageViewModel: ConversationForwardMessageViewModel
	
	@Binding var isShowConversationForwardMessageFragment: Bool
	
	@FocusState var isSearchFieldFocused: Bool
	@State private var delayedColor = Color.white
	
	@FocusState var isMessageTextFocused: Bool

	private var isCarrierForward: Bool {
		conversationForwardMessageViewModel.selectedMessage?.eventModel.isCarrierMessage == true
	}

	private var filteredSMSParties: [Mango9SMSParty] {
		let query = conversationForwardMessageViewModel.searchField
			.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !query.isEmpty else { return mango9ChatStore.smsParties }
		return mango9ChatStore.smsParties.filter {
			Mango9CallerIdentity.formattedPhoneNumber($0.phone)
				.localizedCaseInsensitiveContains(query)
		}
	}
	
	init(conversationsList: [ConversationModel], selectedMessage: EventLogMessage?, isShowConversationForwardMessageFragment: Binding<Bool>) {
		_conversationForwardMessageViewModel = StateObject(wrappedValue: ConversationForwardMessageViewModel(conversationsList: conversationsList, selectedMessage: selectedMessage))
		self._isShowConversationForwardMessageFragment = isShowConversationForwardMessageFragment
	}
	
    var body: some View {
		NavigationView {
			ZStack {
				VStack(spacing: 1) {
					
					Rectangle()
						.foregroundStyle(Color.orangeMain500)
						.edgesIgnoringSafeArea(.top)
						.frame(height: 0)
					
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
								conversationForwardMessageViewModel.searchField = ""
								magicSearch.currentFilter = ""
								magicSearch.searchForContacts()
								
								conversationForwardMessageViewModel.selectedMessage = nil
								withAnimation {
									isShowConversationForwardMessageFragment = false
								}
							}
						
						Text("conversation_forward_message_title")
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
							TextField("history_call_start_search_bar_filter_hint", text: $conversationForwardMessageViewModel.searchField)
								.default_text_style(styleSize: 15)
								.frame(height: 25)
								.focused($isSearchFieldFocused)
								.padding(.horizontal, 30)
								.onChange(of: conversationForwardMessageViewModel.searchField) { newValue in
									guard !isCarrierForward else { return }
									if newValue.isEmpty {
										conversationForwardMessageViewModel.resetFilterConversations()
									} else {
										conversationForwardMessageViewModel.filterConversations()
									}
									magicSearch.currentFilter = newValue
									magicSearch.searchForContacts()
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
								
								if !conversationForwardMessageViewModel.searchField.isEmpty {
									Button(action: {
										conversationForwardMessageViewModel.searchField = ""
										magicSearch.currentFilter = ""
										conversationForwardMessageViewModel.resetFilterConversations()
										magicSearch.searchForContacts()
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
						
						ZStack {
							ScrollView {
								if isCarrierForward {
									HStack(alignment: .center) {
										Text("bottom_navigation_conversations_label")
											.default_text_style_800(styleSize: 16)
										Spacer()
									}
									.padding(.vertical, 10)
									.padding(.horizontal, 16)
									carrierConversationsList
								} else if !conversationForwardMessageViewModel.conversationsList.isEmpty {
									HStack(alignment: .center) {
										Text("bottom_navigation_conversations_label")
											.default_text_style_800(styleSize: 16)
										
										Spacer()
									}
									.padding(.vertical, 10)
									.padding(.horizontal, 16)
									
									conversationsList
								}
								
								if !isCarrierForward && !ContactsManager.shared.lastSearch.isEmpty {
									HStack(alignment: .center) {
										Text("contacts_list_all_contacts_title")
											.default_text_style_800(styleSize: 16)
										
										Spacer()
									}
									.padding(.vertical, 10)
									.padding(.horizontal, 16)
								}
								
								if !isCarrierForward {
								ContactsListFragment(showingSheet: .constant(false), startCallFunc: { addr in
									withAnimation {
										conversationForwardMessageViewModel.createOneToOneChatRoomWith(remote: addr)
									}
									
								})
								.padding(.horizontal, 16)
								}
								
								if !isCarrierForward && !contactsManager.lastSearchSuggestions.isEmpty {
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
							
							if !isCarrierForward && magicSearch.isLoading {
								ProgressView()
									.controlSize(.large)
									.progressViewStyle(CircularProgressViewStyle(tint: .orangeMain500))
							}
						}
					}
					.frame(maxWidth: .infinity)
				}
				.background(.white)
				
				if conversationForwardMessageViewModel.operationInProgress {
					PopupLoadingView()
						.background(.black.opacity(0.65))
						.onDisappear {
							
							conversationForwardMessageViewModel.searchField = ""
							magicSearch.currentFilter = ""
							magicSearch.searchForContacts()
							
							conversationForwardMessageViewModel.forwardMessage()
							
							isShowConversationForwardMessageFragment = false
							
							if conversationForwardMessageViewModel.displayedConversation != nil {
								if SharedMainViewModel.shared.displayedConversation != nil {
									DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
										self.conversationViewModel.changeDisplayedChatRoom(conversationModel: conversationForwardMessageViewModel.displayedConversation!)
									}
								} else {
									self.conversationViewModel.changeDisplayedChatRoom(conversationModel: conversationForwardMessageViewModel.displayedConversation!)
								}
							}
						}
				}
			}
			.navigationTitle("")
			.navigationBarHidden(true)
			.onAppear {
				if isCarrierForward {
					Task { await mango9ChatStore.refreshSMSDirectory() }
					return
				}
				if !magicSearch.currentFilter.isEmpty || (self.contactsManager.lastSearch.isEmpty && self.contactsManager.lastSearchSuggestions.isEmpty) {
					magicSearch.currentFilter = ""
					magicSearch.searchForContacts()
				}
			}
			.onDisappear {
				conversationForwardMessageViewModel.searchField = ""
				magicSearch.currentFilter = ""
				magicSearch.searchForContacts()
				
				conversationForwardMessageViewModel.selectedMessage = nil
				withAnimation {
					isShowConversationForwardMessageFragment = false
				}
			}
		}
		.navigationViewStyle(StackNavigationViewStyle())
    }
	
	var conversationsList: some View {
		ForEach(0..<conversationForwardMessageViewModel.conversationsList.count, id: \.self) { index in
			if index < conversationForwardMessageViewModel.conversationsList.count {
				Button {
					withAnimation {
						conversationForwardMessageViewModel.changeChatRoom(model: conversationForwardMessageViewModel.conversationsList[index])
					}
				} label: {
					HStack {
						Avatar(contactAvatarModel: conversationForwardMessageViewModel.conversationsList[index].avatarModel, avatarSize: 50)
						
						Text(conversationForwardMessageViewModel.conversationsList[index].subject)
							.default_text_style(styleSize: 16)
							.frame(maxWidth: .infinity, alignment: .leading)
							.lineLimit(1)
					}
					.padding(.horizontal)
				}
				.buttonStyle(.borderless)
				.listRowSeparator(.hidden)
			}
		}
	}

	var carrierConversationsList: some View {
		ForEach(filteredSMSParties) { party in
			Button {
				guard let message = conversationForwardMessageViewModel.selectedMessage else { return }
				SharedMainViewModel.shared.pendingSMSComposerText = message.message.text
				Mango9SMSRouting.open(
					Mango9SMSTarget(
						phone: party.phone,
						name: Mango9CallerIdentity.formattedPhoneNumber(party.phone)
					)
				)
				conversationForwardMessageViewModel.selectedMessage = nil
				withAnimation { isShowConversationForwardMessageFragment = false }
			} label: {
				HStack(spacing: 12) {
					Avatar(
						contactAvatarModel: ContactAvatarModel(
							friend: nil,
							name: Mango9CallerIdentity.formattedPhoneNumber(party.phone),
							address: party.phone,
							withPresence: false
						),
						avatarSize: 50
					)
					VStack(alignment: .leading, spacing: 3) {
						Text(Mango9CallerIdentity.formattedPhoneNumber(party.phone))
							.default_text_style(styleSize: 16)
							.foregroundStyle(Color.grayMain2c800)
						Text(party.lastMessage.isEmpty ? "Attachment" : party.lastMessage)
							.default_text_style(styleSize: 13)
							.foregroundStyle(Color.grayMain2c400)
							.lineLimit(1)
					}
					Spacer()
				}
				.padding(.horizontal)
			}
			.buttonStyle(.borderless)
		}
	}
	
	var suggestionsList: some View {
		ForEach(0..<contactsManager.lastSearchSuggestions.count, id: \.self) { index in
			Button {
				withAnimation {
					if contactsManager.lastSearchSuggestions[index].address != nil {
						conversationForwardMessageViewModel.createOneToOneChatRoomWith(remote: contactsManager.lastSearchSuggestions[index].address!)
					}
				}
			} label: {
				HStack {
					if index < contactsManager.lastSearchSuggestions.count
						&& contactsManager.lastSearchSuggestions[index].address != nil {
						let name = contactsManager.suggestionDisplayName(
							for: contactsManager.lastSearchSuggestions[index]
						)
						Image(uiImage: contactsManager.textToImage(
							firstName: name,
							lastName: ""))
						.resizable()
						.frame(width: 45, height: 45)
						.clipShape(Circle())
						
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
		}
	}
}

/*
#Preview {
	ConversationForwardMessageFragment(
		isShowConversationForwardMessageFragment: .constant(true)
	)
}
*/
