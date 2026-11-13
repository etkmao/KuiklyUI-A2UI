#!/usr/bin/env bash
# One-shot script to generate the Xcode project for A2UICardShowcaseApp.
#
# This app is an INDEPENDENT iOS demo. It does NOT depend on the A2UI /
# KuiklyUI libraries. All UI is built with vanilla UIKit and uses mock data
# only, so you can simply run `./setup.sh` and open the generated .xcodeproj.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "[setup] 'xcodegen' not found. Install via:"
  echo "         brew install xcodegen"
  echo "       or:"
  echo "         mint install yonaskolb/XcodeGen"
  exit 1
fi

echo "[setup] Generating Xcode project via XcodeGen..."
xcodegen generate

echo ""
echo "[setup] Done. Open the project with:"
echo "    open A2UICardShowcaseApp.xcodeproj"
