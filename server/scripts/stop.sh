#!/usr/bin/env bash
# Stop the Pi LAN host started by scripts/serve.sh.
#
# Signals the whole process group recorded in runtime/host.pid (the host, its
# stdin-holding `tail`, and every spawned pi child), waits for the process and
# the listening port to go away, then removes host.pid and connection.json.
# Idempotent: reports "not running" and exits 0 when nothing is running.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${PI_LAN_HOST_RUNTIME:-$SERVER_DIR/runtime}"
PID_FILE="$RUNTIME_DIR/host.pid"
CONNECTION_FILE="$RUNTIME_DIR/connection.json"

STOP_TIMEOUT_SECONDS=10
PORT_TIMEOUT_SECONDS=5

usage() {
	cat <<'EOF'
Stop the Pi LAN host (whole process group recorded in runtime/host.pid).

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
			printf 'stop.sh: unknown argument: %s\n' "$arg" >&2
			exit 2
			;;
		esac
done

json_field() {
	# Read a top-level string/number field without requiring jq.
	local key="$1" file="$2"
	sed -nE "s/^[[:space:]]*\"$key\":[[:space:]]*\"?([^,\"]*)\"?.*/\1/p" "$file" | head -n 1
}

# Is anything listening on the given TCP port (localhost is enough: the vendor
# binds 0.0.0.0 in LAN mode)?
port_listening() {
	local port="${1:-}"
	[ -n "$port" ] || return 1
	if command -v ss >/dev/null 2>&1; then
		if ss -H -ltn "sport = :$port" 2>/dev/null | grep -q .; then
			return 0
		fi
		return 1
	fi
	(exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
}

PORT=""
if [ -f "$CONNECTION_FILE" ]; then
	PORT="$(json_field port "$CONNECTION_FILE")"
fi

HOST_PID=""
if [ -f "$PID_FILE" ]; then
	HOST_PID="$(tr -dc '0-9' <"$PID_FILE")"
fi

if [ -z "$HOST_PID" ] || ! kill -0 "$HOST_PID" 2>/dev/null; then
	rm -f "$PID_FILE" "$CONNECTION_FILE"
	if [ -n "$PORT" ] && port_listening "$PORT"; then
		printf 'stop.sh: no host pid recorded, but port %s is still listening (not ours; left alone)\n' \
			"$PORT" >&2
		exit 1
	fi
	printf 'Pi LAN host is not running\n'
	exit 0
fi

printf 'Stopping Pi LAN host (pid %s)\n' "$HOST_PID"
kill -TERM -- "-$HOST_PID" 2>/dev/null || kill -TERM "$HOST_PID" 2>/dev/null || true

deadline=$((SECONDS + STOP_TIMEOUT_SECONDS))
while kill -0 "$HOST_PID" 2>/dev/null; do
	if [ "$SECONDS" -ge "$deadline" ]; then
		printf 'stop.sh: pid %s ignored SIGTERM; sending SIGKILL to the process group\n' \
			"$HOST_PID" >&2
		kill -KILL -- "-$HOST_PID" 2>/dev/null || kill -KILL "$HOST_PID" 2>/dev/null || true
		break
	fi
	sleep 0.1
done

# Give the killed process a moment to be reaped, then confirm the port is free.
for _ in $(seq 1 30); do
	kill -0 "$HOST_PID" 2>/dev/null || break
	sleep 0.1
done

if port_listening "$PORT"; then
	port_deadline=$((SECONDS + PORT_TIMEOUT_SECONDS))
	while port_listening "$PORT"; do
		if [ "$SECONDS" -ge "$port_deadline" ]; then
			printf 'stop.sh: host stopped but port %s is still listening\n' "$PORT" >&2
			rm -f "$PID_FILE" "$CONNECTION_FILE"
			exit 1
		fi
		sleep 0.1
	done
fi

rm -f "$PID_FILE" "$CONNECTION_FILE"
printf 'Stopped (pid %s, port %s closed)\n' "$HOST_PID" "${PORT:-unknown}"
exit 0
