#!/bin/sh

set -eu

app_path="${1:-}"

if [ -z "${app_path}" ] || [ ! -d "${app_path}" ]; then
	echo "Usage: $0 /path/to/Mango9.app"
	exit 64
fi

failure=0

check_manifest() {
	manifest_path="$1"
	description="$2"

	if [ ! -f "${manifest_path}" ]; then
		echo "FAIL ${description}: missing ${manifest_path}"
		failure=1
		return
	fi

	if /usr/bin/plutil -lint "${manifest_path}" >/dev/null; then
		echo "PASS ${description}"
	else
		echo "FAIL ${description}: invalid property list"
		failure=1
	fi
}

check_manifest "${app_path}/PrivacyInfo.xcprivacy" "app privacy manifest"

notification_extension="${app_path}/PlugIns/msgNotificationService.appex"
if [ -d "${notification_extension}" ]; then
	check_manifest \
		"${notification_extension}/PrivacyInfo.xcprivacy" \
		"notification-service privacy manifest"
fi

for framework_path in "${app_path}"/Frameworks/*.framework; do
	[ -d "${framework_path}" ] || continue

	framework_name="$(basename "${framework_path}" .framework)"
	framework_binary="${framework_path}/${framework_name}"
	[ -f "${framework_binary}" ] || continue

	if /usr/bin/nm -u "${framework_binary}" 2>/dev/null |
		/usr/bin/grep -Eq '(_fstat|_stat|_statfs)(\$INODE64)?$|(_getattrlist|_getattrlistbulk|_fgetattrlist|_mach_absolute_time)$|NSUserDefaults|systemUptime|volumeAvailableCapacityKey|creationDate'; then
		check_manifest \
			"${framework_path}/PrivacyInfo.xcprivacy" \
			"${framework_name}.framework required-reason manifest"
	fi
done

version="$(/usr/bin/plutil -extract CFBundleShortVersionString raw "${app_path}/Info.plist")"
build="$(/usr/bin/plutil -extract CFBundleVersion raw "${app_path}/Info.plist")"
echo "Bundle: Mango9 ${version} (${build})"

if [ "${failure}" -ne 0 ]; then
	echo "App Store bundle verification failed."
	exit 1
fi

echo "App Store bundle verification passed."
