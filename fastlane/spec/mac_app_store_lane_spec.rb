#!/usr/bin/env ruby
# frozen_string_literal: true

# fastlane/spec/mac_app_store_lane_spec.rb
#
# RED-phase spec for the fastlane `mac_app_store` lane (FP-14b, Issue #55).
# Will FAIL until FP-14e adds the lane to fastlane/Fastfile.
#
# Run: `ruby fastlane/spec/mac_app_store_lane_spec.rb`
# Exit code 0 = green, non-zero = red.
#
# Plain Ruby (no rspec gem) — the existing iOS lane has no Ruby test deps.
#
# Locked surface per
# docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md §6.

require 'pathname'

REPO_ROOT = Pathname.new(__dir__).parent.parent.expand_path
FASTFILE = REPO_ROOT.join('fastlane', 'Fastfile')

def assert(condition, message)
  return if condition

  warn "FAIL: #{message}"
  exit 1
end

assert FASTFILE.file?, "Fastfile not found at #{FASTFILE}"

content = FASTFILE.read

# 1. Mac platform block exists.
assert content.include?('platform :mac do'),
       "Fastfile is missing `platform :mac do` block"

# 2. mac_app_store lane is defined.
assert content =~ /lane\s+:mac_app_store\s+do/,
       "Fastfile is missing `lane :mac_app_store do` definition"

# 3. Lane invokes the Gradle Mac App Store package task.
assert content.include?(':desktop:app:packageMacAppStore'),
       "mac_app_store lane must invoke `:desktop:app:packageMacAppStore` Gradle task"

# 4. Lane invokes the GPL verification task.
assert content.include?(':desktop:app:verifyMacAppStoreNoGplDeps'),
       "mac_app_store lane must invoke `:desktop:app:verifyMacAppStoreNoGplDeps`"

# 5. Lane uses App Store Connect API key (same identifier as iOS lane).
assert content.include?('app_store_connect_api_key'),
       "mac_app_store lane must call `app_store_connect_api_key` for non-interactive auth"

# 6. Lane uploads with platform: "osx".
assert content =~ /platform:\s*["']osx["']/,
       'mac_app_store lane must call `upload_to_app_store` with platform: "osx"'

# 7. Lane references the .pkg under build/compose/binaries/main/macAppStorePkg.
assert content.include?('macAppStorePkg'),
       "mac_app_store lane must reference the macAppStorePkg output directory"

puts "OK: mac_app_store lane spec passed (#{FASTFILE.relative_path_from(REPO_ROOT)})"
