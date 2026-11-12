# Pi LAN host

Desktop half of Pi Mobile. It runs the third-party
[pi-rpc-web](https://github.com/tohlh/pi-rpc-web) extension inside
`pi --mode rpc`, exposes the Pi agent on the private network behind a
six-digit PIN, and tells the phone where to connect. The phone then speaks the
pi-rpc-web contract directly: an HTTP `/login` plus one WebSocket carrying
JSONL RPC records.

## What it does

- `scripts/serve.sh` starts `pi --mode rpc --no-session --no-mcp --rpc-web
  --rpc-web-lan -e vendor/pi-rpc-web/src/extension.ts`, holds stdin open (the
  host exits on stdin EOF), detaches with `setsid`, waits for the printed URL and
  `Login PIN`, and writes `runtime/connection.json` (mode 0600).
- `scripts/status.sh` shows the PID, URL, PIN, a `/login` health probe and the
  log tail; `scripts/stop.sh` stops the process group and waits for the port.
- `scripts/fetch-vendor.sh` downloads the pinned pi-rpc-web commit, verifies the
  tarball and all 24 kept files against `vendor/pi-rpc-web.sha256`, and installs
  it under `vendor/pi-rpc-web/`. The extension is not committed.
- Children load the user's Pi extensions, skills, prompt templates and MCP
  servers, so the app can run their slash commands and show plan-mode status
  and todo widgets.
- `launcher/install.sh` installs a desktop entry that starts the host, prints the
  phone address and PIN, and opens the Pi CLI in the same terminal window. The
  host stops when the CLI exits (Ctrl+D, or Ctrl+C twice quickly), when the
  window closes, or on Ctrl+C at the prompt.

## Install

1. Install the Pi coding agent (`pi` 1.4.0 or later) and configure a model.
2. Fetch the pinned extension once:

   ```sh
   server/scripts/fetch-vendor.sh
   ```

3. `setsid` (util-linux), `tar` and `sha256sum` are used by the scripts.

## Run

```sh
server/scripts/serve.sh        # production: port 7690, real ~/.pi sessions
server/scripts/status.sh
server/scripts/stop.sh
```

The banner prints the URL (`http://<lan-ip>:7690`) and the PIN
(`Login PIN: 123 456`). The PIN changes on every start.

Desktop shortcut:

```sh
server/launcher/install.sh             # Desktop + application menu
server/launcher/install.sh uninstall
```

Test mode (sandboxed, port 7692, seeded config, no writes to `~/.pi`):

```sh
server/scripts/serve.sh --test
node server/tests/smoke.mjs
server/scripts/stop.sh
```

`tests/smoke.mjs` runs 11 checks without a model call: login page, wrong PIN,
correct PIN and cookie, unauthenticated redirect and WebSocket rejection,
authenticated upgrade, `hello`, `meta ping`, `sessions.list`, `get_state` and
`get_messages`. `tests/capture-fixtures.mjs` optionally records one real turn
when a model backend is already running.

Optional user unit (paths must match the checkout):

```ini
[Service]
Type=forking
PIDFile=%h/pi-LAN-mobile/server/runtime/host.pid
WorkingDirectory=%h/pi-LAN-mobile
Environment=PI_PROJECT_DIR=%h/Documents/project
ExecStart=/bin/bash %h/pi-LAN-mobile/server/scripts/serve.sh
ExecStop=/bin/bash %h/pi-LAN-mobile/server/scripts/stop.sh

[Install]
WantedBy=default.target
```

## Security

- Plain HTTP on the private network, one shared PIN per host process. Five
  failed logins from one peer within 30 seconds lock that peer out for 30
  seconds. The browser cookie is memory-only.
- Anyone who can reach the port and read the PIN controls the agent, including
  its shell tool. Do not expose the port to the internet and do not share the
  PIN. Use a tunnel if remote access is needed.
- `runtime/` is mode 0700 and the logs and `connection.json` are 0600 because
  the PIN is written there.

## Limits

- Each WebSocket owns its own `pi` child; the child exits when the socket
  closes, so killing the phone app aborts an in-flight run.
- The phone and the terminal CLI share the session store; open a session on one
  side at a time, concurrent writers to one session file are not merged.
- MCP servers start per session, and the first prompt after idle loads the local
  model.
- The launcher and smoke test are POSIX shell scripts; Windows is not covered.

## Layout

| Path | Contents |
| --- | --- |
| `scripts/serve.sh` | Start the host, parse the banner, write `connection.json` |
| `scripts/stop.sh`, `scripts/status.sh` | Stop the process group; show state and health |
| `scripts/fetch-vendor.sh` | Download and verify the pinned pi-rpc-web sources |
| `scripts/pi-live.sh` | Host + Pi CLI in one terminal window |
| `launcher/` | Desktop entry template, icon, installer |
| `tests/` | Contract smoke test, optional fixture capture, shared WebSocket helper |
| `VENDOR-NOTES.md` | Upstream provenance and integration notes |
| `runtime/` | Generated state, gitignored |
