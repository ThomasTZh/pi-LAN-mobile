#!/usr/bin/env bash
# Pi Mobile: one terminal window for the Pi LAN host and the Pi CLI.
#
# Double-clicked from the desktop entry (launcher/pi-mobile.desktop). It starts
# the Pi LAN host, shows the URL and PIN for the phone, then opens the normal Pi
# CLI in the same window. The phone app and the CLI can be used at the same
# time; they share the host's session store. When the CLI exits (Ctrl+D, Ctrl+C
# twice, a closed window or a signal) the launcher stops the host as well.
#
# Usage: pi-live.sh [--test] [--no-pause] [--no-cli] [--project DIR]
#   --test       start the host in the sandboxed test mode (port 7692, seeded config)
#   --no-pause   skip the "Press Enter" prompt before the CLI
#   --no-cli     start the host only (no Pi CLI), useful for diagnostics
#   --project    project directory for the CLI and the host (default: repo root)
# Env:
#   PI_LIVE_CLI   command used instead of `pi` (tests use `sleep` or a pi variant)
#   PI_PROJECT_DIR, PI_LAN_HOST_RUNTIME and the other serve.sh knobs pass through.

set -uo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SERVER_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
PI_APP_DIR=$(cd -- "$SERVER_DIR/.." && pwd)
PROJECT_DIR=${PI_PROJECT_DIR:-$PI_APP_DIR}
CLI=${PI_LIVE_CLI:-pi}
RUNTIME=${PI_LAN_HOST_RUNTIME:-$SERVER_DIR/runtime}
CONN=$RUNTIME/connection.json

TEST_MODE=0
PAUSE=1
NO_CLI=0

usage() {
	cat <<'USAGE'
Usage: pi-live.sh [--test] [--no-pause] [--no-cli] [--project DIR]
  --test       start the host in sandboxed test mode (port 7692, seeded config)
  --no-pause   skip the "Press Enter" prompt before the CLI
  --no-cli     start the host only (no Pi CLI), for diagnostics
  --project    project directory for the CLI and the host (default: repo root)
Env: PI_LIVE_CLI overrides the CLI command (default: pi).
USAGE
}

while [ $# -gt 0 ]; do
	case "$1" in
		--test) TEST_MODE=1 ;;
		--no-pause) PAUSE=0 ;;
		--no-cli) NO_CLI=1 ;;
		--project)
			[ $# -ge 2 ] || { echo "pi-live: --project needs a directory" >&2; exit 2; }
			PROJECT_DIR=$2
			shift
			;;
		-h|--help) usage; exit 0 ;;
		*) echo "pi-live: unknown option: $1" >&2; usage >&2; exit 2 ;;
	esac
	shift
done

if [ ! -d "$PROJECT_DIR" ]; then
	echo "pi-live: project directory does not exist: $PROJECT_DIR" >&2
	exit 2
fi
export PI_PROJECT_DIR="$PROJECT_DIR"

HOST_STARTED=0
HOST_STOPPED=0
CLI_PID=""

stop_host() {
	[ "$HOST_STARTED" = 1 ] || return 0
	[ "$HOST_STOPPED" = 0 ] || return 0
	HOST_STOPPED=1
	bash "$SCRIPT_DIR/stop.sh" >/dev/null 2>&1 || true
}

finish() {
	local code=${1:-0}
	trap - EXIT HUP TERM INT
	if [ -n "$CLI_PID" ] && kill -0 "$CLI_PID" 2>/dev/null; then
		kill -TERM "$CLI_PID" 2>/dev/null || true
		wait "$CLI_PID" 2>/dev/null || true
		CLI_PID=""
	fi
	if [ "$HOST_STARTED" = 1 ] && [ "$HOST_STOPPED" = 0 ]; then
		echo
		echo "Stopping the Pi LAN host..."
		stop_host
		echo "Pi LAN host stopped. Double-click the launcher again to restart it."
	fi
	exit "$code"
}
trap 'finish 130' INT HUP TERM
trap 'finish $?' EXIT

conn_field() {
	python3 - "$CONN" "$1" <<'PY' 2>/dev/null
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    sys.exit(1)
value = data.get(sys.argv[2])
if value is None:
    sys.exit(1)
print(value)
PY
}

format_pin() {
	# 123456 -> "123 456" for the same grouping the app and serve.sh use.
	local pin=$1
	if [ "${#pin}" = 6 ]; then
		printf '%s %s' "${pin:0:3}" "${pin:3:3}"
	else
		printf '%s' "$pin"
	fi
}

echo "Pi Mobile: starting the Pi LAN host"
if [ "$TEST_MODE" = 1 ]; then
	serve_args=(--test)
else
	serve_args=()
fi

if ! bash "$SCRIPT_DIR/serve.sh" "${serve_args[@]}"; then
	echo
	echo "The Pi LAN host did not start, so the Pi CLI was not opened."
	echo "If another instance is already running: $SCRIPT_DIR/stop.sh"
	exit 3
fi
HOST_STARTED=1

url=$(conn_field url || true)
pin=$(conn_field pin || true)
project=$(conn_field projectDir || true)
if [ -n "$url" ]; then
	echo
	echo "Phone (Pi Mobile):"
	echo "  Address: ${url}"
	if [ -n "$pin" ]; then
		echo "  PIN:     $(format_pin "$pin")"
	fi
	[ -n "$project" ] && echo "  Project: ${project}"
	echo
	echo "The phone and this CLI share the host's session store. Open a session on"
	echo "one side at a time; both writers to one session file are not merged."
else
	echo
	echo "pi-live: warning: could not read $CONN; see scripts/status.sh" >&2
fi

if [ "$NO_CLI" = 1 ]; then
	echo
	echo "Host only (--no-cli). Press Ctrl+C to stop the Pi LAN host."
	# Wait for a signal; the trap does the cleanup.
	while :; do sleep 3600 & wait $!; done
fi

if [ "$PAUSE" = 1 ]; then
	printf '\nPress Enter to open the Pi CLI (Ctrl+C stops the host). '
	IFS= read -r _ || finish 130
fi

read -r -a CLI_ARR <<< "$CLI"
if [ "${#CLI_ARR[@]}" -eq 0 ]; then
	echo "pi-live: empty PI_LIVE_CLI" >&2
	finish 2
fi

echo
echo "Opening the Pi CLI in ${project:-$PROJECT_DIR}"
echo "  Exit with Ctrl+D, or Ctrl+C twice quickly. The Pi LAN host stops with it."
echo
cd -- "$PROJECT_DIR" || finish 2

# A non-interactive shell gives background jobs /dev/null on stdin, which a TUI
# cannot use. Save the terminal before backgrounding and hand it back to the
# CLI, so `wait` stays interruptible for signals and the CLI still gets a TTY.
exec 3<&0
"${CLI_ARR[@]}" 0<&3 3<&- &
CLI_PID=$!
wait "$CLI_PID"
cli_status=$?
CLI_PID=""
exec 3<&-

echo
if [ "$cli_status" -ne 0 ]; then
	echo "Pi CLI exited with status ${cli_status}."
else
	echo "Pi CLI exited."
fi
# The EXIT trap stops the host and holds the window (konsole --hold).
exit 0
