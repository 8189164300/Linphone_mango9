#!/usr/bin/env python3
# Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
"""Run isolated macOS checks of the iOS session implementation.

Uses source-extracted production types, a unique Keychain/defaults namespace,
injected HTTP failures, and stubs for app/SIP side effects. It neither installs
the app nor contacts a server. Secure Enclave and physical UI acceptance remain
separate; run Mango9DeviceProofTests on an authorized physical test device.
"""
from pathlib import Path
import json
import platform
import subprocess
import sys
import tempfile

if platform.system() != "Darwin":
    raise SystemExit("These checks require macOS Security and the Xcode Swift compiler.")

root = Path(__file__).resolve().parents[1]
fixtures = root / "scripts/session-lifecycle-fixtures"
with tempfile.TemporaryDirectory(prefix="mango9-session-checks-") as directory:
    output = Path(directory)
    subprocess.run([sys.executable, str(fixtures / "prepare.py"), str(root), directory], check=True)
    subprocess.run([
        "xcrun", "swiftc", "-parse-as-library", str(output / "ReleasedSource.swift"),
        str(fixtures / "SwiftPatchTests.swift"), str(fixtures / "StorageCleanupTests.swift"),
        "-o", str(output / "checks"),
    ], check=True)
    run = subprocess.run([str(output / "checks")], capture_output=True, text=True)
    if run.returncode:
        raise SystemExit(run.stderr)
    results = json.loads(run.stdout)
    if not results or not all(item["passed"] for item in results):
        raise SystemExit("Session lifecycle checks failed")
    print(json.dumps({"checks": results, "source": json.loads((output / "extraction.json").read_text())}, indent=2))
