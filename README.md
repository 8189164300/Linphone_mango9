# Mango9 for Android

Mango9 for Android is a modified version of the Linphone Android application.
It provides Mango9 account provisioning, SIP calling through the Mango9 proxy,
CRM access, contacts, Team Chat, SMS/MMS, and Firebase Cloud Messaging support.

This repository is the public corresponding-source location displayed by the
Mango9 app. The immutable release tag
[`android-6.2.6-build-602009`](https://github.com/8189164300/Linphone_mango9/tree/android-6.2.6-build-602009)
corresponds to Mango9 Android version 6.2.6, version code 602009.

Mango9 modified the upstream Linphone Android application in 2026. Mango9 is
not affiliated with or endorsed by Belledonne Communications SARL.

## Repository layout

The public `8189164300/Linphone_mango9` repository contains both mobile
platforms without rewriting either platform's history:

- `main` and `ios`: Mango9 iOS source; App Store releases use immutable
  `ios-<version>-build-<number>` tags.
- `android`: Mango9 Android source; Android releases use immutable
  `android-<version>-build-<versionCode>` tags.

Always use the tag matching the installed binary when obtaining corresponding
source. A moving branch may contain changes for a later build.

## License

The Mango9 application modifications are distributed under the GNU General
Public License, version 3 or (at your option) any later version. See
[`LICENSE.txt`](LICENSE.txt).

Linphone Android is originally published by Belledonne Communications SARL.
Original copyright and license notices remain in the source files. The names
“Linphone” and “Belledonne Communications” are used only for attribution and to
identify the upstream project.

This program is provided without warranty; without even the implied warranty
of merchantability or fitness for a particular purpose.

See [`OPEN_SOURCE_NOTICES.md`](OPEN_SOURCE_NOTICES.md) for the exact upstream
application revision, Linphone SDK artifact/source revision, and direct
dependency license information.

## Source relationship

- Mango9 release source:
  [`android-6.2.6-build-602009`](https://github.com/8189164300/Linphone_mango9/tree/android-6.2.6-build-602009)
- Upstream Linphone Android tag: [`6.2.6`](https://github.com/BelledonneCommunications/linphone-android/tree/6.2.6)
- Upstream application commit:
  [`42b1fcce3c8037e6f5a891cf8d108eb47e308386`](https://github.com/BelledonneCommunications/linphone-android/commit/42b1fcce3c8037e6f5a891cf8d108eb47e308386)
- Linphone SDK Android artifact: `5.5.17-pre.1+3896ec0681`
- Linphone SDK source commit:
  [`3896ec0681`](https://github.com/BelledonneCommunications/linphone-sdk/commit/3896ec0681)

This branch contains the complete modified application source and build scripts,
not only a patch against upstream.

## Building

Requirements:

- Android Studio with Android SDK 37
- Java 21
- Android 9/API 28 or newer for the target device

Open the project in Android Studio, or build from the repository root:

```sh
./gradlew verifyMango9StaticPolicy testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

The build downloads the pinned Linphone SDK Android AAR from Linphone's public
Maven repository. To build the SDK locally, clone its exact source revision with
submodules and follow its Android build instructions, then set
`LinphoneSdkBuildDir` in your Gradle user properties to the resulting SDK build
directory.

Release signing requires your own Android keystore. No signing key, SIP secret,
CRM credential, Firebase service-account key, or FCM server credential is
included in this public repository. `app/mango9-google-services.json` is the
Firebase Android client configuration embedded in the application; it is not a
server credential.

## Verification

The source branch includes JVM tests, Android instrumentation tests, strict
Android lint, and a Mango9 static-policy gate. The detailed implementation and
verification record is in [`MANGO9_ANDROID_PARITY.md`](MANGO9_ANDROID_PARITY.md).

The public-source tag makes the source available; it does not include Mango9
production credentials and does not grant access to Mango9 services.
