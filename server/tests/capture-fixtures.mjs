#!/usr/bin/env node
// Optional fixture capture: one tiny prompt through the running Pi LAN host,
// written as JSONL records for the Android reducer fixtures.
//
// NEVER starts the model server. If the configured model backend is not
// reachable the script prints SKIP and exits 0.
//
// Usage:
//   node tests/capture-fixtures.mjs                        # runtime/connection.json
//   node tests/capture-fixtures.mjs --out tests/fixtures
//   node tests/capture-fixtures.mjs --prompt "Reply with exactly OK"
//   node tests/capture-fixtures.mjs --url http://host:port --pin 123456
//
// Writes <out>/<utc-stamp>-turn.jsonl (metadata line, then every RPC record)
// and <out>/<utc-stamp>-messages.json (get_messages data).
import { mkdirSync, writeFileSync } from "node:fs";
import { resolve as resolvePath } from "node:path";
import {
	DEFAULT_TIMEOUT_MS,
	SERVER_DIR,
	makeRedactor,
	resolveTarget,
} from "./lib/target.mjs";
import { wsHandshake } from "./lib/ws-client.mjs";

const DEFAULT_PROMPT = "Reply with exactly OK";
const MODEL_PROBE_TIMEOUT_MS = 4000;
const TURN_TIMEOUT_MS = 180000;

function parseArgs(argv) {
	const options = { verbose: false };
	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i];
		switch (arg) {
			case "--connection":
				options.connection = argv[++i];
				break;
			case "--url":
				options.url = argv[++i];
				break;
			case "--pin":
				options.pin = argv[++i];
				break;
			case "--prompt":
				options.prompt = argv[++i];
				break;
			case "--out":
				options.out = argv[++i];
				break;
			case "--timeout":
				options.timeout = Number(argv[++i]);
				break;
			case "--verbose":
				options.verbose = true;
				break;
			case "-h":
			case "--help":
				console.log(
					"Usage: node tests/capture-fixtures.mjs [--connection <path>] [--url <url> --pin <digits>] " +
						"[--prompt <text>] [--out <dir>] [--timeout <ms>] [--verbose]",
				);
				process.exit(0);
				break;
			default:
				throw new Error(`unknown argument: ${arg}`);
		}
	}
	return options;
}

const options = parseArgs(process.argv.slice(2));
let target;
try {
	target = resolveTarget({
		url: options.url,
		pin: options.pin,
		connectionPath: options.connection,
	});
} catch (err) {
	console.error(`capture-fixtures.mjs: ${err?.message ?? err}`);
	process.exit(1);
}
const timeoutMs = options.timeout ?? DEFAULT_TIMEOUT_MS;
const prompt = options.prompt ?? DEFAULT_PROMPT;
const outDir = resolvePath(options.out ?? resolvePath(SERVER_DIR, "tests/fixtures"));
const redact = makeRedactor(target.pin);

let ws = null;

async function main() {
	console.log(`Pi LAN host fixture capture -> ${target.base.origin} (pin *** ***)`);

	console.log("1/5 login");
	const loginResponse = await fetch(new URL("/login", target.base), {
		method: "POST",
		redirect: "manual",
		headers: { "Content-Type": "application/x-www-form-urlencoded" },
		body: `pin=${target.pin}`,
		signal: AbortSignal.timeout(timeoutMs),
	});
	if (loginResponse.status !== 303) {
		throw new Error(
			`login failed: expected 303, got ${loginResponse.status} (is the PIN current?)`,
		);
	}
	const setCookie =
		typeof loginResponse.headers.getSetCookie === "function"
			? loginResponse.headers.getSetCookie()[0]
			: loginResponse.headers.get("set-cookie");
	const cookieMatch = /pi_rpc_web_session=[0-9a-f]{64}/.exec(setCookie ?? "");
	if (!cookieMatch) throw new Error("login returned no pi_rpc_web_session cookie");
	const cookie = cookieMatch[0];

	console.log("2/5 connect and hello");
	const handshake = await wsHandshake({ url: target.base.origin, cookie, timeoutMs });
	if (!handshake.upgraded) throw new Error(`WebSocket upgrade rejected with HTTP ${handshake.status}`);
	ws = handshake.client;
	ws.send({ type: "hello", cwd: target.projectDir, extraArgs: ["--no-mcp"] });
	const hello = await ws.expect((record) => record?.type === "hello", "hello reply", timeoutMs);
	if (hello.ok !== true) throw new Error(`hello rejected: ${hello.error ?? "unknown error"}`);

	console.log("3/5 check that the model backend is already running");
	ws.send({ id: "capture-state", type: "get_state" });
	const state = await ws.expect(
		(record) => record?.type === "response" && record.id === "capture-state",
		"get_state response",
		timeoutMs,
	);
	if (state.success !== true) throw new Error(`get_state failed: ${state.error ?? "unknown"}`);
	const model = state.data?.model;
	const baseUrl = typeof model?.baseUrl === "string" ? model.baseUrl.replace(/\/+$/, "") : "";
	if (!baseUrl) {
		console.log(
			`SKIP: the host has no model with a baseUrl (model id ${model?.id ?? "none"}); ` +
				"nothing captured and no model server was started.",
		);
		ws.close();
		ws = null;
		return 0;
	}
	let modelReachable = false;
	try {
		const probe = await fetch(`${baseUrl}/models`, {
			signal: AbortSignal.timeout(MODEL_PROBE_TIMEOUT_MS),
		});
		modelReachable = probe.ok;
		if (!probe.ok) console.log(`SKIP: ${baseUrl}/models returned HTTP ${probe.status}`);
	} catch (err) {
		console.log(`SKIP: model backend ${baseUrl} is not reachable (${err.message})`);
	}
	if (!modelReachable) {
		console.log("SKIP: no fixture captured; this script never starts the model server.");
		ws.close();
		ws = null;
		return 0;
	}

	console.log(`4/5 prompt "${prompt}" (model ${model.name ?? model.id})`);
	const startedAt = new Date().toISOString();
	ws.send({ id: "capture-prompt", type: "prompt", message: prompt });
	const promptResponse = await ws.expect(
		(record) => record?.type === "response" && record.id === "capture-prompt",
		"prompt response",
		timeoutMs,
	);
	if (promptResponse.success !== true) {
		throw new Error(`prompt rejected: ${promptResponse.error ?? "unknown error"}`);
	}
	// Answer any unexpected blocking dialog with "cancelled" so the capture
	// cannot hang; the record stays in the fixture for the app team.
	ws.expect(
		(record) =>
			record?.type === "extension_ui_request" &&
			["confirm", "select", "input", "editor"].includes(record.method),
		"blocking dialog",
		TURN_TIMEOUT_MS,
	).then(
		(record) => {
			console.log(`note: auto-cancelling unexpected ${record.method} dialog ${record.id}`);
			try {
				ws.send({ type: "extension_ui_response", id: record.id, cancelled: true });
			} catch {
				/* socket already closed */
			}
		},
		() => {},
	);
	await ws.expect((record) => record?.type === "agent_settled", "agent_settled", TURN_TIMEOUT_MS);
	const settledAt = new Date().toISOString();

	console.log("5/5 collect get_messages and write fixtures");
	ws.send({ id: "capture-messages", type: "get_messages" });
	const messages = await ws.expect(
		(record) => record?.type === "response" && record.id === "capture-messages",
		"get_messages response",
		timeoutMs,
	);

	mkdirSync(outDir, { recursive: true });
	const stamp = startedAt.replace(/[:.]/g, "-");
	const turnFile = resolvePath(outDir, `${stamp}-turn.jsonl`);
	const messagesFile = resolvePath(outDir, `${stamp}-messages.json`);
	const meta = {
		_capture: {
			startedAt,
			settledAt,
			prompt,
			cwd: target.projectDir,
			model: { provider: model.provider, id: model.id, name: model.name },
			extraArgs: ["--no-mcp"],
			recordCount: ws.records.length,
		},
	};
	const lines = [JSON.stringify(meta)];
	for (const record of ws.records) lines.push(JSON.stringify(record));
	writeFileSync(turnFile, `${lines.join("\n")}\n`);
	writeFileSync(
		messagesFile,
		`${JSON.stringify({ capturedAt: settledAt, data: messages.data }, null, 2)}\n`,
	);

	console.log(`captured ${ws.records.length} record(s)`);
	console.log(`  ${turnFile}`);
	console.log(`  ${messagesFile}`);
	return 0;
}

main()
	.then((code) => {
		if (ws) ws.close();
		process.exitCode = code ?? 0;
	})
	.catch((err) => {
		if (ws) ws.destroy();
		console.log(`FAIL fixture capture: ${redact(err?.message ?? String(err))}`);
		process.exitCode = 1;
	});
