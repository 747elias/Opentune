#!/usr/bin/env bash
# Package two native Compose/jpackage app images into one macOS disk image.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <arm64/OpenTune.app> <x86_64/OpenTune.app> <output.dmg>" >&2
    exit 2
fi
if [[ $(uname -s) != Darwin ]]; then
    echo "The universal installer must be assembled on macOS." >&2
    exit 1
fi

arm_app=$1
intel_app=$2
output=$3
scripts=$(cd "$(dirname "$0")" && pwd)
identity=${OPENTUNE_MACOS_SIGN_IDENTITY:--}

validate_payload() {
    local app=$1 arch=$2
    local contents="$app/Contents"
    [[ -x "$contents/MacOS/OpenTune" && -f "$contents/Info.plist" &&
        -f "$contents/runtime/Contents/Home/lib/modules" ]] || {
        echo "Missing jpackage launcher, metadata or runtime in $app" >&2
        exit 1
    }
    xcrun lipo -verify_arch "$arch" "$contents/MacOS/OpenTune"
    xcrun lipo -verify_arch "$arch" "$contents/runtime/Contents/Home/lib/server/libjvm.dylib"
}

validate_payload "$arm_app" arm64
validate_payload "$intel_app" x86_64
for key in CFBundleIdentifier CFBundleVersion CFBundleShortVersionString; do
    arm_value=$(plutil -extract "$key" raw "$arm_app/Contents/Info.plist")
    intel_value=$(plutil -extract "$key" raw "$intel_app/Contents/Info.plist")
    if [[ "$arm_value" != "$intel_value" ]]; then
        echo "The two app images disagree on $key." >&2
        exit 1
    fi
done

output_dir=$(dirname "$output")
mkdir -p "$output_dir"
work=$(mktemp -d "$output_dir/.opentune-macos.XXXXXX")
trap 'rm -rf "$work"' EXIT

image="$work/image"
bundle="$image/OpenTune.app"
contents="$bundle/Contents"
mkdir -p "$contents/MacOS" "$contents/Helpers"
ditto "$arm_app/Contents/Resources" "$contents/Resources"
cp "$arm_app/Contents/Info.plist" "$contents/Info.plist"
cp "$arm_app/Contents/PkgInfo" "$contents/PkgInfo"
# Apple Silicon and the JavaFX runtime require macOS 11 or newer.
plutil -replace LSMinimumSystemVersion -string 11.0 "$contents/Info.plist"
bundle_id=$(plutil -extract CFBundleIdentifier raw "$contents/Info.plist")

arm_payload="$contents/Helpers/OpenTune-arm64.app"
intel_payload="$contents/Helpers/OpenTune-x86_64.app"
ditto "$arm_app" "$arm_payload"
ditto "$intel_app" "$intel_payload"
# Keep helper IDs distinct from the Finder-facing application.
plutil -replace CFBundleIdentifier -string "$bundle_id.arm64" "$arm_payload/Contents/Info.plist"
plutil -replace CFBundleIdentifier -string "$bundle_id.x86-64" "$intel_payload/Contents/Info.plist"

xcrun clang -arch arm64 -arch x86_64 -mmacosx-version-min=11.0 \
    -std=c11 -D_DARWIN_C_SOURCE -O2 -Wall -Wextra -Werror \
    "$scripts/macos-launcher.c" -o "$contents/MacOS/OpenTune"
xcrun lipo -verify_arch arm64 x86_64 "$contents/MacOS/OpenTune"

sign=(codesign --force --sign "$identity")
if [[ "$identity" != - ]]; then
    sign+=(--timestamp --options runtime)
fi

sign_payload() {
    local payload=$1 path
    local runtime="$payload/Contents/runtime"
    # Sign the jlink runtime inside out, including executable helpers and dylibs.
    while IFS= read -r -d '' path; do
        if [[ "$path" != "$runtime/Contents/MacOS/libjli.dylib" ]] &&
            [[ $(file -b "$path") == *Mach-O* ]]; then
            "${sign[@]}" "$path"
        fi
    done < <(find "$runtime" -type f \( -perm -100 -o -name '*.dylib' \) -print0)
    "${sign[@]}" "$runtime"

    # The JVM compiles code at runtime and extracts media and graphics libraries from jars.
    if [[ "$identity" != - ]]; then
        "${sign[@]}" --entitlements "$scripts/macos-entitlements.plist" "$payload"
    else
        "${sign[@]}" "$payload"
    fi
}

sign_payload "$arm_payload"
sign_payload "$intel_payload"
"${sign[@]}" "$bundle"
codesign --verify --deep --strict "$bundle"

ln -s /Applications "$image/Applications"
disk_image="$work/OpenTune.dmg"
hdiutil create -fs HFS+ -format UDZO -volname OpenTune -srcfolder "$image" "$disk_image"
if [[ "$identity" != - ]]; then
    codesign --sign "$identity" --timestamp "$disk_image"
    codesign --verify "$disk_image"
fi
hdiutil verify "$disk_image"
mv -f "$disk_image" "$output"
