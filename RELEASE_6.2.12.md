# Mango9 iOS 6.2.12 (33)

Base: published iOS 6.2.11 (32), commit `66cf94a10`.

This app update adds authenticated CRM appointments, list/calendar presentations,
status and permitted sharing/assignment actions, in-app reminders, and CRM
preferences. It preserves the existing native SMS UI while splitting its root
view into bounded sections to address the observed SwiftUI metadata stack crash.
Outgoing calls now check current microphone permission before dialing.

One app supports iOS 15+: Exyte is availability-guarded for iOS 18+, and the
compatible Day/Week/Month calendar serves iOS 15–17 using the same API and
appointment actions. No calling/authentication dependency revisions were changed.

Contact loading uses bounded background imports, stable contact identities and
lazy thumbnail loading. Contact details use a simpler native-style layout and
the iPhone contact editor. CallKit receives normalized phone handles without
using formatted phone numbers as caller names. Appointment navigation and
reminder layouts have also been refined, including the compact Edit button.

The corresponding source tag is `ios-6.2.12-build-33`. App-only metadata is in
`AppStoreMetadata/en-US`. Release is through App Store review with automatic
release after approval, not a TestFlight testing distribution.

Regression checks cover calendar boundaries and refresh behavior, reminders,
large contact lists, contact permissions, call presentation, microphone access,
message navigation and account isolation on iOS 26.5 and iOS 17.5. The signed
Debug app was installed and its latest layout changes accepted on the paired
iPhone. These checks are not a guarantee of every device/carrier scenario;
physical iOS 15/16 and live two-way audio acceptance are not claimed by simulator
tests.

Regression coverage is in `LinphoneAppTests`. Internal release evidence is
maintained separately from this public source repository.
Raw build logs, screenshots and test results stay outside the public repository.
