# Mango9 iOS App Store Submission

This file is the release gate for the Mango9 iOS 6.2.4 update.
Do not press **Submit for Review** until every required item below is complete.

## Release identity

- App name: `Mango9`
- Bundle ID: `com.mango9.phone`
- Version: `6.2.4`
- Current build: `19`
- Primary category: `Business`
- Price: `Free`
- Release method: `Manually release this version`
- Seller/signing identity: George Gabrielyan (individual Apple Developer account)

## What's New in 6.2.4

Sign in with a password or a secure code sent by email—QR setup is no longer
required. Incoming calls now open the call screen more reliably, multiple lines
recover better after network changes, and SMS and Team Chat notifications open
the correct conversation. Connection messages are also clearer throughout the
app.

## Business model statement

Use this wording in App Review Notes:

> Mango9 is a free companion application for existing Mango9 business users.
> Mango9 accounts and telecommunications services are provisioned outside the
> application under organizational service agreements. The application contains
> no purchases, pricing, subscription links, or consumer account registration.

## Reviewer access

Create a permanent reviewer tenant and complete these fields immediately before
uploading the release candidate:

- CRM login: `[REQUIRED]`
- CRM password: `[REQUIRED]`
- CRM environment: `[REQUIRED]`
- SIP extension: `[REQUIRED]`
- Safe outgoing-call test number: `[REQUIRED]`
- Second team-chat user: `[REQUIRED]`
- Second team-chat password, if needed: `[REQUIRED]`
- Reviewer email-code delivery must be enabled: `[REQUIRED]`
- Review contact name: `[REQUIRED]`
- Review contact email: `[REQUIRED]`
- Review contact phone: `[REQUIRED]`

The review tenant must contain at least one lead, one client, a seeded Team Chat
conversation, a working SIP registration, and production push registration.
Do not enable additional MFA, expiring passwords, or IP restrictions for the
reviewer account. Keep both password and email-code login available during review.

## App Review Notes

Paste and complete this text in App Store Connect:

> Mango9 provides business calling, CRM access, and private tenant-scoped team
> messaging for existing Mango9 business users.
>
> Sign in using the supplied Mango9 CRM credentials, or choose Use an email code
> and enter the code sent to the reviewer email. Both methods continue through
> the same secure account-provisioning flow. Mango9 discovers the user's assigned
> CRM environment and provisions the corresponding SIP extension. The user can
> then make and receive business calls, access assigned CRM records, and
> communicate with authorized users in the same Mango9 tenant.
>
> Review steps:
> 1. Launch Mango9 and sign in with the supplied CRM credentials. To test the
>    passwordless path, choose Use an email code and enter the delivered code.
> 2. Confirm the extension shows Connected.
> 3. Open CRM to view the seeded lead and client.
> 4. Tap a phone number and select Call with Mango9.
> 5. Open Conversations, then Team Chat, and use the supplied second user.
> 6. In a Team Chat conversation, open the safety menu to report or block a user.
> 7. Open About Mango9 > Licensing to view GPL/AGPL notices, upstream attribution,
>    and the public corresponding-source link.
>
> Accounts are created and managed by Mango9 business customers outside the app.
> There is no consumer signup, purchase, pricing, or subscription link in the app.
> Users may contact support@mango9.com or their organization administrator for
> access changes, privacy requests, or account termination.
>
> Production services used during review:
> - Provisioning: `[REQUIRED]`
> - CRM/API: `[REQUIRED]`
> - SIP proxy/domain: `[REQUIRED]`
> - Team Chat: `[REQUIRED]`
>
> Open-source corresponding source for this exact binary:
> `[FINAL IMMUTABLE GITHUB TAG URL REQUIRED]`

## Privacy declaration working sheet

The release privacy manifest declares no tracking. Confirm the App Store Connect
privacy answers match the production API and retention behavior.

Data linked to the user and used for app functionality:

- Name
- Email address
- Phone number
- User ID
- Device ID or push registration identifier
- Emails or text messages
- Photos or videos shared by the user
- Audio data shared by the user
- Other user content, including CRM records

Do not declare device contacts as collected unless the app uploads or retains the
user's iOS address book off-device. Local permission to display contacts is not by
itself collection.

Privacy policy: <https://www.mango9.com/privacy>

Support and abuse contact: <https://www.mango9.com/support> and
`support@mango9.com`

## Release build gates

- [ ] All app and extension targets compile in Release configuration.
- [ ] `PrivacyInfo.xcprivacy` is present at the root of the archived app bundle.
- [ ] The notification-service extension contains its privacy manifest.
- [ ] Embedded Linphone SDK frameworks that use required-reason APIs contain
      their generated privacy manifests.
- [ ] `scripts/verify-app-store-bundle.sh /path/to/Mango9.app` passes for the
      archived app.
- [ ] Xcode's generated privacy report matches the App Store privacy answers.
- [ ] Release archive is signed with `aps-environment = production`.
- [ ] No development provisioning profiles are embedded in the archive.
- [ ] The archive exports successfully with
      `Config/AppStoreExportOptions.plist`, and the exported IPA verifies with
      `aps-environment = production`.
- [ ] Production APNs token registration succeeds from the TestFlight build.
- [ ] Incoming-call PushKit notification immediately reports the call to CallKit.
- [ ] CRM, chat, lead, and SMS events use ordinary APNs rather than VoIP pushes.
- [ ] Microphone, camera, contacts, photos, calendar, and local-network permission
      prompts appear only when the related feature is used.
- [ ] Login and provisioning work after deleting and reinstalling the app.
- [ ] Remember-login behavior works after reboot.
- [ ] Logout removes CRM tokens and SIP registration.
- [ ] No passwords, SIP secrets, Firebase files, private keys, or review credentials
      are present in the public repository.
- [ ] All visible support, privacy, licensing, and source links load successfully.
- [ ] No reachable Linphone registration, subscription, or provider account flow
      remains in the release UI.
- [ ] Team Chat report, block, and unblock controls work on a physical device.
- [ ] Team Chat message hide, restore, and report controls work on a physical
      device.
- [ ] `support@mango9.com` is monitored and chat-safety reports have a documented
      timely-response and removal process.
- [ ] The app remains usable with contacts, camera, photos, and calendar access
      denied.
- [ ] Accessibility labels exist for icon-only buttons on login, CRM, calling, chat,
      and navigation screens.
- [ ] The app has been tested on the smallest and largest supported iPhone layouts.
- [ ] The app has been tested on iPad, or iPad support is removed before submission.

## Exact-source release gate

Complete this only after the final TestFlight candidate has passed every test:

1. Increment the build number if any code, resource, manifest, entitlement, or
   configuration changes.
2. Choose the immutable tag name, such as `ios-6.2.4-build-19`, and change
   `MANGO9_SOURCE_CODE_URL` in `Shared.xcconfig` to that tag URL.
3. Commit the exact release source, including the final source URL.
4. Create the chosen tag on that commit and push both the commit and tag.
5. Build the App Store archive from that tagged commit. Never move or replace the
   published tag.
6. Confirm About Mango9 > Licensing opens the exact public tag.
7. Record the Git commit and archive checksum in the release record.

## App Store Connect completion

- [ ] Agreements, tax, banking, and developer contact information are current.
- [ ] App name, subtitle, description, keywords, category, copyright, and URLs are
      complete and accurate.
- [ ] Current age-rating questionnaire is complete.
- [ ] Content-rights declaration is complete.
- [ ] The account holder can provide proof, if Apple requests it, that George
      Gabrielyan owns or is authorized to distribute the Mango9 name, logo,
      services, and connected CRM/PBX experience.
- [ ] Export-compliance questionnaire matches the encryption included in Linphone.
- [ ] EU Digital Services Act trader status is complete if distributing in the EU.
- [ ] Screenshots show the real Mango9 login, connected extension, CRM, call flow,
      and Team Chat without test or private customer information.
- [ ] Review credentials and App Review Notes are saved.
- [ ] A TestFlight build has passed physical-device production testing.
- [ ] Final release is set to manual release.
- [ ] The account owner reviews the complete product page and build.

## Final action

The account owner performs the final **Submit for Review** action only after this
checklist is complete.
