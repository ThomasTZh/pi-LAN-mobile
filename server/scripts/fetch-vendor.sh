#!/usr/bin/env bash
# Fetch the pinned pi-rpc-web extension that the Pi LAN host loads.
#
# pi-rpc-web is third-party code (MIT, (c) 2026 tohliheng) and is deliberately
# not committed to this repository. This script downloads the exact upstream
# commit, verifies the tarball SHA-256 and every kept file against
# vendor/pi-rpc-web.sha256, and installs it under vendor/pi-rpc-web/.
#
# Usage:
#   server/scripts/fetch-vendor.sh          # fetch if missing or damaged
#   server/scripts/fetch-vendor.sh --force  # re-download and replace
#   server/scripts/fetch-vendor.sh --check  # verify an existing copy, no download

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
VENDOR_DIR="$SERVER_DIR/vendor"
TARGET="$VENDOR_DIR/pi-rpc-web"

PROJECT="pi-rpc-web"
UPSTREAM="https://github.com/tohlh/pi-rpc-web"
VERSION="0.1.1"
COMMIT="f8a1ba42d23e88ebcd6d44e8352e79ad4cdf4fd6"
TARBALL_URL="https://codeload.github.com/tohlh/pi-rpc-web/tar.gz/$COMMIT"
TARBALL_SHA256="1dd51db520177add5e03b9167019d769bf00034cde08ffcefb45570a6e45b18e"
MANIFEST="$VENDOR_DIR/pi-rpc-web.sha256"

MODE=fetch
case "${1:-}" in
	--force) MODE=force ;;
	--check) MODE=check ;;
	-h|--help)
		sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
		exit 0
		;;
	"") ;;
	*) echo "fetch-vendor.sh: unknown option: $1" >&2; exit 2 ;;
esac

if [ ! -f "$MANIFEST" ]; then
	echo "fetch-vendor.sh: missing checksum manifest: $MANIFEST" >&2
	exit 1
fi

verify_tree() {
	(cd "$VENDOR_DIR" && sha256sum --quiet -c "$MANIFEST")
}

if [ "$MODE" = check ]; then
	if verify_tree; then
		echo "pi-rpc-web $VERSION ($COMMIT): $(wc -l < "$MANIFEST") files verified"
		exit 0
	fi
	echo "pi-rpc-web: existing copy is missing or does not match the manifest" >&2
	exit 1
fi

if [ "$MODE" = fetch ] && [ -d "$TARGET" ] && verify_tree; then
	echo "pi-rpc-web $VERSION ($COMMIT) already present and verified"
	exit 0
fi

for tool in curl sha256sum tar; do
	command -v "$tool" >/dev/null 2>&1 || {
		echo "fetch-vendor.sh: $tool is required" >&2
		exit 1
	}
done

work=$(mktemp -d)
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

echo "Downloading $PROJECT $VERSION ($COMMIT)"
curl -fsSL --retry 2 -o "$work/source.tar.gz" "$TARBALL_URL"
printf '%s  %s\n' "$TARBALL_SHA256" "$work/source.tar.gz" | sha256sum -c -

mkdir -p "$work/extract"
tar -xzf "$work/source.tar.gz" -C "$work/extract"
top=$(find "$work/extract" -mindepth 1 -maxdepth 1 -type d | head -n 1)
if [ -z "$top" ] || [ ! -d "$top" ]; then
	echo "fetch-vendor.sh: unexpected tarball layout" >&2
	exit 1
fi

stage="$work/stage"
mkdir -p "$stage/pi-rpc-web"
while read -r _sum path; do
	rel=${path#pi-rpc-web/}
	src="$top/$rel"
	if [ ! -f "$src" ]; then
		echo "fetch-vendor.sh: upstream is missing $rel" >&2
		exit 1
	fi
	mkdir -p "$stage/pi-rpc-web/$(dirname -- "$rel")"
	cp -p "$src" "$stage/pi-rpc-web/$rel"
done < "$MANIFEST"

(cd "$stage" && sha256sum --quiet -c "$MANIFEST")

rm -rf "$TARGET"
mv "$stage/pi-rpc-web" "$TARGET"

echo "Installed $TARGET"
echo "$(wc -l < "$MANIFEST") files verified against the pinned manifest"
echo "licence: MIT, copyright (c) 2026 tohliheng (vendor/pi-rpc-web/LICENSE)"
echo "upstream: $UPSTREAM"
