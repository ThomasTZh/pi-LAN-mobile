// Minimal dependency-free WebSocket client (RFC 6455, text frames) used by the
// Pi LAN host contract tests. Narrow on purpose: one text frame per protocol
// record, client-side masking, ping/pong and close handling, no extensions.
import { createHash, randomBytes } from "node:crypto";
import { request } from "node:http";

export const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

const OP_CONT = 0x0;
const OP_TEXT = 0x1;
const OP_BINARY = 0x2;
const OP_CLOSE = 0x8;
const OP_PING = 0x9;
const OP_PONG = 0xa;

function endpoint(url) {
	const parsed = new URL(url);
	const secure = parsed.protocol === "https:";
	return {
		host: parsed.hostname,
		port: Number(parsed.port || (secure ? 443 : 80)),
		path: parsed.pathname && parsed.pathname !== "/" ? parsed.pathname : "/",
	};
}

/**
 * Perform the opening handshake.
 * Resolves { upgraded: true, client } on 101, or { upgraded: false, status } for
 * any HTTP rejection (the caller decides whether that status is expected).
 */
export function wsHandshake({ url, cookie, path = "/", timeoutMs = 15000 }) {
	const { host, port } = endpoint(url);
	return new Promise((resolve, reject) => {
		const key = randomBytes(16).toString("base64");
		const headers = {
			Connection: "Upgrade",
			Upgrade: "websocket",
			"Sec-WebSocket-Key": key,
			"Sec-WebSocket-Version": "13",
		};
		if (cookie) headers.Cookie = cookie;
		const req = request({ host, port, path, method: "GET", headers });
		const timer = setTimeout(() => {
			req.destroy(new Error(`WebSocket handshake timed out after ${timeoutMs} ms`));
		}, timeoutMs);
		const settle = (fn, value) => {
			clearTimeout(timer);
			fn(value);
		};
		req.on("upgrade", (res, socket, head) => {
			const expected = createHash("sha1")
				.update(key + WS_GUID)
				.digest("base64");
			if (res.headers["sec-websocket-accept"] !== expected) {
				socket.destroy();
				settle(reject, new Error("WebSocket handshake returned a bad Sec-WebSocket-Accept"));
				return;
			}
			settle(resolve, { upgraded: true, client: new WsClient(socket, head) });
		});
		req.on("response", (res) => {
			res.resume();
			// 401 is written and the socket closed without a body read; the
			// response event still carries the status line.
			settle(resolve, { upgraded: false, status: res.statusCode });
		});
		req.on("error", (err) => settle(reject, err));
		req.end();
	});
}

/** Status code of an upgrade attempt (101 when it actually upgraded). */
export async function wsUpgradeStatus({ url, cookie, path = "/", timeoutMs = 15000 }) {
	const result = await wsHandshake({ url, cookie, path, timeoutMs });
	if (!result.upgraded) return result.status;
	result.client.destroy();
	return 101;
}

function encodeTextFrame(text) {
	const payload = Buffer.from(text, "utf8");
	const length = payload.length;
	let header;
	if (length < 126) {
		header = Buffer.alloc(2);
		header[1] = 0x80 | length;
	} else if (length < 65536) {
		header = Buffer.alloc(4);
		header[1] = 0x80 | 126;
		header.writeUInt16BE(length, 2);
	} else {
		header = Buffer.alloc(10);
		header[1] = 0x80 | 127;
		header.writeBigUInt64BE(BigInt(length), 2);
	}
	header[0] = 0x80 | OP_TEXT;
	const mask = randomBytes(4);
	const masked = Buffer.allocUnsafe(length);
	for (let i = 0; i < length; i++) masked[i] = payload[i] ^ mask[i & 3];
	return Buffer.concat([header, mask, masked]);
}

function encodeControlFrame(opcode, payload = Buffer.alloc(0)) {
	const mask = randomBytes(4);
	const masked = Buffer.allocUnsafe(payload.length);
	for (let i = 0; i < payload.length; i++) masked[i] = payload[i] ^ mask[i & 3];
	const header = Buffer.alloc(2);
	header[0] = 0x80 | opcode;
	header[1] = 0x80 | payload.length;
	return Buffer.concat([header, mask, masked]);
}

export class WsClient {
	#socket;
	#buffer = Buffer.alloc(0);
	#fragments = [];
	#fragmentOpcode = 0;
	#pending = [];
	#waiters = [];
	#closed = false;
	#closeError = null;
	#all = [];

	constructor(socket, head) {
		this.#socket = socket;
		socket.on("data", (chunk) => this.#feed(chunk));
		socket.on("error", (err) => this.#fail(err));
		socket.on("close", () => this.#fail(new Error("WebSocket closed by peer")));
		if (head && head.length > 0) this.#feed(head);
	}

	/** Every protocol record received so far, in arrival order. */
	get records() {
		return this.#all;
	}

	send(record) {
		if (this.#closed) throw new Error("WebSocket is closed");
		this.#socket.write(encodeTextFrame(JSON.stringify(record)));
	}

	/**
	 * Resolve with the first received record matching `predicate` (buffered
	 * records included), rejecting after `timeoutMs`.
	 */
	expect(predicate, label, timeoutMs = 15000) {
		const index = this.#pending.findIndex(predicate);
		if (index !== -1) {
			const [record] = this.#pending.splice(index, 1);
			return Promise.resolve(record);
		}
		if (this.#closeError) return Promise.reject(this.#closeError);
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => {
				const seen = this.#all.map((r) => r?.type ?? typeof r).join(", ");
				reject(
					new Error(
						`timed out after ${timeoutMs} ms waiting for ${label}` +
							(seen ? ` (received types: ${seen})` : ""),
					),
				);
				const at = this.#waiters.indexOf(waiter);
				if (at !== -1) this.#waiters.splice(at, 1);
			}, timeoutMs);
			const waiter = { predicate, resolve, reject, timer, label };
			this.#waiters.push(waiter);
		});
	}

	close(code = 1000, reason = "done") {
		if (this.#closed) return;
		const payload = Buffer.alloc(2 + Buffer.byteLength(reason));
		payload.writeUInt16BE(code, 0);
		payload.write(reason, 2, "utf8");
		try {
			this.#socket.write(encodeControlFrame(OP_CLOSE, payload));
		} catch {
			/* already gone */
		}
		this.destroy();
	}

	destroy() {
		if (!this.#closed) {
			this.#closed = true;
			this.#socket.destroy();
		}
	}

	#fail(error) {
		if (this.#closed) return;
		this.#closed = true;
		this.#closeError = error;
		for (const waiter of this.#waiters.splice(0)) {
			clearTimeout(waiter.timer);
			waiter.reject(error);
		}
	}

	#deliver(record) {
		this.#all.push(record);
		for (let i = 0; i < this.#waiters.length; i++) {
			const waiter = this.#waiters[i];
			let matches = false;
			try {
				matches = waiter.predicate(record);
			} catch {
				matches = false;
			}
			if (matches) {
				this.#waiters.splice(i, 1);
				clearTimeout(waiter.timer);
				waiter.resolve(record);
				return;
			}
		}
		this.#pending.push(record);
	}

	#feed(chunk) {
		this.#buffer = Buffer.concat([this.#buffer, chunk]);
		for (;;) {
			const buf = this.#buffer;
			if (buf.length < 2) return;
			const fin = (buf[0] & 0x80) !== 0;
			const opcode = buf[0] & 0x0f;
			const masked = (buf[1] & 0x80) !== 0;
			let length = buf[1] & 0x7f;
			let offset = 2;
			if (length === 126) {
				if (buf.length < 4) return;
				length = buf.readUInt16BE(2);
				offset = 4;
			} else if (length === 127) {
				if (buf.length < 10) return;
				length = Number(buf.readBigUInt64BE(2));
				offset = 10;
			}
			let maskKey = null;
			if (masked) {
				if (buf.length < offset + 4) return;
				maskKey = Buffer.from(buf.subarray(offset, offset + 4));
				offset += 4;
			}
			if (buf.length < offset + length) return;
			const payload = Buffer.from(buf.subarray(offset, offset + length));
			if (maskKey) {
				for (let i = 0; i < payload.length; i++) payload[i] ^= maskKey[i & 3];
			}
			this.#buffer = buf.subarray(offset + length);

			if (opcode === OP_CLOSE) {
				this.#fail(new Error("WebSocket closed by peer"));
				return;
			}
			if (opcode === OP_PING) {
				try {
					this.#socket.write(encodeControlFrame(OP_PONG, payload));
				} catch {
					/* ignore */
				}
				continue;
			}
			if (opcode === OP_PONG) continue;

			if (opcode === OP_TEXT || opcode === OP_BINARY) {
				if (fin) {
					this.#deliver(this.#decode(payload));
				} else {
					this.#fragmentOpcode = opcode;
					this.#fragments = [payload];
				}
				continue;
			}
			if (opcode === OP_CONT) {
				this.#fragments.push(payload);
				if (fin) {
					const joined = Buffer.concat(this.#fragments);
					this.#fragments = [];
					this.#fragmentOpcode = 0;
					this.#deliver(this.#decode(joined));
				}
				continue;
			}
			// Unknown opcode: ignore.
		}
	}

	#decode(payload) {
		const text = payload.toString("utf8");
		try {
			return JSON.parse(text);
		} catch {
			return { type: "__unparsed__", text };
		}
	}
}
