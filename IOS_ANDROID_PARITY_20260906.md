# Android/iOS messaging parity audit — 2026-09-06

Baselines: Android `505ed923e` (6.2.7 / 602026), iOS `229cb20d2`
(published 6.2.10 / 31), plus iOS 6.2.11 Team Chat recency changes.
Android candidate: 6.2.8 / 602027. Platform build sequences remain independent.

## Confirmed matching defects patched

- Team Chat people were alphabetical even after a new message. Both platforms
  now sort their existing Group/People lists by latest activity. Delayed older
  message events cannot move the preview backward.
- Android conversation teardown used room ID (or null) rather than screen
  ownership. Added per-screen/per-attempt/account leases; superseded opens and
  old screens cannot clear the replacement conversation.
- Android history results needed connection/account guards. Added those guards
  and stopped displaying another room's messages during navigation.
- Attachment sends retain their initiating account/connection. Switching accounts
  during an upload cannot post that message through the newly selected account;
  only the owning conversation screen can send or report typing.
- Added conversation-opening progress and an explicit history retry button.
- Same-account push opening unnecessarily reset the messaging connection.
  Reuse the active account, and do not let a CRM contacts failure prevent chat.
- An SMS-directory or presence failure blocked Team Chat directory publication.
  Team directory readiness is now independent of those optional refreshes.
- Attachments used external ACTION_VIEW even though the app already has a native
  audio/video/image viewer with playback, zoom, export and share. Reuse that
  viewer with bounded, HTTPS-only, on-demand background downloads. Documents
  use FileProvider read grants. Upload bytes and provider routes are unchanged.
- Rebinding delivery status rebuilt attachment views. Retain unchanged previews,
  add a video play overlay without storage filenames, and avoid scrolling a
  user away from older messages when only presence/status changes.

## Already present; not rewritten

Exact-room push targets (`Mango9MessagePush`), account-scoped push resolution and
deleted-session rejection (`Mango9FirebaseMessagingService`), token unregister
before account deletion (`AccountProfileViewModel`), SMS delivery normalization,
tab routing, media URL metadata parsing and Coil video decoding. Existing tests
cover provisioning, multi-account policy, push targeting, phone normalization,
and SMS behavior. No SIP registration, FreeSWITCH/OpenSIPS or backend changes.

## Preservation / release scope

The original Android worktree's unfinished System Alerts modifications were
left untouched. This candidate is a separate worktree/branch. Apple receives
only the iOS 6.2.11 changes, not Android changes or server code.

## Validation

Final Android validation passed 54 JVM tests (zero failures/errors), static policy
and debug APK assembly, including the MIME fallback, ownership/send guards and
scrolling refinements. `lintDebug` could not complete: AndroidX's
`UseRequireInsteadOfGet` detector crashed with `KotlinExceptionWithAttachments`
while resolving Kotlin light classes in `Mango9ChatRouting.kt`. No lint rules were
disabled to conceal this failure. No Android device is attached: actual
push delivery, account switching and native media playback still require a
physical-device smoke test before a Google Play release. This task does not
submit a Google Play release.

iOS: 35 selected Xcode tests passed (zero failures), including navigation,
multi-account isolation, caller identity and Team Chat ordering.
