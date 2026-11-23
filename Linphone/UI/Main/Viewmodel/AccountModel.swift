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
	private var logoutCleanupPending = false
	private var pendingLogoutAccounts: [Account] = []
	private var logoutPreparationPending = false
	
	init(account: Account, core: Core) {
		self.account = account
        // Keep the last confirmed server indication visible until a fresh NOTIFY arrives.
        if let owner = account.params?.identityAddress?.asStringUriOnly(),
           let saved = Mango9VoicemailReceipts.read(owner: owner) {
            showMwi = saved.waiting
            voicemailCount = saved.count
        }
		
		self.computeNotificationsCount()
		
		accountDelegate = AccountDelegateStub(
			onRegistrationStateChanged: { (_: Account, state: RegistrationState, _: String) in
				self.update()
				if state == .Cleared {
					self.completePendingLogout()
				}
			}, onMessageWaitingIndicationChanged: { (account: Account, mwi: MessageWaitingIndication) in
				Log.info("\(AccountModel.TAG) Account \(account.params?.identityAddress?.asStringUriOnly() ?? "Error") has received a MWI NOTIFY. \(mwi.hasMessageWaiting() ? "Message(s) are waiting." : "No message is waiting.")")
                // Only the voice summary contributes to voicemail, not fax/text counts.
                let voice = mwi.summaries.first { $0.contextClass == .Voice }
                let status = Mango9PhonePolicy.voicemailStatus(waiting: mwi.hasMessageWaiting(), voiceCount: voice.map { Int($0.nbNew) })
                let voicemailCountTmp = status.count
                let showMwiTmp = status.waiting
                if let owner = account.params?.identityAddress?.asStringUriOnly() {
                    Mango9VoicemailReceipts.save(owner: owner, waiting: status.waiting, count: status.count)
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
            // Subscribe using the existing Linphone MWI support, scoped to this line.
            if let params = self.account.params?.clone(), params.mwiServerAddress == nil,
               let identity = params.identityAddress {
                params.mwiServerAddress = identity
                self.account.params = params
            }
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
	
	func logout(onPrepared: (@MainActor (Bool) -> Void)? = nil) {
		CoreContext.shared.doOnCoreQueue { core in
			guard !self.logoutCleanupPending, !self.logoutPreparationPending,
				  let identity = self.account.params?.identityAddress?.asStringUriOnly() else { return }
			self.logoutPreparationPending = true
			let session = Mango9SessionStore.load(for: identity)
			let token = self.account.params?.pushNotificationConfig?.voipToken
			let usesMango9Cleanup = session != nil || self.account.params?.identityAddress?.domain?.hasSuffix(".mango9.com") == true
			Task { @MainActor in
				do {
					if usesMango9Cleanup {
						try await Mango9LogoutCoordinator.shared.prepareLogout(identity: identity, token: token, session: session)
					}
					try Mango9SessionStore.remove(for: identity)
				} catch {
					Log.error("[Logout] Local sign-out preparation failed (\(String(reflecting: type(of: error)))): \(error.localizedDescription)")
					CoreContext.shared.doOnCoreQueue { _ in self.logoutPreparationPending = false }
					ToastViewModel.shared.showMessage(error.localizedDescription, duration: 6)
					onPrepared?(false)
					return
				}
				onPrepared?(true)
				Mango9ChatStore.shared.disconnectIfConnected(to: identity)
				if let session, session.logoutToken == nil {
					Task { @MainActor in
						if !(await Mango9ChatStore.shared.unregisterRemotePushToken(for: identity, session: session)) {
							Log.warn("[Logout] Message push cleanup did not complete")
						}
					}
				}
				CoreContext.shared.doOnCoreQueue { core in
					self.logoutPreparationPending = false
					self.logoutCleanupPending = true
					self.pendingLogoutAccounts = core.accountList.filter { $0.params?.identityAddress?.asStringUriOnly() == identity }
					for account in self.pendingLogoutAccounts {
						if let params = account.params?.clone() {
							params.registerEnabled = false
							account.params = params
						}
					}
					// Keep normal SIP unregister, but its timeout is now only a UI
					// grace period. Durable server cleanup survives this process.
					DispatchQueue.main.asyncAfter(deadline: .now() + 5) { self.completePendingLogout() }
				}
			}
		}
	}

	private func completePendingLogout() {
		CoreContext.shared.doOnCoreQueue { core in
			guard self.logoutCleanupPending else { return }
			self.logoutCleanupPending = false
			let identity = self.account.params?.identityAddress?.asStringUriOnly()
			// A delayed unregister completion may only remove the captured accounts.
			// A fresh login can create a new account with the same SIP identity.
			let targets = core.accountList.filter { candidate in
				self.pendingLogoutAccounts.contains { $0 === candidate }
			}
			self.pendingLogoutAccounts.removeAll()
			let authInfos = targets.compactMap { $0.findAuthInfo() }
			for target in targets { core.removeAccount(account: target) }
			for authInfo in authInfos where core.authInfoList.contains(where: { $0 === authInfo })
				&& !core.accountList.contains(where: { $0.findAuthInfo() === authInfo }) {
				core.removeAuthInfo(info: authInfo)
			}
			Mango9SessionStore.activate(sipIdentity: core.defaultAccount?.params?.identityAddress?.asStringUriOnly())
			Task { @MainActor in
				await Mango9LogoutCoordinator.shared.retryPending()
				if let identity, Mango9LogoutCoordinator.shared.hasPendingLogout(identity: identity) {
					ToastViewModel.shared.showMessage("Signed out on this iPhone. Call notification cleanup will finish when the connection is restored.", duration: 6)
				}
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
	
    func loadVoicemail(completion: @escaping (String) -> Void) {
        CoreContext.shared.doOnCoreQueue { _ in
            let domain = self.account.params?.identityAddress?.domain ?? ""
            let value = Mango9PhonePolicy.mailboxNumber(self.account.params?.voicemailAddress?.asStringUriOnly() ?? "", domain: domain) ?? "*97"
            DispatchQueue.main.async { completion(value) }
        }
    }

    func saveVoicemail(_ raw: String, completion: @escaping (Result<String, Error>) -> Void) {
        CoreContext.shared.doOnCoreQueue { core in
            guard core.defaultAccount.map({ $0 == self.account }) == true,
                  let params = self.account.params?.clone(), let identity = params.identityAddress,
                  let value = Mango9PhonePolicy.mailboxNumber(raw, domain: identity.domain ?? ""),
                  let mailbox = identity.clone() else {
                DispatchQueue.main.async { completion(.failure(NSError(domain: "Phone", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "Enter up to 12 digits, * or # for the current phone line."]))) }
                return
            }
            do {
                try mailbox.setUsername(newValue: value)
                try mailbox.setDisplayname(newValue: "")
                params.voicemailAddress = mailbox
                if params.mwiServerAddress == nil { params.mwiServerAddress = identity }
                self.account.params = params
                DispatchQueue.main.async { completion(.success(value)) }
            } catch { DispatchQueue.main.async { completion(.failure(error)) } }
        }
    }

    func callVoicemailUri() {
        CoreContext.shared.doOnCoreQueue { core in
            guard core.defaultAccount.map({ $0 == self.account }) == true, let identity = self.account.params?.identityAddress,
                  let value = Mango9PhonePolicy.mailboxNumber(self.account.params?.voicemailAddress?.asStringUriOnly() ?? "", domain: identity.domain ?? ""),
                  let mailbox = identity.clone() else { return }
            do {
                try mailbox.setUsername(newValue: value)
                try mailbox.setDisplayname(newValue: "")
                TelecomManager.shared.doCallOrJoinConf(address: mailbox)
            } catch { Log.error("[AccountModel] Unable to prepare voicemail call") }
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

struct Mango9VoicemailReceipt: Codable, Equatable {
    let waiting: Bool
    let count: Int
}

enum Mango9VoicemailReceipts {
    static func read(owner: String, defaults: UserDefaults = .standard) -> Mango9VoicemailReceipt? {
        guard !owner.isEmpty, let data = defaults.data(forKey: "phone.voicemailReceipt.v1." + owner),
              let saved = try? JSONDecoder().decode(Mango9VoicemailReceipt.self, from: data), saved.count >= 0 else { return nil }
        return saved
    }

    static func save(owner: String, waiting: Bool, count: Int, defaults: UserDefaults = .standard) {
        guard !owner.isEmpty else { return }
        let receipt = Mango9VoicemailReceipt(waiting: waiting, count: waiting ? max(0, count) : 0)
        if let data = try? JSONEncoder().encode(receipt) {
            defaults.set(data, forKey: "phone.voicemailReceipt.v1." + owner)
        }
    }
}
