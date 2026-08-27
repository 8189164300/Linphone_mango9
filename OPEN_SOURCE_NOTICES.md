# Mango9 Android Open-Source Notices

This file identifies the principal open-source components used by Mango9
Android 6.2.6 (version code 602014). Copyright notices in the source remain with
their respective owners.

## Mango9 application and Linphone Android

- Mango9 modifications: GNU General Public License version 3 or later.
- Upstream application: Linphone Android, originally published by Belledonne
  Communications SARL.
- Upstream release: [`6.2.6`](https://github.com/BelledonneCommunications/linphone-android/tree/6.2.6).
- Upstream commit: [`42b1fcce3c8037e6f5a891cf8d108eb47e308386`](https://github.com/BelledonneCommunications/linphone-android/commit/42b1fcce3c8037e6f5a891cf8d108eb47e308386).
- Mango9 corresponding source: [`android-6.2.6-build-602014`](https://github.com/8189164300/Linphone_mango9/tree/android-6.2.6-build-602014).
- License text: [`LICENSE.txt`](LICENSE.txt).

Mango9 modified the upstream application in 2026. Mango9 is not affiliated with
or endorsed by Belledonne Communications SARL.

## Linphone SDK Android

- Maven coordinate: `org.linphone:linphone-sdk-android:5.5.17-pre.1+3896ec0681`.
- The published POM for this exact artifact declares GNU General Public License
  version 3.0.
- Exact native SDK source commit: [`3896ec0681`](https://github.com/BelledonneCommunications/linphone-sdk/commit/3896ec0681).
- Native SDK and submodule build manifests are available from the source tree at
  that commit.
- Third-party component inventory: [Linphone third-party components](https://wiki.linphone.org/xwiki/wiki/public/view/Linphone/Third%20party%20components/).

The exact artifact version is pinned in `gradle/libs.versions.toml`. The Gradle
build can use the published AAR or a locally built SDK supplied through
`LinphoneSdkBuildDir`.

## Direct Android application dependencies

Exact versions and coordinates are pinned in `gradle/libs.versions.toml`.

- AndroidX libraries: Apache License 2.0.
- Kotlin and Kotlin Gradle tooling: Apache License 2.0.
- Google Material Components and Flexbox Layout: Apache License 2.0.
- Firebase Android SDK/Cloud Messaging: Apache License 2.0.
- Coil: Apache License 2.0.
- OkHttp and MockWebServer: Apache License 2.0.
- AppAuth for Android: Apache License 2.0.
- DotsIndicator: Apache License 2.0.
- PhotoView: Apache License 2.0.
- Protocol Buffers Java Lite: BSD 3-Clause License.
- JUnit 4 test dependency: Eclipse Public License 1.0.
- JSON-java test dependency: public-domain dedication.

Transitive dependencies retain their own copyright and license notices. The
application's direct dependency versions are defined by the checked-in Gradle
version catalog, and Gradle resolves their transitive dependency metadata.

## Firebase configuration

`app/mango9-google-services.json` is Android client configuration. Firebase API
keys identify a Firebase project/app and are embedded in Android applications;
the file does not contain a Firebase Admin private key, service-account private
key, or FCM server credential. Server credentials are intentionally excluded
from this repository.

## No warranty

The covered software is distributed in the hope that it will be useful, but
without any warranty; without even the implied warranty of merchantability or
fitness for a particular purpose. Refer to the applicable license text for the
complete terms.
