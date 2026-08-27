# Mango9 Android parity contract

This document is the implementation and verification contract for the Mango9
Android client. The reference implementation is the local Mango9 iOS 6.2.6
build 22 source at `../Linphone_mango9`. Android starts from the official
Linphone Android `6.2.6` tag, commit
`42b1fcce3c8037e6f5a891cf8d108eb47e308386`.

Parity means matching the user-visible behavior and Mango9 service contracts;
it does not mean transliterating Swift into Kotlin or forcing Android to copy
iOS platform conventions where Android has a native equivalent.

## Status legend

- `[ ]` not implemented or not proven
- `[~]` implemented or inherited, but end-to-end Mango9 behavior is not yet proven
- `[x]` implemented and backed by the evidence named in the row

## Verified baseline (2026-08-26)

- Android source: official Linphone Android `6.2.6`, commit
  `42b1fcce3c8037e6f5a891cf8d108eb47e308386`.
- Communication SDK: exact Maven version
  `5.5.17-pre.1+3896ec0681`, with matching source link in Licensing.
- Local gate: `verifyMango9StaticPolicy`, 32 JVM unit tests, `assembleDebug`,
  `assembleDebugAndroidTest`, and strict `lintDebug` all pass.
- Clean emulator install: ten instrumentation tests pass. A manual cold launch
  resolves from `org.linphone.ui.main.MainActivity` to the Mango9
  `AssistantActivity` permissions flow; the device suite traverses the Mango9
  login UI, and the cold launch has no fatal app crash.
- Built debug APK: application ID `com.mango9.phone`, version `6.2.6`, cleartext
  disabled, exact MDM metadata present, required vCard grammar present, ZIP
  integrity valid, Mango9-owned Firebase client resources generated, FCM service
  enabled, no server private-key asset, and explicit exclusion of account state
  from backup and device transfer.
- UI evidence is stored under `app/build/reports/mango9-smoke/`. Pixel captures
  are intentionally blocked while Mango9's default Android `FLAG_SECURE` policy
  is active, so the XML accessibility hierarchies are the authoritative screen
  evidence.
- The login screen uses the same fixed iOS palette in both Android light and
  dark device modes: `#F9F9F9` page/field surfaces, a white enrollment card,
  `#4E6074` copy, and `#4053C8` brand/actions. The dark-mode emulator hierarchy
  confirms that all controls and the English `Password` hint remain present.
- The Android launch screen matches iOS with a solid `#4053C8` background and
  centered white Mango9 wordmark in every API/day-night theme. Android's former
  Linphone center icon and bottom branding image are not referenced. The email
  code action positions its envelope independently so the label stays centered.

## Identity, branding, and distribution

- [x] App name is **Mango9** in the manifest, launcher, recents, notification,
      Android Auto, Telecom, About, and accessibility labels.
- [x] Application ID is `com.mango9.phone`; the app-owned deep-link scheme is
      `mango9` (standard `tel`, `sip`, `sips`, and `callto` remain supported).
- [x] Mango9 launcher/adaptive icons use the approved SVG paths, and the
      wordmark is derived from `../Linphone_mango9/Branding`.
- [~] Mango9 blue/brand theme, typography, spacing, login card, navigation, and
      empty/error/loading states are implemented. The login palette and solid
      brand card are pinned to the iOS source values in both device modes; an
      exhaustive phone/tablet visual comparison against iOS remains.
- [x] Linphone upstream copyright and GPL notices remain available in Licensing;
      reachable Linphone consumer-account branding and hosted-service links are
      rejected by the static policy.
- [x] Upstream Firebase credentials are deleted. The Manushak Firebase project
      now owns an Android app registered as `com.mango9.phone`; Firebase Messaging
      is compiled from `app/mango9-google-services.json`, Gradle consumes that
      explicit Mango9 file, and the current APK enables the Mango9 FCM service.
- [x] Licensing, README, and open-source notices link the exact upstream
      Android/SDK revisions and immutable Mango9 corresponding-source tag
      `android-6.2.6-build-602010`; the static policy requires these links to
      remain consistent.

## Enrollment and account lifecycle

Reference: `LoginFragment.swift`, `CorePreferences.swift`, and
`Mango9MultiAccountTests.swift`.

- [x] Password login calls `POST https://provision.mango9.com/v1/mobile/login`
      with `platform=android` and a stable device identifier.
- [x] Passwordless login requests and verifies the six-digit email code through
      `/v1/mobile/login-code/request` and `/v1/mobile/login-code/verify`.
- [x] Login supports password visibility, validation, resend countdown,
      user-facing stage-specific server errors, loading state, and “keep me
      signed in”. Authentication, one-time XML enrollment, and SIP installation
      failures are distinguished without logging credentials or tokens.
- [x] Enrollment URLs are accepted only over HTTPS from
      `provision.mango9.com`, with no user-info and only the default/443 port.
- [x] One-time Linphone provisioning XML is parsed for SIP identity, username,
      domain, realm, and HA1 without persisting the one-time URL. Android uses
      XML content negotiation like iOS and does not misreport an expired or
      missing enrollment as an email-code or CRM-password failure.
- [x] Every managed SIP account registers through
      `sip:proxy.mango9.com;transport=tls`; registrar and route cannot be changed
      by the managed-account UI.
- [x] CRM bearer/refresh tokens and SIP credentials remain separate trust
      boundaries and are stored with Android Keystore-backed encryption.
- [x] Saved sessions restore through the CRM `/provisioning` endpoint and refresh
      expired access tokens before rebuilding the SIP account.
- [x] Multiple SIP/CRM identities are stored independently and switching the
      default line switches CRM, chat, SMS, unread, and caller context atomically.
- [x] DID and extension labels are cached per normalized SIP identity, shown on
      the account switcher, activated with the selected account, and cleared only
      with that account.
- [x] Duplicate SIP identities are deduplicated; logout removes only the selected
      account’s session, line cache, push registration, auth info, and account.
- [x] Manual SIP setup remains available from the first-launch gear action,
      accepts an independent SIP domain/transport, and does not create or expose
      a Mango9 CRM session; a dedicated instrumentation test enforces isolation.
- [x] QR enrollment accepts only verified Mango9 one-time enrollment URLs.
- [x] Android managed app configuration matches the iOS MDM contract for
      `xmlConfig`, `rootCa`, and `configUri`, including runtime removal.

## Calling and registration

Reference: `CoreContext.swift`, `TelecomManager.swift`, `CallViewModel.swift`,
and `Mango9PushCallerIdentityTests.swift`.

- [~] Audio/video calling, DTMF, hold, mute, speaker/Bluetooth routing, attended
      transfer, multi-call, conference UI, encryption indicators, statistics,
      recording, and call history exist upstream and require Mango9 regression
      tests.
- [~] Outbound calls use the selected Mango9 account and preserve CRM display
      name/number formatting.
- [x] Managed-account registration targets Mango9 and static policy rejects
      public Linphone runtime endpoints. Push is enabled only for a reviewed
      Mango9 FCM build.
- [x] Android mirrors the iOS 30-day mobile registration expiry. If the Mango9
      proxy returns the exact `555 Push Notification Service Not Supported`
      response for an FCM contact, Android retries that managed account without
      SIP push parameters so foreground registration can complete. Background
      incoming-call wake still requires proxy-side FCM support.
- [~] Android Telecom/full-screen incoming-call behavior is inherited for
      foreground, background, locked, killed, reboot, and network-change cases,
      but the complete Mango9 physical-device matrix is not yet run.
- [x] Incoming push caller identity is parsed from the same payload variants as
      iOS, scoped by SIP Call-ID, expired after 120 seconds, and removed when the
      call is released. Parser, cache-isolation, expiry, notification, call UI,
      and Android Telecom integration are covered locally. Like iOS, Android
      hands token changes to Linphone on its core thread and refreshes SIP
      registration as soon as a call push wakes the core, allowing OpenSIPS to
      attach its held INVITE to the fresh mobile contact. Live FCM wake remains
      in the physical-device matrix.
- [x] Managed-account registration failures use the same actionable Mango9
      categories as iOS. The eight-second delay is cancelled by recovery or a
      newer account generation so stale duplicate-account errors are suppressed.
- [~] Call forwarding is implemented against
      `/app/api/v2/mobile/call-settings[/forwarding]` for the active line. The
      enable control and API client both reject a missing or invalid destination;
      live CRM-to-PBX propagation still needs a credentialed test.
- [x] SIP suggestions and contact-detail address rows display only the friendly
      username/number while retaining the complete SIP address for call and chat
      actions, matching the iOS presentation/routing separation.
- [x] Hamburger account rows show the formatted PBX company directly beneath
      the DID/extension label. Android derives it from the saved session and SIP
      tenant with the same normalization and fallback order as iOS.
- [ ] Physical-device calls pass two-way audio, Bluetooth/wired-headset routing,
      DTMF, hold/resume, transfer, inbound wake, and outbound >30-second tests.

## CRM

Reference: `Mango9CRMFragment.swift` and `Mango9DynamicCRM.swift`.

- [x] CRM is a first-class main-footer destination between Contacts and Calls,
      matching the iOS navigation order; the duplicate drawer entry is removed.
- [~] CRM navigation is available only for a valid Mango9 session and retains
      active-account isolation.
- [~] Dashboard metrics and deep-linked lead navigation are implemented.
- [~] Leads: schema-driven list, search, status/group/date filters, pagination,
      create, detail, edit, delete, and communication actions.
- [~] Clients: schema-driven list, search, status/group/date filters, pagination,
      create, detail, edit, delete, and communication actions.
- [x] CRM dashboard, context, and record requests use generation/identity guards;
      an older filter or tenant response cannot overwrite the latest selection.
- [~] CRM phone actions offer Mango9 call, Mango9 SMS where entitled, native call,
      native SMS, email, and copy with the same normalization rules as iOS.
- [~] Lead push parsing, stored-account selection, guarded CRM refresh, and
      record deep-linking are implemented with tenant/identity checks. Live FCM
      delivery and a credentialed cross-tenant test remain.

## Team Chat and SMS/MMS

Reference: `Mango9ChatStore` and the Mango9 adapters in conversation views.

- [~] Teammates load from `/app/api/v2/mobile/team-members` and sync into the
      contact/search experience without creating SIP chat rooms.
- [~] `/app/api/v2/mobile/chat/bootstrap` supplies a short-lived credential for
      the CRM JSON-RPC WebSocket.
- [~] Direct and group rooms implement list/history, create, membership changes,
      real-time messages, typing, presence, delivery/read states, reconnect, and
      active-account isolation.
- [~] Team Chat supports text, image, video, PDF/general file, and AAC/M4A voice
      messages using the authenticated presigned-upload flow.
- [~] SMS/MMS is separate from SIP and Team Chat and implements directory/history,
      sender selection, attachments, real-time updates, and delivery state.
- [~] Conversations, navigation, and CRM workspace rows show combined and
      category-specific unread counts consistent with iOS.
- [~] Report, block/unblock, hide/restore message, and delete-conversation safety
      controls match iOS behavior.
- [~] FCM message pushes use the iOS-compatible HTTPS `/push/register` contract,
      register after chat bootstrap/token refresh, unregister before managed
      logout, reject unknown accounts, atomically select the stored account, and
      deep-link to the intended Team Chat, SMS, or lead target. Parser, request,
      intent round-trip, token-store, and isolation behavior pass locally. Firebase
      initializes and issues an FCM registration token in the emulator; live FCM
      delivery still awaits the backend sender integration and a physical-device
      test.

## Contacts, settings, help, and resilience

- [~] Device contacts, Mango9 teammate contacts, history, recordings, voicemail,
      permissions, accessibility, landscape/tablet layouts, and denied-permission
      behavior exist in upstream pieces and require Mango9 integration tests.
- [x] Reachable first-launch/Help routes expose Mango9-supported controls; public
      Linphone account creation/recovery, update, translation, developer, and
      debug routes are absent and protected by the static policy. The wider
      signed-in settings surface still requires a credentialed walkthrough.
- [x] Support, privacy, GPL, exact upstream source, corresponding-source, and
      dependency notices use Mango9/accurate Android links. The exact Linphone
      SDK artifact POM declares GPL version 3.0.
- [ ] Network, token-expiry, SIP outage, CRM outage, WebSocket reconnect, process
      death, upgrade, logout/revocation, and reinstall paths do not cross accounts
      or leave credentials behind.

## Verification gates

- [x] `assembleDebug`, `assembleDebugAndroidTest`, 23 unit tests, strict lint, and
      Mango9 static-policy checks pass in the current workspace.
- [~] Android instrumentation currently covers real-launcher password/email-code
      UI, manifest identity/cleartext/MDM metadata, Help/Licensing, push caller
      parsing/cache isolation/expiry, per-account line identity, and manual SIP
      session isolation plus message-push token persistence/validation (10 tests).
      It still needs verified enrollment, live registration, account switching,
      logout, CRM navigation, Team Chat, and SMS coverage.
- [~] Emulator smoke covers clean install, permissions, cold first launch, both
      login modes, Help/Licensing, and process restart. Credentialed offline/error,
      signed-in navigation, and account switching remain.
- [~] The live public provisioning health endpoint and Android password-login
      request contract were exercised on 2026-08-26. A synthetic invalid account
      returned the expected HTTP 401 `invalid_credentials` response with a server
      request ID, and the installed Android app rendered the matching user error
      without crashing. A successful credentialed enrollment still requires a
      non-production tester account.
- [ ] Physical-device smoke test covers provisioning and the complete calling and
      push matrix named above.
- [ ] A final screen-by-screen comparison is captured for the supported phone and
      tablet layouts, including accessibility font scaling and dark/light modes.
- [~] The debug APK passes credential-pattern, upstream-Firebase, manifest,
      required-asset, backup-exclusion, and ZIP-integrity inspection. The public
      corresponding-source URL is now pinned; release APK/AAB generation still
      requires the private signing configuration.

## External verification/release inputs still required

- Live Mango9 push-backend access using FCM HTTP v1 authorization (prefer a
  workload identity or attached service account over a downloaded long-lived key).
- Release keystore/signing configuration.
- Non-production Mango9 credentials with at least two accounts/tenants and
  Team Chat/SMS/CRM entitlements.
- At least one physical Android device, Bluetooth and wired headsets, and real
  inbound/outbound numbers for audio, DTMF, transfer, push-wake, and network
  transition testing.
