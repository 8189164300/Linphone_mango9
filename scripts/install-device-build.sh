#!/bin/sh
# Verify the signed artifact before replacing an app on a physical device.
set -eu
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 /path/to/LinphoneApp.app device-identifier" >&2
    exit 64
fi
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
python3 "$script_dir/verify-push-environment.py" "$1"
exec xcrun devicectl device install app --device "$2" "$1"
