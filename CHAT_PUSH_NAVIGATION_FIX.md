# Native chat push navigation

## Scope

Based on published Mango9 iOS 6.2.9 (30), commit `660ba34`. This fixes opening an existing team-chat notification. It does not change notification delivery, server routes, the APNs registration payload, SMS sending, or call pushes. The older local checkout's unfinished virtual System Alerts changes are not included.

## Confirmed code defects

- The notification screen switched from a sender-based chat to a room-based chat when the directory arrived. The outgoing view's cancelled task could then close the newly opened room.
- Reconnection cleared the pending conversation, and connection callers could proceed before the initial directory request finished.
- Selecting the already-active account could trigger an unnecessary account reset. For a different account, the push screen could open before that reset completed.
- A missing/failed history load was rendered as a blank/empty conversation without an explicit retry state.

## Fix boundaries

- Resolve a room-bearing push to that exact room once; never create a sender chat as its fallback.
- Use per-screen/per-attempt ownership for open/close and history responses; ignore superseded or wrong-account results.
- Preserve the conversation through a same-account reconnect and reload its history on reconnect/foreground.
- Keep the existing push-token registration, but do not hold chat readiness behind that separate HTTP request.
- Open account-scoped chat pushes after account selection, without resetting the already-active account.
- Keep existing navigation from contacts and the team-chat list, with loading and retry feedback.

## Automated acceptance

`Mango9ChatPushNavigationTests` covers exact room selection, a directory not yet loaded, retry after directory arrival, room-only and contact targets, old-view cleanup, repeat-open ownership, account isolation, and numeric/string APNs room IDs. Run alongside `Mango9MultiAccountTests` and `Mango9PushCallerIdentityTests`.

The test host must be signed, including on the simulator: Linphone requires its App Group entitlement for shared storage. An unsigned test host fails before XCTest starts.

Validation on September 4, 2026: all 33 selected tests passed on iOS 26.5, including eight new navigation tests. The development-signed iPhone build succeeded with version 6.2.9 and build number 31 (`CURRENT_PROJECT_VERSION=31`). Build outputs and test results are on the external Mango9BuildCache volume; no App Store submission is part of this change.

## Physical-device acceptance

On September 4, 2026, the user confirmed that a fresh Team Chat push arrived and opened the conversation correctly on the installed 6.2.9 development build 31. Earlier tests targeted this phone's stale production registration on an inactive account. Opening Team Chat on the intended account refreshed its sandbox registration; the next test passed. This confirms the tested scenario, not every variation below. The App Store package uses version 6.2.10 (31), since 6.2.9 is already released, with the same runtime fix.

Additional scenarios for regression coverage:

1. With Mango9 backgrounded on the correct account, tap a lead chat push. The existing conversation and its history must open without backing out.
2. Repeat from a cold launch and while another conversation is already open.
3. Tap two notifications in quick succession; only the latest target should remain visible.
4. Open a notification for another stored account; confirm the correct account and room, with no previous-account messages.
5. Interrupt networking while loading history, then retry after restoring networking. The screen must show loading/retry feedback, not an empty successful chat.
6. Confirm normal team-chat opening/sending, carrier SMS, and incoming calls still work.

Do not treat APNs gateway acceptance or automated unit tests as confirmation of the physical push-tap flow.
