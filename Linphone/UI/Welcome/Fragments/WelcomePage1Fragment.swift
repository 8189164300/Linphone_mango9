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
import SwiftUI

struct WelcomePage1Fragment: View {
	
	var body: some View {
		VStack {
			Spacer()
			VStack {
				Image("mango9-mark")
					.resizable()
					.scaledToFit()
					.frame(width: 150, height: 88)
					.padding(.horizontal, 26)
					.padding(.vertical, 20)
					.background(Color.orangeMain500)
					.cornerRadius(24)
				Text(Bundle.main.displayName)
					.welcome_text_style_gray_800(styleSize: 30)
					.padding(.bottom, 20)
				Text("welcome_page_1_message")
					.welcome_text_style_gray(styleSize: 15)
					.multilineTextAlignment(.center)
				
			}
			.padding(.horizontal)
			.padding(.bottom, 60)
			
			Spacer()
		}
		.frame(maxWidth: .infinity)
		.padding(.horizontal, 20)
	}
}

#Preview {
	WelcomePage1Fragment()
}
