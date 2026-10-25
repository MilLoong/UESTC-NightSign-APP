import http from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { APP_ID, CODE_PATTERN, BrokerError, getLoginCode } from './cdp.mjs';

function reply(response, status, body) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    Pragma: 'no-cache', 'X-Content-Type-Options': 'nosniff',
  });
  response.end(JSON.stringify(body));
}

function authorized(header, token) {
  const actual = Buffer.from(typeof header === 'string' ? header : '');
  const expected = Buffer.from(`Bearer ${token}`);
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    let overflow = false;
    const chunks = [];
    request.on('data', chunk => {
      size += chunk.length;
      if (size > 1024) {
        overflow = true;
        chunks.length = 0;
        reject(new BrokerError('BODY_TOO_LARGE', 413));
      } else if (!overflow) chunks.push(chunk);
    });
    request.on('error', () => reject(new BrokerError('INVALID_JSON', 400)));
    request.on('end', () => {
      if (overflow) return;
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(new BrokerError('INVALID_JSON', 400)); }
    });
  });
}

export function createBroker({ token, provider = getLoginCode, cooldownMs = 3000 }) {
  if (!/^[A-Za-z0-9_-]{43,128}$/.test(token || '')) throw new Error('Invalid pairing token');
  let busy = false;
  let lastCodeAt = -Infinity;
  const server = http.createServer(async (request, response) => {
    if (!authorized(request.headers.authorization, token)) return reply(response, 401, { error: 'UNAUTHORIZED' });
    if (request.headers.origin) return reply(response, 403, { error: 'BROWSER_ORIGIN_DENIED' });
    if (request.method === 'GET' && request.url === '/health') return reply(response, 200, { ok: true, appId: APP_ID });
    if (request.method !== 'POST' || request.url !== '/v1/code') return reply(response, 404, { error: 'NOT_FOUND' });
    if (request.headers['content-type']?.split(';')[0].trim() !== 'application/json') {
      return reply(response, 415, { error: 'JSON_REQUIRED' });
    }
    try {
      const body = await readBody(request);
      if (!body || Array.isArray(body) || Object.keys(body).length !== 1 || body.appId !== APP_ID) {
        throw new BrokerError('INVALID_APP_ID', 400);
      }
      if (busy || Date.now() - lastCodeAt < cooldownMs) throw new BrokerError('BUSY', 429);
      busy = true;
      try {
        const code = await provider();
        if (typeof code !== 'string' || !CODE_PATTERN.test(code)) throw new BrokerError('INVALID_CODE');
        lastCodeAt = Date.now();
        reply(response, 200, { appId: APP_ID, code });
      } finally { busy = false; }
    } catch (error) {
      // Never expose CDP messages, exception details, headers or credentials.
      reply(response, error instanceof BrokerError ? error.status : 503,
        { error: error instanceof BrokerError ? error.code : 'CODE_UNAVAILABLE' });
    }
  });
  server.requestTimeout = 30000;
  server.headersTimeout = 10000;
  server.maxHeadersCount = 32;
  return server;
}

export async function loadToken(file) {
  await mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
  try { await writeFile(file, randomBytes(32).toString('base64url'), { flag: 'wx', mode: 0o600 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  const token = (await readFile(file, 'utf8')).trim();
  if (!/^[A-Za-z0-9_-]{43,128}$/.test(token)) throw new Error('Invalid pairing token file');
  return token;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const tokenFile = path.join(path.dirname(fileURLToPath(import.meta.url)), '.local', 'pairing-token');
  const token = await loadToken(tokenFile);
  const port = Number(process.env.WX_CODE_PORT || 18765);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid port');
  const server = createBroker({ token });
  server.listen(port, '127.0.0.1', () => {
    console.log(`Code broker listening on http://127.0.0.1:${port}`);
    console.log(`Pairing token file: ${tokenFile}`);
  });
  server.on('error', () => { console.error('Code broker could not listen'); process.exitCode = 1; });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
