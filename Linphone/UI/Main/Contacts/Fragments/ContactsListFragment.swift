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
	var selectContact: ((ContactAvatarModel) -> Void)? = nil
	var rowLimit: Int = .max
	var onRowAppear: ((Int) -> Void)? = nil
	
	var body: some View {
		let rows = Self.rows(contactsManager.avatarListModel, limit: rowLimit)
		ForEach(rows) { row in
			ContactRow(contactAvatarModel: row.contact, heading: row.heading, showingSheet: $showingSheet,
				startCallFunc: startCallFunc, selectContact: selectContact)
				.onAppear { onRowAppear?(row.index) }
		}
	}

	struct Row: Identifiable {
		var id: UUID { contact.id }
		let index: Int
		let contact: ContactAvatarModel
		let previous: ContactAvatarModel?
		var heading: String {
			let initial = Self.initial(contact.name)
			return previous.map { Self.initial($0.name) == initial } == true ? "" : initial
		}
		private static func initial(_ name: String) -> String {
			String(name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current).uppercased().first ?? "#")
		}
	}
	/// Constant-time snapshot: List can read stable IDs without folding every
	/// contact's name on each keystroke, focus change or presentation animation.
	/// Values retain the old snapshot safely if a search replaces the live array.
	struct Rows: RandomAccessCollection {
		let contacts: [ContactAvatarModel]
		let limit: Int
		var startIndex: Int { contacts.startIndex }
		var endIndex: Int { Swift.min(contacts.endIndex, Swift.max(0, limit)) }
		subscript(index: Int) -> Row {
			Row(index: index, contact: contacts[index], previous: index > startIndex ? contacts[index - 1] : nil)
		}
	}
	static func rows(_ contacts: [ContactAvatarModel], limit: Int = .max) -> Rows { Rows(contacts: contacts, limit: limit) }
}

struct ContactRow: View {
	@EnvironmentObject var contactsListViewModel: ContactsListViewModel
	
	@ObservedObject var contactAvatarModel: ContactAvatarModel
	
	let heading: String
	
	@Binding var showingSheet: Bool
	
	var startCallFunc: (_ addr: Address) -> Void
	var selectContact: ((ContactAvatarModel) -> Void)? = nil
	
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
			if let selectContact { selectContact(contactAvatarModel); return }
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
