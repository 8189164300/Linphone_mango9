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

import Foundation
import linphonesw
import Combine

enum Mango9ContactRemovalSource: Equatable {
	case mango9, iPhone, directory
	var message: String {
		switch self {
		case .mango9: return "This removes the contact from Mango9. This action can't be undone."
		case .iPhone: return "This removes the contact from Mango9's current list, not from your iPhone. It may reappear when contacts refresh."
		case .directory: return "This removes the contact from its synced address book. The change may also appear on your other devices."
		}
	}
}

class ContactAvatarModel: ObservableObject, Identifiable {
	let id = UUID()
	
	var friend: Friend?
	
	@Published var name: String = ""
	@Published var address: String = ""
	@Published var addresses: [String] = []
	@Published var phoneNumbersWithLabel: [(label: String, phoneNumber: String)] = []
	@Published var emails: [String] = []
	
	var nativeUri: String = ""
	var sourceName: String = ""
	var editable: Bool = true
	var isReadOnly: Bool = false
	var removalSource: Mango9ContactRemovalSource = .mango9
	var withPresence: Bool?
	
	@Published var starred: Bool = false
	
	var vcard: Vcard?
	var organization: String = ""
	var jobTitle: String = ""
	
	@Published var photo: String = ""
	@Published var lastPresenceInfo: String = ""
	@Published var presenceStatus: ConsolidatedPresence = .Offline
	@Published var unsafeFriend: Bool = false
	@Published var trustedFriend: Bool = false
	
	private var friendDelegate: FriendDelegate?
	
	init(friend: Friend?, name: String, address: String, withPresence: Bool?) {
		self.name = name
	 	self.address = address
		guard friend != nil else { self.withPresence = withPresence; return }
		self.resetContactAvatarModel(friend: friend, name: name, address: address, withPresence: withPresence)
	}
	
	func resetContactAvatarModel(friend: Friend?, name: String, address: String, withPresence: Bool?) {
		CoreContext.shared.doOnCoreQueue { _ in
			if self.friend !== friend || withPresence != true { self.removeFriendDelegate() }
			self.friend = friend
			let nameTmp = name
			let addressTmp = address
			var addressesTmp: [String] = []
			if let friend = friend {
				friend.addresses.forEach { address in
					addressesTmp.append(address.asStringUriOnly())
				}
			}
			var phoneNumbersWithLabelTmp: [(label: String, phoneNumber: String)] = []
			if let friend = friend {
				friend.phoneNumbersWithLabel.forEach { phoneNum in
					phoneNumbersWithLabelTmp.append((label: phoneNum.label ?? "", phoneNumber: phoneNum.phoneNumber))
				}
			}
			let nativeUriTmp = friend?.nativeUri ?? ""
			let sourceName = friend?.friendList?.displayName ?? ""
			let removalSource: Mango9ContactRemovalSource = friend?.friendList?.type == .CardDAV ? .directory :
				(friend?.friendList?.displayName == "Native address-book" ? .iPhone : .mango9)
			let editableTmp = friend?.friendList?.type == .CardDAV || nativeUriTmp.isEmpty
			let isReadOnlyTmp = (friend?.isReadOnly == true) || (friend?.inList() == false)
			let withPresenceTmp = withPresence
			let starredTmp = friend?.starred ?? false
			let vcardTmp = friend?.vcard ?? nil
			let emailsTmp = vcardTmp?.getExtendedPropertiesValuesByName(name: ContactsManager.emailVCardProperty) ?? []
			let organizationTmp = friend?.organization ?? ""
			let jobTitleTmp = friend?.jobTitle ?? ""
			var photoTmp = friend?.photo ?? ""
			
			if friend?.friendList?.type == .CardDAV && friend?.photo?.isEmpty == false {
				let fileName = "file:/" + name + ".png"
				photoTmp = fileName.replacingOccurrences(of: " ", with: "")
			}
			
			var lastPresenceInfoTmp = ""
			var presenceStatusTmp: ConsolidatedPresence = .Offline
			
			let security = withPresence == true ? (friend?.securityLevel ?? .None) : .None
			let unsafeFriendTmp = security == .Unsafe
			let trustedFriendTmp = security == .EndToEndEncryptedAndVerified
			
			if let friend = friend, withPresence == true {
                
				lastPresenceInfoTmp = ""
				
				presenceStatusTmp = friend.consolidatedPresence
                
				if friend.consolidatedPresence == .Online || friend.consolidatedPresence == .Busy {
					let timestamp = friend.presenceModel?.latestActivityTimestamp ?? -1
					if friend.consolidatedPresence == .Online || timestamp != -1 {
						lastPresenceInfoTmp = (friend.consolidatedPresence == .Online) ?
						"Online" : self.getCallTime(startDate: timestamp)
					} else {
						lastPresenceInfoTmp = "Away"
					}
				}
				
				if self.friendDelegate == nil { self.addFriendDelegate() }
			}
			
			DispatchQueue.main.async {
				if self.name != nameTmp { self.name = nameTmp }
				if self.address != addressTmp { self.address = addressTmp }
				if self.addresses != addressesTmp { self.addresses = addressesTmp }
				if !self.phoneNumbersWithLabel.elementsEqual(phoneNumbersWithLabelTmp, by: { $0.label == $1.label && $0.phoneNumber == $1.phoneNumber }) { self.phoneNumbersWithLabel = phoneNumbersWithLabelTmp }
				self.nativeUri = nativeUriTmp
				self.sourceName = sourceName
				self.removalSource = removalSource
				self.editable = editableTmp
				self.isReadOnly = isReadOnlyTmp
				self.withPresence = withPresenceTmp
				if self.starred != starredTmp { self.starred = starredTmp }
				self.vcard = vcardTmp
				if self.emails != emailsTmp { self.emails = emailsTmp }
				self.organization = organizationTmp
				self.jobTitle = jobTitleTmp
				if self.photo != photoTmp { self.photo = photoTmp }
				if self.lastPresenceInfo != lastPresenceInfoTmp { self.lastPresenceInfo = lastPresenceInfoTmp }
				if self.presenceStatus != presenceStatusTmp { self.presenceStatus = presenceStatusTmp }
				if self.unsafeFriend != unsafeFriendTmp { self.unsafeFriend = unsafeFriendTmp }
				if self.trustedFriend != trustedFriendTmp { self.trustedFriend = trustedFriendTmp }
			}
		}
	}
	
	func isSameContact(as other: ContactAvatarModel) -> Bool {
		guard sourceName == other.sourceName else { return false }
		if !nativeUri.isEmpty || !other.nativeUri.isEmpty { return !nativeUri.isEmpty && nativeUri == other.nativeUri }
		return !address.isEmpty && address == other.address
	}

	func addFriendDelegate() {
		friendDelegate = FriendDelegateStub(onPresenceReceived: { [weak self] (friend: Friend) in
			guard let self else { return }
			let latestActivityTimestamp = friend.presenceModel?.latestActivityTimestamp ?? -1
			let consolidatedPresenceTmp = friend.consolidatedPresence
			DispatchQueue.main.async {
				self.presenceStatus = consolidatedPresenceTmp
				if consolidatedPresenceTmp == .Online || consolidatedPresenceTmp == .Busy {
					if consolidatedPresenceTmp == .Online || latestActivityTimestamp != -1 {
						self.lastPresenceInfo = consolidatedPresenceTmp == .Online ?
						"Online" : self.getCallTime(startDate: latestActivityTimestamp)
					} else {
						self.lastPresenceInfo = "Away"
					}
				} else {
					self.lastPresenceInfo = ""
				}
			}
		})
		
		if friend != nil && friendDelegate != nil {
			friend!.addDelegate(delegate: friendDelegate!)
		}
	}
	
	func removeFriendDelegate() {
		if let delegate = friendDelegate {
			DispatchQueue.main.async {
				self.presenceStatus = .Offline
			}
			if let friendTmp = friend {
				friendTmp.removeDelegate(delegate: delegate)
			}
			friendDelegate = nil
		}
	}

	deinit {
		// SwiftUI may release a row on the main thread. Keep the final SDK wrapper
		// references alive until they can be released alongside core operations.
		let retainedFriend = friend, retainedVcard = vcard, retainedDelegate = friendDelegate
		guard retainedFriend != nil || retainedVcard != nil || retainedDelegate != nil else { return }
		coreQueue.async {
			if let retainedDelegate { retainedFriend?.removeDelegate(delegate: retainedDelegate) }
			withExtendedLifetime(retainedFriend) {}
			withExtendedLifetime(retainedVcard) {}
		}
	}
	
	func getCallTime(startDate: time_t) -> String {
		let timeInterval = TimeInterval(startDate)
		
		let myNSDate = Date(timeIntervalSince1970: timeInterval)
		
		if Calendar.current.isDateInToday(myNSDate) {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "HH:mm" : "h:mm a"
			return "Online today at " + formatter.string(from: myNSDate)
		} else if Calendar.current.isDateInYesterday(myNSDate) {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "HH:mm" : "h:mm a"
			return "Online yesterday at " + formatter.string(from: myNSDate)
		} else if Calendar.current.isDate(myNSDate, equalTo: .now, toGranularity: .year) {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "dd/MM | HH:mm" : "MM/dd | h:mm a"
			return "Online on " + formatter.string(from: myNSDate)
		} else {
			let formatter = DateFormatter()
			formatter.dateFormat = Locale.current.identifier == "fr_FR" ? "dd/MM/yy | HH:mm" : "MM/dd/yy | h:mm a"
			return "Online on " + formatter.string(from: myNSDate)
		}
	}
	
	static func getAvatarModelFromAddress(address: Address, completion: @escaping (ContactAvatarModel) -> Void) {
		ContactsManager.shared.getFriendWithAddressInCoreQueue(address: address) { resultFriend in
			if let addressFriend = resultFriend {
				if addressFriend.address != nil {
					var avatarModel = ContactsManager.shared.avatarListModel.first(where: {
						$0.friend != nil && $0.friend!.name == addressFriend.name && $0.friend!.address != nil
						&& $0.friend!.address!.asStringUriOnly() == addressFriend.address!.asStringUriOnly()
					})
					
					if avatarModel == nil {
						avatarModel = ContactAvatarModel(friend: nil, name: addressFriend.name!, address: addressFriend.address!.asStringUriOnly(), withPresence: false)
					}
					completion(avatarModel!)
				} else if !addressFriend.phoneNumbers.isEmpty {
					var avatarModel = ContactsManager.shared.avatarListModel.first(where: {
						$0.friend != nil && $0.friend!.name == addressFriend.name && !$0.friend!.phoneNumbers.isEmpty
						&& $0.friend!.phoneNumbers == addressFriend.phoneNumbers
					})
					
					if avatarModel == nil {
						avatarModel = ContactAvatarModel(friend: nil, name: addressFriend.name!, address: addressFriend.phoneNumbers.first ?? addressFriend.address?.asStringUriOnly() ?? "", withPresence: false)
					}
					
					completion(avatarModel!)
				} else {
					var name = ""
					if address.displayName != nil {
						name = address.displayName!
					} else if address.username != nil {
						name = address.username!
					} else {
						name = String(address.asStringUriOnly().dropFirst(4))
					}
					completion(ContactAvatarModel(friend: nil, name: name, address: address.asStringUriOnly(), withPresence: false))
				}
			} else {
				var name = ""
				if address.displayName != nil {
					name = address.displayName!
				} else if address.username != nil {
					name = address.username!
				} else {
					name = String(address.asStringUriOnly().dropFirst(4))
				}
				completion(ContactAvatarModel(friend: nil, name: name, address: address.asStringUriOnly(), withPresence: false))
			}
		}
	}
}
