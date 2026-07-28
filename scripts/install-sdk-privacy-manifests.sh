#!/bin/sh

set -eu

frameworks_directory="${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}"
manifest_directory="${SRCROOT}/PrivacyManifests/LinphoneSDK"
timestamp_manifest="${manifest_directory}/FileTimestamp.xcprivacy"
timestamp_disk_manifest="${manifest_directory}/FileTimestampAndDiskSpace.xcprivacy"

if [ ! -d "${frameworks_directory}" ]; then
	echo "Mango9 privacy audit: no embedded frameworks directory at ${frameworks_directory}"
	exit 0
fi

install_manifest() {
	framework_name="$1"
	manifest_path="$2"
	framework_path="${frameworks_directory}/${framework_name}.framework"

	if [ ! -d "${framework_path}" ]; then
		echo "Mango9 privacy audit: ${framework_name}.framework is not embedded"
		return
	fi

	/usr/bin/plutil -lint "${manifest_path}" >/dev/null
	/bin/cp "${manifest_path}" "${framework_path}/PrivacyInfo.xcprivacy"
	echo "Mango9 privacy audit: installed manifest in ${framework_name}.framework"
}

install_manifest "bctoolbox" "${timestamp_manifest}"
install_manifest "belle-sip" "${timestamp_manifest}"
install_manifest "linphone" "${timestamp_manifest}"
install_manifest "mbedx509" "${timestamp_manifest}"
install_manifest "mediastreamer2" "${timestamp_disk_manifest}"

if [ "${CODE_SIGNING_ALLOWED:-NO}" = "YES" ] && [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]; then
	for framework_name in bctoolbox belle-sip linphone mbedx509 mediastreamer2; do
		framework_path="${frameworks_directory}/${framework_name}.framework"
		[ -d "${framework_path}" ] || continue
		/usr/bin/codesign \
			--force \
			--sign "${EXPANDED_CODE_SIGN_IDENTITY}" \
			--preserve-metadata=identifier,entitlements,flags \
			--timestamp=none \
			--generate-entitlement-der \
			"${framework_path}"
		echo "Mango9 privacy audit: re-signed ${framework_name}.framework"
	done
fi
