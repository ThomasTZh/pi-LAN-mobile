#!/usr/bin/env bash
# Pi LAN host launcher.
#
# Starts the vendored pi-rpc-web extension inside `pi --mode rpc` with
# --rpc-web --rpc-web-lan, keeps stdin open (the host exits immediately on
# stdin EOF), detaches into its own session so it survives the launching
# shell, waits for the printed URL and Login PIN, and writes
# runtime/connection.json (mode 0600).
#
# Usage:
#   scripts/serve.sh                # production: port 7690, real ~/.pi sessions
#   scripts/serve.sh --test         # test mode: port 7692, sandboxed PI_HOME
#   PI_PROJECT_DIR=... PI_WEB_PORT=... scripts/serve.sh
#
# See README.md for the full contract.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd -- "$SERVER_DIR/.." && pwd)"
SELF="$SCRIPT_DIR/$(basename -- "${BASH_SOURCE[0]}")"
VENDOR_EXTENSION="$SERVER_DIR/vendor/pi-rpc-web/src/extension.ts"

RUNTIME_DIR="${PI_LAN_HOST_RUNTIME:-$SERVER_DIR/runtime}"
case "$RUNTIME_DIR" in
	/*) ;;
	*) RUNTIME_DIR="$PWD/$RUNTIME_DIR" ;;
esac
PID_FILE="$RUNTIME_DIR/host.pid"
CONNECTION_FILE="$RUNTIME_DIR/connection.json"
STDOUT_LOG="$RUNTIME_DIR/host.stdout.log"
STDERR_LOG="$RUNTIME_DIR/host.log"
STARTUP_DEADLINE_SECONDS=20

usage() {
	cat <<'EOF'
Start the Pi LAN host (pi-rpc-web in LAN mode).

Options:
  --test        Test mode: PI_WEB_PORT=7692, sandboxed PI_HOME under
                runtime/pi-home, child session store
                $PI_HOME/agent/sessions/<--encoded-project--> (pi's own layout,
                so sessions.list can see it), and a writable copy of
                ~/.pi/agent/{models,settings,models-store}.json as
                PI_CODING_AGENT_DIR (the sandbox makes ~/.pi/agent read-only,
                which otherwise leaves pi without a model).
  -h, --help    Show this help.

Environment:
  PI_PROJECT_DIR   Working folder for the agent child (default: repo root)
  PI_WEB_PORT      Port to request (default: 7690; falls back to an ephemeral
                   port when busy, the actual port is read back from the log)
  PI_HOME          Overrides the pi home used by pi-rpc-web session listing
  PI_CODING_AGENT_SESSION_DIR  Overrides the session store of spawned children
  PI_CODING_AGENT_DIR          Overrides the pi config dir (models/settings)
  PI_BIN           pi executable (default: pi on PATH, then ~/.local/bin/pi)

The host changes into PI_PROJECT_DIR before launching, so children spawned
without hello.cwd and their bash commands run in the project root.

Writes runtime/connection.json with mode 0600 and prints URL, PIN, project,
pid and log paths. Stop with scripts/stop.sh.
EOF
}

# ---------------------------------------------------------------------------
# Internal launcher mode: this is the detached, setsid'd process. It records
# its own pid (== the pid of the pi host after exec) and holds stdin open.
# ---------------------------------------------------------------------------
if [ "${PI_LAN_HOST_LAUNCH:-0}" = "1" ]; then
	printf '%s\n' "$$" >"$PID_FILE"
	# `pi --mode rpc` exits on stdin EOF, so keep an endless pipe on fd 0.
	# `tail` stays in this session/process group and dies with stop.sh.
	exec 0< <(exec tail -f /dev/null)
	exec "$PI_BIN" --mode rpc --no-session --no-mcp --rpc-web --rpc-web-lan \
		-e "$VENDOR_EXTENSION"
fi

# ---------------------------------------------------------------------------
# Foreground setup / supervisor
# ---------------------------------------------------------------------------
TEST_MODE=0
for arg in "$@"; do
	case "$arg" in
		--test) TEST_MODE=1 ;;
		-h | --help)
			usage
			exit 0
			;;
		*)
			printf 'serve.sh: unknown argument: %s\n\n' "$arg" >&2
			usage >&2
			exit 2
			;;
		esac
done

PI_PROJECT_DIR="${PI_PROJECT_DIR:-$REPO_ROOT}"
if [ ! -d "$PI_PROJECT_DIR" ]; then
	printf 'serve.sh: PI_PROJECT_DIR is not a directory: %s\n' "$PI_PROJECT_DIR" >&2
	exit 1
fi
PI_PROJECT_DIR="$(cd -- "$PI_PROJECT_DIR" && pwd)"

if [ "$TEST_MODE" = "1" ]; then
	PI_WEB_PORT="${PI_WEB_PORT:-7692}"
	PI_HOME="${PI_HOME:-$RUNTIME_DIR/pi-home}"
	# pi writes session files flat into PI_CODING_AGENT_SESSION_DIR, but the
	# bridge lists only <PI_HOME>/agent/sessions/<--encoded-project-->/ subdirs
	# (its delete/rename validation expects that shape too). Point the child
	# store at the encoded project dir, matching pi's own ~/.pi layout, so the
	# app can list and resume sessions created by this host.
	encoded_project_dir="--$(printf '%s' "${PI_PROJECT_DIR#/}" | tr '/' '-')--"
	if [ -z "${PI_CODING_AGENT_SESSION_DIR:-}" ]; then
		PI_CODING_AGENT_SESSION_DIR="$PI_HOME/agent/sessions/$encoded_project_dir"
		CREATE_SESSION_DIR=1
	fi
	export PI_LAN_HOST_TEST=1
	# The sandbox makes ~/.pi/agent read-only, and pi rejects its settings/
	# models there (lock file EROFS), which leaves an "unknown" model. Use a
	# writable copy of the non-secret config inside the sandboxed pi home.
	if [ -z "${PI_CODING_AGENT_DIR:-}" ] || [ "${PI_CODING_AGENT_DIR}" = "$HOME/.pi/agent" ]; then
		PI_CODING_AGENT_DIR="$PI_HOME/agent"
		SEED_AGENT_CONFIG=1
	fi
	export PI_HOME PI_CODING_AGENT_SESSION_DIR PI_CODING_AGENT_DIR
fi

# The launcher changes into PI_PROJECT_DIR, so relative PI_* paths would break:
# make them absolute while the caller's cwd is still current.
absolute_path() {
	case "$1" in
		/*) printf '%s' "$1" ;;
		*) printf '%s' "$PWD/$1" ;;
	esac
}
if [ -n "${PI_HOME:-}" ]; then PI_HOME="$(absolute_path "$PI_HOME")"; fi
if [ -n "${PI_CODING_AGENT_DIR:-}" ]; then PI_CODING_AGENT_DIR="$(absolute_path "$PI_CODING_AGENT_DIR")"; fi
if [ -n "${PI_CODING_AGENT_SESSION_DIR:-}" ]; then
	PI_CODING_AGENT_SESSION_DIR="$(absolute_path "$PI_CODING_AGENT_SESSION_DIR")"
fi
if [ "${CREATE_SESSION_DIR:-0}" = "1" ]; then
	mkdir -p "$PI_CODING_AGENT_SESSION_DIR"
	chmod 700 "$PI_CODING_AGENT_SESSION_DIR" 2>/dev/null || true
fi

# Copy models/settings into the sandboxed pi home (test mode only).
seed_agent_config() {
	[ "${SEED_AGENT_CONFIG:-0}" = "1" ] || return 0
	mkdir -p "$PI_CODING_AGENT_DIR/sessions"
	for config_file in models.json settings.json models-store.json; do
		if [ -f "$HOME/.pi/agent/$config_file" ]; then
			cp -f "$HOME/.pi/agent/$config_file" "$PI_CODING_AGENT_DIR/$config_file"
			chmod 600 "$PI_CODING_AGENT_DIR/$config_file" 2>/dev/null || true
		fi
	done
	printf 'serve.sh: test mode seeded %s from %s (models/settings/models-store; auth.json is not copied)\n' \
		"$PI_CODING_AGENT_DIR" "$HOME/.pi/agent"
	# pi only discovers user extensions in <PI_CODING_AGENT_DIR>/extensions, and
	# the seeded config dir has none: without this the test-mode children never
	# load workspace-boundary.ts, so no approval dialog can reach the app (G4).
	# The user's extensions live read-only outside the workspace, which the
	# sandbox permits, so a symlink is enough and stays in sync with ~/.pi.
	if [ -d "$HOME/.pi/agent/extensions" ]; then
		if [ -e "$PI_CODING_AGENT_DIR/extensions" ] && [ ! -L "$PI_CODING_AGENT_DIR/extensions" ]; then
			# Only ever a generated directory from an older seed; start clean.
			rm -rf -- "$PI_CODING_AGENT_DIR/extensions"
		fi
		ln -sfn "$HOME/.pi/agent/extensions" "$PI_CODING_AGENT_DIR/extensions"
		printf 'serve.sh: test mode linked %s -> %s/extensions (user extensions load in test-mode children)\n' \
			"$PI_CODING_AGENT_DIR/extensions" "$HOME/.pi/agent"
	else
		printf 'serve.sh: warning: no user extensions at %s; test-mode children run without extensions\n' \
			"$HOME/.pi/agent/extensions" >&2
	fi
}
PI_WEB_PORT="${PI_WEB_PORT:-7690}"

case "$PI_WEB_PORT" in
	'' | *[!0-9]*)
		printf 'serve.sh: PI_WEB_PORT must be a number, got: %s\n' "$PI_WEB_PORT" >&2
		exit 2
		;;
esac

if ! command -v setsid >/dev/null 2>&1; then
	printf 'serve.sh: setsid is required (util-linux)\n' >&2
	exit 1
fi
if [ ! -f "$VENDOR_EXTENSION" ]; then
	printf 'serve.sh: pi-rpc-web is not fetched yet: %s\n' "$VENDOR_EXTENSION" >&2
	printf 'Run server/scripts/fetch-vendor.sh once to download the pinned extension.\n' >&2
	exit 1
fi
if [ -z "${PI_BIN:-}" ]; then
	PI_BIN="$(command -v pi || true)"
fi
if [ -z "${PI_BIN:-}" ] && [ -x "$HOME/.local/bin/pi" ]; then
	PI_BIN="$HOME/.local/bin/pi"
fi
if [ -z "${PI_BIN:-}" ] || [ ! -x "$PI_BIN" ]; then
	printf 'serve.sh: pi executable not found; set PI_BIN or put pi on PATH\n' >&2
	exit 1
fi
mkdir -p "$RUNTIME_DIR"
chmod 700 "$RUNTIME_DIR" 2>/dev/null || true

HOST_PID=""
stop_group() {
	local pid="${1:-}"
	[ -n "$pid" ] || return 0
	kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
	for _ in $(seq 1 50); do
		kill -0 "$pid" 2>/dev/null || return 0
		sleep 0.1
	done
	kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
	return 0
}

log_tail() {
	if [ -s "$STDERR_LOG" ]; then
		printf -- '--- %s (last 40 lines) ---\n' "$STDERR_LOG" >&2
		tail -n 40 "$STDERR_LOG" >&2 || true
	else
		printf -- '--- %s is empty ---\n' "$STDERR_LOG" >&2
	fi
	if [ -s "$STDOUT_LOG" ]; then
		printf -- '--- %s (last 10 lines) ---\n' "$STDOUT_LOG" >&2
		tail -n 10 "$STDOUT_LOG" >&2 || true
	fi
}

fail_start() {
	printf 'serve.sh: %s\n' "$1" >&2
	log_tail
	if [ -n "$HOST_PID" ]; then stop_group "$HOST_PID"; fi
	rm -f "$PID_FILE"
	exit 1
}

# Refuse to start a second host with the same runtime dir.
if [ -f "$PID_FILE" ]; then
	existing_pid="$(tr -dc '0-9' <"$PID_FILE")"
	if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
		printf 'serve.sh: already running (pid %s); use scripts/status.sh or scripts/stop.sh\n' \
			"$existing_pid" >&2
		exit 1
	fi
	rm -f "$PID_FILE"
fi

seed_agent_config

rm -f "$CONNECTION_FILE"
: >"$STDOUT_LOG"
: >"$STDERR_LOG"
chmod 600 "$STDOUT_LOG" "$STDERR_LOG" 2>/dev/null || true

# Run the host in PI_PROJECT_DIR: the bridge spawns children with
# cwd: hello.cwd ?? process.cwd(), so this makes the project root authoritative
# for children started without hello.cwd and for their bash commands.
cd -- "$PI_PROJECT_DIR"

export PI_BIN PI_LAN_HOST_RUNTIME="$RUNTIME_DIR" PI_PROJECT_DIR PI_WEB_PORT
if [ -n "${PI_HOME:-}" ]; then export PI_HOME; fi
if [ -n "${PI_CODING_AGENT_SESSION_DIR:-}" ]; then export PI_CODING_AGENT_SESSION_DIR; fi
if [ -n "${PI_CODING_AGENT_DIR:-}" ]; then export PI_CODING_AGENT_DIR; fi

export PI_LAN_HOST_LAUNCH=1
setsid -f bash "$SELF" >"$STDOUT_LOG" 2>"$STDERR_LOG" || {
	printf 'serve.sh: failed to spawn the detached host\n' >&2
	exit 1
}
unset PI_LAN_HOST_LAUNCH

# Wait for the detached process to publish its pid.
pid_deadline=$((SECONDS + 5))
while [ -z "$HOST_PID" ]; do
	if [ -s "$PID_FILE" ]; then
		candidate="$(tr -dc '0-9' <"$PID_FILE")"
		if [ -n "$candidate" ]; then
			if kill -0 "$candidate" 2>/dev/null; then
				HOST_PID="$candidate"
				break
			fi
			# The pid file exists but the process is already gone (bad
			# PI_BIN, missing extension, instant crash). Re-check once so a
			# just-written pid is not misread.
			sleep 0.2
			if ! kill -0 "$candidate" 2>/dev/null; then
				fail_start "the host exited during startup (pid $candidate, see log below)"
			fi
		fi
	fi
	if [ "$SECONDS" -ge "$pid_deadline" ]; then
		fail_start "the host did not start within 5 s (no live pid in $PID_FILE)"
	fi
	sleep 0.1
done

# Parse the startup banner: URL + "Login PIN: NNN NNN".
URL=""
PIN=""
while :; do
	if [ -z "$URL" ]; then
		URL="$(grep -oE 'http://[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]{1,5}' "$STDERR_LOG" 2>/dev/null |
			head -n 1 || true)"
	fi
	if [ -z "$PIN" ]; then
		pin_line="$(grep -oE 'Login PIN:[[:space:]]*[0-9]{3}[[:space:]]+[0-9]{3}' "$STDERR_LOG" 2>/dev/null |
			tail -n 1 || true)"
		if [ -n "$pin_line" ]; then
			PIN="${pin_line//[!0-9]/}"
		fi
	fi
	if [ -n "$URL" ] && [ -n "$PIN" ]; then
		break
	fi
	if ! kill -0 "$HOST_PID" 2>/dev/null; then
		fail_start "the host exited before printing its URL and PIN"
	fi
	if [ "$SECONDS" -ge "$STARTUP_DEADLINE_SECONDS" ]; then
		fail_start "no URL/PIN in the host log after ${STARTUP_DEADLINE_SECONDS} s"
	fi
	sleep 0.25
done

if [ "${#PIN}" -ne 6 ]; then
	fail_start "parsed PIN is not six digits: $PIN"
fi

URL_HOST="${URL#http://}"
URL_HOST="${URL_HOST%%:*}"
URL_PORT="${URL##*:}"
PIN_DISPLAY="${PIN:0:3} ${PIN:3:3}"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# JSON-escape a string for the connection file.
json_escape() {
	printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

umask 077
tmp_conn="$CONNECTION_FILE.tmp.$$"
{
	printf '{\n'
	printf '  "version": 1,\n'
	printf '  "url": "%s",\n' "$(json_escape "$URL")"
	printf '  "host": "%s",\n' "$(json_escape "$URL_HOST")"
	printf '  "port": %s,\n' "$URL_PORT"
	printf '  "pin": "%s",\n' "$(json_escape "$PIN")"
	printf '  "pid": %s,\n' "$HOST_PID"
	printf '  "projectDir": "%s",\n' "$(json_escape "$PI_PROJECT_DIR")"
	printf '  "startedAt": "%s"\n' "$STARTED_AT"
	printf '}\n'
} >"$tmp_conn"
chmod 600 "$tmp_conn"
mv -f "$tmp_conn" "$CONNECTION_FILE"
chmod 600 "$PID_FILE" 2>/dev/null || true
umask 022

printf 'Pi LAN host running\n'
printf '  URL:        %s\n' "$URL"
printf '  PIN:        %s\n' "$PIN_DISPLAY"
printf '  Project:    %s\n' "$PI_PROJECT_DIR"
if [ -n "${PI_CODING_AGENT_SESSION_DIR:-}" ]; then
	printf '  Sessions:   %s\n' "$PI_CODING_AGENT_SESSION_DIR"
fi
printf '  PID:        %s\n' "$HOST_PID"
printf '  Port:       %s\n' "$URL_PORT"
printf '  Mode:       %s\n' "$([ "$TEST_MODE" = "1" ] && echo 'test (sandboxed PI_HOME)' || echo 'production')"
printf '  Log:        %s\n' "$STDERR_LOG"
printf '  Stdout log: %s\n' "$STDOUT_LOG"
printf '  Connection: %s\n' "$CONNECTION_FILE"
printf '  Started:    %s\n' "$STARTED_AT"
printf 'Launch: %s --mode rpc --no-session --no-mcp --rpc-web --rpc-web-lan -e %s\n' \
	"$PI_BIN" "$VENDOR_EXTENSION"
exit 0
