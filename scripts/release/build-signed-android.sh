#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
output_dir=${SIGNED_OUTPUT_DIR:-"$repo_root/build/release"}
temp_parent=${RUNNER_TEMP:-/private/tmp}
temp_dir=$(mktemp -d "$temp_parent/super-alarm-android-signing.XXXXXX")

cleanup() {
  unset SUPER_ALARM_UPLOAD_STORE_FILE
  unset SUPER_ALARM_UPLOAD_STORE_PASSWORD
  unset SUPER_ALARM_UPLOAD_KEY_ALIAS
  unset SUPER_ALARM_UPLOAD_KEY_PASSWORD
  unset SENTRY_AUTH_TOKEN

  if [[ -d "$temp_dir" && ! -L "$temp_dir" && $(basename "$temp_dir") == super-alarm-android-signing.* ]]; then
    find "$temp_dir" -depth -delete
  fi
}

trap cleanup EXIT HUP INT TERM

command -v op >/dev/null
command -v keytool >/dev/null
command -v jarsigner >/dev/null
command -v jq >/dev/null

chmod 700 "$temp_dir"
manifest="$repo_root/release/manifest.json"
signing_item=$(jq -er '.android.signing_item' "$manifest")
sentry_item=$(jq -er '.sentry.credential_item' "$manifest")
signing_ref="op://Mobile App Releases/$signing_item"
sentry_ref="op://Mobile App Releases/$sentry_item"

echo "Recovering Android release credentials from 1Password."
op read --out-file "$temp_dir/upload.keystore" "$signing_ref/keystore" >/dev/null
export SUPER_ALARM_UPLOAD_STORE_FILE="$temp_dir/upload.keystore"
export SUPER_ALARM_UPLOAD_STORE_PASSWORD
SUPER_ALARM_UPLOAD_STORE_PASSWORD=$(op read "$signing_ref/password")
export SUPER_ALARM_UPLOAD_KEY_ALIAS
SUPER_ALARM_UPLOAD_KEY_ALIAS=$(op read "$signing_ref/key_alias")
export SUPER_ALARM_UPLOAD_KEY_PASSWORD
SUPER_ALARM_UPLOAD_KEY_PASSWORD=$(op read "$signing_ref/key_password")
export SENTRY_AUTH_TOKEN
SENTRY_AUTH_TOKEN=$(op read "$sentry_ref/password")

if [[ $(uname -s) == Darwin && -z ${JAVA_HOME:-} ]]; then
  export JAVA_HOME
  if JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null); then
    :
  elif command -v brew >/dev/null && [[ -d $(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home ]]; then
    JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
  else
    echo "Java 17 is required for the Android release build." >&2
    exit 1
  fi
fi

if [[ $(uname -s) == Darwin && -z ${ANDROID_HOME:-} ]]; then
  if [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif command -v brew >/dev/null && [[ -d $(brew --prefix)/share/android-commandlinetools/platforms ]]; then
    export ANDROID_HOME
    ANDROID_HOME=$(brew --prefix)/share/android-commandlinetools
  fi
fi

if [[ -n ${ANDROID_HOME:-} && -z ${ANDROID_SDK_ROOT:-} ]]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

echo "Verifying the Android signing bundle."
expected_keystore_sha256=$(jq -er '.android.keystore_sha256' "$manifest")
expected_certificate_sha256=$(jq -er '.android.certificate_sha256' "$manifest")
actual_keystore_sha256=$(shasum -a 256 "$SUPER_ALARM_UPLOAD_STORE_FILE" | awk '{print $1}')
if ! keytool -list -v \
    -keystore "$SUPER_ALARM_UPLOAD_STORE_FILE" \
    -alias "$SUPER_ALARM_UPLOAD_KEY_ALIAS" \
    -storepass "$SUPER_ALARM_UPLOAD_STORE_PASSWORD" \
    > "$temp_dir/keytool.txt" 2> "$temp_dir/keytool-error.txt"; then
  cat "$temp_dir/keytool-error.txt" >&2
  exit 1
fi
actual_certificate_sha256=$(awk '/SHA256:/{sub(/^[[:space:]]*SHA256: /, ""); print; exit}' "$temp_dir/keytool.txt")

if [[ "$actual_keystore_sha256" != "$expected_keystore_sha256" ]]; then
  echo "The recovered Android keystore checksum does not match the release manifest." >&2
  exit 1
fi

if [[ "$actual_certificate_sha256" != "$expected_certificate_sha256" ]]; then
  echo "The recovered Android signing certificate does not match the release manifest." >&2
  exit 1
fi

echo "Running Android tests and the signed release build."
(
  cd "$repo_root/android"
  ./gradlew --no-daemon testDebugUnitTest bundleRelease
)

built_bundle="$repo_root/android/app/build/outputs/bundle/release/app-release.aab"
if [[ ! -f "$built_bundle" ]]; then
  echo "Gradle completed without producing the expected release bundle." >&2
  exit 1
fi

jarsigner -verify "$built_bundle" >/dev/null
bundle_certificate_sha256=$(
  keytool -printcert -jarfile "$built_bundle" 2>/dev/null |
    awk '/SHA256:/{sub(/^[[:space:]]*SHA256: /, ""); print; exit}'
)
if [[ "$bundle_certificate_sha256" != "$expected_certificate_sha256" ]]; then
  echo "The built Android bundle was signed with an unexpected certificate." >&2
  exit 1
fi
echo "The signed Android bundle passed signature verification."
mkdir -p "$output_dir"
cp "$built_bundle" "$output_dir/super-alarm-android-signed.aab"

printf 'android_bundle=%s\n' "$output_dir/super-alarm-android-signed.aab"
printf 'keystore_sha256=%s\n' "$actual_keystore_sha256"
printf 'certificate_sha256=%s\n' "$bundle_certificate_sha256"
