#!/usr/bin/env bash
# .github/workflows-test/mac_app_store_workflow_check.sh
#
# RED-phase content check for the Mac App Store CI workflow (FP-14b, Issue #55).
# Will FAIL until FP-14e adds `.github/workflows/mac-app-store.yml`.
#
# Run: `bash .github/workflows-test/mac_app_store_workflow_check.sh`
# Exit code 0 = green, non-zero = red.
#
# Locked surface per
# docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md §7.

set -u

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
workflow="$repo_root/.github/workflows/mac-app-store.yml"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[[ -f "$workflow" ]] || fail "Workflow file not found: $workflow"

grep -qE '^on:' "$workflow"               || fail "Workflow missing top-level 'on:' trigger block"
grep -q  'workflow_dispatch:' "$workflow" || fail "Workflow must declare workflow_dispatch trigger"
grep -qE 'runs-on:\s*macos-15' "$workflow" || fail "Workflow job must use 'runs-on: macos-15'"
grep -q  'MAC_APP_DIST_P12_BASE64' "$workflow" || fail "Workflow must reference MAC_APP_DIST_P12_BASE64 secret"
grep -q  'MAC_INSTALLER_DIST_P12_BASE64' "$workflow" || fail "Workflow must reference MAC_INSTALLER_DIST_P12_BASE64 secret"
grep -q  'MAC_PROVISIONING_PROFILE_BASE64' "$workflow" || fail "Workflow must reference MAC_PROVISIONING_PROFILE_BASE64 secret"
grep -q  'fastlane mac mac_app_store' "$workflow" || fail "Workflow must invoke 'fastlane mac mac_app_store'"
grep -qE "distribution:\s*temurin" "$workflow" || fail "Workflow must set up JDK with distribution: temurin"
grep -qE "java-version:\s*['\"]?21" "$workflow" || fail "Workflow must set up Java 21"

echo "OK: mac-app-store.yml workflow content check passed"
