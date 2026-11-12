// Shared helpers for the Pi LAN host tests: locate the host from
// runtime/connection.json (written by scripts/serve.sh) or explicit flags.
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const TEST_DIR = dirname(fileURLToPath(import.meta.url)); // .../server/tests/lib
export const SERVER_DIR = resolve(TEST_DIR, "../..");
export const DEFAULT_CONNECTION = resolve(SERVER_DIR, "runtime/connection.json");
export const REPO_ROOT = resolve(SERVER_DIR, "../..");
export const DEFAULT_TIMEOUT_MS = 20000;

/**
 * Resolve the host target. Reads connection.json unless both url and pin are
 * given explicitly.
 *
 * @param {{url?: string, pin?: string, connectionPath?: string}} input
 * @returns {{base: URL, pin: string, projectDir: string, connectionPath: string,
 *            connection: object|null}}
 */
export function resolveTarget({ url, pin, connectionPath } = {}) {
	const path = connectionPath ?? process.env.PI_CONNECTION ?? DEFAULT_CONNECTION;
	let connection = null;
	if (!url || !pin) {
		try {
			connection = JSON.parse(readFileSync(path, "utf8"));
		} catch (err) {
			throw new Error(
				`cannot read connection file ${path}: ${err.message}\n` +
					"Start the host with scripts/serve.sh first, or pass --url and --pin.",
			);
		}
	}
	const resolvedUrl = url ?? connection?.url;
	const resolvedPin = String(pin ?? connection?.pin ?? "");
	if (!resolvedUrl) throw new Error(`connection file ${path} has no url`);
	if (!/^\d{6}$/.test(resolvedPin)) {
		throw new Error(
			`PIN must be six digits (got ${resolvedPin.length} characters); check ${path}`,
		);
	}
	const base = new URL(resolvedUrl.endsWith("/") ? resolvedUrl : `${resolvedUrl}/`);
	if (base.protocol !== "http:" && base.protocol !== "https:") {
		throw new Error(`URL must be http(s): ${resolvedUrl}`);
	}
	return {
		base,
		pin: resolvedPin,
		projectDir: connection?.projectDir ?? REPO_ROOT,
		connectionPath: path,
		connection,
	};
}

/** Replace the PIN with a placeholder so logs and reports stay safe. */
export function makeRedactor(pin) {
	return (text) => String(text).replaceAll(pin, "[redacted]");
}
