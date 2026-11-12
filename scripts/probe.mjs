#!/usr/bin/env node
/*
 * scripts/probe.mjs — CLI contract probe for the Pi LAN host.
 *
 * No dependencies, Node 22. Reads server/runtime/connection.json by
 * default (or --url/--pin), logs in, opens the WebSocket, sends hello, prints
 * the child pid, queries get_state and counts sessions.list rows.
 *
 * The PIN and the session cookie are never printed. Exit codes:
 *   0 success, 1 protocol/network failure, 2 missing configuration.
 *
 * Usage:
 *   node scripts/probe.mjs
 *   node scripts/probe.mjs --connection server/runtime/connection.json
 *   node scripts/probe.mjs --url http://192.168.1.50:7690 --pin "123 456"
 *   node scripts/probe.mjs --url ... --pin ... --cwd /home/user/project
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_CONNECTION = path.resolve(HERE, '..', 'server', 'runtime', 'connection.json');
const COOKIE_NAME = 'pi_rpc_web_session';

function usage(code = 0) {
  const text = [
    'Usage: node scripts/probe.mjs [--url URL] [--pin PIN] [--cwd DIR] [--connection FILE] [--timeout MS]',
    '',
    'Without --url/--pin it reads server/runtime/connection.json.',
    'Prints: login status, child pid, ping pong, model, sessions.list count.',
  ].join('\n');
  (code === 0 ? console.log : console.error)(text);
  process.exit(code);
}

function parseArgs(argv) {
  const args = { connection: DEFAULT_CONNECTION, timeoutMs: 15_000 };
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    const value = () => {
      const next = argv[index + 1];
      if (next === undefined) usage(2);
      index += 1;
      return next;
    };
    switch (token) {
      case '--url': args.url = value(); break;
      case '--pin': args.pin = value(); break;
      case '--cwd': args.cwd = value(); break;
      case '--connection': args.connection = value(); break;
      case '--timeout': {
        const parsed = Number(value());
        if (!Number.isFinite(parsed) || parsed <= 0) usage(2);
        args.timeoutMs = parsed;
        break;
      }
      case '--help':
      case '-h': usage(0); break;
      default:
        console.error(`Unknown argument: ${token}`);
        usage(2);
    }
  }
  return args;
}

function loadConnection(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    console.error(`No connection file at ${file} (${error.code || error.message}).`);
    console.error('Start the host first: server/scripts/serve.sh');
    return null;
  }
}

function normalizeBase(raw) {
  const trimmed = String(raw).trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(trimmed)) return { error: `URL must start with http:// or https:// (got ${trimmed})` };
  if (/\s/.test(trimmed)) return { error: 'URL contains whitespace' };
  return { base: trimmed };
}

function parseCookie(headers) {
  const values = typeof headers.getSetCookie === 'function'
    ? headers.getSetCookie()
    : [headers.get('set-cookie')].filter(Boolean);
  for (const value of values) {
    const pair = String(value).split(';')[0].trim();
    if (pair.startsWith(`${COOKIE_NAME}=`)) {
      const secret = pair.slice(COOKIE_NAME.length + 1);
      if (secret) return secret;
    }
  }
  return null;
}

function makeInbox(ws) {
  const items = [];
  const waiters = new Set();
  ws.addEventListener('message', (event) => {
    let record;
    try {
      record = JSON.parse(event.data);
    } catch {
      return;
    }
    for (const waiter of waiters) {
      if (waiter.predicate(record)) {
        waiters.delete(waiter);
        clearTimeout(waiter.timer);
        waiter.resolve(record);
        return;
      }
    }
    items.push(record);
  });
  return function next(predicate, label, timeoutMs) {
    const index = items.findIndex(predicate);
    if (index >= 0) return Promise.resolve(items.splice(index, 1)[0]);
    return new Promise((resolve, reject) => {
      const waiter = {
        predicate,
        resolve,
        timer: setTimeout(() => {
          waiters.delete(waiter);
          reject(new Error(`timeout after ${timeoutMs} ms waiting for ${label}`));
        }, timeoutMs),
      };
      waiters.add(waiter);
    });
  };
}

function openSocket(url, cookie, timeoutMs) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers: { Cookie: `${COOKIE_NAME}=${cookie}` } });
    const timer = setTimeout(() => {
      try {
        ws.close();
      } catch {
        /* ignore */
      }
      reject(new Error(`timeout after ${timeoutMs} ms opening the WebSocket`));
    }, timeoutMs);
    ws.addEventListener('open', () => {
      clearTimeout(timer);
      resolve(ws);
    });
    ws.addEventListener('error', () => {
      clearTimeout(timer);
      reject(new Error('WebSocket upgrade failed (stale cookie or not a Pi host?)'));
    });
  });
}

function logStep(name, detail) {
  console.log(`${name}: ${detail}`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  let url = args.url;
  let pin = args.pin;
  let cwd = args.cwd;

  if (!url || !pin) {
    const connection = loadConnection(args.connection);
    if (!connection) process.exit(2);
    url = url || connection.url;
    pin = pin || connection.pin;
    cwd = cwd || connection.projectDir;
  }
  if (!url || !pin) {
    console.error('Both a URL and a PIN are required (or a connection.json with both).');
    process.exit(2);
  }

  const normalized = normalizeBase(url);
  if (normalized.error) {
    console.error(normalized.error);
    process.exit(2);
  }
  const base = normalized.base;
  const digits = String(pin).replace(/\s+/g, '');

  logStep('host', base);
  logStep('pin', `${digits.length} digits`);

  const loginResponse = await fetch(`${base}/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'text/html',
      'Cache-Control': 'no-cache',
    },
    body: new URLSearchParams({ pin: digits }),
    redirect: 'manual',
    signal: AbortSignal.timeout(args.timeoutMs),
  });
  if (loginResponse.status < 300 || loginResponse.status >= 400) {
    throw new Error(`login rejected the PIN (HTTP ${loginResponse.status})`);
  }
  const cookie = parseCookie(loginResponse.headers);
  if (!cookie) throw new Error(`login returned HTTP ${loginResponse.status} without a session cookie`);
  logStep('login', `HTTP ${loginResponse.status}, cookie ${cookie.length} chars`);

  const wsUrl = `${base.replace(/^http/i, 'ws')}/`;
  const ws = await openSocket(wsUrl, cookie, args.timeoutMs);
  const next = makeInbox(ws);
  try {
    const hello = { type: 'hello', extraArgs: ['--no-mcp'] };
    if (cwd) hello.cwd = cwd;
    ws.send(JSON.stringify(hello));
    const helloReply = await next((record) => record.type === 'hello', 'hello reply', args.timeoutMs);
    if (!helloReply.ok) throw new Error(`hello failed: ${helloReply.error || 'unknown error'}`);
    logStep('hello', `ok, child pid ${helloReply.pid ?? 'n/a'}`);

    ws.send(JSON.stringify({ type: 'meta', id: 'probe-ping', action: 'ping' }));
    const pong = await next((record) => record.type === 'meta' && record.id === 'probe-ping', 'meta ping', args.timeoutMs);
    logStep('ping', pong.ok ? `pong=${Boolean(pong.data && pong.data.pong)}` : `failed: ${pong.error || 'unknown'}`);

    ws.send(JSON.stringify({ type: 'get_state' }));
    const state = await next((record) => record.type === 'response' && record.command === 'get_state', 'get_state', args.timeoutMs);
    if (!state.success) throw new Error(`get_state failed: ${JSON.stringify(state.error)}`);
    const model = state.data && (state.data.model && (state.data.model.id || state.data.model) || state.data.modelId);
    logStep('model', model ? String(model) : 'none reported');

    ws.send(JSON.stringify({ type: 'meta', id: 'probe-sessions', action: 'sessions.list' }));
    const sessions = await next(
      (record) => record.type === 'meta' && record.id === 'probe-sessions',
      'sessions.list',
      args.timeoutMs,
    );
    if (!sessions.ok) throw new Error(`sessions.list failed: ${sessions.error || 'unknown'}`);
    const rows = Array.isArray(sessions.data)
      ? sessions.data
      : (sessions.data && sessions.data.projects
        ? sessions.data.projects.flatMap((project) => (Array.isArray(project.sessions) ? project.sessions : []))
        : (sessions.data && (sessions.data.sessions || sessions.data.rows)) || []);
    logStep('sessions', `${rows.length} listed`);
    if (rows.length > 0) logStep('newest session', rows[0].name || rows[0].firstPrompt || rows[0].path || 'unnamed');

    console.log('probe: ok');
    ws.close(1000, 'probe done');
    process.exit(0);
  } catch (error) {
    try {
      ws.close();
    } catch {
      /* ignore */
    }
    throw error;
  }
}

main().catch((error) => {
  console.error(`probe: failed — ${error.message}`);
  process.exit(1);
});
