# Calendar compatibility sources

Mango9 ships **one iOS 15+ binary**. iOS 18+ uses Exyte CalendarView. iOS 15–17
uses `Mango9LegacyCalendar`, with the same authenticated calendar API, account
scope, appointment detail/editor, status, sharing/assignment, and reminders.
The legacy calendar supplies a month grid and day/week timeline. It does not
pretend to run Exyte's iOS 18 scroll APIs on older systems.

## Provenance

- `CalendarView/`: https://github.com/exyte/CalendarView at
  `dd749f29f18366ed6b2631c3f82f72e62b5fadc2`.
- `AnchoredPopup/`: https://github.com/exyte/AnchoredPopup at
  `8051eae56e20567b52b1ee30261d4b292a081da0` (1.2.2).
- Each directory contains only upstream `Sources`, `LICENSE`, and `Package.swift`.
  Both licenses are MIT and are retained verbatim.

## Narrow compatibility changes

Swift Package Manager requires dependency minimum versions to be no higher than
the application's minimum. These local source packages build at iOS 15, with
their declarations explicitly annotated `@available(iOS 18.0, *)`. This lets the
compiler check every call site and emit weak imports for newer Apple symbols.
The app calls Exyte only inside an `if #available(iOS 18, *)` branch. Merely
lowering a package's platform version without these annotations is unsafe.

`scripts/calendar-availability-patch.mjs` emits the mechanical availability
patch against fresh exports of the pinned revisions. Existing pure retroactive
conformances on Foundation/SwiftUI types stay unconditional. No new API is used
in their implementations. CalendarView's AnchoredPopup dependency is local so
both compatibility manifests are included in the corresponding source.

Do not update these packages independently of the annotations and minimum-OS
tests. No third-party package cache is patched. Modern calendar runtime behavior
is unchanged by annotations. Mango9 uses the upstream `monthDayBuilder` hook to
bound compact cell previews instead of the upstream negative-range crash path.

## In-place appointment refresh patch

`CalendarView.updateData()` refreshes the visible month plus its neighbors rather
than an unrelated selected day. `DayInMonthSwitcher` distributes changed event
snapshots into existing `MonthCellModel` instances. This allows Mango9 to use
the library's `idForUpdate` data refresh without replacing the whole calendar
with `.id(revision)`, which discarded month scroll and day zoom state. These two
runtime edits are in the vendored source (not the SPM cache) and are separate
from the mechanical availability script above.

## Day/Week/Month normalization

The September 11 calendar pass adds an upstream-compatible `.week` display mode,
aligned day-column headings, date-count paging, axis-gated swipes, and a larger-text
horizontal week. Mango9 exposes only Day, Week and Month. The old three-day picker
is removed from the app. Timed/month filtering now includes overlapping overnight
events with half-open boundaries. Overnight display segments preserve event IDs.
Layout-cache keys include geometry and event values; wall-clock placement avoids
the post-DST hour shift. Header/grid gutters and short-event clipping are normalized.
Calendar regression coverage is in `LinphoneAppTests/Mango9CalendarTests.swift`.

## Verification requirements

Build without `IPHONEOS_DEPLOYMENT_TARGET` overrides. Check the archived app and
extensions have `MinimumOSVersion = 15.0`, inspect weak linking of newer system
libraries, and run the shared calendar/authentication/message tests on both a
pre-iOS-18 runtime and a current runtime. Check month/day navigation, DST and
month boundaries, selected-account scope, stale-response rejection, and CRUD.
Simulator success does not replace testing on a physical older iPhone.
