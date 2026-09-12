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

struct ContactsListFragment: View {
	
	@ObservedObject var contactsManager = ContactsManager.shared
	
	@EnvironmentObject var contactsListViewModel: ContactsListViewModel
	
	@Binding var showingSheet: Bool
	
    var startCallFunc: (_ addr: Address) -> Void
	
	var body: some View {
		let rows = Self.rows(contactsManager.avatarListModel)
		ForEach(rows) { row in
			ContactRow(contactAvatarModel: row.contact, heading: row.heading, showingSheet: $showingSheet, startCallFunc: startCallFunc)
		}
	}

	struct Row: Identifiable {
		var id: UUID { contact.id }
		let contact: ContactAvatarModel
		let heading: String
	}
	static func rows(_ contacts: [ContactAvatarModel]) -> [Row] {
		var previous: String?
		return contacts.map { contact in
			let initial = String(contact.name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current).uppercased().first ?? "#")
			let heading = initial == previous ? "" : initial
			previous = initial
			return Row(contact: contact, heading: heading)
		}
	}
}

struct ContactRow: View {
	@EnvironmentObject var contactsListViewModel: ContactsListViewModel
	
	@ObservedObject var contactAvatarModel: ContactAvatarModel
	
	let heading: String
	
	@Binding var showingSheet: Bool
	
	var startCallFunc: (_ addr: Address) -> Void
	
	var body: some View {
		HStack {
			HStack {
				if !heading.isEmpty {
					Text(heading)
					.contact_text_style_500(styleSize: 20)
					.frame(width: 18)
					.padding(.leading, -5)
					.padding(.trailing, 10)
				} else {
					Text("")
						.contact_text_style_500(styleSize: 20)
						.frame(width: 18)
						.padding(.leading, -5)
						.padding(.trailing, 10)
				}
				
				Avatar(contactAvatarModel: contactAvatarModel, avatarSize: 50)
				
				Text(contactAvatarModel.name)
					.default_text_style(styleSize: 16)
					.lineLimit(2)
					.fixedSize(horizontal: false, vertical: true)
					.frame(maxWidth: .infinity, alignment: .leading)
					.foregroundStyle(Color.orangeMain500)
			}
		}
		.frame(minHeight: 50)
		.buttonStyle(.borderless)
		.listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
		.listRowSeparator(.hidden)
		.background(.white)
		.onTapGesture {
            if SharedMainViewModel.shared.indexView == 0 {
                withAnimation {
                    SharedMainViewModel.shared.displayedFriend = contactAvatarModel
                }
            }
			
			CoreContext.shared.doOnCoreQueue { core in
				if let friend = contactAvatarModel.friend {
					if let friendAddress = friend.address {
						startCallFunc(friendAddress)
					} else if !friend.phoneNumbers.isEmpty {
						if let address = core.interpretUrl(url: friend.phoneNumbers.first ?? "", applyInternationalPrefix: LinphoneUtils.applyInternationalPrefix(core: core)) {
							startCallFunc(address)
						}
					}
				}
			}
		}
		.onLongPressGesture(minimumDuration: 0.2) {
            if SharedMainViewModel.shared.indexView == 0 {
                contactsListViewModel.selectedFriend = contactAvatarModel
                showingSheet.toggle()
            }
		}
	}
}

#Preview {
    ContactsListFragment(showingSheet: .constant(false), startCallFunc: {_ in })
}
