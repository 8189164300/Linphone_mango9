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

struct ConversationsListFragment: View {
	
	@Environment(\.scenePhase) var scenePhase
	
	@EnvironmentObject var navigationManager: NavigationManager
	
	@ObservedObject var contactsManager = ContactsManager.shared
	@ObservedObject private var mango9ChatStore = Mango9ChatStore.shared
	
	@EnvironmentObject var conversationsListViewModel: ConversationsListViewModel
	
	@Binding var text: String
	@Binding var showingSheet: Bool
	
	var body: some View {
		VStack {
			List {
				ForEach(visibleSMSParties) { party in
					Mango9SMSConversationRow(party: party)
						.contentShape(Rectangle())
						.onTapGesture {
							Mango9SMSRouting.open(
								Mango9SMSTarget(
									phone: party.phone,
									name: Mango9CallerIdentity.formattedPhoneNumber(party.phone)
								)
							)
						}
						.listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 14))
						.listRowSeparator(.hidden)
						.listRowBackground(Color.white)
				}

				if hasMango9Inbox {
					NavigationLink(destination: Mango9TeamChatListFragment()) {
						Mango9InboxConversationRow()
					}
					.listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 14))
					.listRowSeparator(.hidden)
					.listRowBackground(Color.white)
				}

				ForEach(visibleSIPConversations) { conversation in
					ConversationRow(
						navigationManager: _navigationManager,
						conversation: conversation,
						showingSheet: $showingSheet,
						text: $text
					)
				}
				
				if !conversationsListViewModel.currentFilter.isEmpty {
					if !contactsManager.lastSearch.isEmpty {
						HStack(alignment: .center) {
							Text("contacts_list_all_contacts_title")
								.default_text_style_800(styleSize: 16)
							
							Spacer()
						}
					}
					
					ContactsListFragment(showingSheet: .constant(false), startCallFunc: { addr in
						withAnimation {
							conversationsListViewModel.createOneToOneChatRoomWith(remote: addr)
						}
					})
					
					if !contactsManager.lastSearchSuggestions.isEmpty {
						HStack(alignment: .center) {
							Text("generic_address_picker_suggestions_list_title")
								.default_text_style_800(styleSize: 16)
							
							Spacer()
						}
						
						suggestionsList
					}
				}
			}
			.safeAreaInset(edge: .top, content: {
				Spacer()
					.frame(height: 12)
			})
			.listStyle(.plain)
			.overlay(
				VStack {
					if visibleSIPConversations.isEmpty &&
						visibleSMSParties.isEmpty &&
						!hasMango9Inbox &&
						(
							conversationsListViewModel.currentFilter.isEmpty ||
							(!conversationsListViewModel.currentFilter.isEmpty &&
							 contactsManager.lastSearch.isEmpty &&
							 contactsManager.lastSearchSuggestions.isEmpty)
						) {
						Spacer()
						Image("illus-belledonne")
							.resizable()
							.scaledToFit()
							.clipped()
							.padding(.all)
						Text(!text.isEmpty ? "list_filter_no_result_found" : "conversations_list_empty")
							.default_text_style_800(styleSize: 16)
						Spacer()
						Spacer()
					}
				}
					.padding(.all)
			)
			.onDisappear {
				if !conversationsListViewModel.currentFilter.isEmpty {
					conversationsListViewModel.resetFilterConversations()
				}
			}
		}
		.navigationTitle("")
		.navigationBarHidden(true)
		.onChange(of: scenePhase) { newPhase in
			if newPhase == .active {
				if navigationManager.peerAddr != nil {
					conversationsListViewModel.getChatRoomWithStringAddress(stringAddr: navigationManager.peerAddr!)
					navigationManager.peerAddr = nil
				}
			}
		}
		.task {
			if Mango9SessionStore.load() != nil {
				await mango9ChatStore.refreshSMSDirectory()
			}
		}
	}

	private var hasMango9Inbox: Bool {
		Mango9SessionStore.load() != nil
			&& conversationsListViewModel.currentFilter.isEmpty
	}

	private var visibleSMSParties: [Mango9SMSParty] {
		guard Mango9SessionStore.load() != nil else { return [] }
		let query = conversationsListViewModel.currentFilter
			.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !query.isEmpty else { return mango9ChatStore.smsParties }
		return mango9ChatStore.smsParties.filter {
			Mango9CallerIdentity.formattedPhoneNumber($0.phone)
				.localizedCaseInsensitiveContains(query)
			|| $0.lastMessage.localizedCaseInsensitiveContains(query)
		}
	}

	private var visibleSIPConversations: [ConversationModel] {
		conversationsListViewModel.conversationsList.filter { conversation in
			guard Mango9SessionStore.load() != nil,
				  !conversation.isGroup,
				  let remote = conversation.chatRoom.peerAddress else {
				return true
			}
			return Mango9SMSRouting.target(
				remote: remote,
				fallbackName: conversation.subject
			) == nil
		}
	}
	
	var suggestionsList: some View {
		ForEach(0..<contactsManager.lastSearchSuggestions.count, id: \.self) { index in
			Button {
				if let address = contactsManager.lastSearchSuggestions[index].address {
					withAnimation {
						conversationsListViewModel.createOneToOneChatRoomWith(remote: address)
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
			}
			.buttonStyle(.borderless)
			.listRowSeparator(.hidden)
		}
	}
}

private struct Mango9InboxConversationRow: View {
	@ObservedObject private var store = Mango9ChatStore.shared

	var body: some View {
		HStack(spacing: 12) {
			ZStack {
				Circle()
					.fill(Color(uiColor: .systemBlue))
					.frame(width: 50, height: 50)
				Image(systemName: "message.fill")
					.font(.system(size: 21, weight: .semibold))
					.foregroundStyle(Color.white)
			}

			VStack(alignment: .leading, spacing: 4) {
				Text("Mango9 Team Chat")
					.font(.custom("NotoSans-Bold", size: 14))
					.foregroundStyle(Color.grayMain2c800)
					.lineLimit(1)
				Text(previewText)
					.default_text_style_uncolored(styleSize: 13)
					.foregroundStyle(Color.grayMain2c400)
					.lineLimit(1)
			}
			.frame(maxWidth: .infinity, alignment: .leading)

			if store.teamUnreadCount > 0 {
				Text(store.teamUnreadCount < 100 ? String(store.teamUnreadCount) : "99+")
					.font(.system(size: 10, weight: .bold))
					.foregroundStyle(Color.white)
					.frame(minWidth: 22, minHeight: 22)
					.padding(.horizontal, store.teamUnreadCount > 9 ? 4 : 0)
					.background(Color.redDanger500)
					.clipShape(Capsule())
					.accessibilityLabel("\(store.teamUnreadCount) unread Mango9 messages")
			}
		}
		.frame(minHeight: 50)
	}

	private var previewText: String {
		guard let room = store.inboxPreviewRoom else {
			return store.isConnected ? "No new messages" : "Connect to Team Chat"
		}
		let message = room.lastMessage.trimmingCharacters(in: .whitespacesAndNewlines)
		let preview = message.isEmpty ? "Attachment" : message
		return "\(store.roomTitle(room)): \(preview)"
	}
}

private struct Mango9SMSConversationRow: View {
	let party: Mango9SMSParty

	var body: some View {
		HStack(spacing: 12) {
			ZStack {
				Circle()
					.fill(Color(uiColor: .systemBlue).opacity(0.12))
					.frame(width: 50, height: 50)
				Text(String(party.phone.suffix(10).first ?? "#"))
					.font(.system(size: 20, weight: .bold))
					.foregroundStyle(Color.grayMain2c700)
			}

			VStack(alignment: .leading, spacing: 4) {
				Text(Mango9CallerIdentity.formattedPhoneNumber(party.phone))
					.if(party.unread > 0) { $0.default_text_style_700(styleSize: 14) }
					.default_text_style(styleSize: 14)
					.foregroundStyle(Color.grayMain2c800)
					.lineLimit(1)
				Text(party.lastMessage.isEmpty ? "Attachment" : party.lastMessage)
					.default_text_style(styleSize: 13)
					.foregroundStyle(Color.grayMain2c400)
					.lineLimit(1)
			}
			.frame(maxWidth: .infinity, alignment: .leading)

			VStack(alignment: .trailing, spacing: 7) {
				Text(displayTime(party.latest))
					.default_text_style(styleSize: 11)
					.foregroundStyle(Color.grayMain2c400)
				if party.unread > 0 {
					Text(party.unread < 100 ? String(party.unread) : "99+")
						.font(.system(size: 10, weight: .bold))
						.foregroundStyle(Color.white)
						.frame(minWidth: 20, minHeight: 20)
						.background(Color.redDanger500)
						.clipShape(Capsule())
				}
			}
		}
		.frame(minHeight: 50)
	}

	private func displayTime(_ value: String) -> String {
		guard let date = Mango9SMSConversationAdapter.date(from: value) else { return "" }
		let formatter = DateFormatter()
		formatter.locale = Locale(identifier: "en_US_POSIX")
		formatter.timeZone = .autoupdatingCurrent
		formatter.dateFormat = Calendar.current.isDateInToday(date) ? "h:mma" : "MM-dd-yyyy"
		return formatter.string(from: date)
	}
}

struct ConversationRow: View {
	@EnvironmentObject var navigationManager: NavigationManager
	
	@EnvironmentObject var conversationsListViewModel: ConversationsListViewModel
	
	@ObservedObject var conversation: ConversationModel
	
	@Binding var showingSheet: Bool
	@Binding var text: String
	
	var body: some View {
		HStack {
			Avatar(contactAvatarModel: conversation.avatarModel, avatarSize: 50)
			
			VStack(spacing: 0) {
				Spacer()
				
				Text(conversation.subject)
					.foregroundStyle(Color.grayMain2c800)
					.if(conversation.unreadMessagesCount > 0) { view in
						view.default_text_style_700(styleSize: 14)
					}
					.default_text_style(styleSize: 14)
					.frame(maxWidth: .infinity, alignment: .leading)
					.lineLimit(1)
				
				HStack(spacing: 0) {
					Text(conversation.lastMessagePrefixText)
						.foregroundStyle(Color.grayMain2c400)
						.if(conversation.unreadMessagesCount > 0) { view in
							view.default_text_style_700(styleSize: 14)
						}
						.default_text_style(styleSize: 14)
						.lineLimit(1)
						.layoutPriority(1)
					
					if !conversation.lastMessageIcon.isEmpty {
						Image(conversation.lastMessageIcon)
							.resizable()
							.frame(width: 16, height: 16)
							.layoutPriority(0)
							.padding(.trailing, 2)
					}
					
					if conversation.lastMessageInItalic {
						Text(conversation.lastMessageText)
							.italic()
							.if(conversation.unreadMessagesCount > 0) { view in
								view.bold()
							}
							.foregroundStyle(Color.grayMain2c400)
							.font(.system(size: 14))
							.lineLimit(1)
							.layoutPriority(-1)
					} else {
						Text(conversation.lastMessageText)
							.foregroundStyle(Color.grayMain2c400)
							.if(conversation.unreadMessagesCount > 0) { view in
								view.default_text_style_700(styleSize: 14)
							}
							.default_text_style(styleSize: 14)
							.lineLimit(1)
							.layoutPriority(-1)
					}
				}
				.frame(maxWidth: .infinity, alignment: .leading)
				
				Spacer()
			}
			
			Spacer()
			
			VStack(alignment: .trailing, spacing: 0) {
				Spacer()
				
				HStack {
					Text(conversationsListViewModel.getCallTime(startDate: conversation.lastUpdateTime))
						.foregroundStyle(Color.grayMain2c400)
						.default_text_style(styleSize: 14)
						.lineLimit(1)
				}
				
				Spacer()
				
				HStack {
					if conversation.isMuted == false
						&& !(!conversation.lastMessageText.isEmpty
							 && conversation.lastMessageIsOutgoing == true)
						&& conversation.unreadMessagesCount == 0 {
						Text("")
							.frame(width: 18, height: 18, alignment: .trailing)
					}
					
					if !conversation.encryptionEnabled && conversation.isEndToEndEncryptionAvailable {
						Image("lock-simple-open")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.orangeWarning600)
							.frame(width: 16, height: 16, alignment: .trailing)
					}
					
					if conversation.isEphemeral {
						Image("clock-countdown")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.grayMain2c400)
							.frame(width: 18, height: 18, alignment: .trailing)
					}
					
					if conversation.isMuted {
						Image("bell-slash")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.grayMain2c400)
							.frame(width: 18, height: 18, alignment: .trailing)
					}
					
					if !conversation.lastMessageText.isEmpty
						&& conversation.lastMessageIsOutgoing == true {
						let imageName = LinphoneUtils.getChatIconState(chatState: conversation.lastMessageState)
						Image(imageName)
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.orangeMain500)
							.frame(width: 18, height: 18, alignment: .trailing)
					}
					
					if conversation.unreadMessagesCount > 0 {
						HStack {
							Text(
								conversation.unreadMessagesCount < 99
								? String(conversation.unreadMessagesCount)
								: "99+"
							)
							.foregroundStyle(.white)
							.default_text_style(styleSize: 10)
							.lineLimit(1)
						}
						.frame(width: 18, height: 18)
						.background(Color.redDanger500)
						.cornerRadius(50)
					}
				}
				
				Spacer()
			}
			.padding(.trailing, 10)
		}
		.frame(height: 50)
		.buttonStyle(.borderless)
		.listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
		.listRowSeparator(.hidden)
		.background(.white)
		.onTapGesture {
			conversationsListViewModel.changeDisplayedChatRoom(conversationModel: conversation)
		}
		.onLongPressGesture(minimumDuration: 0.2) {
			conversationsListViewModel.selectedConversation = conversation
			showingSheet.toggle()
		}
	}
}

#Preview {
	ConversationsListFragment(
		text: .constant(""),
		showingSheet: .constant(false)
	)
}
