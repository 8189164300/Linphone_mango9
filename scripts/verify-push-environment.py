#!/usr/bin/env python3
"""Reject an iOS bundle whose push routing disagrees with its final signature."""
import argparse
import plistlib
import subprocess
import sys
from pathlib import Path


def verify_values(info, entitlements, expected=None):
    configured = info.get("Mango9APSEnvironment")
    signed = entitlements.get("aps-environment")
    allowed = {"development", "production"}
    if configured not in allowed or signed not in allowed:
        raise ValueError("Missing or invalid push environment in Info.plist or signature")
    if configured != signed:
        raise ValueError(
            f"Push environment mismatch: app={configured}, signature={signed}. "
            "Rebuild with MANGO9_APS_ENVIRONMENT matching the signing profile."
        )
    if expected is not None and signed != expected:
        raise ValueError(f"Expected {expected} signing; bundle uses {signed}")
    return signed


def verify_bundle(app, expected=None):
    subprocess.run(
        ["/usr/bin/codesign", "--verify", "--strict", str(app)],
        check=True, capture_output=True,
    )
    signature = subprocess.run(
        ["/usr/bin/codesign", "-d", "--entitlements", ":-", str(app)],
        check=True, capture_output=True,
    )
    info = plistlib.loads((app / "Info.plist").read_bytes())
    entitlements = plistlib.loads(signature.stdout)
    return verify_values(info, entitlements, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--expect", choices=["development", "production"])
    args = parser.parse_args()
    try:
        environment = verify_bundle(args.app, args.expect)
    except (ValueError, OSError, subprocess.CalledProcessError, plistlib.InvalidFileException) as error:
        # Never dump the complete signature or provisioning profile.
        print(f"FAIL push environment: {error}", file=sys.stderr)
        return 1
    print(f"PASS push environment: app and signature both use {environment}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
