#!/usr/bin/env bash
# Package the Linux app image from :desktop:createDistributable for pacman.
set -euo pipefail
umask 022

if [[ $# != 2 ]]; then
    echo "Usage: $0 <OpenTune app image directory> <output directory>" >&2
    exit 2
fi

if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
    echo "The Arch Linux package must be built on x86-64 Linux." >&2
    exit 1
fi

for command in bsdtar zstd; do
    if ! command -v "$command" >/dev/null; then
        echo "Missing build tool: $command" >&2
        exit 1
    fi
done

app=$1
if [[ ! -x $app/bin/OpenTune || ! -f $app/lib/runtime/lib/server/libjvm.so ]]; then
    echo "Missing Linux app image at $app. Run ./gradlew :desktop:createDistributable first." >&2
    exit 1
fi

here=$(cd -- "$(dirname -- "$0")" && pwd)
repo=$(cd -- "$here/../.." && pwd)
version=$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)"$/\1/p' "$repo/app/build.gradle.kts")
if [[ ! $version =~ ^[a-zA-Z0-9][a-zA-Z0-9._+]*$ ]]; then
    echo "Invalid Arch package version: $version" >&2
    exit 1
fi
epoch=${SOURCE_DATE_EPOCH:-$(git -C "$repo" log -1 --format=%ct)}
if [[ ! $epoch =~ ^[0-9]+$ ]]; then
    echo "SOURCE_DATE_EPOCH must be a Unix timestamp." >&2
    exit 1
fi

mkdir -p -- "$2"
out=$(cd -- "$2" && pwd)/opentune-$version-1-x86_64.pkg.tar.zst
work=$(mktemp -d)
temporary_package=
trap 'rm -rf -- "$work"; [[ -z $temporary_package ]] || rm -f -- "$temporary_package"' EXIT

root=$work/pkg
install -d "$root/usr/lib/opentune" "$root/usr/bin"
cp -a -- "$app/." "$root/usr/lib/opentune"
chmod -R u+rwX,go+rX,go-w "$root/usr/lib/opentune"
cat > "$root/usr/bin/opentune" <<'EOF'
#!/bin/sh
exec /usr/lib/opentune/bin/OpenTune "$@"
EOF
chmod 755 "$root/usr/bin/opentune"
install -Dm644 "$here/opentune.desktop" "$root/usr/share/applications/opentune.desktop"
install -Dm644 "$here/icons/AppIcon.png" "$root/usr/share/icons/hicolor/512x512/apps/opentune.png"
install -Dm644 "$repo/LICENSE" "$root/usr/share/licenses/opentune/LICENSE"
install -Dm644 "$repo/THIRD_PARTY_NOTICES.md" "$root/usr/share/licenses/opentune/THIRD_PARTY_NOTICES.md"

size=$(find "$root/usr" -type f -printf '%s\n' | awk '{ size += $1 } END { printf "%.0f\n", size }')
cat > "$root/.PKGINFO" <<EOF
pkgname = opentune
pkgbase = opentune
xdata = pkgtype=pkg
pkgver = $version-1
pkgdesc = A music player for YouTube Music
url = https://github.com/justshivv/Opentune
builddate = $epoch
packager = OpenTune
size = $size
arch = x86_64
license = GPL-3.0-only
depend = alsa-lib
depend = fontconfig
depend = glibc
depend = gtk3
depend = libpulse
depend = libx11
depend = libxext
depend = libxi
depend = libxrender
depend = libxtst
depend = ttf-font
depend = xorg-xwayland
optdepend = pipewire-alsa: Route ALSA audio to PipeWire
optdepend = pipewire-pulse: Play audio over PipeWire through its PulseAudio server
EOF

temporary_package=$(mktemp -- "$(dirname -- "$out")/.opentune-package.XXXXXX")
(
    cd -- "$root"
    find . -exec touch -h -d "@$epoch" {} +

    { printf '.PKGINFO\0'; find usr -print0 | LC_ALL=C sort -z; } > "$work/files"
    archive_options=(--uid 0 --gid 0 --uname root --gname root --no-fflags --no-acls --no-xattrs)
    LC_ALL=C bsdtar -cnf - "${archive_options[@]}" --format=mtree \
        --options='!all,use-set,type,uid,gid,mode,time,size,sha256,link' \
        --null --files-from "$work/files" | gzip -n > .MTREE
    touch -d "@$epoch" .MTREE

    { printf '.MTREE\0'; cat "$work/files"; } |
        LC_ALL=C bsdtar -cnf - "${archive_options[@]}" --null --files-from - |
        zstd -q -T1 -9 -c > "$temporary_package"
)
chmod 644 "$temporary_package"
mv -f -- "$temporary_package" "$out"
printf 'Built %s\n' "$out"
