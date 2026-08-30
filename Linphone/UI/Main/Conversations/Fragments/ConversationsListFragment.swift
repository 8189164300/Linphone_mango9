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

private enum Mango9ConversationTab: Hashable {
	case sms
	case team
}

struct ConversationsListFragment: View {
	@Environment(\.scenePhase) var scenePhase
	
	@EnvironmentObject var navigationManager: NavigationManager
	
	@ObservedObject var contactsManager = ContactsManager.shared
	@ObservedObject private var mango9ChatStore = Mango9ChatStore.shared
	
	@EnvironmentObject var conversationsListViewModel: ConversationsListViewModel
	
	@Binding var text: String
	@Binding var showingSheet: Bool
	@State private var mango9Tab: Mango9ConversationTab = .sms
	
	var body: some View {
		VStack(spacing: 0) {
			if hasMango9Account {
				Mango9ConversationTabs(
					selectedTab: $mango9Tab,
					smsUnreadCount: mango9ChatStore.smsUnreadCount,
					teamUnreadCount: mango9ChatStore.teamUnreadCount
				)
				Mango9ConversationConnectionStatus(
					isConnecting: mango9ChatStore.isConnecting,
					isConnected: mango9ChatStore.isConnected
				)
				if mango9Tab == .sms {
					mango9SMSConversationList
				} else {
					Mango9TeamChatListFragment(
						embedded: true,
						searchText: conversationsListViewModel.currentFilter
					)
				}
			} else {
				legacyConversationList
			}
		}
		.navigationTitle("")
		.navigationBarHidden(true)
		.onDisappear {
			if !conversationsListViewModel.currentFilter.isEmpty {
				conversationsListViewModel.resetFilterConversations()
			}
		}
		.onChange(of: scenePhase) { newPhase in
			if newPhase == .active {
				if navigationManager.peerAddr != nil {
					conversationsListViewModel.getChatRoomWithStringAddress(stringAddr: navigationManager.peerAddr!)
					navigationManager.peerAddr = nil
				}
			}
		}
		.task(id: mango9Tab) {
			guard hasMango9Account else { return }
			if mango9Tab == .sms {
				await mango9ChatStore.refreshSMSDirectory()
			} else {
				await mango9ChatStore.connectIfNeeded()
				await mango9ChatStore.refreshDirectory()
			}
		}
	}

	private var hasMango9Account: Bool {
		Mango9SessionStore.load() != nil
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
		conversationsListViewModel.conversationsList
	}

	private var mango9SMSConversationList: some View {
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
		}
		.listStyle(.plain)
		.refreshable {
			await mango9ChatStore.refreshSMSDirectory()
		}
		.overlay {
			if visibleSMSParties.isEmpty && !mango9ChatStore.isConnecting {
				Text(
					conversationsListViewModel.currentFilter.isEmpty
						? "No SMS conversations"
						: "No matching SMS conversations"
				)
				.default_text_style_800(styleSize: 16)
				.foregroundStyle(Color.grayMain2c500)
				.padding(24)
			}
		}
	}

	private var legacyConversationList: some View {
		List {
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
		.safeAreaInset(edge: .top) {
			Spacer().frame(height: 12)
		}
		.listStyle(.plain)
		.overlay {
			if visibleSIPConversations.isEmpty &&
				(
					conversationsListViewModel.currentFilter.isEmpty ||
					(!conversationsListViewModel.currentFilter.isEmpty &&
					 contactsManager.lastSearch.isEmpty &&
					 contactsManager.lastSearchSuggestions.isEmpty)
				) {
				VStack {
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
				.padding(.all)
			}
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

private struct Mango9ConversationTabs: View {
	@Binding var selectedTab: Mango9ConversationTab
	let smsUnreadCount: Int
	let teamUnreadCount: Int

	var body: some View {
		HStack(spacing: 0) {
			tabButton(
				title: "SMS",
				tab: .sms,
				unreadCount: smsUnreadCount
			)
			tabButton(
				title: "Team Chat",
				tab: .team,
				unreadCount: teamUnreadCount
			)
		}
		.frame(height: 48)
		.background(Color.white)
		.overlay(alignment: .bottom) {
			Rectangle()
				.fill(Color.gray200)
				.frame(height: 1)
		}
	}

	private func tabButton(
		title: String,
		tab: Mango9ConversationTab,
		unreadCount: Int
	) -> some View {
		let isSelected = selectedTab == tab
		return Button {
			withAnimation(.easeInOut(duration: 0.18)) {
				selectedTab = tab
			}
		} label: {
			VStack(spacing: 0) {
				Spacer(minLength: 2)
				HStack(spacing: 7) {
					Text(title)
						.font(.custom("NotoSans-SemiBold", size: 14))
						.foregroundStyle(isSelected ? Color.orangeMain500 : Color.grayMain2c500)
					if unreadCount > 0 {
						Text(unreadCount < 100 ? String(unreadCount) : "99+")
							.font(.system(size: 10, weight: .bold))
							.foregroundStyle(Color.white)
							.padding(.horizontal, 6)
							.frame(minHeight: 19)
							.background(Color.orangeMain500)
							.clipShape(Capsule())
					}
				}
				.frame(maxWidth: .infinity, maxHeight: .infinity)
				Capsule()
					.fill(isSelected ? Color.orangeMain500 : Color.clear)
					.frame(width: 54, height: 3)
			}
			.contentShape(Rectangle())
		}
		.buttonStyle(.plain)
		.frame(maxWidth: .infinity)
		.accessibilityAddTraits(isSelected ? .isSelected : [])
	}
}

private struct Mango9ConversationConnectionStatus: View {
	let isConnecting: Bool
	let isConnected: Bool

	var body: some View {
		Text(statusText)
			.default_text_style(styleSize: 11)
			.foregroundStyle(Color.grayMain2c500)
			.frame(maxWidth: .infinity, alignment: .leading)
			.padding(.horizontal, 16)
			.padding(.vertical, 7)
			.background(Color.white)
	}

	private var statusText: String {
		if isConnecting { return "Connecting…" }
		return isConnected ? "Connected" : "Disconnected"
	}
}

private struct Mango9SMSConversationRow: View {
	let party: Mango9SMSParty
	@State private var isMuted: Bool

	init(party: Mango9SMSParty) {
		self.party = party
		_isMuted = State(
			initialValue: Mango9SMSMutePreferences.isMuted(phone: party.phone)
		)
	}

	var body: some View {
		HStack(spacing: 12) {
			Image("profil-picture-default")
				.resizable()
				.frame(width: 50, height: 50)
				.clipShape(Circle())

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
				HStack(spacing: 6) {
					if isMuted {
						Image("bell-slash")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.grayMain2c400)
							.frame(width: 18, height: 18)
					}
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
		}
		.frame(minHeight: 50)
		.onAppear {
			isMuted = Mango9SMSMutePreferences.isMuted(phone: party.phone)
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9SMSMuteDidChange)) { notification in
			guard notification.object as? String == Mango9SMSMutePreferences.normalizedPhone(party.phone) else {
				return
			}
			isMuted = Mango9SMSMutePreferences.isMuted(phone: party.phone)
		}
		.onReceive(NotificationCenter.default.publisher(for: .mango9AccountContextChanged)) { _ in
			isMuted = Mango9SMSMutePreferences.isMuted(phone: party.phone)
		}
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
