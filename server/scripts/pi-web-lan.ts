/**
 * Pi LAN host extension: the vendored pi-rpc-web server with a PIN chosen by the
 * launcher.
 *
 * The upstream extension always draws a random six-digit PIN per process, which
 * means the phone must be re-paired after every host restart. Pi Mobile stores
 * the PIN and the host, so the launcher keeps one PIN in
 * `server/runtime/pin` (mode 0600) and passes it here through `PI_WEB_PIN`.
 * Everything else is the unchanged vendored server (MIT, see THIRD_PARTY.md).
 *
 * Launch: `pi --mode rpc --no-session --no-mcp --lan-host -e scripts/pi-web-lan.ts`
 */
import { networkInterfaces } from "node:os";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { startServer, type RunningServer } from "../vendor/pi-rpc-web/src/server.ts";

const DEFAULT_PORT = 7690;

const state: { running: RunningServer | null; starting: boolean } = {
	running: null,
	starting: false,
};

export default function extension(pi: ExtensionAPI): void {
	pi.registerFlag("lan-host", {
		description: "Serve the Pi LAN host on the local network with the launcher's PIN",
		type: "boolean",
		default: false,
	});

	pi.on("session_start", async (_event, ctx) => {
		if (!pi.getFlag("lan-host")) return;
		if (ctx.mode !== "rpc") {
			process.stderr.write("pi-web-lan: --lan-host requires --mode rpc\n");
			return;
		}
		if (state.running || state.starting) return;
		state.starting = true;
		try {
			const pin = (process.env.PI_WEB_PIN ?? "").trim();
			if (!/^\d{6}$/.test(pin)) {
				throw new Error("PI_WEB_PIN must be exactly six digits (set by serve.sh)");
			}
			const requested = parsePort(process.env.PI_WEB_PORT) ?? DEFAULT_PORT;
			let server: RunningServer;
			let fellBack = false;
			try {
				server = await startServer({ port: requested, host: "0.0.0.0", lanAuth: { pin } });
			} catch (error) {
				if (!isAddrInUse(error)) throw error;
				server = await startServer({ port: 0, host: "0.0.0.0", lanAuth: { pin } });
				fellBack = true;
			}
			state.running = server;
			process.stderr.write(formatStartupMessage(server, requested, fellBack, pin));
		} catch (error) {
			const detail = error instanceof Error ? error.message : String(error);
			process.stderr.write(`pi-web-lan failed to start: ${detail}\n`);
		} finally {
			state.starting = false;
		}
	});

	pi.on("session_shutdown", async () => {
		await stopServer();
	});

	// Best effort if the host exits without session_shutdown.
	const cleanup = (): void => {
		const server = state.running;
		state.running = null;
		if (server) void server.close().catch(() => {});
	};
	process.once("exit", cleanup);
}

async function stopServer(): Promise<void> {
	const server = state.running;
	state.running = null;
	if (server) await server.close().catch(() => {});
}

function parsePort(raw: string | undefined): number | null {
	if (!raw) return null;
	const value = Number(raw);
	return Number.isInteger(value) && value >= 0 && value <= 65535 ? value : null;
}

function isAddrInUse(error: unknown): boolean {
	return (
		typeof error === "object" &&
		error !== null &&
		"code" in error &&
		(error as { code?: unknown }).code === "EADDRINUSE"
	);
}

function formatPin(pin: string): string {
	return `${pin.slice(0, 3)} ${pin.slice(3)}`;
}

function lanUrls(port: number): string[] {
	const urls: string[] = [];
	const seen = new Set<string>();
	for (const entries of Object.values(networkInterfaces())) {
		for (const entry of entries ?? []) {
			if (!entry || entry.internal) continue;
			if (String(entry.family) !== "IPv4") continue;
			if (seen.has(entry.address)) continue;
			seen.add(entry.address);
			urls.push(`http://${entry.address}:${port}`);
		}
	}
	return urls;
}

function formatStartupMessage(
	server: RunningServer,
	requested: number,
	fellBack: boolean,
	pin: string,
): string {
	const busy = fellBack ? ` (requested port ${requested} was busy)` : "";
	const urls = lanUrls(server.port);
	if (urls.length === 0) {
		return (
			`Pi LAN host${busy}: no active LAN IPv4 address detected\n` +
			`Login PIN: ${formatPin(pin)}\n`
		);
	}
	return (
		`Pi LAN host${busy}:\n` +
		`${urls.map((url) => `  ${url}`).join("\n")}\n` +
		`Login PIN: ${formatPin(pin)}\n`
	);
}
