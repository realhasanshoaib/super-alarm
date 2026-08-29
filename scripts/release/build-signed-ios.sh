#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
output_dir=${SIGNED_OUTPUT_DIR:-"$repo_root/build/release"}
temp_parent=${RUNNER_TEMP:-/private/tmp}
temp_dir=$(mktemp -d "$temp_parent/super-alarm-ios-signing.XXXXXX")
keychain_path="$temp_dir/release.keychain-db"
installed_profile="$HOME/Library/MobileDevice/Provisioning Profiles/bbb4a431-2bf4-4075-802c-b44a37860335.mobileprovision"
profile_was_present=false
original_keychains=()

while IFS= read -r keychain; do
  keychain=${keychain#*\"}
  keychain=${keychain%\"*}
  [[ -n "$keychain" ]] && original_keychains+=("$keychain")
done < <(security list-keychains -d user)

cleanup() {
  unset SENTRY_AUTH_TOKEN

  if ((${#original_keychains[@]})); then
    security list-keychains -d user -s "${original_keychains[@]}" >/dev/null 2>&1 || true
  fi

  security delete-keychain "$keychain_path" >/dev/null 2>&1 || true

  if [[ "$profile_was_present" == false && -f "$installed_profile" && ! -L "$installed_profile" ]]; then
    find "$installed_profile" -delete
  fi

  if [[ -d "$temp_dir" && ! -L "$temp_dir" && $(basename "$temp_dir") == super-alarm-ios-signing.* ]]; then
    find "$temp_dir" -depth -delete
  fi
}

trap cleanup EXIT HUP INT TERM

command -v op >/dev/null
command -v sentry-cli >/dev/null
command -v jq >/dev/null

chmod 700 "$temp_dir"
manifest="$repo_root/release/manifest.json"
p12_item_id=$(jq -er '.apple.p12_item_id' "$manifest")
p12_password_item_id=$(jq -er '.apple.p12_password_item_id' "$manifest")
profile_item_id=$(jq -er '.apple.profile_item_id' "$manifest")
expected_certificate_sha256=$(jq -er '.apple.certificate_sha256' "$manifest")
expected_profile_name=$(jq -er '.apple.profile_name' "$manifest")
expected_profile_uuid=$(jq -er '.apple.profile_uuid' "$manifest")
expected_team_id=$(jq -er '.apple.team_id' "$manifest")
expected_bundle_id=$(jq -er '.apple.bundle_id' "$manifest")
sentry_item=$(jq -er '.sentry.credential_item' "$manifest")
sentry_org=$(jq -er '.sentry.organization' "$manifest")
sentry_project=$(jq -er '.sentry.ios_project' "$manifest")

echo "Recovering Apple release credentials from 1Password."
op document get "$p12_item_id" --vault "Mobile App Releases" --output "$temp_dir/distribution.p12" >/dev/null
op document get "$p12_password_item_id" --vault "Mobile App Releases" --output "$temp_dir/p12.pass" >/dev/null
op document get "$profile_item_id" --vault "Mobile App Releases" --output "$temp_dir/profile.mobileprovision" >/dev/null
chmod 600 "$temp_dir/distribution.p12" "$temp_dir/p12.pass" "$temp_dir/profile.mobileprovision"

openssl pkcs12 -legacy -in "$temp_dir/distribution.p12" -nokeys -passin "file:$temp_dir/p12.pass" 2>/dev/null |
  openssl x509 -outform der -out "$temp_dir/distribution.cer"
certificate_sha256=$(openssl x509 -inform der -in "$temp_dir/distribution.cer" -noout -fingerprint -sha256 | cut -d= -f2)

security cms -D -i "$temp_dir/profile.mobileprovision" > "$temp_dir/profile.plist"
profile_name=$(plutil -extract Name raw -o - "$temp_dir/profile.plist")
profile_uuid=$(plutil -extract UUID raw -o - "$temp_dir/profile.plist")
profile_app_id=$(plutil -extract Entitlements.application-identifier raw -o - "$temp_dir/profile.plist")
plutil -extract DeveloperCertificates.0 raw -o - "$temp_dir/profile.plist" | base64 --decode > "$temp_dir/profile.cer"
profile_certificate_sha256=$(openssl x509 -inform der -in "$temp_dir/profile.cer" -noout -fingerprint -sha256 | cut -d= -f2)

echo "Verifying the Apple signing identity and provisioning profile."
if [[ "$profile_name" != "$expected_profile_name" || "$profile_uuid" != "$expected_profile_uuid" || "$profile_app_id" != "$expected_team_id.$expected_bundle_id" ]]; then
  echo "The recovered provisioning profile does not belong to Super Alarm." >&2
  exit 1
fi

if [[ "$certificate_sha256" != "$expected_certificate_sha256" || "$profile_certificate_sha256" != "$expected_certificate_sha256" ]]; then
  echo "The recovered Apple identity does not match the provisioning profile." >&2
  exit 1
fi

keychain_password=$(openssl rand -hex 24)
p12_password=$(<"$temp_dir/p12.pass")
security create-keychain -p "$keychain_password" "$keychain_path"
security set-keychain-settings -lut 21600 "$keychain_path"
security unlock-keychain -p "$keychain_password" "$keychain_path"
security import "$temp_dir/distribution.p12" -k "$keychain_path" -P "$p12_password" -T /usr/bin/codesign -T /usr/bin/security >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" "$keychain_path" >/dev/null
security list-keychains -d user -s "$keychain_path" "${original_keychains[@]}"

mkdir -p "$(dirname "$installed_profile")"
if [[ -f "$installed_profile" ]]; then
  profile_was_present=true
else
  cp "$temp_dir/profile.mobileprovision" "$installed_profile"
fi

archive_path="$temp_dir/SuperAlarm.xcarchive"
export_path="$temp_dir/export"
echo "Building the signed iOS archive."
if ! xcodebuild \
  -workspace "$repo_root/ios/SuperAlarm.xcworkspace" \
  -scheme SuperAlarm \
  -configuration Release \
  -destination generic/platform=iOS \
  -archivePath "$archive_path" \
  archive > "$temp_dir/archive.log" 2>&1; then
  rg -n 'error:|ARCHIVE FAILED' "$temp_dir/archive.log" | tail -80 >&2 || true
  tail -120 "$temp_dir/archive.log" >&2
  exit 1
fi
echo "The signed iOS archive completed."

cat > "$temp_dir/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>destination</key>
  <string>export</string>
  <key>manageAppVersionAndBuildNumber</key>
  <false/>
  <key>method</key>
  <string>app-store-connect</string>
  <key>signingStyle</key>
  <string>manual</string>
  <key>stripSwiftSymbols</key>
  <true/>
  <key>teamID</key>
  <string>$expected_team_id</string>
  <key>uploadSymbols</key>
  <false/>
  <key>provisioningProfiles</key>
  <dict>
    <key>$expected_bundle_id</key>
    <string>$expected_profile_name</string>
  </dict>
</dict>
</plist>
PLIST

if ! xcodebuild -exportArchive \
  -archivePath "$archive_path" \
  -exportPath "$export_path" \
  -exportOptionsPlist "$temp_dir/ExportOptions.plist" > "$temp_dir/export.log" 2>&1; then
  rg -n 'error:|EXPORT FAILED' "$temp_dir/export.log" | tail -80 >&2 || true
  tail -120 "$temp_dir/export.log" >&2
  exit 1
fi
echo "The App Store IPA export completed."

built_ipa=$(find "$export_path" -maxdepth 1 -type f -name '*.ipa' -print -quit)
if [[ -z "$built_ipa" ]]; then
  echo "Xcode completed without producing an IPA." >&2
  exit 1
fi

mkdir -p "$temp_dir/extracted"
unzip -q "$built_ipa" -d "$temp_dir/extracted"
signed_app=$(find "$temp_dir/extracted/Payload" -maxdepth 1 -type d -name '*.app' -print -quit)
codesign --verify --deep --strict "$signed_app"
codesign -d --extract-certificates="$temp_dir/signed-cert-" "$signed_app" 2>/dev/null
signed_certificate_sha256=$(openssl x509 -inform der -in "$temp_dir/signed-cert-0" -noout -fingerprint -sha256 | cut -d= -f2)
if [[ "$signed_certificate_sha256" != "$expected_certificate_sha256" ]]; then
  echo "The built iOS app was signed with an unexpected certificate." >&2
  exit 1
fi
echo "The signed iOS app passed code-signature verification."

mkdir -p "$output_dir"
cp "$built_ipa" "$output_dir/super-alarm-ios-signed.ipa"
ditto -c -k --keepParent "$archive_path/dSYMs" "$output_dir/super-alarm-ios-dsyms.zip"

export SENTRY_AUTH_TOKEN
SENTRY_AUTH_TOKEN=$(op read "op://Mobile App Releases/$sentry_item/password")
echo "Uploading iOS debug symbols to Sentry."
sentry-cli debug-files upload --org "$sentry_org" --project "$sentry_project" "$archive_path/dSYMs"

printf 'ios_ipa=%s\n' "$output_dir/super-alarm-ios-signed.ipa"
printf 'ios_dsyms=%s\n' "$output_dir/super-alarm-ios-dsyms.zip"
printf 'certificate_sha256=%s\n' "$signed_certificate_sha256"
