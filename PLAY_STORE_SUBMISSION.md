# Mango9 Google Play submission

This file is the release gate for the initial Mango9 Android listing. Do not
promote the release to production until every required item is complete.

## Release identity

- App name: `Mango9`
- Package name: `com.mango9.phone`
- Version: `6.2.6`
- Version code: `602016`
- Primary category: `Business`
- Price: `Free`
- Contains ads: `No`
- Business model: existing Mango9 business users only; no in-app purchases,
  consumer signup, pricing, or subscription links
- Privacy policy: <https://www.mango9.com/privacy>
- Support: <https://www.mango9.com/support> and `support@mango9.com`
- Corresponding source:
  <https://github.com/8189164300/Linphone_mango9/tree/android-6.2.6-build-602016>

## Developer account and app ownership

- [ ] Choose the correct Google Play developer account type and complete the
      required identity/business verification.
- [ ] Confirm the public developer name, legal name, address, phone, website,
      and support email before payment.
- [ ] Pay the Google Play developer registration fee from the account owner.
- [ ] Confirm the account owner can document authorization to distribute the
      Mango9 name, logo, services, and connected CRM/PBX experience.
- [ ] Enable two-step verification and add at least one backup account owner or
      administrator after enrollment.

## Reviewer access

Create a permanent reviewer tenant and enter these values only in Play Console,
never in the public repository:

- CRM login: `[REQUIRED]`
- CRM password: `[REQUIRED]`
- CRM environment: `[REQUIRED]`
- SIP extension: `[REQUIRED]`
- Safe outgoing-call test number: `[REQUIRED]`
- Second Team Chat user: `[REQUIRED]`
- Second Team Chat password, if needed: `[REQUIRED]`
- Reviewer email-code delivery: `[REQUIRED]`
- Review contact name, email, and phone: `[REQUIRED]`

The reviewer tenant must contain at least one lead, one client, a seeded Team
Chat conversation, working SIP registration, and production Firebase push
registration. Do not add expiring credentials, extra MFA, or IP restrictions.

## App access instructions

Use this text in Play Console and complete the placeholders:

> Mango9 is a companion application for existing Mango9 business users.
> Accounts and telecommunications services are provisioned outside the app
> under organizational service agreements. The app has no consumer signup,
> purchase, pricing, or subscription link.
>
> Sign in with the supplied Mango9 CRM credentials. To test passwordless login,
> tap Use an email code and enter the code delivered to the reviewer email. Both
> methods use the same secure provisioning flow.
>
> After login: (1) confirm the assigned extension has a green SIP status dot;
> (2) open CRM and view the seeded lead and client; (3) tap a phone number and
> place a call with Mango9; (4) open Conversations and Team Chat and use the
> supplied second reviewer user; and (5) open About Mango9 > Licensing to view
> GPL/AGPL notices and the exact public corresponding-source tag.
>
> Production services: provisioning `[REQUIRED]`; CRM/API `[REQUIRED]`; SIP
> proxy/domain `[REQUIRED]`; Team Chat `[REQUIRED]`.

## Policy declarations to complete

- [ ] App access: restricted functionality; provide permanent reviewer login.
- [ ] Ads: no ads.
- [ ] Content rating questionnaire: communications/business app; disclose user
      interaction and user-generated messaging accurately.
- [ ] Target audience: business users; do not target children.
- [ ] Data safety: match actual production collection, sharing, encryption,
      retention, and deletion behavior.
- [ ] Data deletion/account management: explain that Mango9 accounts are created
      and managed by customer organizations outside the app; provide the support
      path for access changes, privacy requests, and termination.
- [ ] Full-screen intent: declare that it is used only for incoming calls.
- [ ] Foreground service types: declare phone call, microphone, camera, data sync,
      and special-use behavior exactly as implemented.
- [ ] Permissions: explain microphone for calls, camera for video/attachments,
      contacts for local caller matching, and notifications for calls/messages.
- [ ] Government, financial, health, news, and dating declarations: no, unless
      production scope changes before submission.

## Data safety working sheet

Confirm these answers against the production APIs and retention policy before
saving the Play Console form. The app does not use data for advertising or
cross-app tracking.

Data used for app functionality may include:

- Name, email address, phone number, and internal user/account identifiers
- Device or Firebase push-registration identifiers
- SMS, Team Chat messages, attachments, and other user content
- CRM records available to the authenticated tenant user
- Call and messaging activity required to provide the service

Do not declare the device address book as server-collected unless production
code uploads or retains it off-device. Local contacts permission by itself is
not collection. Confirm TLS in transit, server retention, deletion-request
handling, and whether any service provider qualifies as data sharing under the
current Google definitions.

## Release gates

- [ ] `verifyMango9StaticPolicy`, unit tests, lint, and `bundleRelease` pass.
- [ ] The release AAB is signed by the dedicated Mango9 upload key.
- [ ] Google Play App Signing is enabled; the upload key is not used as the
      long-term app-signing key.
- [ ] The upload keystore and its password are backed up separately and are not
      committed to Git.
- [ ] Firebase `google-services.json`, private keys, passwords, and reviewer
      credentials are absent from the public source repository except the
      non-secret Android Firebase client configuration required by the app.
- [ ] Login, email-code authentication, SIP registration, CRM, calling, SMS,
      Team Chat, push calls, and account switching pass on a physical Android
      device using the release build.
- [ ] Microphone, camera, contacts, and notification prompts appear only when
      needed, and the app remains usable when optional permissions are denied.
- [ ] Logout removes CRM tokens and SIP registration.
- [ ] All support, privacy, licensing, and source links load successfully.
- [ ] No reachable upstream registration or provider-account flow remains.
- [ ] Store icon, feature graphic, phone screenshots, descriptions, and release
      notes are Mango9-branded and contain no private customer data.
- [ ] A closed-test build passes Play pre-launch reports before production.

## Exact-source release gate

1. Increment the version code after any code, resource, manifest, or release
   configuration change.
2. Commit the exact release source with the final source URL.
3. Create and push the immutable tag `android-6.2.6-build-602016`.
4. Build the signed AAB from that tagged commit; never move the tag.
5. Confirm About Mango9 > Licensing opens the exact public tag.
6. Record the Git commit, AAB SHA-256, upload-certificate fingerprints, and Play
   release identifier in the release record.

## Final action

The account owner reviews the complete product page, declarations, reviewer
access, territories, and release settings before starting production rollout.
