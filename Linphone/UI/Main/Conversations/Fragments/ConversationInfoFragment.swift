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
import UniformTypeIdentifiers

// swiftlint:disable type_body_length
struct ConversationInfoFragment: View {
	@State private var orientation = UIDevice.current.orientation
	
	@ObservedObject var contactsManager = ContactsManager.shared
	
	@EnvironmentObject var conversationViewModel: ConversationViewModel
	@EnvironmentObject var conversationsListViewModel: ConversationsListViewModel
	@EnvironmentObject var accountProfileViewModel: AccountProfileViewModel
	
	@State var addParticipantsViewModel = AddParticipantsViewModel()
	var smsTarget: Mango9SMSTarget? = nil
	
	@Binding var isMuted: Bool
	@Binding var isShowEphemeralFragment: Bool
	@Binding var isShowMediaFilesFragment: Bool
	@Binding var isShowDocumentsFilesFragment: Bool
	@Binding var isShowStartCallGroupPopup: Bool
	@Binding var isShowInfoConversationFragment: Bool
	@Binding var isShowEditContactFragment: Bool
	@Binding var isShowEditContactFragmentAddress: String
	@Binding var isShowRemoveParticipantPopup: Bool
	
	@Binding var isShowScheduleMeetingFragment: Bool
	
	@Binding var isShowScheduleMeetingFragmentSubject: String
	@Binding var isShowScheduleMeetingFragmentParticipants: [SelectedAddressModel]
	
	@State private var participantListIsOpen = true
	@Binding var isShowConversationInfoPopup: Bool
	@Binding var conversationInfoPopupText: String
	
	@Binding var showLeaveConversationPopup: Bool
	@Binding var showDeleteConversationPopup: Bool
	@Binding var showDeleteConversationHistoryPopup: Bool

	private var displayedNumber: String {
		let candidates = [
			conversationViewModel.participantConversationModel.first?.address,
			conversationViewModel.peerAddress,
			SharedMainViewModel.shared.displayedConversation?.remoteSipUri
		]
		for candidate in candidates {
			if let number = Mango9CallerIdentity.dialedNumber(candidate) {
				return number
			}
		}
		return SharedMainViewModel.shared.displayedConversation?.avatarModel.name ?? ""
	}
	
	var body: some View {
		let accountModel = CoreContext.shared.accounts[accountProfileViewModel.accountModelIndex ?? 0]
		NavigationView {
			GeometryReader { geometry in
				if let smsTarget {
					smsConversationInfo(target: smsTarget, geometry: geometry)
				} else if SharedMainViewModel.shared.displayedConversation != nil {
					VStack(spacing: 1) {
						Rectangle()
							.foregroundColor(Color.orangeMain500)
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
									withAnimation {
										isShowInfoConversationFragment = false
									}
								}
							
							Spacer()
							
							Rectangle()
								.foregroundColor(.white)
								.frame(width: 45, height: 45)
						}
						.frame(maxWidth: .infinity)
						.frame(height: 50)
						.padding(.horizontal)
						.padding(.bottom, 4)
						.background(.white)
						
						ScrollView {
							VStack(spacing: 0) {
								VStack(spacing: 0) {
									if #unavailable(iOS 16.0) {
										Rectangle()
											.foregroundColor(Color.gray100)
											.frame(height: 7)
									}
									
									VStack(spacing: 0) {
										if SharedMainViewModel.shared.displayedConversation != nil && !SharedMainViewModel.shared.displayedConversation!.isGroup {
											
											Avatar(contactAvatarModel: SharedMainViewModel.shared.displayedConversation!.avatarModel, avatarSize: 100)
												.padding(.top, 4)
											
											Button {
												UIPasteboard.general.setValue(
													displayedNumber,
													forPasteboardType: UTType.plainText.identifier
												)
												ToastViewModel.shared.show("Success_address_copied_into_clipboard")
											} label: {
												HStack(spacing: 6) {
													Text(displayedNumber)
														.foregroundStyle(Color.grayMain2c700)
														.default_text_style(styleSize: 14)
													Image("copy")
														.renderingMode(.template)
														.resizable()
														.foregroundStyle(Color.grayMain2c500)
														.frame(width: 20, height: 20)
												}
												.padding(.top, 10)
											}
											
											if !SharedMainViewModel.shared.displayedConversation!.avatarModel.lastPresenceInfo.isEmpty {
												Text(SharedMainViewModel.shared.displayedConversation!.avatarModel.lastPresenceInfo)
													.foregroundStyle(SharedMainViewModel.shared.displayedConversation!.avatarModel.lastPresenceInfo == "Online"
																	 ? Color.greenSuccess500
																	 : Color.orangeWarning600)
													.multilineTextAlignment(.center)
													.default_text_style_300(styleSize: 12)
													.frame(maxWidth: .infinity)
													.frame(height: 20)
													.padding(.top, 5)
											} else {
												Text("")
													.multilineTextAlignment(.center)
													.default_text_style_300(styleSize: 12)
													.frame(maxWidth: .infinity)
													.frame(height: 20)
											}
										} else {
											Avatar(contactAvatarModel: SharedMainViewModel.shared.displayedConversation!.avatarModel, avatarSize: 100)
												.padding(.top, 4)
											
											HStack {
												Text(SharedMainViewModel.shared.displayedConversation!.avatarModel.name)
													.foregroundStyle(Color.grayMain2c700)
													.multilineTextAlignment(.center)
													.default_text_style(styleSize: 14)
													.padding(.top, 10)
												
												if conversationViewModel.isUserAdmin {
													Button(
														action: {
															isShowConversationInfoPopup = true
														},
														label: {
															Image("pencil-simple")
																.renderingMode(.template)
																.resizable()
																.foregroundStyle(Color.orangeMain500)
																.frame(width: 20, height: 20)
														}
													)
													.padding(.top, 10)
												}
											}
											.padding(.leading, conversationViewModel.isUserAdmin ? 20 : 0)
											
										}
									}
									.frame(minHeight: 150)
									.frame(maxWidth: .infinity)
									.padding(.top, 10)
									.padding(.bottom, 2)
									.background(Color.gray100)
									
									if !SharedMainViewModel.shared.displayedConversation!.isReadOnly {
										HStack {
											Spacer()
											
											Button(action: {
												SharedMainViewModel.shared.displayedConversation!.toggleMute()
												isMuted = !isMuted
											}, label: {
												VStack {
													HStack(alignment: .center) {
														Image(isMuted ? "bell-simple" : "bell-simple-slash")
															.renderingMode(.template)
															.resizable()
															.foregroundStyle(Color.grayMain2c600)
															.frame(width: 25, height: 25)
													}
													.padding(16)
													.background(Color.grayMain2c200)
													.cornerRadius(40)
													
													Text(isMuted ? "conversation_action_unmute" : "conversation_action_mute")
														.default_text_style(styleSize: 14)
														.frame(minWidth: 80)
														.lineLimit(1)
												}
											})
											.frame(width: geometry.size.width / 4)
											
											Spacer()
											
											Button(action: {
												if SharedMainViewModel.shared.displayedConversation!.isGroup {
													isShowStartCallGroupPopup.toggle()
												} else {
													SharedMainViewModel.shared.displayedConversation!.call()
												}
											}, label: {
												VStack {
													HStack(alignment: .center) {
														Image("phone")
															.renderingMode(.template)
															.resizable()
															.foregroundStyle(Color.grayMain2c600)
															.frame(width: 25, height: 25)
													}
													.padding(16)
													.background(Color.grayMain2c200)
													.cornerRadius(40)
													
													Text("conversation_action_call")
														.default_text_style(styleSize: 14)
														.frame(minWidth: 80)
														.lineLimit(1)
												}
											})
											.frame(width: geometry.size.width / 4)
											
											Spacer()
											
											Button(action: {
												if let displayedConversation = SharedMainViewModel.shared.displayedConversation {
													if displayedConversation.isGroup {
														isShowScheduleMeetingFragmentSubject = displayedConversation.subject
													}
													isShowScheduleMeetingFragmentParticipants = conversationViewModel.participants
													
													SharedMainViewModel.shared.displayedConversation = nil
													SharedMainViewModel.shared.changeIndexView(indexViewInt: 3)
													withAnimation {
														isShowScheduleMeetingFragment = true
													}
												}
											}, label: {
												VStack {
													HStack(alignment: .center) {
														Image("video-conference")
															.renderingMode(.template)
															.resizable()
															.foregroundStyle(Color.grayMain2c600)
															.frame(width: 25, height: 25)
													}
													.padding(16)
													.background(Color.grayMain2c200)
													.cornerRadius(40)
													
													Text("meeting_schedule_meeting_label")
														.default_text_style(styleSize: 14)
														.frame(minWidth: 80)
														.lineLimit(1)
												}
											})
											.frame(width: geometry.size.width / 4)
											
											Spacer()
										}
										.padding(.top, 20)
										.padding(.bottom, 10)
										.frame(maxWidth: .infinity)
										.background(Color.gray100)
									}
									
									if SharedMainViewModel.shared.displayedConversation!.isGroup {
										HStack(alignment: .center) {
											Text(String(format: NSLocalizedString("conversation_info_participants_list_title", comment: ""), conversationViewModel.participantConversationModel.count))
												.default_text_style_800(styleSize: 18)
												.frame(maxWidth: .infinity, alignment: .leading)
											
											Spacer()
											
											Image(participantListIsOpen ? "caret-up" : "caret-down")
												.renderingMode(.template)
												.resizable()
												.foregroundStyle(Color.grayMain2c600)
												.frame(width: 25, height: 25, alignment: .leading)
												.padding(.all, 10)
										}
										.padding(.top, 30)
										.padding(.bottom, 10)
										.padding(.horizontal, 20)
										.background(Color.gray100)
										.onTapGesture {
											withAnimation {
												participantListIsOpen.toggle()
											}
										}
										
										if participantListIsOpen {
											VStack(spacing: 0) {
												ForEach(conversationViewModel.participantConversationModel) { participantConversationModel in
													HStack {
														if conversationViewModel.myParticipantConversationModel != nil && conversationViewModel.myParticipantConversationModel!.address != participantConversationModel.address {
															Avatar(contactAvatarModel: participantConversationModel, avatarSize: 50)
														} else {
															let avatarSize = 50.0
															if accountModel.usesGeneratedDefaultAvatar {
																Image(uiImage: contactsManager.textToImage(
																	firstName: accountModel.avatarModel?.name,
																	lastName: ""))
																.resizable()
																.frame(width: avatarSize, height: avatarSize)
																.clipShape(Circle())
															} else {
																AsyncImage(url: CoreContext.shared.accounts[accountProfileViewModel.accountModelIndex!].imagePathAvatar) { image in
																	switch image {
																	case .empty:
																		ProgressView()
																			.frame(width: avatarSize, height: avatarSize)
																	case .success(let image):
																		image
																			.resizable()
																			.aspectRatio(contentMode: .fill)
																			.frame(width: avatarSize, height: avatarSize)
																			.clipShape(Circle())
																	case .failure:
																		Image(uiImage: contactsManager.textToImage(
																			firstName: accountModel.avatarModel?.name,
																			lastName: ""))
																		.resizable()
																		.frame(width: avatarSize, height: avatarSize)
																		.clipShape(Circle())
																	@unknown default:
																		EmptyView()
																	}
																}
															}
														}
														
														VStack {
															if conversationViewModel.myParticipantConversationModel != nil && conversationViewModel.myParticipantConversationModel!.address != participantConversationModel.address {
																Text(participantConversationModel.name)
																	.foregroundStyle(Color.grayMain2c700)
																	.default_text_style(styleSize: 14)
																	.frame(maxWidth: .infinity, alignment: .leading)
																	.lineLimit(1)
															} else {
																Text(accountModel.displayName.isEmpty ? participantConversationModel.name : accountModel.displayName)
																	.foregroundStyle(Color.grayMain2c700)
																	.default_text_style(styleSize: 14)
																	.frame(maxWidth: .infinity, alignment: .leading)
																	.lineLimit(1)
															}
															
															let participantConversationModelIsAdmin = conversationViewModel.participantConversationModelAdmin.first(
																where: {$0.address == participantConversationModel.address})
															
															if participantConversationModelIsAdmin != nil {
																Text("conversation_info_participant_is_admin_label")
																	.foregroundStyle(Color.grayMain2c400)
																	.default_text_style(styleSize: 12)
																	.frame(maxWidth: .infinity, alignment: .leading)
																	.lineLimit(1)
															}
														}
														
														if conversationViewModel.myParticipantConversationModel != nil && conversationViewModel.myParticipantConversationModel!.address != participantConversationModel.address {
															Menu {
																let addressConv = participantConversationModel.address
																			
																let friendIndex = contactsManager.lastSearch.firstIndex(
																				where: {$0.friend!.addresses.contains(where: {$0.asStringUriOnly() == addressConv})})
																
																let disableAddContact = AppServices.corePreferences.disableAddContact
																let hideContactEdition = AppServices.corePreferences.hideContactEdition

																if (!disableAddContact || (disableAddContact && friendIndex != nil)) && !hideContactEdition {
																	Button(
																		action: {
																			let addressConv = participantConversationModel.address

																			let friendIndex = contactsManager.avatarListModel.first(
																				where: {$0.addresses.contains(where: {$0 == addressConv})})
																			
																			SharedMainViewModel.shared.changeIndexView(indexViewInt: 0)
																			
																			if friendIndex != nil {
																				withAnimation {
																					SharedMainViewModel.shared.displayedFriend = friendIndex
																				}
																			} else {
																				withAnimation {
																					isShowEditContactFragment.toggle()
																					isShowEditContactFragmentAddress = String(participantConversationModel.address.dropFirst(4))
																				}
																			}
																			
																			SharedMainViewModel.shared.displayedConversation = nil
																		},
																		label: {
																			HStack {
																				if friendIndex != nil {
																					Image("address-book")
																						.renderingMode(.template)
																						.resizable()
																						.foregroundStyle(Color.grayMain2c600)
																						.frame(width: 25, height: 25)
																					
																					Text("conversation_info_menu_go_to_contact")
																						.default_text_style(styleSize: 16)
																						.frame(maxWidth: .infinity, alignment: .leading)
																						.lineLimit(1)
																				} else {
																					Image("user-plus")
																						.renderingMode(.template)
																						.resizable()
																						.foregroundStyle(Color.grayMain2c600)
																						.frame(width: 25, height: 25)
																					
																					Text("conversation_info_menu_add_to_contacts")
																						.default_text_style(styleSize: 16)
																						.frame(maxWidth: .infinity, alignment: .leading)
																						.lineLimit(1)
																				}
																			}
																		}
																	)
																}
																
																if conversationViewModel.isUserAdmin {
																	let participantConversationModelIsAdmin = conversationViewModel.participantConversationModelAdmin.first(
																		where: {$0.address == participantConversationModel.address})
																	
																	Button {
																		conversationViewModel.toggleAdminRights(address: participantConversationModel.address)
																	} label: {
																		HStack {
																			Text(participantConversationModelIsAdmin != nil ? "conversation_info_admin_menu_unset_participant_admin" : "conversation_info_admin_menu_set_participant_admin")
																			Spacer()
																			Image("user-circle")
																				.renderingMode(.template)
																				.resizable()
																				.foregroundStyle(Color.grayMain2c500)
																				.frame(width: 25, height: 25, alignment: .leading)
																				.padding(.all, 10)
																		}
																	}
																	
																	Button(role: .destructive) {
																		SharedMainViewModel.shared.participantAddressToRemove = participantConversationModel.address
																		self.isShowRemoveParticipantPopup.toggle()
																	} label: {
																		HStack {
																			Text("conversation_info_admin_menu_remove_participant")
																			Spacer()
																			Image("trash-simple-red")
																				.renderingMode(.template)
																				.resizable()
																				.foregroundStyle(Color.grayMain2c500)
																				.frame(width: 25, height: 25, alignment: .leading)
																				.padding(.all, 10)
																		}
																	}
																}
															} label: {
																Image("dots-three-vertical")
																	.renderingMode(.template)
																	.resizable()
																	.foregroundStyle(Color.grayMain2c500)
																	.frame(width: 25, height: 25, alignment: .leading)
																	.padding(.all, 10)
																	.padding(.top, 4)
															}
														}
													}
													.padding(.vertical, 15)
													.padding(.horizontal, 20)
												}
												
												if conversationViewModel.isUserAdmin {
													NavigationLink(destination: {
														AddParticipantsFragment(addParticipantsViewModel: addParticipantsViewModel, confirmAddParticipantsFunc: conversationViewModel.addParticipants, dismissOnCheckClick: true)
															.onAppear {
																conversationViewModel.getParticipants()
																addParticipantsViewModel.participantsToAdd = conversationViewModel.participants
															}
													}, label: {
														HStack {
															Image("plus-circle")
																.renderingMode(.template)
																.resizable()
																.foregroundStyle(Color.orangeMain500)
																.frame(width: 20, height: 20)
															
															Text("conversation_info_add_participants_label")
																.default_text_style_orange_500(styleSize: 14)
																.frame(height: 35)
														}
														
													})
													.padding(.horizontal, 20)
													.padding(.vertical, 5)
													.background(Color.orangeMain100)
													.cornerRadius(60)
													.padding(.top, 10)
													.padding(.bottom, 20)
													
													/*
													Button(
														action: {
														},
														label: {
															HStack {
																Image("plus-circle")
																	.renderingMode(.template)
																	.resizable()
																	.foregroundStyle(Color.orangeMain500)
																	.frame(width: 20, height: 20)
																
																Text("conversation_info_add_participants_label")
																	.default_text_style_orange_500(styleSize: 14)
																	.frame(height: 35)
															}
														}
													)
													.padding(.horizontal, 20)
													.padding(.vertical, 5)
													.background(Color.orangeMain100)
													.cornerRadius(60)
													.padding(.top, 10)
													.padding(.bottom, 20)
													 */
												}
											}
											.background(.white)
											.cornerRadius(15)
											.padding(.horizontal)
											.zIndex(-1)
											.transition(.move(edge: .top))
										}
									}
									
									Text("conversation_details_media_documents_title")
										.default_text_style_800(styleSize: 18)
										.frame(maxWidth: .infinity, alignment: .leading)
										.padding(.horizontal, 20)
										.padding(.top, 20)
									
									VStack(spacing: 0) {
										Button(
											action: {
												withAnimation {
													isShowMediaFilesFragment = true
												}
											},
											label: {
												HStack {
													Image("image")
														.renderingMode(.template)
														.resizable()
														.foregroundStyle(Color.grayMain2c600)
														.frame(width: 25, height: 25)
													
													Text("conversation_menu_media_files")
														.default_text_style(styleSize: 16)
														.frame(maxWidth: .infinity, alignment: .leading)
														.lineLimit(1)
													
												}
											}
										)
										.frame(height: 60)
										
										Divider()
										
										Button(
											action: {
												withAnimation {
													isShowDocumentsFilesFragment = true
												}
											},
											label: {
												HStack {
													Image("file-pdf")
														.renderingMode(.template)
														.resizable()
														.foregroundStyle(Color.grayMain2c600)
														.frame(width: 25, height: 25)
													
													Text("conversation_menu_documents_files")
														.default_text_style(styleSize: 16)
														.frame(maxWidth: .infinity, alignment: .leading)
														.lineLimit(1)
													
												}
											}
										)
										.frame(height: 60)
									}
									.padding(.horizontal, 20)
									.padding(.vertical, 4)
									.background(.white)
									.cornerRadius(15)
									.padding(.all)
									
									Text("contact_details_actions_title")
										.default_text_style_800(styleSize: 18)
										.frame(maxWidth: .infinity, alignment: .leading)
										.padding(.horizontal, 20)
										.padding(.top, 20)
									
									VStack(spacing: 0) {
										if !SharedMainViewModel.shared.displayedConversation!.isReadOnly {
											let addressConv = conversationViewModel.participantConversationModel.first?.address ?? ""
											
											let friendIndex = contactsManager.lastSearch.firstIndex(
												where: {$0.friend!.addresses.contains(where: {$0.asStringUriOnly() == addressConv})})
											
											let disableAddContact = AppServices.corePreferences.disableAddContact
											let hideContactEdition = AppServices.corePreferences.hideContactEdition

											if !SharedMainViewModel.shared.displayedConversation!.isGroup && (!disableAddContact || (disableAddContact && friendIndex != nil)) && !hideContactEdition {
												Button(
													action: {
														if SharedMainViewModel.shared.displayedConversation != nil {
															if let participantConversationModel = conversationViewModel.participantConversationModel.first {
																let addressConv = participantConversationModel.address
																
																let friendIndex = contactsManager.avatarListModel.first(
																	where: {$0.addresses.contains(where: {$0 == addressConv})})
																
																SharedMainViewModel.shared.displayedCall = nil
																SharedMainViewModel.shared.changeIndexView(indexViewInt: 0)
																
																if friendIndex != nil {
																	withAnimation {
																		SharedMainViewModel.shared.displayedFriend = friendIndex
																	}
																} else {
																	withAnimation {
																		isShowEditContactFragment.toggle()
																		isShowEditContactFragmentAddress = String(participantConversationModel.address.dropFirst(4))
																	}
																}
															}
														}
													},
													label: {
														HStack {
															if friendIndex != nil {
																Image("address-book")
																 .renderingMode(.template)
																 .resizable()
																 .foregroundStyle(Color.grayMain2c600)
																 .frame(width: 25, height: 25)
															 
															 Text("conversation_info_menu_go_to_contact")
																 .default_text_style(styleSize: 16)
																 .frame(maxWidth: .infinity, alignment: .leading)
																 .lineLimit(1)
															} else {
																Image("user-plus")
																	.renderingMode(.template)
																	.resizable()
																	.foregroundStyle(Color.grayMain2c600)
																	.frame(width: 25, height: 25)
																
																Text("conversation_info_menu_add_to_contacts")
																	.default_text_style(styleSize: 16)
																	.frame(maxWidth: .infinity, alignment: .leading)
																	.lineLimit(1)
															}
														}
													}
												)
												.frame(height: 60)
												
												Divider()
											}
											
											Button(
												action: {
													withAnimation {
														isShowEphemeralFragment = true
													}
												},
												label: {
													HStack {
														Image("clock-countdown")
															.renderingMode(.template)
															.resizable()
															.foregroundStyle(Color.grayMain2c600)
															.frame(width: 25, height: 25)
														
														Text("conversation_action_configure_ephemeral_messages")
															.default_text_style(styleSize: 16)
															.frame(maxWidth: .infinity, alignment: .leading)
															.lineLimit(1)
														
													}
												}
											)
											.frame(height: 60)
											
											Divider()
											
											if SharedMainViewModel.shared.displayedConversation!.isGroup {
												Button(
													action: {
														conversationsListViewModel.targetConversation = SharedMainViewModel.shared.displayedConversation!
							 							showLeaveConversationPopup = true
														isShowInfoConversationFragment = false
													},
													label: {
														HStack {
															Image("sign-out")
																.renderingMode(.template)
																.resizable()
																.foregroundStyle(Color.grayMain2c600)
																.frame(width: 25, height: 25)
															
															Text("conversation_action_leave_group")
																.default_text_style(styleSize: 16)
																.frame(maxWidth: .infinity, alignment: .leading)
																.lineLimit(1)
															
														}
													}
												)
												.frame(height: 60)
												
												Divider()
											}
										}
										
										Button(
											action: {
												conversationsListViewModel.targetConversation = SharedMainViewModel.shared.displayedConversation!
												showDeleteConversationHistoryPopup = true
												isShowInfoConversationFragment = false
											},
											label: {
												HStack {
													Image("trash-simple")
														.renderingMode(.template)
														.resizable()
														.foregroundStyle(Color.redDanger500)
														.frame(width: 25, height: 25)
													
													Text("conversation_info_delete_history_action")
														.foregroundStyle(Color.redDanger500)
														.default_text_style(styleSize: 16)
														.frame(maxWidth: .infinity, alignment: .leading)
														.lineLimit(1)
													
												}
											}
										)
										.frame(height: 60)
									}
									.padding(.horizontal, 20)
									.padding(.vertical, 4)
									.background(.white)
									.cornerRadius(15)
									.padding(.all)
								}
								.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
							}
							.frame(maxWidth: .infinity)
							.padding(.top, 2)
						}
						.background(Color.gray100)
					}
					.background(.white)
					.navigationBarHidden(true)
					.onAppear {
						conversationViewModel.getParticipants()
					}
					.onRotate { newOrientation in
						orientation = newOrientation
					}
				}
			}
		}
		.navigationViewStyle(.stack)
	}

	private func smsConversationInfo(target: Mango9SMSTarget, geometry: GeometryProxy) -> some View {
		VStack(spacing: 1) {
			Rectangle()
				.foregroundColor(Color.orangeMain500)
				.edgesIgnoringSafeArea(.top)
				.frame(height: 0)

			HStack {
				Image("caret-left")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.orangeMain500)
					.frame(width: 25, height: 25)
					.padding(10)
					.padding(.leading, -10)
					.onTapGesture {
						withAnimation { isShowInfoConversationFragment = false }
					}
				Spacer()
				Rectangle().foregroundColor(.white).frame(width: 45, height: 45)
			}
			.frame(height: 50)
			.padding(.horizontal)
			.background(.white)

			ScrollView {
				VStack(spacing: 0) {
					VStack(spacing: 8) {
						Avatar(contactAvatarModel: conversationViewModel.conversationAvatar, avatarSize: 100)
						Text(target.name)
							.default_text_style_800(styleSize: 18)
						Button {
							UIPasteboard.general.setValue(
								target.phone,
								forPasteboardType: UTType.plainText.identifier
							)
							ToastViewModel.shared.show("Success_address_copied_into_clipboard")
						} label: {
							HStack(spacing: 6) {
								Text(Mango9CallerIdentity.formattedPhoneNumber(target.phone))
									.default_text_style(styleSize: 14)
								Image("copy")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.grayMain2c500)
									.frame(width: 20, height: 20)
							}
						}
					}
					.padding(.vertical, 20)
					.frame(maxWidth: .infinity)
					.background(Color.gray100)

					HStack {
						Spacer()
						Button {
							isMuted = conversationViewModel.toggleConversationMute()
						} label: {
							VStack {
								Image(isMuted ? "bell-simple" : "bell-simple-slash")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.grayMain2c600)
									.frame(width: 25, height: 25)
									.padding(16)
									.background(Color.grayMain2c200)
									.clipShape(Circle())
								Text(isMuted ? "conversation_action_unmute" : "conversation_action_mute")
									.default_text_style(styleSize: 14)
							}
						}
						.frame(width: geometry.size.width / 3)
						Spacer()
						Button { conversationViewModel.callActiveConversation() } label: {
							VStack {
								Image("phone")
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.grayMain2c600)
									.frame(width: 25, height: 25)
									.padding(16)
									.background(Color.grayMain2c200)
									.clipShape(Circle())
								Text("conversation_action_call")
									.default_text_style(styleSize: 14)
							}
						}
						.frame(width: geometry.size.width / 3)
						Spacer()
					}
					.padding(.vertical, 20)

					VStack(spacing: 0) {
						Button { isShowMediaFilesFragment = true } label: {
							Label("conversation_menu_media_files", image: "image")
								.default_text_style(styleSize: 15)
								.frame(maxWidth: .infinity, alignment: .leading)
								.padding(18)
						}
						Divider().padding(.horizontal, 18)
						Button { isShowDocumentsFilesFragment = true } label: {
							Label("conversation_menu_documents_files", image: "file-pdf")
								.default_text_style(styleSize: 15)
								.frame(maxWidth: .infinity, alignment: .leading)
								.padding(18)
						}
					}
					.background(.white)
					.cornerRadius(14)
					.padding(20)

					smsActivitySection
					crmRecordSection
					localCallSection
				}
			}
			.background(Color.gray100)
		}
		.onAppear {
			conversationViewModel.refreshSMSConversationInsights()
		}
	}

	private var smsActivitySection: some View {
		let stats = conversationViewModel.smsStats
		return VStack(alignment: .leading, spacing: 12) {
			Text("Conversation activity")
				.default_text_style_800(styleSize: 18)
			Text("Based on messages available from Mango9.")
				.default_text_style(styleSize: 13)
				.foregroundStyle(Color.grayMain2c600)

			HStack(spacing: 10) {
				smsInsightMetric(title: "Messages", value: String(stats.total))
				smsInsightMetric(title: "Received", value: String(stats.received))
				smsInsightMetric(title: "Sent", value: String(stats.sent))
			}
			HStack(spacing: 10) {
				smsInsightMetric(title: "Attachments", value: String(stats.attachments))
				if stats.failed > 0 {
					smsInsightMetric(title: "Failed", value: String(stats.failed), isWarning: true)
				}
			}

			if let first = stats.firstAvailableMessage {
				smsInsightRow(title: "First available SMS", value: smsInsightDate(first))
			}
			if let latest = stats.latestMessage {
				smsInsightRow(title: "Latest SMS", value: smsInsightDate(latest))
			}
			if !stats.senderIDs.isEmpty {
				smsInsightRow(
					title: "Mango9 number",
					value: stats.senderIDs
						.map(Mango9CallerIdentity.formattedPhoneNumber)
						.joined(separator: ", ")
				)
			}
		}
		.padding(18)
		.background(.white)
		.cornerRadius(14)
		.padding(.horizontal, 20)
		.padding(.bottom, 20)
	}

	@ViewBuilder
	private var crmRecordSection: some View {
		if conversationViewModel.smsInsightsAreLoading && !conversationViewModel.smsCRMLookupComplete {
			HStack(spacing: 12) {
				ProgressView()
				Text("Checking CRM record…")
					.default_text_style(styleSize: 14)
			}
			.frame(maxWidth: .infinity, alignment: .leading)
			.padding(18)
			.background(.white)
			.cornerRadius(14)
			.padding(.horizontal, 20)
			.padding(.bottom, 20)
		} else if let match = conversationViewModel.smsCRMMatch {
			VStack(alignment: .leading, spacing: 12) {
				Text("CRM record")
					.default_text_style_800(styleSize: 18)
				smsInsightRow(
					title: match.kind.singular,
					value: match.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
						? "Unnamed \(match.kind.singular.lowercased())"
						: match.name
				)
				if !match.ownerName.isEmpty {
					smsInsightRow(title: "Owner", value: match.ownerName)
				}
				if !match.status.isEmpty {
					smsInsightRow(title: "Status", value: match.status)
				}
				if !match.createdAt.isEmpty {
					smsInsightRow(title: "Created", value: smsCRMDate(match.createdAt))
				}
			}
			.padding(18)
			.background(.white)
			.cornerRadius(14)
			.padding(.horizontal, 20)
			.padding(.bottom, 20)
		} else if conversationViewModel.smsCRMLookupComplete,
			let statusMessage = conversationViewModel.smsCRMStatusMessage {
			VStack(alignment: .leading, spacing: 8) {
				Text("CRM record")
					.default_text_style_800(styleSize: 18)
				Text(statusMessage)
					.default_text_style(styleSize: 14)
					.foregroundStyle(Color.grayMain2c600)
			}
			.frame(maxWidth: .infinity, alignment: .leading)
			.padding(18)
			.background(.white)
			.cornerRadius(14)
			.padding(.horizontal, 20)
			.padding(.bottom, 20)
		}
	}

	@ViewBuilder
	private var localCallSection: some View {
		if conversationViewModel.smsCallLookupComplete {
			let stats = conversationViewModel.smsLocalCallStats
			VStack(alignment: .leading, spacing: 12) {
				Text("Calls on this iPhone")
					.default_text_style_800(styleSize: 18)
				Text("This includes matching call history stored on this device, not PBX-wide history.")
					.default_text_style(styleSize: 13)
					.foregroundStyle(Color.grayMain2c600)
				HStack(spacing: 10) {
					smsInsightMetric(title: "Calls", value: String(stats.total))
					smsInsightMetric(title: "Inbound", value: String(stats.inbound))
					smsInsightMetric(title: "Outbound", value: String(stats.outbound))
				}
				if stats.missed > 0 {
					smsInsightRow(title: "Missed", value: String(stats.missed))
				}
				if stats.connectedDuration > 0 {
					smsInsightRow(
						title: "Connected time",
						value: smsCallDuration(stats.connectedDuration)
					)
				}
				if let lastCall = stats.lastCall {
					smsInsightRow(title: "Last call", value: smsInsightDate(lastCall))
				}
			}
			.padding(18)
			.background(.white)
			.cornerRadius(14)
			.padding(.horizontal, 20)
			.padding(.bottom, 28)
		}
	}

	private func smsInsightMetric(
		title: String,
		value: String,
		isWarning: Bool = false
	) -> some View {
		VStack(alignment: .leading, spacing: 4) {
			Text(value)
				.default_text_style_800(styleSize: 20)
				.foregroundStyle(isWarning ? Color.redDanger500 : Color.orangeMain500)
			Text(title)
				.default_text_style(styleSize: 12)
				.foregroundStyle(Color.grayMain2c600)
				.lineLimit(1)
				.minimumScaleFactor(0.8)
		}
		.frame(maxWidth: .infinity, alignment: .leading)
		.padding(12)
		.background(Color.gray100)
		.cornerRadius(10)
	}

	private func smsInsightRow(title: String, value: String) -> some View {
		HStack(alignment: .firstTextBaseline, spacing: 12) {
			Text(title)
				.default_text_style(styleSize: 14)
				.foregroundStyle(Color.grayMain2c600)
			Spacer(minLength: 10)
			Text(value)
				.default_text_style_800(styleSize: 14)
				.multilineTextAlignment(.trailing)
		}
		.frame(maxWidth: .infinity)
	}

	private func smsInsightDate(_ date: Date) -> String {
		date.formatted(date: .abbreviated, time: .shortened)
	}

	private func smsCRMDate(_ value: String) -> String {
		if let date = Mango9SMSConversationAdapter.date(from: value) {
			return date.formatted(date: .abbreviated, time: .omitted)
		}
		return value.replacingOccurrences(of: "T", with: " ")
	}

	private func smsCallDuration(_ seconds: Int) -> String {
		let hours = seconds / 3600
		let minutes = (seconds % 3600) / 60
		let remainingSeconds = seconds % 60
		if hours > 0 { return "\(hours)h \(minutes)m" }
		if minutes > 0 { return "\(minutes)m \(remainingSeconds)s" }
		return "\(remainingSeconds)s"
	}
}

#Preview {
	ConversationInfoFragment(
		isMuted: .constant(false),
		isShowEphemeralFragment: .constant(false),
		isShowMediaFilesFragment: .constant(false),
		isShowDocumentsFilesFragment: .constant(false),
		isShowStartCallGroupPopup: .constant(false),
		isShowInfoConversationFragment: .constant(true),
		isShowEditContactFragment: .constant(false),
		isShowEditContactFragmentAddress: .constant(""),
		isShowRemoveParticipantPopup: .constant(false),
		isShowScheduleMeetingFragment: .constant(false),
		isShowScheduleMeetingFragmentSubject: .constant(""),
		isShowScheduleMeetingFragmentParticipants: .constant([]),
		isShowConversationInfoPopup: .constant(false),
		conversationInfoPopupText: .constant(""),
		showLeaveConversationPopup: .constant(false),
		showDeleteConversationPopup: .constant(false),
		showDeleteConversationHistoryPopup: .constant(false)
	)
}
// swiftlint:enable type_body_length
