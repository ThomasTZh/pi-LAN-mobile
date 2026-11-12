#!/usr/bin/env bash
# Install or remove the Pi Mobile desktop launcher.
#
#   server/launcher/install.sh            # install (Desktop + application menu)
#   server/launcher/install.sh uninstall  # remove both links
#
# The desktop file is generated from pi-mobile.desktop.in with this checkout's
# absolute path, so rerun the installer after moving the repository.

set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
TEMPLATE=$HERE/pi-mobile.desktop.in
APPS=${XDG_DATA_HOME:-$HOME/.local/share}/applications
DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || true)
DESKTOP_DIR=${DESKTOP_DIR:-$HOME/Desktop}
MENU_LINK=$APPS/pi-mobile.desktop
DESKTOP_LINK="$DESKTOP_DIR/Pi Mobile.desktop"

fail() {
	echo "pi-mobile launcher: $*" >&2
	exit 1
}

uninstall() {
	rm -f -- "$MENU_LINK" "$DESKTOP_LINK"
	if command -v update-desktop-database >/dev/null 2>&1; then
		update-desktop-database "$APPS" >/dev/null 2>&1 || true
	fi
	echo "Removed:"
	echo "  $MENU_LINK"
	echo "  $DESKTOP_LINK"
}

install() {
	[ -f "$TEMPLATE" ] || fail "missing $TEMPLATE"
	[ -x "$REPO/server/scripts/pi-live.sh" ] || fail "missing $REPO/server/scripts/pi-live.sh"
	[ -f "$REPO/server/launcher/pi-mobile.svg" ] || fail "missing the launcher icon"
	KONSOLE=$(command -v konsole || true)
	[ -n "$KONSOLE" ] || fail "konsole is not installed (the desktop entry uses it)"
	mkdir -p -- "$APPS" "$DESKTOP_DIR"
	sed -e "s|@KONSOLE@|$KONSOLE|g" -e "s|@REPO@|$REPO|g" "$TEMPLATE" > "$MENU_LINK"
	chmod 755 -- "$MENU_LINK"
	if command -v desktop-file-validate >/dev/null 2>&1; then
		desktop-file-validate "$MENU_LINK"
	fi
	ln -sfn -- "$MENU_LINK" "$DESKTOP_LINK"
	if command -v update-desktop-database >/dev/null 2>&1; then
		update-desktop-database "$APPS" >/dev/null 2>&1 || true
	fi
	echo "Installed:"
	echo "  $MENU_LINK"
	echo "  $DESKTOP_LINK"
	echo
	echo "Double-click \"Pi Mobile: server + CLI\" to start the host and the CLI."
	echo "The window prints the phone address and PIN before opening the Pi CLI."
}

case "${1:-install}" in
	install) install ;;
	uninstall|remove) uninstall ;;
	-h|--help|help)
		sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
		;;
	*) fail "unknown command: $1 (use install or uninstall)" ;;
esac
