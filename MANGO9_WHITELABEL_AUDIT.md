# Mango9 iOS White-Label Audit

**Status:** Development provisioning and CRM internal chat path implemented
**Date:** July 26, 2026

## Locked Client Behavior

- The product name is **Mango 9**.
- The client uses the approved Mango9 white SVG logo and Mango9 blue branding.
- The onboarding screen offers Mango9 account login, QR enrollment, and manual SIP setup.
- The shared login/QR screen has no domain or server field.
- A gear action in the login header opens the retained manual SIP configuration screen.
- Mango9 unread chat totals appear in the Linphone Conversations list, the Conversations tab badge, and the CRM Team Chat workspace row. CRM workspace modules do not use decorative `LIVE` labels.
- Provisioning URLs must use HTTPS on `provision.mango9.com`.
- Every SIP account is routed through `sips:proxy.mango9.com:5061;transport=tls`.
- CRM enrollment and FusionPBX SIP authentication remain separate trust boundaries.

CRM login now resolves the development CRM through the Mango9 provisioning control plane, returns a one-time Linphone enrollment URL, stores the CRM session in the iOS Keychain, and registers the assigned SIP extension. Manual SIP setup can still register independently, but it does not receive CRM, SMS, or internal-chat access without Mango9 mobile authentication.

## Removed from the Runtime

- Firebase Analytics and Crashlytics.
- Firebase/Google application configuration plists and the Crashlytics build hook.
- Linphone account creation and public-provider defaults.
- Linphone-hosted STUN, conference, LIME, file transfer, log upload, and version checking.
- Meeting controls while no Mango9 conference backend is configured.
- Third-party-provider selection and arbitrary outside SIP proxy selection.
- User-facing arbitrary remote provisioning.
- Linphone update, translation, developer, and debug navigation.

## Intentionally Retained

- The Linphone SDK and SIP/media stack.
- AppAuth for a future Mango9-controlled OAuth/OIDC exchange.
- The emoji picker.
- Apple PushKit and CallKit integration points, pending final Apple configuration.
- Linphone copyright, GPL notices, and open-source attributions.
- Unreachable legacy assistant source files, temporarily, to reduce rebase risk during the first provisioning pilot.

## Locked Network Design

The application has three intentionally separate service lanes:

1. **Calls:** the CRM supplies the assigned extension and PBX domain; Linphone registers through `proxy.mango9.com` using SIP/TLS. Flexisip and FusionPBX handle call signaling. SIP chat is disabled for Mango9 teammates.
2. **CRM internal chat:** the app exchanges its authenticated CRM bearer token for a 15-minute chat credential at `GET /app/api/v2/mobile/chat/bootstrap`. It then uses JSON-RPC over `wss://<crm>/connect` for users, direct and group rooms, history, presence, typing, delivery/read status, real-time messages, and group membership. Attachments use the chat server's existing authenticated presigned-upload endpoint. Image, video, general file, and AAC/M4A voice messages are carried in the chat message's media array.
3. **External SMS/MMS:** the app uses the existing CRM SMS/chat service and Telnyx-backed routes. SMS conversations remain separate from CRM teammate chat and do not traverse SIP.

The native Linphone conversation presentation is retained, but Mango9 teammate actions are intercepted before Linphone creates a SIP `ChatRoom`. A Mango9 adapter supplies the same chat-style interface from the existing CRM WebSocket data model. Bubbles use Apple's adaptive system-blue message color with white message/media text. The teammate SIP URI remains available only for calls.

```text
Mango9 iOS
  ├─ CRM login / provisioning ─> provision.mango9.com ─> selected CRM
  ├─ Calls ────────────────────> proxy.mango9.com ─────> assigned FusionPBX
  ├─ CRM teammate chat ────────> selected CRM /connect > existing chat server
  └─ SMS/MMS ──────────────────> selected CRM SMS API ─> Telnyx
```

Each CRM can register with the provisioning control plane. The control plane locates the CRM that owns the username, delegates credential validation to that CRM, and returns that CRM's API, chat, SMS, and PBX assignment. It does not centralize tenant data or PBX passwords.

## Current Development Contract

The client expects:

1. `POST https://provision.mango9.com/v1/mobile/login` for CRM discovery, delegated credential validation, tokens, and a one-time enrollment URL.
2. A one-time QR URL under `https://provision.mango9.com/`.
3. Server-side validation of the CRM login or enrollment token.
4. A CRM lookup of tenant, user, permissions, and FusionPBX assignment.
5. A Linphone provisioning response containing SIP identity and credentials.
6. The Mango9 proxy route, never a client-selected outside SIP provider.
7. Mobile API tokens issued separately from SIP credentials.
8. `GET /app/api/v2/mobile/team-members` for the CRM teammate/contact directory.
9. `GET /app/api/v2/mobile/chat/bootstrap` for a short-lived internal-chat WebSocket credential.
10. Existing JSON-RPC chat methods for directory, direct/group rooms, group membership, presence, history, send, typing, and message status.
11. `POST /sms_chat_api/upload` with the short-lived chat credential to obtain a presigned object-store PUT URL and media URL.

The media URL carries `ffName`, `ffSize`, `ffType`, and `ffExt` metadata after the signed query. The client preserves those fields in chat history but removes the `ff*` suffix before downloading the signed object. This is required for the object-store signature to remain valid.

The client must never receive CRM database credentials, Telnyx credentials, Firebase service-account credentials, or Apple server credentials.

The development CRM at `2.mango9.com` currently implements this mobile contract. The chat server itself was not rewritten; the mobile API only adds the authenticated bootstrap boundary needed by iOS.

## Deferred Until Skin Approval

- Final Apple bundle identifier and App Groups.
- Apple Team ID and distribution profiles.
- APNs/PushKit credentials in Flexisip.
- Physical-device incoming-call push tests.
- App Store signing and submission.
- AI-assistant selection and routing are excluded from the initial App Store release. The CRM/server capability remains available for a later, separately reviewed opt-in app update.

The current development bundle identifier, `com.mango9.phone`, is provisional.

Media testing must determine whether Mango9 needs its own STUN/TURN service. The client must not fall back to a public Linphone-hosted NAT traversal service.

## Validation

- `xcodebuild` Debug simulator build: passed.
- Main bundle name: Mango 9.
- Development bundle identifier: `com.mango9.phone`.
- Firebase packages: absent from Swift package resolution.
- Google service plist files: removed.
- `GET /app/api/v2/mobile/chat/bootstrap`: HTTP 200 with a short-lived chat credential; `sip_chat=false` and `sms_separate=true`.
- Apple Foundation WebSocket handshake: passed against `wss://2.mango9.com/connect`.
- Two-user development chat: direct room, real-time receipt, stored history, presence, and read status all passed.
- Three-user development group chat: creation, online presence, typing, delivery to two recipients, stored history, and read status passed. The test room was archived afterward.
- Attachment round trip: PNG image, AAC/M4A voice recording (`audio/mp4`), and playable MP4 video all passed authenticated prepare, presigned upload, signed download, real-time group delivery, and history persistence.
- iOS composer: photo/video picker, general file picker, AAC/M4A voice recorder, local attachment preview/removal, upload progress, received image/video/audio/file presentation, and group-member management are implemented.
- No SMS-server or SIP-server code was changed for the internal-chat test.

## Release Gate

Do not call the iOS replacement complete until account login, QR enrollment, manual SIP setup, SIP registration through Flexisip, an incoming call on a locked physical device, SMS/MMS synchronization, foreground and background buddy chat, logout/revocation, and rollback have all passed against the development environment.

Foreground CRM buddy chat is now functional. Background buddy-chat alerts, new-lead alerts, and incoming-call wakeup still require the final Apple APNs/PushKit credentials and a physical-device test; simulator success does not close that release gate.
