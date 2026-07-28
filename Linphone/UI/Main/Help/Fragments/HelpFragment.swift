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

struct HelpFragment: View {
	
	@StateObject private var helpViewModel = HelpViewModel()
	
	@Binding var isShowHelpFragment: Bool
	
	var showAssistant: Bool {
		(CoreContext.shared.codeScannerIsOpen && CoreContext.shared.accounts.isEmpty)
		|| (CoreContext.shared.coreIsStarted && CoreContext.shared.accounts.isEmpty)
		|| SharedMainViewModel.shared.displayProfileMode
	}
	
	var body: some View {
		NavigationView {
			ZStack {
				VStack(spacing: 1) {
					if !showAssistant {
						Rectangle()
							.foregroundColor(Color.orangeMain500)
							.edgesIgnoringSafeArea(.top)
							.frame(height: 0)
					}
					
					HStack {
						Image("caret-left")
							.renderingMode(.template)
							.resizable()
							.foregroundStyle(Color.orangeMain500)
							.frame(width: 25, height: 25, alignment: .leading)
							.padding(.all, 10)
							.padding(.top, 4)
							.padding(.leading, -10)
							.onTapGesture {
								withAnimation {
									isShowHelpFragment = false
								}
							}
						
						Text("help_title")
							.default_text_style_orange_800(styleSize: 16)
							.frame(maxWidth: .infinity, alignment: .leading)
							.padding(.top, 4)
							.lineLimit(1)
						
						Spacer()
					}
					.frame(maxWidth: .infinity)
					.frame(height: 50)
					.padding(.horizontal)
					.padding(.bottom, 4)
					.background(.white)
					
					ScrollView {
						VStack(spacing: 0) {
							VStack(spacing: 20) {
								if let urlString = AppServices.corePreferences.themeAboutPictureUrl,
								   let url = URL(string: urlString) {
									AsyncImage(url: url) { phase in
										switch phase {
										case .empty:
											ProgressView()
												.frame(maxWidth: .infinity, minHeight: 100, maxHeight: 100)
										case .success(let image):
											image
												.resizable()
												.scaledToFit()
												.frame(maxWidth: .infinity, maxHeight: 100, alignment: .center)
										case .failure:
											EmptyView()
										@unknown default:
											EmptyView()
										}
									}
								} else {
									EmptyView()
								}
								Text("About Mango9")
									.default_text_style_800(styleSize: 16)
									.frame(maxWidth: .infinity, alignment: .leading)
									.padding(.bottom, 5)
									Button {
										if let url = URL(string: "https://www.mango9.com/support") {
										UIApplication.shared.open(url)
									}
								} label: {
									HStack {
										Image("book-open-text")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.orangeMain500)
											.frame(width: 30, height: 30)
										
										VStack {
											Text("Mango9 support")
												.default_text_style_700(styleSize: 14)
												.frame(maxWidth: .infinity, alignment: .leading)
												.multilineTextAlignment(.leading)
											
											Text("Get help with Mango9 calling, messaging, and account setup.")
												.default_text_style(styleSize: 14)
												.frame(maxWidth: .infinity, alignment: .leading)
												.multilineTextAlignment(.leading)
										}
										.padding(.horizontal, 5)
										
										Image("arrow-square-out")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.grayMain2c600)
											.frame(width: 25, height: 25)
									}
								}
								
									Button {
										if let url = URL(string: "https://www.mango9.com/privacy") {
										UIApplication.shared.open(url)
									}
								} label: {
									HStack {
										Image("detective")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.orangeMain500)
											.frame(width: 30, height: 30)
										
										VStack {
											Text("Privacy policy")
												.default_text_style_700(styleSize: 14)
												.frame(maxWidth: .infinity, alignment: .leading)
												.multilineTextAlignment(.leading)
											
											Text("How Mango9 collects and uses information.")
												.default_text_style(styleSize: 14)
												.frame(maxWidth: .infinity, alignment: .leading)
												.multilineTextAlignment(.leading)
										}
										.padding(.horizontal, 5)
										
										Image("arrow-square-out")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.grayMain2c600)
											.frame(width: 25, height: 25)
										}
									}

								Button {
									if let url = URL(
										string: "mailto:support@mango9.com?subject=Mango9%20iOS%20account%20or%20safety%20support"
									) {
										UIApplication.shared.open(url)
									}
								} label: {
									HStack {
										Image(systemName: "person.crop.circle.badge.questionmark")
											.resizable()
											.scaledToFit()
											.foregroundStyle(Color.orangeMain500)
											.frame(width: 30, height: 30)

										VStack {
											Text("Account and safety support")
												.default_text_style_700(styleSize: 14)
												.frame(maxWidth: .infinity, alignment: .leading)
												.multilineTextAlignment(.leading)

											Text("Report abusive content or request account access or termination.")
												.default_text_style(styleSize: 14)
												.frame(maxWidth: .infinity, alignment: .leading)
												.multilineTextAlignment(.leading)
										}
										.padding(.horizontal, 5)

										Image("arrow-square-out")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.grayMain2c600)
											.frame(width: 25, height: 25)
									}
								}
								
								HStack {
									Image("info")
										.renderingMode(.template)
										.resizable()
										.foregroundStyle(Color.orangeMain500)
										.frame(width: 30, height: 30)
									
									VStack {
										Text("help_about_version_title")
											.default_text_style_700(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.multilineTextAlignment(.leading)
										
										Text(helpViewModel.appVersion)
											.default_text_style(styleSize: 14)
											.frame(maxWidth: .infinity, alignment: .leading)
											.multilineTextAlignment(.leading)
										}
										.padding(.horizontal, 5)
									}
									.background(Color.gray100)
								
									NavigationLink {
										Mango9LicensingFragment()
									} label: {
										HStack {
											Image("check-square-offset")
												.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.orangeMain500)
												.frame(width: 30, height: 30)
											
											VStack {
												Text("Licensing")
													.default_text_style_700(styleSize: 14)
													.frame(maxWidth: .infinity, alignment: .leading)
													.multilineTextAlignment(.leading)
												
												Text("Open-source licenses, attribution, and source code.")
													.default_text_style(styleSize: 14)
													.frame(maxWidth: .infinity, alignment: .leading)
													.multilineTextAlignment(.leading)
										}
										.padding(.horizontal, 5)
										
										Image("arrow-right")
											.renderingMode(.template)
											.resizable()
											.foregroundStyle(Color.grayMain2c600)
											.frame(width: 25, height: 25)
									}
								}
								}
							.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
							.padding(.all, 20)
						}
						.frame(maxWidth: .infinity)
					}
					.background(Color.gray100)
				}
				.background(Color.gray100)
			}
			.navigationTitle("")
			.navigationBarHidden(true)
		}
		.navigationViewStyle(StackNavigationViewStyle())
		.navigationTitle("")
		.navigationBarHidden(true)
	}
}

private struct Mango9LicensingFragment: View {
	@Environment(\.dismiss) private var dismiss
	
	private let gplURL = URL(string: "https://www.gnu.org/licenses/gpl-3.0.html")!
	private let agplURL = URL(string: "https://www.gnu.org/licenses/agpl-3.0.html")!
	private let upstreamAppURL = URL(string: "https://github.com/BelledonneCommunications/linphone-iphone")!
	private let upstreamSdkURL = URL(
		string: "https://github.com/BelledonneCommunications/linphone-sdk-swift-ios/tree/5.5.5"
	)!
	private let upstreamNativeSdkURL = URL(
		string: "https://github.com/BelledonneCommunications/linphone-sdk/tree/5.5.5"
	)!
	private let appAuthLicenseURL = URL(
		string: "https://github.com/openid/AppAuth-iOS/blob/2.0.0/LICENSE"
	)!
	private let emojiPickerLicenseURL = URL(
		string: "https://github.com/Finalet/Elegant-Emoji-Picker/blob/598ff0a72198375d7317b61982fa8648d0ba3a44/LICENSE"
	)!
	private let linphoneThirdPartyURL = URL(
		string: "https://wiki.linphone.org/xwiki/wiki/public/view/Linphone/Third%20party%20components%20/"
	)!
	
	private var correspondingSourceURL: URL? {
		guard let value = Bundle.main.object(forInfoDictionaryKey: "Mango9SourceCodeURL") as? String else {
			return nil
		}
		
		let trimmedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !trimmedValue.isEmpty else {
			return nil
		}
		
		return URL(string: trimmedValue)
	}

	private var appVersionDescription: String {
		let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
		let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "unknown"
		return "Public corresponding-source repository for Mango9 iOS \(version) (build \(build))"
	}
	
	var body: some View {
		ZStack {
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
						.padding(.top, 4)
						.padding(.leading, -10)
						.onTapGesture {
							dismiss()
						}
					
					Text("Licensing")
						.default_text_style_orange_800(styleSize: 16)
						.frame(maxWidth: .infinity, alignment: .leading)
						.padding(.top, 4)
						.lineLimit(1)
					
					Spacer()
				}
				.frame(maxWidth: .infinity)
				.frame(height: 50)
				.padding(.horizontal)
				.padding(.bottom, 4)
				.background(.white)
				
				ScrollView {
					VStack(alignment: .leading, spacing: 24) {
						Text(
							"Mango9 for iOS is free and open-source software based on Linphone. "
							+ "Mango9 modified the Linphone iOS application in 2026. "
							+ "The notices and exact-version source links below apply to this app and its included communication components."
						)
						.default_text_style(styleSize: 14)
						.frame(maxWidth: .infinity, alignment: .leading)

						Text(
							"This program is distributed in the hope that it will be useful, "
							+ "but WITHOUT ANY WARRANTY; without even the implied warranty of "
							+ "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. You may copy, "
							+ "modify, and redistribute the covered software under the license terms shown below."
						)
						.default_text_style(styleSize: 13)
						.frame(maxWidth: .infinity, alignment: .leading)
						
						licenseSection(title: "Mango9 application") {
							licenseLink(
								title: "GNU General Public License v3.0 or later",
								subtitle: "License for the Mango9 iOS application",
								url: gplURL
							)
							
							licenseLink(
								title: "Mango9 corresponding source code",
								subtitle: correspondingSourceURL == nil
									? "Public repository URL must be configured before distribution"
									: appVersionDescription,
								url: correspondingSourceURL
							)
							
							licenseLink(
								title: "Linphone iOS upstream source",
								subtitle: "Original application project and attribution",
								url: upstreamAppURL
							)
						}
						
						licenseSection(title: "Communication SDK") {
							licenseLink(
								title: "GNU Affero General Public License v3.0",
								subtitle: "License included with Linphone SDK 5.5.5",
								url: agplURL
							)
							
							licenseLink(
								title: "Linphone SDK Swift iOS source",
								subtitle: "Exact Swift package source for SDK 5.5.5",
								url: upstreamSdkURL
							)

							licenseLink(
								title: "Linphone SDK native source",
								subtitle: "Native SDK build source and dependency manifests for version 5.5.5",
								url: upstreamNativeSdkURL
							)
							
							licenseLink(
								title: "Linphone third-party components",
								subtitle: "Licenses for native SDK dependencies",
								url: linphoneThirdPartyURL
							)
						}
						
						licenseSection(title: "Direct Swift packages") {
							licenseLink(
								title: "AppAuth for iOS 2.0.0",
								subtitle: "Apache License 2.0",
								url: appAuthLicenseURL
							)
							
							licenseLink(
								title: "Elegant Emoji Picker",
								subtitle: "MIT License • revision 598ff0a",
								url: emojiPickerLicenseURL
							)
						}
						
						Text(
							"Linphone is originally published by Belledonne Communications SARL. "
							+ "Mango9 is a modified version and is not endorsed by Belledonne Communications. "
							+ "Copyright notices in the source remain with their respective holders. "
							+ "Mango9 modifications are released under GNU GPL v3.0 or later."
						)
							.default_text_style(styleSize: 13)
							.frame(maxWidth: .infinity, alignment: .leading)
					}
					.frame(maxWidth: SharedMainViewModel.shared.maxWidth)
					.padding(20)
					.frame(maxWidth: .infinity)
				}
				.background(Color.gray100)
			}
			.background(Color.gray100)
		}
		.navigationTitle("")
		.navigationBarHidden(true)
	}
	
	@ViewBuilder
	private func licenseSection<Content: View>(
		title: String,
		@ViewBuilder content: () -> Content
	) -> some View {
		VStack(alignment: .leading, spacing: 14) {
			Text(title)
				.default_text_style_800(styleSize: 16)
				.frame(maxWidth: .infinity, alignment: .leading)
			
			content()
		}
	}
	
	@ViewBuilder
	private func licenseLink(
		title: String,
		subtitle: String,
		url: URL?
	) -> some View {
		Button {
			if let url {
				UIApplication.shared.open(url)
			}
		} label: {
			HStack(spacing: 10) {
				Image(url == nil ? "warning-circle" : "file-text")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(url == nil ? Color.grayMain2c500 : Color.orangeMain500)
					.frame(width: 27, height: 27)
				
				VStack(alignment: .leading, spacing: 3) {
					Text(title)
						.default_text_style_700(styleSize: 14)
						.frame(maxWidth: .infinity, alignment: .leading)
						.multilineTextAlignment(.leading)
					
					Text(subtitle)
						.default_text_style(styleSize: 13)
						.frame(maxWidth: .infinity, alignment: .leading)
						.multilineTextAlignment(.leading)
				}
				
				Image(url == nil ? "info" : "arrow-square-out")
					.renderingMode(.template)
					.resizable()
					.foregroundStyle(Color.grayMain2c600)
					.frame(width: 23, height: 23)
			}
			.contentShape(Rectangle())
		}
		.buttonStyle(.plain)
		.disabled(url == nil)
		.accessibilityHint(url == nil ? subtitle : "Opens in your browser")
	}
}
