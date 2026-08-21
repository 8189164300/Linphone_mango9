/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
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

import Foundation
import linphonesw
import SwiftUI
import Combine

class AccountModel: ObservableObject {
	static let TAG = "[AccountModel]"
	
	let account: Account
	@Published var registrationState: RegistrationState = .None
	@Published var humanReadableRegistrationState: String = ""
	@Published var summary: String = ""
	@Published var registrationStateAssociatedUIColor: Color = .clear
	@Published var isRegistrered: Bool = false
	@Published var notificationsCount: Int = 0
	@Published var showMwi: Bool = false
	@Published var voicemailCount: Int = 0
	@Published var isDefaultAccount: Bool = false
	@Published var displayName: String = ""
	@Published var address: String = ""
	@Published var avatarModel: ContactAvatarModel?
	@Published var photoAvatarModel: String?
	@Published var displayNameAvatar: String = ""
	@Published var usernaneAvatar: String = ""
	@Published var imagePathAvatar: URL?

	var usesGeneratedDefaultAvatar: Bool {
		guard let photoAvatarModel, !photoAvatarModel.isEmpty else { return true }
		return photoAvatarModel.hasSuffix("-default.png")
	}
	
	private var accountDelegate: AccountDelegate?
	private var coreDelegate: CoreDelegate?
	
	init(account: Account, core: Core) {
		self.account = account
		
		self.computeNotificationsCount()
		
		accountDelegate = AccountDelegateStub(
			onRegistrationStateChanged: { (_: Account, _: RegistrationState, _: String) in
				self.update()
			}, onMessageWaitingIndicationChanged: { (account: Account, mwi: MessageWaitingIndication) in
				Log.info("\(AccountModel.TAG) Account \(account.params?.identityAddress?.asStringUriOnly() ?? "Error") has received a MWI NOTIFY. \(mwi.hasMessageWaiting() ? "Message(s) are waiting." : "No message is waiting.")")
				let showMwiTmp = mwi.hasMessageWaiting()
				var voicemailCountTmp = 0
				for summary in mwi.summaries {
					let contextClass = summary.contextClass
					let nbNew = summary.nbNew
					let nbNewUrgent = summary.nbNewUrgent
					let nbOld = summary.nbOld
					let nbOldUrgent = summary.nbOldUrgent
					Log.info("\(AccountModel.TAG) [MWI] \(contextClass): new \(nbNew) urgent \(nbNewUrgent), old \(nbOld) urgent \(nbOldUrgent)")
					
					voicemailCountTmp = Int(nbNew)
				}
				
				DispatchQueue.main.async {
					self.showMwi = showMwiTmp
					self.voicemailCount = voicemailCountTmp
				}
			}
		)
		account.addDelegate(delegate: accountDelegate!)
		
		coreDelegate = CoreDelegateStub(
			onCallStateChanged: { (_: Core, _: Call, _: Call.State, _: String) in
				self.computeNotificationsCount()
			}, onMessagesReceived: { (_: Core, _: ChatRoom, _: [ChatMessage]) in
				self.computeNotificationsCount()
			}, onChatRoomRead: { (_: Core, _: ChatRoom) in
				self.computeNotificationsCount()
			}, onMessageRetracted: { (_: Core, _: ChatRoom, _: ChatMessage) in
				self.computeNotificationsCount()
			}
		)
		core.addDelegate(delegate: coreDelegate!)
		
		CoreContext.shared.doOnCoreQueue { _ in
			self.update()
		}
	}
	
	deinit {
		if let delegate = accountDelegate {
			account.removeDelegate(delegate: delegate)
		}
		if let delegate = coreDelegate {
			CoreContext.shared.doOnCoreQueue { core in
				core.removeDelegate(delegate: delegate)
			}
		}
	}
	
	private func update() {
		let state = account.state
		var isDefault: Bool = false
		if let defaultAccount = account.core?.defaultAccount {
			isDefault = (defaultAccount == account)
		}
		let displayName = account.displayName()
		let address = account.params?.identityAddress?.asString()
		
		let displayNameTmp = account.params?.identityAddress?.displayName ?? displayName
		let usernaneAvatarTmp = account.params?.identityAddress?.username ?? displayName
		var photoAvatarModelTmp = ""
		
		let preferences = UserDefaults.standard
		
		let photoAvatarModelKey = usernaneAvatarTmp
		
		if !photoAvatarModelKey.isEmpty {
			if preferences.object(forKey: photoAvatarModelKey) == nil {
				self.saveImage(
					image: ContactsManager.shared.textToImage(
						firstName: usernaneAvatarTmp, lastName: ""),
					name: usernaneAvatarTmp,
					prefix: "-default")
			} else {
				photoAvatarModelTmp = preferences.string(forKey: photoAvatarModelKey)!
			}
			
			DispatchQueue.main.async { [self] in
				switch state {
				case .Cleared, .None:
					humanReadableRegistrationState = "drawer_menu_account_connection_status_cleared".localized()
					summary = "manage_account_status_cleared_summary".localized()
					registrationStateAssociatedUIColor = .orangeWarning600
				case .Progress:
					humanReadableRegistrationState = "drawer_menu_account_connection_status_progress".localized()
					summary = "manage_account_status_progress_summary".localized()
					registrationStateAssociatedUIColor = .greenSuccess500
				case .Failed:
					humanReadableRegistrationState = "drawer_menu_account_connection_status_failed".localized()
					summary = "manage_account_status_failed_summary".localized()
					registrationStateAssociatedUIColor = .redDanger500
				case .Ok:
					humanReadableRegistrationState = "drawer_menu_account_connection_status_connected".localized()
					summary = "manage_account_status_connected_summary".localized()
					registrationStateAssociatedUIColor = .greenSuccess500
				case .Refreshing:
					humanReadableRegistrationState = "drawer_menu_account_connection_status_refreshing".localized()
					summary = "manage_account_status_progress_summary".localized()
					registrationStateAssociatedUIColor = .grayMain2c500
				}
				
				registrationState = state
				
				isRegistrered = state == .Ok
				isDefaultAccount = isDefault
				self.displayName = displayName
				address.map {self.address = $0}
				
				photoAvatarModel = photoAvatarModelTmp
				displayNameAvatar = displayNameTmp
				usernaneAvatar = usernaneAvatarTmp
				imagePathAvatar = getImagePath()
			}
		}
	}
	
	private func computeNotificationsCount() {
		CoreContext.shared.doOnCoreQueue { core in
			let count = self.account.unreadChatMessageCount + self.account.missedCallsCount
			SharedMainViewModel.shared.updateMissedCallsCount()
			SharedMainViewModel.shared.updateUnreadMessagesCount()
			
			DispatchQueue.main.async {
				self.notificationsCount = count
			}
		}
	}
	
	func refreshRegiter() {
		CoreContext.shared.doOnCoreQueue { _ in
			self.account.refreshRegister()
		}
	}
	
	func getImagePath() -> URL {
		let imagePath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!.appendingPathComponent(
			photoAvatarModel ?? "Error"
		)
		
		return imagePath
	}
	
	func logout() {
		CoreContext.shared.doOnCoreQueue { core in
			Log.info("Account \(self.account.displayName()) has been removed")
			if let sipIdentity = self.account.params?
				.identityAddress?.asStringUriOnly() {
				Mango9SessionStore.remove(for: sipIdentity)
			}
			// removeAccountWithData() deletes associated authentication only when
			// the asynchronous unregister finishes. If the same Mango9 line is
			// provisioned again before then, that delayed cleanup can delete the
			// replacement credential and leave the new account in a 401 loop.
			// Remove this account and its current credential synchronously instead.
			let authInfo = self.account.findAuthInfo()
			core.removeAccount(account: self.account)
			if let authInfo {
				core.removeAuthInfo(info: authInfo)
			}
		}
	}
	
	func saveImage(image: UIImage, name: String, prefix: String) {
		guard let data = image.jpegData(compressionQuality: 1) ?? image.pngData() else {
			return
		}
		
		let photoAvatarModelKey = name
		
		ContactsManager.shared.awaitDataWrite(data: data, name: name, prefix: prefix) { result in
			UserDefaults.standard.set(result, forKey: photoAvatarModelKey)
			
			self.photoAvatarModel = ""
			self.imagePathAvatar = nil
			NotificationCenter.default.post(name: NSNotification.Name("ImageChanged"), object: nil)
			
			DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
				self.photoAvatarModel = result
				self.imagePathAvatar = self.getImagePath()
				NotificationCenter.default.post(name: NSNotification.Name("ImageChanged"), object: nil)
			}
		}
	}
	
	func setAsDefault() {
		CoreContext.shared.doOnCoreQueue { core in
			if core.defaultAccount.map({ $0 === self.account }) != true {
				core.defaultAccount = self.account
				
				for friendList in core.friendsLists {
					if (friendList.subscriptionsEnabled) {
						Log.info(
							"\(AccountModel.TAG) Default account has changed, refreshing friend list \(friendList.displayName ?? "") subscriptions"
						)
						// friendList.updateSubscriptions() won't trigger a refresh unless a friend has changed
						friendList.subscriptionsEnabled = false
						friendList.subscriptionsEnabled = true
					}
				}
			}
		}
		
		self.isDefaultAccount = true
	}
	
	func callVoicemailUri() {
		CoreContext.shared.doOnCoreQueue { core in
			if let voicemail = self.account.params?.voicemailAddress {
				Log.info("\(AccountModel.TAG) Calling voicemail address \(voicemail.asStringUriOnly())")
				TelecomManager.shared.doCallOrJoinConf(address: voicemail)
			}
		}
	}
}

class AccountDeviceModel: ObservableObject {
	let accountDevice: AccountDevice
	@Published var deviceName: String = ""
	@Published var lastDate: String = ""
	@Published var lastTime: String = ""
	@Published var isMobileDevice: Bool = true
	
	init(accountDevice: AccountDevice) {
		self.accountDevice = accountDevice
		self.deviceName = accountDevice.name ?? ""
		
		let timeInterval = TimeInterval(accountDevice.lastUpdateTimestamp ?? 0)
		let dateTmp = Date(timeIntervalSince1970: timeInterval)
		
		let dateFormat = DateFormatter()
		dateFormat.dateFormat = Locale.current.identifier == "fr_FR" ? "dd/MM/YYYY" : "MM/dd/YYYY"
		let date = dateFormat.string(from: dateTmp)
		
		let dateFormatBis = DateFormatter()
		dateFormatBis.dateFormat = "HH:mm"
		let time = dateFormatBis.string(from: dateTmp)
		
		self.lastDate = date
		self.lastTime = time
		
		self.isMobileDevice =
			accountDevice.userAgent.contains("Mango9Android") ||
			accountDevice.userAgent.contains("Mango9iOS") ||
			accountDevice.userAgent.contains("LinphoneAndroid") ||
			accountDevice.userAgent.contains("LinphoneiOS")
	}
}
