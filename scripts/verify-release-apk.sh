#!/bin/sh
set -eu

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 /absolute/path/NasFinder-Android-vVERSION_CODE.apk" >&2
  exit 64
fi

apk_path=$1
apk_name=${apk_path##*/}

case "$apk_name" in
  NasFinder-Android-v*.apk) ;;
  *) echo "unexpected release filename: $apk_name" >&2; exit 65 ;;
esac

if ! command -v apksigner >/dev/null 2>&1; then
  echo "apksigner was not found in PATH" >&2
  exit 69
fi
if ! command -v aapt >/dev/null 2>&1; then
  echo "aapt was not found in PATH" >&2
  exit 69
fi

badging=$(aapt dump badging "$apk_path")
package_line=$(printf '%s\n' "$badging" | sed -n '1p')
case "$package_line" in
  "package: name='com.armsone.nasfinder'"*) ;;
  *) echo "unexpected package identity" >&2; exit 65 ;;
esac

version_code=$(printf '%s\n' "$package_line" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
case "$version_code" in
  ''|*[!0-9]*) echo "invalid versionCode" >&2; exit 65 ;;
esac
if [ "$apk_name" != "NasFinder-Android-v${version_code}.apk" ]; then
  echo "filename/versionCode mismatch" >&2
  exit 65
fi

if printf '%s\n' "$badging" | grep -q "application-debuggable"; then
  echo "release APK is debuggable" >&2
  exit 65
fi

certificate=$(apksigner verify --verbose --print-certs "$apk_path")
expected_certificate=${NASFINDER_EXPECTED_CERT_SHA256:-}
if [ -n "$expected_certificate" ]; then
  normalized_expected=$(printf '%s' "$expected_certificate" | tr -d ': ' | tr '[:lower:]' '[:upper:]')
  normalized_certificate=$(printf '%s\n' "$certificate" | tr -d ': ' | tr '[:lower:]' '[:upper:]')
  case "$normalized_certificate" in
    *"$normalized_expected"*) ;;
    *) echo "release signer does not match the established update lineage" >&2; exit 65 ;;
  esac
elif printf '%s\n' "$certificate" | grep -qi "CN=Android Debug"; then
  echo "debug signing certificate requires an explicit expected certificate digest" >&2
  exit 65
fi

printf '%s\n' "$package_line"
printf '%s\n' "$certificate"
shasum -a 256 "$apk_path"
