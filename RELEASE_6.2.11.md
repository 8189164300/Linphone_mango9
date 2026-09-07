# Mango9 iOS 6.2.11 (32)

Base: published iOS 6.2.10 (31), commit `229cb20d2`.

This release changes Team Chat ordering only: directory loads, live group updates,
and message updates publish sorted room snapshots. People with conversations sort
by recent activity ahead of directory-only contacts. Older delayed messages cannot
move an existing room backward or replace its latest preview.

Calling, account provisioning, SMS transport, push delivery and media encoding are
unchanged from the published version. Android parity work is on the separate
`codex/android-ios-parity-20260906` branch and is not part of this Apple binary.

Release metadata is in `AppStoreMetadata/en-US`. The corresponding source tag is
`ios-6.2.11-build-32`. App Store release is automatic after Apple's approval.

Validation: 35 selected Xcode tests passed with zero failures on iOS 26.5
Simulator. Suites: `Mango9ChatPushNavigationTests`, `Mango9MultiAccountTests`,
`Mango9PushCallerIdentityTests`. Result bundle: `Mango9-6211-tests.xcresult`
(kept outside Git). This validates source-level behavior, not a new live
carrier/push end-to-end test. Archive/signing validation is performed separately.
