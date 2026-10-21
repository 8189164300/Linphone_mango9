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

struct ConversationsView: View {
	
	@EnvironmentObject var conversationsListViewModel: ConversationsListViewModel
	
	@Binding var text: String
	
	@Binding var isShowStartConversationFragment: Bool
	
	@Binding var showLeaveConversationPopup: Bool
	@Binding var showDeleteConversationPopup: Bool
	@Binding var showDeleteConversationHistoryPopup: Bool
	@Binding var isShowRemoveParticipantPopup: Bool
	
	let header: AnyView

	var body: some View {
		if #available(iOS 16.0, *) {
			// The header belongs to the inbox route. Keeping it outside the stack
			// resizes the stack during Back and carries the detail safe area over
			// the tabs, producing a gap or an invisible toolbar above them.
			NavigationStack { inbox }
		} else {
			NavigationView { inbox }.navigationViewStyle(.stack)
		}
	}

	private var inbox: some View {
		VStack(spacing: 0) {
			header.padding(.top, 1).background(Color.orangeMain500)
			ConversationsFragment(
				text: $text,
				onNewConversation: { withAnimation { isShowStartConversationFragment = true } },
				showLeaveConversationPopup: $showLeaveConversationPopup,
				showDeleteConversationPopup: $showDeleteConversationPopup,
				showDeleteConversationHistoryPopup: $showDeleteConversationHistoryPopup,
				isShowRemoveParticipantPopup: $isShowRemoveParticipantPopup
			)
		}
		.navigationBarHidden(true)
	}
}
