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

import linphonesw
import Combine
import SwiftUI

final class MagicSearchSingleton: ObservableObject {
	
	static let shared = MagicSearchSingleton()
	private var coreContext = CoreContext.shared
	private var contactsManager = ContactsManager.shared
	
	private var magicSearch: MagicSearch?
	// SDK wrappers and their delegates are owned/reused on the core queue, not
	// read back from a main-queue @Published array during a search callback.
	private var modelsByFriend: [OpaquePointer: ContactAvatarModel] = [:]

	/// SearchResult retains the SDK friend, not its temporary Swift wrapper.
	/// Wrapper allocations can reuse an ObjectIdentifier within the same loop.
	/// The SDK pointer stays valid while these results/models retain the friend.
	static func uniqueFriendResults(_ results: [SearchResult]) -> [SearchResult] {
		var seen = Set<OpaquePointer>()
		return results.filter { result in
			guard let key = result.friend?.getCobject else { return false }
			return seen.insert(key).inserted
		}
	}
	
	var currentFilter: String = ""
	var previousFilter: String?
	
	var needUpdateLastSearchContacts = false
	
	private var limitSearchToLinphoneAccounts = true
	
	@Published var allContact = true
	
	var linphoneDomain = true
	var domainDefaultAccount = ""
	
	var searchDelegate: MagicSearchDelegate?
    
    private var contactLoadedDebounceWorkItem: DispatchWorkItem?
    
    let nativeAddressBookFriendList = "Native address-book"
    let linphoneAddressBookFriendList = "Linphone address-book"
    let tempRemoteAddressBookFriendList = "TempRemoteDirectoryContacts address-book"
	
	@Published var isLoading = false
	
	func destroyMagicSearch() {
		magicSearch = nil
	}
	
	private init() {
		allContact = AppServices.corePreferences.contactsFilter == ""
		
		coreContext.doOnCoreQueue { core in
			self.linphoneDomain = AppServices.corePreferences.defaultDomain == core.defaultAccount?.params?.domain
			self.domainDefaultAccount = AppServices.corePreferences.contactsFilter
			
			self.magicSearch = try? core.createMagicSearch()
			
			guard let magicSearch = self.magicSearch else {
				return
			}
			
			magicSearch.limitedSearch = false
			
			self.searchDelegate = MagicSearchDelegateStub(onSearchResultsReceived: { (magicSearch: MagicSearch) in
				print("[MagicSearchSingleton] [onSearchResultsReceived] Received search results")
				self.needUpdateLastSearchContacts = true
				
				var lastSearchFriend: [SearchResult] = []
				var lastSearchSuggestions: [SearchResult] = []
				
				magicSearch.lastSearch.forEach { searchResult in
					if let friend = searchResult.friend, (friend.friendList?.displayName == self.nativeAddressBookFriendList || friend.friendList?.displayName == self.linphoneAddressBookFriendList || friend.friendList?.displayName == self.tempRemoteAddressBookFriendList) {
						lastSearchFriend.append(searchResult)
					} else if searchResult.friend != nil && (searchResult.hasSourceFlag(source: .RemoteCardDAV) || searchResult.friend?.friendList?.type == .CardDAV || searchResult.hasSourceFlag(source: .LdapServers)) {
						lastSearchFriend.append(searchResult)
					} else {
						lastSearchSuggestions.append(searchResult)
					}
				}
				lastSearchFriend = Self.uniqueFriendResults(lastSearchFriend)
				
				lastSearchSuggestions.sort(by: {
					($0.address?.asStringUriOnly() ?? "") < ($1.address?.asStringUriOnly() ?? "")
				})
				
				if let defaultAccount = core.defaultAccount, let contactAddress = defaultAccount.params?.identityAddress {
					lastSearchSuggestions.removeAll {
						$0.address?.weakEqual(address2: contactAddress) ?? false
					}
				}
				
				var sortable: [(index: Int, result: SearchResult, name: String)] = []
				for (index, result) in lastSearchFriend.enumerated() {
					let name = (result.friend?.name ?? "").folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
					sortable.append((index: index, result: result, name: name))
				}
				sortable.sort { lhs, rhs in lhs.name == rhs.name ? lhs.index < rhs.index : lhs.name < rhs.name }
				let sortedLastSearch: [SearchResult] = sortable.map(\.result)
				
				var nextModels: [OpaquePointer: ContactAvatarModel] = [:]
				let addedAvatarListModel = sortedLastSearch.compactMap { result -> ContactAvatarModel? in
					guard let friend = result.friend else { return nil }
					guard let key = friend.getCobject else { return nil }
					guard nextModels[key] == nil else { return nil }
					let name = friend.name ?? ""
					let address = friend.address?.asStringUriOnly() ?? ""
					let presence = friend.friendList?.displayName != self.nativeAddressBookFriendList && !result.hasSourceFlag(source: .LdapServers)
					let model: ContactAvatarModel
					if let existing = self.modelsByFriend[key] {
						model = existing
						model.resetContactAvatarModel(friend: friend, name: name, address: address, withPresence: presence)
					} else { model = ContactAvatarModel(friend: friend, name: name, address: address, withPresence: presence) }
					nextModels[key] = model
					return model
				}
				for (key, model) in self.modelsByFriend where nextModels[key] == nil { model.removeFriendDelegate() }
				self.modelsByFriend = nextModels
				#if DEBUG
				let nativeCount = sortedLastSearch.filter { $0.friend?.friendList?.displayName == self.nativeAddressBookFriendList }.count
				print("[ContactsSearch] raw=\(magicSearch.lastSearch.count) visible=\(addedAvatarListModel.count) native=\(nativeCount) all=\(self.allContact) filterLength=\(self.currentFilter.count)")
				#endif
                
                self.updateContacts(sortedLastSearch: sortedLastSearch, lastSearchSuggestions: lastSearchSuggestions, addedAvatarListModel: addedAvatarListModel)
			})
			
			magicSearch.addDelegate(delegate: self.searchDelegate!)
		}
	}
	
	func changeAllContact(allContactBool: Bool) {
		allContact = allContactBool
		domainDefaultAccount = allContactBool ? "" : (linphoneDomain ? AppServices.corePreferences.defaultDomain : "*")
		AppServices.corePreferences.contactsFilter = domainDefaultAccount
	}
    
    func updateContacts(
        sortedLastSearch: [SearchResult],
        lastSearchSuggestions: [SearchResult],
        addedAvatarListModel: [ContactAvatarModel]
    ) {
        DispatchQueue.main.async {			
			if let displayed = SharedMainViewModel.shared.displayedFriend {
				if displayed.removalSource == .iPhone && !Mango9ContactAccess.current.canRead {
					SharedMainViewModel.shared.displayedFriend = nil
				} else if let updated = addedAvatarListModel.first(where: { displayed.isSameContact(as: $0) }) {
					// Keep the open page's identity/scroll position. Phone-only entries
					// often have an empty SIP address; that is never an identity key.
					displayed.resetContactAvatarModel(friend: updated.friend, name: updated.name,
						address: updated.address, withPresence: updated.withPresence)
				}
			}
			
            let retiredResults = self.contactsManager.lastSearch + self.contactsManager.lastSearchSuggestions
            self.contactsManager.lastSearch = sortedLastSearch
            self.contactsManager.lastSearchSuggestions = lastSearchSuggestions
            coreQueue.async { withExtendedLifetime(retiredResults) {} }
            
            // One publication: never flash an empty list during a refresh.
            self.contactsManager.avatarListModel = addedAvatarListModel

            // Cancel previous debounce task
            self.contactLoadedDebounceWorkItem?.cancel()

            // Schedule new debounce task
            let workItem = DispatchWorkItem {
                NotificationCenter.default.post(name: NSNotification.Name("ContactLoaded"), object: nil)
            }
			
			self.isLoading = false

            self.contactLoadedDebounceWorkItem = workItem
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: workItem)
        }
    }
	
	func searchForContacts() {
		coreContext.doOnCoreQueue { _ in
			DispatchQueue.main.async {
				self.isLoading = true
			}
			
			var needResetCache = false
			
			if let oldFilter = self.previousFilter {
				if oldFilter.count > self.currentFilter.count || oldFilter != self.currentFilter {
					needResetCache = true
				}
			}
			
			self.previousFilter = self.currentFilter
			
			guard let magicSearch = self.magicSearch else {
				return
			}
			
			if needResetCache {
				magicSearch.resetSearchCache()
			}
			
			magicSearch.getContactsListAsync(
				filter: self.currentFilter,
				domain: self.allContact ? "" : self.domainDefaultAccount,
				sourceFlags: MagicSearch.Source.All.rawValue, //MagicSearch.Source.Friends.rawValue | MagicSearch.Source.LdapServers.rawValue | MagicSearch.Source.RemoteCardDAV.rawValue,
				aggregation: MagicSearch.Aggregation.Friend
			)
		}
	}
}
