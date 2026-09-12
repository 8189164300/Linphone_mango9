# Mango9 iOS Open-Source Notices

This file identifies the principal open-source components included in Mango9
iOS 6.2.12 (build 33). Copyright notices in the source remain with their
respective owners.

## Mango9 application and Linphone iOS

- Mango9 modifications: GNU General Public License version 3 or later.
- Upstream application: Linphone iOS, originally published by Belledonne
  Communications SARL.
- Upstream source:
  https://github.com/BelledonneCommunications/linphone-iphone
- License text: `LICENSE.txt`
- Mango9 corresponding source:
  https://github.com/8189164300/Linphone_mango9/tree/ios-6.2.12-build-33

Mango9 modified the upstream application in 2026. Mango9 is not affiliated
with or endorsed by Belledonne Communications SARL.

## Linphone SDK 5.5.5

- License: GNU Affero General Public License version 3.
- Exact Swift package source:
  https://github.com/BelledonneCommunications/linphone-sdk-swift-ios/tree/5.5.5
- Native SDK source and build manifests:
  https://github.com/BelledonneCommunications/linphone-sdk/tree/5.5.5
- Third-party component inventory:
  https://wiki.linphone.org/xwiki/wiki/public/view/Linphone/Third%20party%20components%20/

The Swift package revision used by this build is
`6d81a65d5fee9d06a8008787e873ff1dc392a19a`. Swift Package Manager records it in
`LinphoneApp.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved`.

## AppAuth for iOS 2.0.0

- License: Apache License 2.0.
- Source: https://github.com/openid/AppAuth-iOS/tree/2.0.0
- License: https://github.com/openid/AppAuth-iOS/blob/2.0.0/LICENSE

## Elegant Emoji Picker

- License: MIT License.
- Source revision:
  https://github.com/Finalet/Elegant-Emoji-Picker/tree/598ff0a72198375d7317b61982fa8648d0ba3a44
- License:
  https://github.com/Finalet/Elegant-Emoji-Picker/blob/598ff0a72198375d7317b61982fa8648d0ba3a44/LICENSE

## Exyte CalendarView and AnchoredPopup

- CalendarView source: https://github.com/exyte/CalendarView
- Pinned revision: `dd749f29f18366ed6b2631c3f82f72e62b5fadc2`.
- AnchoredPopup source: https://github.com/exyte/AnchoredPopup
- Resolved version: 1.2.2, revision `8051eae56e20567b52b1ee30261d4b292a081da0`.
- Both components use the MIT license. Mango9 uses CalendarView's presentation
  with its CRM API provider, not the default local/EventKit providers.
- The corresponding source includes both components under `Vendor/`, with
  iOS availability annotations so the app remains compatible with iOS 15+.
  Exyte is used on iOS 18+; the same CRM appointment flow has a compatible
  calendar on earlier supported iOS versions. See `Vendor/README.md`.

Copyright (c) 2019 exyte <info@exyte.com>
Copyright (c) 2023 Exyte (AnchoredPopup)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

## No warranty

The covered software is distributed in the hope that it will be useful, but
without any warranty; without even the implied warranty of merchantability or
fitness for a particular purpose. Refer to the applicable license text for the
complete terms.
