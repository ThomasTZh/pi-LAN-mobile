#!/usr/bin/env node
// Contract smoke test for the Pi LAN host (vendored pi-rpc-web on pi --mode rpc).
//
// No LLM call and no external dependency: HTTP login checks plus a hand-rolled
// WebSocket client (tests/lib/ws-client.mjs).
//
// Usage:
//   node tests/smoke.mjs                     # uses runtime/connection.json
//   node tests/smoke.mjs --connection <path>
//   node tests/smoke.mjs --url http://host:port --pin 123456
//   node tests/smoke.mjs --verbose           # dump every received record
//
// Exit code 0 only when every check passes.
import { wsHandshake, wsUpgradeStatus } from "./lib/ws-client.mjs";
import { DEFAULT_TIMEOUT_MS, makeRedactor, resolveTarget } from "./lib/target.mjs";

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
			case "--timeout":
				options.timeout = Number(argv[++i]);
				break;
			case "--verbose":
				options.verbose = true;
				break;
			case "-h":
			case "--help":
				console.log(
					"Usage: node tests/smoke.mjs [--connection <path>] [--url <url> --pin <digits>] [--timeout <ms>] [--verbose]",
				);
				process.exit(0);
				break;
			default:
				throw new Error(`unknown argument: ${arg}`);
		}
	}
	return options;
}

function loadTarget() {
	const options = parseArgs(process.argv.slice(2));
	const target = resolveTarget({
		url: options.url,
		pin: options.pin,
		connectionPath: options.connection,
	});
	return {
		...target,
		timeoutMs: options.timeout ?? DEFAULT_TIMEOUT_MS,
		verbose: options.verbose,
	};
}

let target;
try {
	target = loadTarget();
} catch (err) {
	console.error(`smoke.mjs: ${err?.message ?? err}`);
	process.exit(1);
}
const { base, pin, timeoutMs } = target;

let passed = 0;
let failed = 0;
let ws = null;

const redactPin = makeRedactor(pin);

function fail(message) {
	throw new Error(redactPin(message));
}

async function step(name, fn) {
	try {
		const detail = await fn();
		passed++;
		console.log(`PASS ${name}${detail ? ` - ${redactPin(detail)}` : ""}`);
		return detail;
	} catch (err) {
		failed++;
		const message = redactPin(err?.message ?? String(err));
		console.log(`FAIL ${name}: ${message}`);
		if (err && typeof err === "object") err.reported = true;
		throw err;
	}
}

function check(condition, message) {
	if (!condition) fail(message);
}

function fetchWithTimeout(url, init = {}) {
	return fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
}

function setCookies(response) {
	if (typeof response.headers.getSetCookie === "function") {
		return response.headers.getSetCookie();
	}
	const single = response.headers.get("set-cookie");
	return single ? [single] : [];
}

function postLogin(candidatePin) {
	return fetchWithTimeout(new URL("/login", base), {
		method: "POST",
		redirect: "manual",
		headers: {
			"Content-Type": "application/x-www-form-urlencoded",
			"Content-Length": String(Buffer.byteLength(`pin=${candidatePin}`)),
		},
		body: `pin=${candidatePin}`,
	});
}

function wrongPinFor(correctPin) {
	const last = Number(correctPin[5]);
	const candidate = `${correctPin.slice(0, 5)}${(last + 1) % 10}`;
	return candidate === correctPin ? "000000" : candidate;
}

async function main() {
	console.log(
		`Pi LAN host smoke test -> ${base.origin} (project ${target.projectDir}, pin *** ***)`,
	);

	const form = await step("GET /login returns 200 with a PIN form", async () => {
		const response = await fetchWithTimeout(new URL("/login", base), { redirect: "manual" });
		const body = await response.text();
		check(response.status === 200, `expected 200, got ${response.status}`);
		check(/name=["']?pin["']?/.test(body), "login page has no pin input");
		check(/method=["']?post["']?/i.test(body), "login page has no POST form");
		return "form present";
	});
	void form;

	await step("wrong PIN is rejected", async () => {
		const wrong = wrongPinFor(pin);
		const response = await postLogin(wrong);
		check(response.status === 401, `expected 401, got ${response.status}`);
		check(setCookies(response).length === 0, "rejected login still set a cookie");
		const body = await response.text();
		check(/invalid pin/i.test(body), "rejection page does not mention an invalid PIN");
		return "401 without cookie";
	});

	let cookie = "";
	await step("correct PIN returns 303 and a session cookie", async () => {
		const response = await postLogin(pin);
		check(response.status === 303, `expected 303, got ${response.status}`);
		check(
			response.headers.get("location") === "/",
			`expected Location: /, got ${response.headers.get("location")}`,
		);
		const cookies = setCookies(response);
		check(cookies.length === 1, `expected exactly one Set-Cookie, got ${cookies.length}`);
		const header = cookies[0];
		const match = /(?:^|;\s*)pi_rpc_web_session=([0-9a-f]{64})(?:;|$)/.exec(header);
		check(match !== null, "no pi_rpc_web_session=<64 hex> cookie value in Set-Cookie");
		check(/HttpOnly/i.test(header), "cookie is missing HttpOnly");
		check(/SameSite=Strict/i.test(header), "cookie is missing SameSite=Strict");
		check(/Path=\//i.test(header), "cookie is missing Path=/");
		cookie = `pi_rpc_web_session=${match[1]}`;
		return `pi_rpc_web_session=${match[1].slice(0, 8)}… (64 hex), HttpOnly, SameSite=Strict`;
	});

	await step("unauthenticated HTTP page redirects to /login", async () => {
		const response = await fetchWithTimeout(new URL("/", base), { redirect: "manual" });
		check(response.status === 303, `expected 303, got ${response.status}`);
		check(
			response.headers.get("location") === "/login",
			`expected Location: /login, got ${response.headers.get("location")}`,
		);
		return "303 -> /login";
	});

	await step("unauthenticated WebSocket upgrade is rejected", async () => {
		const status = await wsUpgradeStatus({ url: base.origin, timeoutMs });
		check(status === 401, `expected 401 Unauthorized, got ${status}`);
		return "401 Unauthorized";
	});

	const handshake = await step("authenticated WebSocket upgrade is accepted", async () => {
		const result = await wsHandshake({ url: base.origin, cookie, timeoutMs });
		if (!result.upgraded) {
			fail(`upgrade rejected with HTTP ${result.status}`);
		}
		ws = result.client;
		return "101 Switching Protocols";
	});
	void handshake;

	await step('hello {cwd, extraArgs:["--no-mcp"]} spawns a child', async () => {
		ws.send({ type: "hello", cwd: target.projectDir, extraArgs: ["--no-mcp"] });
		const reply = await ws.expect((record) => record?.type === "hello", "hello reply", timeoutMs);
		check(reply.ok === true, `hello rejected: ${reply.error ?? "unknown error"}`);
		check(typeof reply.pid === "number", "hello reply has no child pid");
		return `ok, child pid ${reply.pid}`;
	});

	await step("meta ping returns pong", async () => {
		ws.send({ type: "meta", id: "smoke-ping", action: "ping" });
		const reply = await ws.expect(
			(record) => record?.type === "meta" && record.id === "smoke-ping",
			"meta ping reply",
			timeoutMs,
		);
		check(reply.ok === true, `meta ping failed: ${reply.error ?? "no ok flag"}`);
		check(reply.data?.pong === true, "meta ping did not return {pong:true}");
		return "{pong:true}";
	});

	await step("meta sessions.list returns arrays", async () => {
		ws.send({ type: "meta", id: "smoke-sessions", action: "sessions.list" });
		const reply = await ws.expect(
			(record) => record?.type === "meta" && record.id === "smoke-sessions",
			"meta sessions.list reply",
			timeoutMs,
		);
		check(reply.ok === true, `sessions.list failed: ${reply.error ?? "no ok flag"}`);
		// Vendor contract: grouped listing {projects:[{project, sessions}]}.
		const projects = reply.data?.projects;
		check(Array.isArray(projects), "sessions.list data.projects is not an array");
		for (const project of projects) {
			check(
				Array.isArray(project?.sessions),
				"sessions.list project entry has no sessions array",
			);
		}
		// Project-scoped listing returns a flat {project, sessions} object.
		ws.send({
			type: "meta",
			id: "smoke-sessions-project",
			action: "sessions.list",
			project: target.projectDir,
		});
		const scoped = await ws.expect(
			(record) => record?.type === "meta" && record.id === "smoke-sessions-project",
			"meta sessions.list project reply",
			timeoutMs,
		);
		check(scoped.ok === true, `scoped sessions.list failed: ${scoped.error ?? "no ok flag"}`);
		check(Array.isArray(scoped.data?.sessions), "scoped sessions.list sessions is not an array");
		return `${projects.length} project group(s), ${scoped.data.sessions.length} row(s) for the project`;
	});

	await step("get_state succeeds with a model", async () => {
		ws.send({ id: "smoke-state", type: "get_state" });
		const reply = await ws.expect(
			(record) => record?.type === "response" && record.id === "smoke-state",
			"get_state response",
			timeoutMs,
		);
		check(reply.success === true, `get_state failed: ${reply.error ?? "no error field"}`);
		if (reply.command) check(reply.command === "get_state", `unexpected command ${reply.command}`);
		const model = reply.data?.model;
		check(model !== null && typeof model === "object", "get_state data.model missing");
		const label = model.name ?? model.id;
		check(typeof label === "string" && label.length > 0, "model has no id or name");
		if (model.id === "unknown") {
			console.log(
				"WARN get_state returned the placeholder model; check PI_CODING_AGENT_DIR and ~/.pi/agent/models.json",
			);
		}
		return `model ${label}`;
	});

	await step("get_messages succeeds with a messages array", async () => {
		ws.send({ id: "smoke-messages", type: "get_messages" });
		const reply = await ws.expect(
			(record) => record?.type === "response" && record.id === "smoke-messages",
			"get_messages response",
			timeoutMs,
		);
		check(reply.success === true, `get_messages failed: ${reply.error ?? "no error field"}`);
		check(Array.isArray(reply.data?.messages), "get_messages data.messages is not an array");
		return `${reply.data.messages.length} message(s)`;
	});

	if (ws) {
		if (target.verbose) {
			console.log("\nReceived records:");
			for (const record of ws.records) {
				console.log(
					`  ${JSON.stringify(record).slice(0, 400)}`,
				);
			}
		}
		ws.close();
	}

	console.log(`\n${passed} check(s) passed, ${failed} failed`);
	if (failed > 0) process.exitCode = 1;
}

main().catch((err) => {
	if (err?.reported !== true) {
		failed++;
		console.log(`FAIL smoke test aborted: ${redactPin(err?.message ?? String(err))}`);
	}
	if (ws) ws.destroy();
	console.log(`\n${passed} check(s) passed, ${failed} failed (suite aborted)`);
	process.exitCode = 1;
});
