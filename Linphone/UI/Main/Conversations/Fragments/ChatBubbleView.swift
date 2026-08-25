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
import WebKit
import QuickLook
import Combine
import AVFoundation
import Photos
import ImageIO

// swiftlint:disable type_body_length
// swiftlint:disable cyclomatic_complexity
struct ChatBubbleView: View {
	
	private var idiom: UIUserInterfaceIdiom { UIDevice.current.userInterfaceIdiom }
	
	@EnvironmentObject var conversationViewModel: ConversationViewModel
	
	let eventLogMessage: EventLogMessage
	
	let geometryProxy: GeometryProxy
	
	@State private var ticker = Ticker()
	@State private var isPressed: Bool = false
	@State private var didLongPress = false
	@State private var timePassed: TimeInterval?
	
	@State private var timer: Timer?
	@State private var ephemeralLifetime: String = ""
	
	@State private var selectedAttachment: Bool = false
	@State private var selectedAttachmentIndex: Int = 0
	
	@State private var selectedURLAttachment: URL?
	@State private var previewImageAttachment: Attachment?
	
	@State private var showShareSheet = false
	
	var body: some View {
		HStack {
			if eventLogMessage.eventModel.eventLogType == .ConferenceChatMessage {
				VStack {
					if !eventLogMessage.message.text.isEmpty || !eventLogMessage.message.attachments.isEmpty || eventLogMessage.message.isIcalendar || eventLogMessage.message.isRetracted {
						HStack(alignment: .top, content: {
							if eventLogMessage.message.isOutgoing {
								Spacer()
							}
							if conversationViewModel.conversationIsGroup
								&& !eventLogMessage.message.isOutgoing && eventLogMessage.message.isFirstMessage {
								VStack {
									Avatar(
										contactAvatarModel: conversationViewModel.participantConversationModel.first(where: {$0.address == eventLogMessage.message.address}) ??
										ContactAvatarModel(friend: nil, name: "??", address: "", withPresence: false),
										avatarSize: 35
									)
									.padding(.top, 30)
								}
							} else if conversationViewModel.conversationIsGroup && !eventLogMessage.message.isOutgoing {
								VStack {
								}
								.padding(.leading, 43)
							}
							
							VStack(alignment: .leading, spacing: 0) {
								if conversationViewModel.conversationIsGroup
									&& !eventLogMessage.message.isOutgoing && eventLogMessage.message.isFirstMessage {
									Text(conversationViewModel.participantConversationModel.first(where: {$0.address == eventLogMessage.message.address})?.name ?? "")
										.default_text_style(styleSize: 12)
										.padding(.top, 5)
										.padding(.bottom, 2)
								}
								
								if eventLogMessage.message.isForward {
									HStack {
										if eventLogMessage.message.isOutgoing {
											Spacer()
										}
										
										VStack(alignment: eventLogMessage.message.isOutgoing ? .trailing : .leading, spacing: 0) {
											HStack {
												Image("forward")
													.resizable()
													.frame(width: 15, height: 15, alignment: .leading)
												
												Text("message_forwarded_label")
													.default_text_style(styleSize: 12)
											}
											.padding(.bottom, 2)
										}
										
										if !eventLogMessage.message.isOutgoing {
											Spacer()
										}
									}
									.frame(maxWidth: .infinity)
								}
								
								if eventLogMessage.message.replyMessage != nil {
									HStack {
										if eventLogMessage.message.isOutgoing {
											Spacer()
										}
										
										VStack(alignment: eventLogMessage.message.isOutgoing ? .trailing : .leading, spacing: 0) {
											HStack {
												Image("reply")
													.resizable()
													.frame(width: 15, height: 15, alignment: .leading)
												
												Text(conversationViewModel.participantConversationModel.first(
													where: {$0.address == eventLogMessage.message.replyMessage!.address})?.name ?? "")
												.default_text_style(styleSize: 12)
											}
											.padding(.bottom, 2)
											
											VStack(alignment: eventLogMessage.message.isOutgoing ? .trailing : .leading) {
												if !eventLogMessage.message.replyMessage!.text.isEmpty {
													Text(eventLogMessage.message.replyMessage!.text)
														.foregroundStyle(Color.grayMain2c700)
														.default_text_style(styleSize: 14)
														.lineLimit(/*@START_MENU_TOKEN@*/2/*@END_MENU_TOKEN@*/)
												} else if !eventLogMessage.message.replyMessage!.attachmentsNames.isEmpty {
													Text(eventLogMessage.message.replyMessage!.attachmentsNames)
														.foregroundStyle(Color.grayMain2c700)
														.default_text_style(styleSize: 14)
														.lineLimit(/*@START_MENU_TOKEN@*/2/*@END_MENU_TOKEN@*/)
												} else if eventLogMessage.message.replyMessage!.isRetracted {
													Text(eventLogMessage.message.replyMessage!.isOutgoing ? "conversation_message_content_deleted_by_us_label" : "conversation_message_content_deleted_label")
														.italic()
														.foregroundStyle(Color.grayMain2c500)
														.font(.system(size: 14))
														.lineLimit(1)
												}
											}
											.padding(.all, 15)
											.padding(.bottom, 15)
											.background(Color.gray200)
											.clipShape(RoundedRectangle(cornerRadius: 1))
											.roundedCorner(
												16,
												corners: eventLogMessage.message.isOutgoing ? [.topLeft, .topRight, .bottomLeft] : [.topLeft, .topRight, .bottomRight],
												stroke: eventLogMessage.message.id == conversationViewModel.highlightedMessageID
											)
										}
										.onTapGesture {
											conversationViewModel.scrollToMessage(message: eventLogMessage.message)
										}
										
										if !eventLogMessage.message.isOutgoing {
											Spacer()
										}
									}
									.frame(maxWidth: .infinity)
									.padding(.bottom, -20)
								}
								
								ZStack {
									HStack {
										if eventLogMessage.message.isOutgoing {
											Spacer()
										}
										
										VStack(alignment: eventLogMessage.message.isOutgoing ? .trailing : .leading) {
											VStack(alignment: eventLogMessage.message.isOutgoing ? .trailing : .leading) {
												if !eventLogMessage.message.attachments.isEmpty && !eventLogMessage.message.isIcalendar {
													messageAttachments()
												}
												
												if !eventLogMessage.message.text.isEmpty {
											DynamicLinkText(
												text: eventLogMessage.message.text,
												isMessageId: eventLogMessage.message.id == conversationViewModel.highlightedMessageID,
												searchText: conversationViewModel.searchText,
												participantConversationModel: conversationViewModel.participantConversationModel,
												foregroundColor: conversationViewModel.isSMSConversation && eventLogMessage.message.isOutgoing
													? .white
													: Color.grayMain2c700,
												linkColor: conversationViewModel.isSMSConversation && eventLogMessage.message.isOutgoing
													? .white
													: .blue
											)
												} else if eventLogMessage.message.isRetracted {
													Text(eventLogMessage.message.isOutgoing ? "conversation_message_content_deleted_by_us_label" : "conversation_message_content_deleted_label")
														.italic()
														.foregroundStyle(Color.grayMain2c500)
														.font(.system(size: 14))
														.lineLimit(1)
												}
												
												if eventLogMessage.message.isIcalendar && eventLogMessage.message.messageConferenceInfo != nil {
													VStack(spacing: 0) {
														VStack {
															if eventLogMessage.message.messageConferenceInfo!.meetingState != .new {
																if eventLogMessage.message.messageConferenceInfo!.meetingState == .updated {
																	Text("conversation_message_meeting_updated_label")
																		.foregroundStyle(Color.orangeWarning600)
																		.default_text_style_600(styleSize: 12)
																		.lineLimit(1)
																		.frame(maxWidth: .infinity, alignment: .leading)
																		.padding(.bottom, 5)
																} else {
																	Text("conversation_message_meeting_cancelled_label")
																		.foregroundStyle(Color.redDanger500)
																		.default_text_style_600(styleSize: 12)
																		.lineLimit(1)
																		.frame(maxWidth: .infinity, alignment: .leading)
																		.padding(.bottom, 5)
																}
															}
															
															HStack {
																VStack(spacing: 0) {
																	Text(eventLogMessage.message.messageConferenceInfo!.meetingDay)
																		.default_text_style(styleSize: 16)
																	
																	Text(eventLogMessage.message.messageConferenceInfo!.meetingDayNumber)
																		.foregroundStyle(.white)
																		.default_text_style_800(styleSize: 18)
																		.lineLimit(1)
																		.frame(width: 30, height: 30, alignment: .center)
																		.background(Color.orangeMain500)
																		.clipShape(Circle())
																	
																}
																.padding(.all, 10)
																.frame(width: 70, height: 70)
																.background(.white)
																.cornerRadius(15)
																.shadow(color: .black.opacity(0.1), radius: 15)
																
																VStack {
																	HStack {
																		Image("video-conference")
																			.renderingMode(.template)
																			.resizable()
																			.foregroundStyle(Color.grayMain2c600)
																			.frame(width: 25, height: 25)
																		
																		Text(eventLogMessage.message.messageConferenceInfo!.meetingSubject)
																			.default_text_style_800(styleSize: 15)
																			.lineLimit(1)
																			.frame(maxWidth: .infinity, alignment: .leading)
																	}
																	.frame(maxWidth: .infinity, alignment: .leading)
																	
																	Text(eventLogMessage.message.messageConferenceInfo!.meetingDate)
																		.default_text_style_300(styleSize: 14)
																		.lineLimit(1)
																		.frame(maxWidth: .infinity, alignment: .leading)
																	
																	Text(eventLogMessage.message.messageConferenceInfo!.meetingTime)
																		.default_text_style_300(styleSize: 14)
																		.lineLimit(1)
																		.frame(maxWidth: .infinity, alignment: .leading)
																}
																.padding(.leading, 5)
															}
															.frame(maxWidth: .infinity)
														}
														.padding(.all, 15)
														.frame(maxWidth: .infinity)
														.background(Color.gray100)
														
														VStack(spacing: 2) {
															if !eventLogMessage.message.messageConferenceInfo!.meetingDescription.isEmpty {
																Text("meeting_schedule_description_title")
																	.default_text_style(styleSize: 14)
																	.frame(maxWidth: .infinity, alignment: .leading)
																
																Text(eventLogMessage.message.messageConferenceInfo!.meetingDescription)
																	.default_text_style_300(styleSize: 14)
																	.frame(maxWidth: .infinity, alignment: .leading)
															}
															
															if eventLogMessage.message.messageConferenceInfo!.meetingState != .cancelled {
																HStack {
																	Image("users")
																		.renderingMode(.template)
																		.resizable()
																		.foregroundStyle(Color.grayMain2c600)
																		.frame(width: 20, height: 20)
																	
																	Text(eventLogMessage.message.messageConferenceInfo!.meetingParticipants)
																		.default_text_style(styleSize: 14)
																		.frame(maxWidth: .infinity, alignment: .leading)
																	
																	Button(action: {
																		conversationViewModel.joinMeetingInvite(addressUri: eventLogMessage.message.messageConferenceInfo!.meetingConferenceUri)
																	}, label: {
																		Text("meeting_waiting_room_join")
																			.default_text_style_white_600(styleSize: 14)
																	})
																	.padding(.horizontal, 15)
																	.padding(.vertical, 10)
																	.background(Color.orangeMain500)
																	.cornerRadius(60)
																}
																.padding(.top, !eventLogMessage.message.messageConferenceInfo!.meetingDescription.isEmpty ? 10 : 0)
															}
														}
														.padding(.all,
															eventLogMessage.message.messageConferenceInfo!.meetingState != .cancelled
															|| !eventLogMessage.message.messageConferenceInfo!.meetingDescription.isEmpty
															? 15
															: 0
														)
														.frame(maxWidth: .infinity)
														.background(.white)
													}
													.frame(width: geometryProxy.size.width >= 110 ? geometryProxy.size.width - 110 : geometryProxy.size.width)
													.background(.white)
													.cornerRadius(10)
												}
												
												HStack(alignment: .center) {
													if eventLogMessage.message.isEphemeral && eventLogMessage.message.isOutgoing {
														Text(ephemeralLifetime)
															.foregroundStyle(Color.grayMain2c500)
															.default_text_style_300(styleSize: 12)
															.padding(.top, 1)
															.padding(.trailing, -4)
															.onAppear {
																updateEphemeralTimer()
															}
															.onChange(of: eventLogMessage.message.ephemeralExpireTime) { ephemeralExpireTimeTmp in
																if ephemeralExpireTimeTmp > 0 {
																	updateEphemeralTimer()
																}
															}
														
														Image("clock-countdown")
															.renderingMode(.template)
															.resizable()
															.foregroundStyle(Color.grayMain2c500)
															.frame(width: 15, height: 15)
															.padding(.top, 1)
													}
													
													if eventLogMessage.message.isEdited && eventLogMessage.message.isOutgoing {
														Text("conversation_message_edited_label")
														 .foregroundStyle(Color.grayMain2c500)
														 .default_text_style_300(styleSize: 12)
														 .padding(.top, 1)
														 .padding(.trailing, -4)
													}
													
												Text(conversationViewModel.getMessageTime(startDate: eventLogMessage.message.dateReceived))
														.foregroundStyle(
															conversationViewModel.isSMSConversation && eventLogMessage.message.isOutgoing
																? Color.white
																: Color.grayMain2c500
														)
														.default_text_style_300(styleSize: 12)
														.padding(.top, 1)
													.padding(.trailing, -4)

												if let delivery = conversationViewModel.smsDeliveryLabel(for: eventLogMessage.message) {
													Text(delivery)
														.foregroundStyle(
															conversationViewModel.isSMSConversation && eventLogMessage.message.isOutgoing
																? Color.white
																: Color.grayMain2c500
														)
														.default_text_style_300(styleSize: 12)
														.padding(.top, 1)
												}
													
											if !conversationViewModel.isSMSConversation
												&& (conversationViewModel.conversationIsGroup || eventLogMessage.message.isOutgoing) {
														if eventLogMessage.message.status == .sending {
															ProgressView()
																.controlSize(.mini)
																.progressViewStyle(CircularProgressViewStyle(tint: .orangeMain500))
																.frame(width: 10, height: 10)
																.padding(.top, 1)
														} else if eventLogMessage.message.status != nil && !(CoreContext.shared.imdnToEverybodyThreshold && !eventLogMessage.message.isOutgoing) {
															Image(conversationViewModel.getImageIMDN(status: eventLogMessage.message.status!))
																.renderingMode(.template)
																.resizable()
																.foregroundStyle(Color.orangeMain500)
																.frame(width: 15, height: 15)
																.padding(.top, 1)
														}
													}
													
													if eventLogMessage.message.isEdited && !eventLogMessage.message.isOutgoing {
														Text("conversation_message_edited_label")
														 .foregroundStyle(Color.grayMain2c500)
														 .default_text_style_300(styleSize: 12)
														 .padding(.top, 1)
														 .padding(.trailing, -4)
													}
													
													if eventLogMessage.message.isEphemeral && !eventLogMessage.message.isOutgoing {
														Image("clock-countdown")
															.renderingMode(.template)
															.resizable()
															.foregroundStyle(Color.grayMain2c500)
															.frame(width: 15, height: 15)
															.padding(.top, 1)
															.padding(.trailing, -4)
														
														Text(ephemeralLifetime)
															.foregroundStyle(Color.grayMain2c500)
															.default_text_style_300(styleSize: 12)
															.padding(.top, 1)
															.onAppear {
																updateEphemeralTimer()
															}
															.onChange(of: eventLogMessage.message.ephemeralExpireTime) { ephemeralExpireTimeTmp in
																if ephemeralExpireTimeTmp > 0 {
																	updateEphemeralTimer()
																}
															}
													}
												}
												.onTapGesture {
													if !(CoreContext.shared.imdnToEverybodyThreshold && !eventLogMessage.message.isOutgoing) {
														conversationViewModel.selectedMessageToDisplayDetails = eventLogMessage
														conversationViewModel.prepareBottomSheetForDeliveryStatus()
													}
												}
												.disabled(conversationViewModel.selectedMessage != nil)
												.padding(.top, -4)
															}
										.padding(.all, 15)
										.background(
											conversationViewModel.isSMSConversation
												? (eventLogMessage.message.isOutgoing ? Color(uiColor: .systemBlue) : Color(uiColor: .systemGray5))
												: (eventLogMessage.message.isOutgoing ? Color.orangeMain100 : Color.grayMain2c100)
										)
											.clipShape(RoundedRectangle(cornerRadius: 3))
											.roundedCorner(
												16,
												corners: eventLogMessage.message.isOutgoing && eventLogMessage.message.isFirstMessage ? [.topLeft, .topRight, .bottomLeft] :
													(!eventLogMessage.message.isOutgoing && eventLogMessage.message.isFirstMessage ? [.topRight, .bottomRight, .bottomLeft] : [.allCorners]),
												stroke: eventLogMessage.message.id == conversationViewModel.highlightedMessageID
											)
											
											if !eventLogMessage.message.reactions.isEmpty {
												HStack {
													ForEach(0..<eventLogMessage.message.reactions.count, id: \.self) { index in
														if eventLogMessage.message.reactions.firstIndex(of: eventLogMessage.message.reactions[index]) == index {
															Text(eventLogMessage.message.reactions[index])
																.default_text_style(styleSize: 12)
																.padding(.horizontal, -2)
														}
													}
													
													if containsDuplicates(strings: eventLogMessage.message.reactions) {
														Text("\(eventLogMessage.message.reactions.count)")
															.default_text_style(styleSize: 12)
															.padding(.horizontal, -2)
													}
												}
												.onTapGesture {
													conversationViewModel.selectedMessageToDisplayDetails = eventLogMessage
													conversationViewModel.prepareBottomSheetForReactions()
												}
												.disabled(conversationViewModel.selectedMessage != nil)
												.padding(.vertical, 6)
												.padding(.horizontal, 10)
												.background(eventLogMessage.message.isOutgoing ? Color.orangeMain100 : Color.grayMain2c100)
												.cornerRadius(20)
												.overlay(
													RoundedRectangle(cornerRadius: 20)
														.stroke(.white, lineWidth: 3)
												)
												.padding(.top, -20)
												.padding(eventLogMessage.message.isOutgoing ? .trailing : .leading, 5)
											}
										}
										
										if !eventLogMessage.message.isOutgoing {
											Spacer()
										}
									}
									.frame(maxWidth: .infinity)
								}
								.frame(maxWidth: .infinity)
							}
							
							if !eventLogMessage.message.isOutgoing {
								Spacer()
							}
						})
						.padding(.leading, eventLogMessage.message.isOutgoing ? 40 : 0)
						.padding(.trailing, !eventLogMessage.message.isOutgoing ? 40 : 0)
					}
				}
				.onTapGesture {}
				.onLongPressGesture(minimumDuration: .infinity, maximumDistance: .infinity, pressing: { value in
					if !self.conversationViewModel.isSwiping {
						self.isPressed = value
						if !value {
							if !didLongPress {
								self.isPressed = false
							}
						} else {
							self.timePassed = 0
							self.ticker.start(interval: 0.2)
							self.didLongPress = false
						}
					} else {
						self.ticker.stop()
						return
					}
				}, perform: {})
				.onReceive(ticker.objectWillChange) { _ in
					guard isPressed else {
						ticker.stop()
						return
					}

					timePassed = ticker.timeIntervalSinceStarted

					if let timePassed = timePassed, timePassed >= 0.2 {
						didLongPress = true
						if !conversationViewModel.isSwiping {
							withAnimation {
								conversationViewModel.selectedMessage = eventLogMessage
							}
						}
					}
				}
			} else if !eventLogMessage.eventModel.text.isEmpty {
				HStack {
					Spacer()
					
					HStack {
						if eventLogMessage.eventModel.icon != nil {
							eventLogMessage.eventModel.icon!
								.renderingMode(.template)
								.resizable()
								.foregroundStyle(Color.grayMain2c500)
								.frame(width: 25, height: 25, alignment: .leading)
						}
						
						Text(eventLogMessage.eventModel.text)
							.foregroundStyle(Color.grayMain2c500)
							.default_text_style(styleSize: 12)
					}
					.padding(.horizontal, 10)
					.padding(.vertical, 4)
					.overlay(
						RoundedRectangle(cornerRadius: 6)
							.stroke(Color.grayMain2c200, lineWidth: 1)
					)
					
					Spacer()
				}
				.padding(.vertical, 10)
			}
		}
		.contentShape(Rectangle())
		.onTapGesture {
			if conversationViewModel.selectedMessage != nil {
				conversationViewModel.selectedMessage = nil
			}
			UIApplication.shared.endEditing()
		}
		.quickLookPreview($selectedURLAttachment, in: conversationViewModel.attachments.map { $0.full })
		.fullScreenCover(item: $previewImageAttachment) { attachment in
			Mango9ImageAttachmentViewer(attachment: attachment)
		}
	}
	
	func containsDuplicates(strings: [String]) -> Bool {
		let uniqueStrings = Set(strings)
		return uniqueStrings.count != strings.count
	}
	
	@ViewBuilder
	func messageAttachments() -> some View {
		if eventLogMessage.message.attachments.count == 1 {
			let attachment = eventLogMessage.message.attachments.first!
			if attachment.type == .image || attachment.type == .gif || attachment.type == .video {
				let previewWidth = min(max(geometryProxy.size.width - 110, 180), 280)
				let previewHeight = min(previewWidth * 0.75, UIScreen.main.bounds.height / 2.5)
				ZStack {
					RoundedRectangle(cornerRadius: 10)
						.fill(Color.grayMain2c100)
						.frame(width: previewWidth, height: previewHeight)

					if attachment.type == .video {
						VStack(spacing: 8) {
							Image("play-fill")
								.renderingMode(.template)
								.resizable()
								.scaledToFit()
								.foregroundStyle(Color.white)
								.frame(width: 44, height: 44)
							Text(attachment.name)
								.font(.system(size: 12, weight: .semibold))
								.foregroundStyle(Color.white)
								.lineLimit(1)
						}
						.frame(width: previewWidth, height: previewHeight)
						.background(Color.black.opacity(0.72))
						.onTapGesture {
							if !isPressed && !didLongPress {
								selectedURLAttachment = attachment.full
							}
						}
					} else {
						CachedAsyncImage(
							url: attachment.thumbnail,
							placeholder: ProgressView(),
							maxPixelSize: 1_200,
							onImageTapped: {
								if !isPressed && !didLongPress {
									previewImageAttachment = attachment
								}
							}
						)
						.frame(width: previewWidth, height: previewHeight)
						.overlay(alignment: .bottomTrailing) {
							if attachment.type == .gif {
								Text("GIF")
									.font(.system(size: 10, weight: .bold))
									.foregroundStyle(Color.white)
									.padding(.horizontal, 7)
									.padding(.vertical, 4)
									.background(Color.black.opacity(0.65))
									.clipShape(Capsule())
									.padding(8)
							}
						}
					}
				}
				.frame(width: previewWidth, height: previewHeight)
				.clipShape(RoundedRectangle(cornerRadius: 10))
				.clipped()
			} else if attachment.type == .voiceRecording {
				CustomSlider(
					eventLogMessage: eventLogMessage
				)
				.environmentObject(conversationViewModel)
				.frame(width: geometryProxy.size.width - 160, height: 50)
			} else if attachment.type == .audio {
				Mango9RemoteAudioPlayer(
					attachment: attachment,
					isOutgoing: eventLogMessage.message.isOutgoing
				)
				.frame(width: max(220, geometryProxy.size.width - 160))
			} else {
				HStack {
					VStack {
						if conversationViewModel.attachmentTransferInProgress != nil && conversationViewModel.attachmentTransferInProgress!.id == eventLogMessage.message.attachments.first!.id {
							CircularProgressView(progress: Double(conversationViewModel.attachmentTransferInProgress!.transferProgressIndication) / 100.0)
								.frame(width: 80, height: 80)
						} else {
							Image(getImageOfType(type: eventLogMessage.message.attachments.first!.type))
								.renderingMode(.template)
								.resizable()
								.foregroundStyle(Color.grayMain2c700)
								.frame(width: 60, height: 60, alignment: .leading)
						}
					}
					.frame(width: 100, height: 100)
					.background(Color.grayMain2c200)
					.onTapGesture {
						if eventLogMessage.message.attachments.first!.type == .fileTransfer && eventLogMessage.message.attachments.first!.transferProgressIndication == -1 {
							CoreContext.shared.doOnCoreQueue { _ in
								if let chatMessage = eventLogMessage.eventModel.eventLog.chatMessage {
									if let firstContent = chatMessage.contents.first, firstContent.type != "text" {
										conversationViewModel.downloadContent(
											chatMessage: chatMessage,
											content: firstContent
										)
									} else if chatMessage.contents.count >= 2 {
										let secondContent = chatMessage.contents[1]
										conversationViewModel.downloadContent(
											chatMessage: chatMessage,
											content: secondContent
										)
									}
								}
							}
						} else {
							if !isPressed && !didLongPress {
								selectedURLAttachment = eventLogMessage.message.attachments.first!.full
							}
						}
					}
					
					VStack {
						Text(eventLogMessage.message.attachments.first!.name)
							.foregroundStyle(Color.grayMain2c700)
							.default_text_style_600(styleSize: 14)
							.truncationMode(.middle)
							.frame(maxWidth: .infinity, alignment: .leading)
							.lineLimit(1)
						
						if eventLogMessage.message.attachments.first!.size > 0 {
							Text(eventLogMessage.message.attachments.first!.size.formatBytes())
							 .default_text_style_300(styleSize: 14)
							 .frame(maxWidth: .infinity, alignment: .leading)
							 .lineLimit(1)
						} else {
							if let size = self.getFileSize(atPath: eventLogMessage.message.attachments.first!.full.path) {
								Text(size.formatBytes())
									.default_text_style_300(styleSize: 14)
									.frame(maxWidth: .infinity, alignment: .leading)
									.lineLimit(1)
							} else {
								Text(eventLogMessage.message.attachments.first!.size.formatBytes())
									.default_text_style_300(styleSize: 14)
									.frame(maxWidth: .infinity, alignment: .leading)
									.lineLimit(1)
							}
						}
					}
					.padding(.horizontal, 10)
					.frame(maxWidth: .infinity, alignment: .leading)
				}
				.background(.white)
				.clipShape(RoundedRectangle(cornerRadius: 10))
				.onTapGesture {
					if !isPressed && !didLongPress {
						selectedURLAttachment = eventLogMessage.message.attachments.first!.full
					}
				}
			}
		} else if eventLogMessage.message.attachments.count > 1 {
			let sizeCard = ((geometryProxy.size.width - 150)/2)-2
			let columns = [GridItem(.adaptive(minimum: sizeCard), spacing: 1)]
			
			VStack {
				LazyVGrid(columns: columns) {
					ForEach(eventLogMessage.message.attachments.filter({ $0.type == .image || $0.type == .gif
						|| $0.type == .video }), id: \.id) { attachment in
							ZStack {
								RoundedRectangle(cornerRadius: 6)
									.fill(Color.grayMain2c100)
									.frame(width: sizeCard, height: sizeCard)

								if attachment.type == .video {
									Image("play-fill")
										.renderingMode(.template)
										.resizable()
										.scaledToFit()
										.foregroundStyle(Color.white)
										.frame(width: 40, height: 40)
								} else {
									CachedAsyncImage(
										url: attachment.thumbnail,
										placeholder: ProgressView(),
										maxPixelSize: 700,
										onImageTapped: {
											if !isPressed && !didLongPress {
												previewImageAttachment = attachment
											}
										}
									)
									.frame(width: sizeCard, height: sizeCard)
								}

								if attachment.type == .gif {
									Text("GIF")
										.font(.system(size: 9, weight: .bold))
										.foregroundStyle(Color.white)
										.padding(.horizontal, 6)
										.padding(.vertical, 3)
										.background(Color.black.opacity(0.65))
										.clipShape(Capsule())
										.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
										.padding(6)
								}
							}
							.frame(width: sizeCard, height: sizeCard)
							.background(attachment.type == .video ? Color.black.opacity(0.72) : Color.clear)
							.clipShape(RoundedRectangle(cornerRadius: 6))
							.contentShape(Rectangle())
							.onTapGesture {
								guard !isPressed && !didLongPress else { return }
								if attachment.type == .video {
									selectedURLAttachment = attachment.full
								} else {
									previewImageAttachment = attachment
								}
							}
						}
				}
				
				ForEach(eventLogMessage.message.attachments.filter({ $0.type != .image && $0.type != .gif
					&& $0.type != .video }), id: \.id) { attachment in
					HStack {
						VStack {
							if conversationViewModel.attachmentTransferInProgress != nil && conversationViewModel.attachmentTransferInProgress!.id == attachment.id {
								CircularProgressView(progress: Double(conversationViewModel.attachmentTransferInProgress!.transferProgressIndication) / 100.0)
									.frame(width: 80, height: 80)
							} else {
								Image(getImageOfType(type: attachment.type))
									.renderingMode(.template)
									.resizable()
									.foregroundStyle(Color.grayMain2c700)
									.frame(width: 60, height: 60, alignment: .leading)
							}
						}
						.frame(width: 100, height: 100)
						.background(Color.grayMain2c200)
						.onTapGesture {
							if conversationViewModel.attachmentTransferInProgress == nil {
								if attachment.type == .fileTransfer && attachment.transferProgressIndication == -1 {
									CoreContext.shared.doOnCoreQueue { _ in
										if let content = eventLogMessage.eventModel.eventLog.chatMessage!.contents.first(where: {$0.name == attachment.name && $0.isFileTransfer}) {
											conversationViewModel.downloadContent(
												chatMessage: eventLogMessage.eventModel.eventLog.chatMessage!,
												content: content
											)
										}
									}
								} else {
									if !isPressed && !didLongPress {
										selectedURLAttachment = attachment.full
									}
								}
							}
						}
						
						VStack {
							Text(attachment.name)
								.foregroundStyle(Color.grayMain2c700)
								.default_text_style_600(styleSize: 14)
								.truncationMode(.middle)
								.frame(maxWidth: .infinity, alignment: .leading)
								.lineLimit(1)
							
							if attachment.size > 0 {
								Text(attachment.size.formatBytes())
								 .default_text_style_300(styleSize: 14)
								 .frame(maxWidth: .infinity, alignment: .leading)
								 .lineLimit(1)
							} else {
								if let size = self.getFileSize(atPath: attachment.full.path) {
									Text(size.formatBytes())
										.default_text_style_300(styleSize: 14)
										.frame(maxWidth: .infinity, alignment: .leading)
										.lineLimit(1)
								} else {
									Text(attachment.size.formatBytes())
										.default_text_style_300(styleSize: 14)
										.frame(maxWidth: .infinity, alignment: .leading)
										.lineLimit(1)
								}
							}
						}
						.padding(.horizontal, 10)
						.frame(maxWidth: .infinity, alignment: .leading)
					}
					.background(.white)
					.clipShape(RoundedRectangle(cornerRadius: 10))
					.onTapGesture {
						if !isPressed && !didLongPress {
							selectedURLAttachment = attachment.full
						}
					}
				}
			}
			.frame(width: max(0, geometryProxy.size.width - 150))
		}
	}
	
	func getImageOfType(type: AttachmentType) -> String {
		if type == .audio {
			return "file-audio"
		} else if type == .pdf {
			return "file-pdf"
		} else if type == .text {
			return "file-text"
		} else if type == .fileTransfer {
			return "download-simple"
		} else {
			return "file"
		}
	}
	
	private func updateEphemeralTimer() {
		if eventLogMessage.message.isEphemeral {
			if eventLogMessage.message.ephemeralExpireTime == 0 {
				// Message hasn't been read by all participants yet
				self.ephemeralLifetime = eventLogMessage.message.ephemeralLifetime.convertDurationToString()
			} else {
				let remaining = eventLogMessage.message.ephemeralExpireTime - Int(Date().timeIntervalSince1970)
				self.ephemeralLifetime = remaining.convertDurationToString()
				
				if timer == nil {
					timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
						let updatedRemaining = eventLogMessage.message.ephemeralExpireTime - Int(Date().timeIntervalSince1970)
						if updatedRemaining <= 0 {
							timer?.invalidate()
							timer = nil
						} else {
							self.ephemeralLifetime = updatedRemaining.convertDurationToString()
						}
					}
				}
			}
		}
	}
	
	private func getFileSize(atPath path: String) -> Int? {
		do {
			let attributes = try FileManager.default.attributesOfItem(atPath: path)
			if let fileSize = attributes[.size] as? Int {
				return fileSize
			}
		} catch {
			print("Error: \(error)")
		}
		return nil
	}
}

struct DynamicLinkText: View {
	let text: String
	let isMessageId: Bool
	let searchText: String
	let participantConversationModel: [ContactAvatarModel]
	var foregroundColor: Color = Color.grayMain2c700
	var linkColor: Color = .blue
	
	var body: some View {
		Text(makeAttributedString(from: text))
			.fixedSize(horizontal: false, vertical: true)
			.multilineTextAlignment(.leading)
			.lineLimit(nil)
			.default_text_style(styleSize: 14)
	}
	
	// MARK: - Builder
	
	private func makeAttributedString(from text: String) -> AttributedString {
		var result = AttributedString()
		var currentWord = ""
		
		for char in text {
			if char == " " || char == "\n" {
				appendWord(currentWord, to: &result)
				result.append(AttributedString(String(char)))
				currentWord = ""
			} else {
				currentWord.append(char)
			}
		}
		
		appendWord(currentWord, to: &result)
		
		highlightSearch(in: &result, originalText: text)
		
		return result
	}
	
	// MARK: - Word handling
	
	private func appendWord(_ word: String, to result: inout AttributedString) {
		guard !word.isEmpty else { return }
		
		// URL
		if
			let encoded = word.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
			let url = URL(string: encoded),
			["http", "https", "sip", "sips"].contains(url.scheme)
		{
			var link = AttributedString(word)
			link.link = url
			link.foregroundColor = linkColor
			link.underlineStyle = .single
			result.append(link)
			return
		}
		
		// Mention
		if isMention(word),
		   let participant = participantConversationModel.first(
				where: { ($0.address.dropFirst(4).split(separator: "@").first ?? "") == word.dropFirst() }
		   ),
		   let mentionURL = URL(string: "mango9-mention://\(participant.address)")
		{
			var mention = AttributedString("@" + participant.name)
			mention.link = mentionURL
			mention.foregroundColor = Color.orangeMain500
			mention.font = .system(size: 14)
			result.append(mention)
			return
		}
		
		// Text
		var normal = AttributedString(word)
		normal.foregroundColor = foregroundColor
		result.append(normal)
	}
	
	// MARK: - Highlight global
	
	private func highlightSearch(
		in attributed: inout AttributedString,
		originalText: String
	) {
		guard !searchText.isEmpty && isMessageId else { return }
		
		let base = originalText.folding(
			options: [.caseInsensitive, .diacriticInsensitive],
			locale: .current
		)
		
		let search = searchText.folding(
			options: [.caseInsensitive, .diacriticInsensitive],
			locale: .current
		)
		
		var searchRange = base.startIndex..<base.endIndex
		
		while let found = base.range(of: search, range: searchRange) {
			guard
				let start = AttributedString.Index(found.lowerBound, within: attributed),
				let end = AttributedString.Index(found.upperBound, within: attributed)
			else { break }
			
			attributed[start..<end].font = .system(size: 14, weight: .bold)
			
			searchRange = found.upperBound..<base.endIndex
		}
	}
	
	// MARK: - Mention validation
	
	private func isMention(_ word: String) -> Bool {
		guard word.first == "@", word.count > 1 else { return false }
		
		let username = word.dropFirst()
		return username.allSatisfy {
			$0.isLetter || $0.isNumber || $0 == "." || $0 == "_"
		}
	}
}

enum URLType {
	case name(String) // local file name of gif
	case url(URL) // remote url
	
	var url: URL? {
		switch self {
		case .name(let name):
			return Bundle.main.url(forResource: name, withExtension: "gif")
		case .url(let remoteURL):
			return remoteURL
		}
	}
}

struct GifImageView: UIViewRepresentable {
	private let name: URL
	init(_ name: URL) {
		self.name = name
	}
	
	func makeUIView(context: Context) -> WKWebView {
		let webview = WKWebView()
		let url = name
		let data = try? Data(contentsOf: url)
		if data != nil {
			webview.load(data!, mimeType: "image/gif", characterEncodingName: "UTF-8", baseURL: url.deletingLastPathComponent())
			webview.scrollView.isScrollEnabled = false
			webview.isUserInteractionEnabled = false
		}
		return webview
	}
	
	func updateUIView(_ uiView: WKWebView, context: Context) {
		uiView.reload()
	}
}

class Ticker: ObservableObject {

	var startedAt: Date = Date()

	var timeIntervalSinceStarted: TimeInterval {
		return Date().timeIntervalSince(startedAt)
	}

	private var timer: Timer?
	func start(interval: TimeInterval) {
		stop()
		startedAt = Date()
		timer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { _ in
			self.objectWillChange.send()
		}
	}

	func stop() {
		timer?.invalidate()
	}

	deinit {
		timer?.invalidate()
	}

}

struct RoundedCorner: Shape {
	var radius: CGFloat = .infinity
	var corners: UIRectCorner = .allCorners
	
	func path(in rect: CGRect) -> Path {
		let path = UIBezierPath(roundedRect: rect, byRoundingCorners: corners, cornerRadii: CGSize(width: radius, height: radius))
		return Path(path.cgPath)
	}
}

extension View {
	func roundedCorner(_ radius: CGFloat, corners: UIRectCorner, stroke: Bool? = false) -> some View {
		clipShape(RoundedCorner(radius: radius, corners: corners) )
			.overlay(
				RoundedCorner(radius: radius, corners: corners)
					.stroke(Color.orangeMain500, lineWidth: (stroke ?? false) ? 1 : 0)
			)
	}
}

struct CustomSlider: View {
	@EnvironmentObject var conversationViewModel: ConversationViewModel
	
	let eventLogMessage: EventLogMessage
	
	@State private var timer: Timer?
	@State private var value: Double = 0.0
	@State private var isPlaying: Bool = false
	@State private var cancellable: AnyCancellable?
	
	var minTrackColor: Color = .white.opacity(0.5)
	var maxTrackGradient: Gradient = Gradient(colors: [Color.orangeMain500.opacity(0.5), Color.orangeMain500])
	
	
	var body: some View {
		GeometryReader { geometry in
			let radius = geometry.size.height * 0.5
			ZStack(alignment: .leading) {
				LinearGradient(
					gradient: maxTrackGradient,
					startPoint: .leading,
					endPoint: .trailing
				)
				.frame(width: geometry.size.width, height: geometry.size.height)
				HStack {
					Rectangle()
						.foregroundColor(minTrackColor)
						.frame(width: self.value * geometry.size.width / 100, height: geometry.size.height)
						.animation(self.value > 0 ? .linear(duration: 0.1) : nil, value: self.value)
				}
				
				HStack {
					Button(
						action: {
							if isPlaying {
								conversationViewModel.pauseVoiceRecordPlayer()
								pauseProgress()
							} else {
								conversationViewModel.startVoiceRecordPlayer(voiceRecordPath: eventLogMessage.message.attachments.first!.full)
								playProgress()
							}
						},
						label: {
							Image(isPlaying ? "pause-fill" : "play-fill")
								.renderingMode(.template)
								.resizable()
								.foregroundStyle(Color.orangeMain500)
								.frame(width: 20, height: 20)
						}
					)
					.padding(8)
					.background(.white)
					.clipShape(RoundedRectangle(cornerRadius: 25))
					
					Spacer()
					
					HStack {
						Text((eventLogMessage.message.attachments.first!.duration/1000).convertDurationToString())
							.default_text_style(styleSize: 16)
							.padding(.horizontal, 5)
					}
					.padding(8)
					.background(.white)
					.clipShape(RoundedRectangle(cornerRadius: 25))
				}
				.padding(.horizontal, 10)
			}
			.clipShape(RoundedRectangle(cornerRadius: radius))
			.onAppear {
				if eventLogMessage.message.attachments.first?.type == .voiceRecording {
					cancellable =
					NotificationCenter.default
						.publisher(for: NSNotification.Name("VoiceRecording"))
						.compactMap { $0.userInfo?["messageId"] as? String }
						.sink { messageId in
							if messageId == eventLogMessage.message.id {
								conversationViewModel.startVoiceRecordPlayer(
									voiceRecordPath: eventLogMessage.message.attachments.first!.full
								)
								playProgress()
							}
						}
				}
			}
			.onDisappear {
				cancellable?.cancel()
				cancellable = nil
				
				resetProgress()
			}
		}
	}
	
	private func playProgress() {
		isPlaying = true
		self.value = conversationViewModel.getPositionVoiceRecordPlayer(voiceRecordPath: eventLogMessage.message.attachments.first!.full)
		timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
			if self.value < 100.0 {
				let valueTmp = conversationViewModel.getPositionVoiceRecordPlayer(voiceRecordPath: eventLogMessage.message.attachments.first!.full)
				if self.value > 90 && self.value == valueTmp {
					self.value = 100
				} else {
					if valueTmp == 0 && !conversationViewModel.isPlayingVoiceRecordPlayer(voiceRecordPath: eventLogMessage.message.attachments.first!.full) {
						stopProgress()
						value = 0.0
						isPlaying = false
					} else {
						self.value = valueTmp
					}
				}
			} else {
				self.resetProgress()
				
				DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
					let rows = conversationViewModel.conversationMessagesSection[0].rows
					
					if let index = rows.firstIndex(where: { $0.eventModel.eventLogId == eventLogMessage.message.id }),
					   rows.indices.contains(index - 1) {
						let nextRow = rows[index - 1]
						if nextRow.message.attachments.first?.type == .voiceRecording {
							NotificationCenter.default.post(
								name: NSNotification.Name("VoiceRecording"),
								object: nil,
								userInfo: ["messageId": nextRow.message.id]
							)
						}
					}
				}
			}
		}
	}
	
	// Pause the progress
	private func pauseProgress() {
		isPlaying = false
		stopProgress()
	}
	
	// Reset the progress
	private func resetProgress() {
		conversationViewModel.stopVoiceRecordPlayer()
		stopProgress()
		value = 0.0
		isPlaying = false
	}
	
	// Stop the progress and invalidate the timer
	private func stopProgress() {
		timer?.invalidate()
		timer = nil
	}
}

struct CircularProgressView: View {
	var progress: Double

	var body: some View {
		ZStack {
			Circle()
				.stroke(Color(.systemGray4), lineWidth: 5)
			Circle()
				.trim(from: 0, to: progress)
				.stroke(
					Color.orangeMain500,
					style: StrokeStyle(lineWidth: 5, lineCap: .round))
				.rotationEffect(Angle(degrees: -90))
				.animation(.easeInOut(duration: 0.5), value: progress)
				.overlay(
					Text("\(Int(progress * 100))%")
						.font(.system(size: 15, weight: .bold, design: .rounded))
						.foregroundColor(Color.orangeMain500)
				)
		}
		.padding()
	}
}

final class ImageCache {
	static let shared: NSCache<NSString, UIImage> = {
		let cache = NSCache<NSString, UIImage>()
		cache.countLimit = 80
		cache.totalCostLimit = 96 * 1_024 * 1_024
		return cache
	}()
}

private actor Mango9AttachmentImagePipeline {
	static let shared = Mango9AttachmentImagePipeline()

	private var inFlight: [String: Task<UIImage?, Never>] = [:]

	func image(from url: URL, maxPixelSize: CGFloat) async -> UIImage? {
		let cacheKey = "\(url.absoluteString)|\(Int(maxPixelSize))" as NSString
		if let cachedImage = ImageCache.shared.object(forKey: cacheKey) {
			return cachedImage
		}

		if let existingTask = inFlight[cacheKey as String] {
			return await existingTask.value
		}

		let task = Task<UIImage?, Never>(priority: .userInitiated) {
			var request = URLRequest(url: url)
			request.cachePolicy = .returnCacheDataElseLoad
			request.timeoutInterval = 20

			do {
				let (data, response) = try await URLSession.shared.data(for: request)
				if let httpResponse = response as? HTTPURLResponse,
				   !(200...299).contains(httpResponse.statusCode) {
					return nil
				}

				guard let image = Self.downsample(data: data, maxPixelSize: maxPixelSize) else {
					return nil
				}

				let pixelWidth = Int(image.size.width * image.scale)
				let pixelHeight = Int(image.size.height * image.scale)
				ImageCache.shared.setObject(
					image,
					forKey: cacheKey,
					cost: max(pixelWidth * pixelHeight * 4, 1)
				)
				return image
			} catch {
				return nil
			}
		}

		inFlight[cacheKey as String] = task
		let image = await task.value
		inFlight[cacheKey as String] = nil
		return image
	}

	private nonisolated static func downsample(data: Data, maxPixelSize: CGFloat) -> UIImage? {
		let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
		guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
			return nil
		}

		let options = [
			kCGImageSourceCreateThumbnailFromImageAlways: true,
			kCGImageSourceCreateThumbnailWithTransform: true,
			kCGImageSourceShouldCacheImmediately: true,
			kCGImageSourceThumbnailMaxPixelSize: max(1, Int(maxPixelSize))
		] as CFDictionary

		guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else {
			return nil
		}
		return UIImage(cgImage: image)
	}
}

struct CachedAsyncImage<Placeholder: View>: View {
	let url: URL
	let placeholder: Placeholder
	let maxPixelSize: CGFloat
	let onImageTapped: (() -> Void)?

	@State private var image: UIImage?
	@State private var failed = false

	init(
		url: URL,
		placeholder: Placeholder,
		maxPixelSize: CGFloat = 1_200,
		onImageTapped: (() -> Void)? = nil
	) {
		self.url = url
		self.placeholder = placeholder
		self.maxPixelSize = maxPixelSize
		self.onImageTapped = onImageTapped
	}

	var body: some View {
		ZStack {
			if let image = image {
				Image(uiImage: image)
					.resizable()
					.interpolation(.medium)
					.aspectRatio(contentMode: .fill)
					.onTapGesture {
						onImageTapped?()
					}
			} else if failed {
				VStack(spacing: 6) {
					Image(systemName: "photo")
					Text("Unable to load")
						.font(.caption2)
				}
				.foregroundStyle(Color.grayMain2c500)
			} else {
				placeholder
			}
		}
		.clipped()
		.task(id: "\(url.absoluteString)|\(Int(maxPixelSize))") {
			image = nil
			failed = false
			let loadedImage = await Mango9AttachmentImagePipeline.shared.image(
				from: url,
				maxPixelSize: maxPixelSize
			)
			guard !Task.isCancelled else { return }
			image = loadedImage
			failed = loadedImage == nil
		}
	}
}

private struct Mango9RemoteAudioPlayer: View {
	let attachment: Attachment
	let isOutgoing: Bool

	@State private var player: AVPlayer
	@State private var isPlaying = false
	@State private var isScrubbing = false
	@State private var currentTime: Double = 0
	@State private var duration: Double = 0
	@State private var timeObserver: Any?

	init(attachment: Attachment, isOutgoing: Bool) {
		self.attachment = attachment
		self.isOutgoing = isOutgoing
		_player = State(initialValue: AVPlayer(url: attachment.full))
	}

	private var primaryColor: Color {
		isOutgoing ? .white : Color.grayMain2c700
	}

	var body: some View {
		HStack(spacing: 12) {
			Button(action: togglePlayback) {
				Image(systemName: isPlaying ? "pause.fill" : "play.fill")
					.font(.system(size: 16, weight: .bold))
					.foregroundStyle(isOutgoing ? Color.orangeMain500 : Color.white)
					.frame(width: 38, height: 38)
					.background(isOutgoing ? Color.white : Color.orangeMain500)
					.clipShape(Circle())
			}
			.buttonStyle(.plain)

			VStack(alignment: .leading, spacing: 4) {
				Text(attachment.name.isEmpty ? "Audio message" : attachment.name)
					.font(.system(size: 13, weight: .semibold))
					.foregroundStyle(primaryColor)
					.lineLimit(1)

				Slider(
					value: $currentTime,
					in: 0...max(duration, 1),
					onEditingChanged: { editing in
						isScrubbing = editing
						if !editing {
							player.seek(to: CMTime(seconds: currentTime, preferredTimescale: 600))
						}
					}
				)
				.tint(isOutgoing ? .white : Color.orangeMain500)

				HStack {
					Text(formatTime(currentTime))
					Spacer()
					Text(formatTime(duration))
				}
				.font(.system(size: 10, weight: .medium, design: .monospaced))
				.foregroundStyle(primaryColor.opacity(0.78))
			}
		}
		.padding(.horizontal, 12)
		.padding(.vertical, 10)
		.background(isOutgoing ? Color.orangeMain500 : Color.grayMain2c100)
		.clipShape(RoundedRectangle(cornerRadius: 14))
		.onAppear(perform: preparePlayer)
		.onDisappear(perform: stopObserving)
		.onReceive(
			NotificationCenter.default.publisher(
				for: AVPlayerItem.didPlayToEndTimeNotification,
				object: player.currentItem
			)
		) { _ in
			isPlaying = false
			currentTime = 0
			player.seek(to: .zero)
		}
	}

	private func togglePlayback() {
		if isPlaying {
			player.pause()
		} else {
			player.play()
		}
		isPlaying.toggle()
	}

	private func preparePlayer() {
		guard timeObserver == nil else { return }
		timeObserver = player.addPeriodicTimeObserver(
			forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
			queue: .main
		) { time in
			guard !isScrubbing else { return }
			currentTime = max(0, time.seconds.isFinite ? time.seconds : 0)
		}

		Task {
			guard let item = player.currentItem else { return }
			if let loadedDuration = try? await item.asset.load(.duration),
			   loadedDuration.seconds.isFinite {
				duration = max(loadedDuration.seconds, 0)
			}
		}
	}

	private func stopObserving() {
		player.pause()
		isPlaying = false
		if let timeObserver {
			player.removeTimeObserver(timeObserver)
			self.timeObserver = nil
		}
	}

	private func formatTime(_ seconds: Double) -> String {
		let totalSeconds = max(Int(seconds.rounded(.down)), 0)
		return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
	}
}

private struct Mango9ImageAttachmentViewer: View {
	@Environment(\.dismiss) private var dismiss

	let attachment: Attachment

	@State private var image: UIImage?
	@State private var controlsVisible = false
	@State private var isSaving = false
	@State private var statusMessage: String?

	var body: some View {
		ZStack {
			Color.black.ignoresSafeArea()

			if let image {
				Mango9ZoomableImage(image: image) {
					withAnimation(.easeInOut(duration: 0.18)) {
						controlsVisible.toggle()
					}
				}
			} else {
				ProgressView("Loading image…")
					.tint(.white)
					.foregroundStyle(Color.white)
			}

			VStack {
				HStack {
					Button(action: { dismiss() }) {
						Image(systemName: "xmark")
							.font(.system(size: 16, weight: .bold))
							.foregroundStyle(Color.white)
							.frame(width: 42, height: 42)
							.background(.ultraThinMaterial)
							.clipShape(Circle())
					}
					.accessibilityLabel("Close image")

					Spacer()

					if controlsVisible {
						Button(action: saveImage) {
							HStack(spacing: 7) {
								if isSaving {
									ProgressView().tint(.white)
								} else {
									Image(systemName: "square.and.arrow.down")
								}
								Text("Save")
							}
							.font(.system(size: 15, weight: .semibold))
							.foregroundStyle(Color.white)
							.padding(.horizontal, 14)
							.frame(height: 42)
							.background(.ultraThinMaterial)
							.clipShape(Capsule())
						}
						.disabled(isSaving)
						.accessibilityLabel("Save image to Photos")
					}
				}
				.padding(.horizontal, 18)
				.padding(.top, 12)

				Spacer()

				if controlsVisible {
					Text("Pinch to zoom • Double-tap to magnify")
						.font(.footnote.weight(.medium))
						.foregroundStyle(Color.white)
						.padding(.horizontal, 14)
						.padding(.vertical, 9)
						.background(.ultraThinMaterial)
						.clipShape(Capsule())
						.padding(.bottom, 24)
				}
			}

			if let statusMessage {
				Text(statusMessage)
					.font(.system(size: 14, weight: .semibold))
					.foregroundStyle(Color.white)
					.padding(.horizontal, 16)
					.padding(.vertical, 11)
					.background(Color.black.opacity(0.75))
					.clipShape(Capsule())
					.transition(.opacity.combined(with: .scale))
			}
		}
		.task(id: attachment.full) {
			image = await Mango9AttachmentImagePipeline.shared.image(
				from: attachment.full,
				maxPixelSize: 4_096
			)
			if image == nil {
				statusMessage = "Unable to load this image"
			}
		}
	}

	private func saveImage() {
		guard !isSaving else { return }
		isSaving = true
		statusMessage = nil

		Task {
			do {
				var request = URLRequest(url: attachment.full)
				request.cachePolicy = .returnCacheDataElseLoad
				request.timeoutInterval = 30
				let (data, response) = try await URLSession.shared.data(for: request)
				if let httpResponse = response as? HTTPURLResponse,
				   !(200...299).contains(httpResponse.statusCode) {
					throw Mango9ImageSaveError.downloadFailed
				}

				let authorization = await requestPhotoAuthorization()
				guard authorization == .authorized || authorization == .limited else {
					throw Mango9ImageSaveError.permissionDenied
				}

				try await saveToPhotoLibrary(data: data)
				showStatus("Saved to Photos")
			} catch let error as Mango9ImageSaveError {
				showStatus(error.errorDescription ?? "Unable to save image")
			} catch {
				showStatus("Unable to save image")
			}
			isSaving = false
		}
	}

	private func requestPhotoAuthorization() async -> PHAuthorizationStatus {
		await withCheckedContinuation { continuation in
			PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
				continuation.resume(returning: status)
			}
		}
	}

	private func saveToPhotoLibrary(data: Data) async throws {
		try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
			PHPhotoLibrary.shared().performChanges {
				let request = PHAssetCreationRequest.forAsset()
				request.addResource(with: .photo, data: data, options: nil)
			} completionHandler: { saved, error in
				if let error {
					continuation.resume(throwing: error)
				} else if saved {
					continuation.resume(returning: ())
				} else {
					continuation.resume(throwing: Mango9ImageSaveError.saveFailed)
				}
			}
		}
	}

	private func showStatus(_ message: String) {
		withAnimation {
			statusMessage = message
		}
		Task {
			try? await Task.sleep(nanoseconds: 2_000_000_000)
			withAnimation {
				if statusMessage == message {
					statusMessage = nil
				}
			}
		}
	}
}

private enum Mango9ImageSaveError: LocalizedError {
	case downloadFailed
	case permissionDenied
	case saveFailed

	var errorDescription: String? {
		switch self {
		case .downloadFailed:
			return "Unable to download image"
		case .permissionDenied:
			return "Allow Photos access to save images"
		case .saveFailed:
			return "Unable to save image"
		}
	}
}

private struct Mango9ZoomableImage: View {
	let image: UIImage
	let onSingleTap: () -> Void

	@State private var scale: CGFloat = 1
	@State private var settledScale: CGFloat = 1
	@State private var offset: CGSize = .zero
	@State private var settledOffset: CGSize = .zero

	var body: some View {
		Image(uiImage: image)
			.resizable()
			.interpolation(.high)
			.scaledToFit()
			.scaleEffect(scale)
			.offset(offset)
			.contentShape(Rectangle())
			.gesture(
				MagnificationGesture()
					.onChanged { value in
						scale = min(max(settledScale * value, 1), 5)
					}
					.onEnded { _ in
						settledScale = scale
						if scale == 1 {
							offset = .zero
							settledOffset = .zero
						}
					}
			)
			.simultaneousGesture(
				DragGesture()
					.onChanged { value in
						guard scale > 1 else { return }
						offset = CGSize(
							width: settledOffset.width + value.translation.width,
							height: settledOffset.height + value.translation.height
						)
					}
					.onEnded { _ in
						settledOffset = offset
					}
			)
			.onTapGesture(count: 2) {
				withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
					if scale > 1 {
						scale = 1
						settledScale = 1
						offset = .zero
						settledOffset = .zero
					} else {
						scale = 2.5
						settledScale = 2.5
					}
				}
			}
			.onTapGesture(perform: onSingleTap)
	}
}

/*
 #Preview {
 ChatBubbleView(index: 0)
 }
 */

// swiftlint:enable type_body_length
// swiftlint:enable cyclomatic_complexity
