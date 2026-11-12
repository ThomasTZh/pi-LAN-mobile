#!/usr/bin/env bash
# Status of the Pi LAN host started by scripts/serve.sh.
#
# Prints pid, port, URL, PIN, a GET /login health probe and the tail of the
# host log. Exit code 0 only when the host is alive and /login answers 200.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${PI_LAN_HOST_RUNTIME:-$SERVER_DIR/runtime}"
PID_FILE="$RUNTIME_DIR/host.pid"
CONNECTION_FILE="$RUNTIME_DIR/connection.json"
HOST_LOG="$RUNTIME_DIR/host.log"

LOG_TAIL_LINES=10
HEALTH_TIMEOUT_SECONDS=5

usage() {
	cat <<'EOF'
Show the Pi LAN host status (pid, port, URL, PIN, GET /login health, log tail).

Options:
  -h, --help    Show this help.
EOF
}

for arg in "$@"; do
	case "$arg" in
		-h | --help)
			usage
			exit 0
			;;
		*)
			printf 'status.sh: unknown argument: %s\n' "$arg" >&2
			exit 2
			;;
		esac
done

json_field() {
	# Read a top-level string/number field without requiring jq.
	local key="$1" file="$2"
	sed -nE "s/^[[:space:]]*\"$key\":[[:space:]]*\"?([^,\"]*)\"?.*/\1/p" "$file" | head -n 1
}

print_log_tail() {
	if [ -f "$HOST_LOG" ]; then
		printf -- '--- host log (%s, last %s lines) ---\n' "$HOST_LOG" "$LOG_TAIL_LINES"
		tail -n "$LOG_TAIL_LINES" "$HOST_LOG" || true
	fi
}

HOST_PID=""
if [ -f "$PID_FILE" ]; then
	HOST_PID="$(tr -dc '0-9' <"$PID_FILE")"
fi

if [ ! -f "$CONNECTION_FILE" ] && { [ -z "$HOST_PID" ] || ! kill -0 "$HOST_PID" 2>/dev/null; }; then
	printf 'Pi LAN host: not running\n'
	printf '  Runtime dir: %s\n' "$RUNTIME_DIR"
	print_log_tail
	exit 1
fi

URL=""
PIN=""
PORT=""
PROJECT_DIR=""
STARTED_AT=""
CONN_PID=""
if [ -f "$CONNECTION_FILE" ]; then
	URL="$(json_field url "$CONNECTION_FILE")"
	PIN="$(json_field pin "$CONNECTION_FILE")"
	PORT="$(json_field port "$CONNECTION_FILE")"
	PROJECT_DIR="$(json_field projectDir "$CONNECTION_FILE")"
	STARTED_AT="$(json_field startedAt "$CONNECTION_FILE")"
	CONN_PID="$(json_field pid "$CONNECTION_FILE")"
fi
[ -n "$HOST_PID" ] || HOST_PID="$CONN_PID"

if [ -n "$HOST_PID" ] && kill -0 "$HOST_PID" 2>/dev/null; then
	printf 'Pi LAN host: running (pid %s)\n' "$HOST_PID"
else
	printf 'Pi LAN host: not running (stale pid file: %s)\n' "${HOST_PID:-none}"
	print_log_tail
	exit 1
fi

if [ -n "$PIN" ] && [ "${#PIN}" -eq 6 ]; then
	PIN_DISPLAY="${PIN:0:3} ${PIN:3:3}"
else
	PIN_DISPLAY="(unknown)"
fi

printf '  URL:        %s\n' "${URL:-unknown}"
printf '  Port:       %s\n' "${PORT:-unknown}"
printf '  PIN:        %s\n' "$PIN_DISPLAY"
printf '  Project:    %s\n' "${PROJECT_DIR:-unknown}"
printf '  Started:    %s\n' "${STARTED_AT:-unknown}"
printf '  Log:        %s\n' "$HOST_LOG"

if [ -z "$URL" ]; then
	printf '  Health:     unknown (no connection.json URL)\n'
	print_log_tail
	exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
	printf '  Health:     skipped (curl not found)\n'
	print_log_tail
	exit 0
fi

health_url="${URL%/}/login"
health_code="$(curl -s -o /dev/null -m "$HEALTH_TIMEOUT_SECONDS" -w '%{http_code}' "$health_url" || true)"
if [ "$health_code" = "200" ]; then
	printf '  Health:     GET %s -> 200 OK\n' "$health_url"
	print_log_tail
	exit 0
fi

printf '  Health:     GET %s -> %s (expected 200)\n' "$health_url" "${health_code:-no response}"
print_log_tail
exit 1
