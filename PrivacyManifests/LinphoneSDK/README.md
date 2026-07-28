# Linphone SDK privacy manifests

The Linphone SDK 5.5.5 binary frameworks import Apple required-reason APIs but do
not currently place privacy manifests in the embedded framework bundles.

`scripts/install-sdk-privacy-manifests.sh` installs these declarations into the
copied framework bundles before Xcode signs the application:

- `bctoolbox`, `belle-sip`, `linphone`, and `mbedx509` access file metadata for
  files used by the communication stack inside the app container (`C617.1`).
- `mediastreamer2` accesses file metadata and checks available space before
  writing media or recordings (`C617.1` and `E174.1`).

Re-audit the final framework binaries whenever the Linphone SDK version changes.
Do not carry these declarations to a different SDK binary without verifying its
actual imported APIs and behavior.
