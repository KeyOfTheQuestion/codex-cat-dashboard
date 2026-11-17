import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { request } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const program = fileURLToPath(new URL('./server.mjs', import.meta.url));

async function start(t, bind, token) {
  const directory = await mkdtemp(join(tmpdir(), 'codex-cat-server-'));
  const child = spawn(process.execPath, [program], {
    env: {
      ...process.env,
      CODEX_DISPLAY_PORT: '0',
      CODEX_DISPLAY_HOST: bind,
      CODEX_DISPLAY_TOKEN: token,
      CODEX_DISPLAY_CLI: join(directory, 'missing-codex'),
      CODEX_HOME: directory,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let errors = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', chunk => { errors += chunk; });
  const closed = once(child, 'close');
  t.after(async () => {
    child.kill();
    await closed;
    await rm(directory, { recursive: true, force: true });
  });

  const lines = createInterface({ input: child.stdout });
  return Promise.race([
    once(lines, 'line').then(([line]) => ({ port: Number(/:(\d+)\//.exec(line)?.[1]) })),
    closed.then(([code]) => ({ code, errors })),
  ]);
}

function get(port, path, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = request({ hostname: '127.0.0.1', port, path, headers }, res => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, body }));
    });
    req.on('error', reject);
    req.end();
  });
}

test('loopback defaults work and reject a remote Host without a token', { timeout: 5000 }, async t => {
  const { port } = await start(t, '127.0.0.1', '');
  assert.equal((await get(port, '/')).status, 200);
  assert.equal((await get(port, '/reminders.mjs')).status, 200);
  assert.equal((await get(port, '/api/usage')).status, 200);
  assert.equal((await get(port, '/api/usage', { Host: 'untrusted.example' })).status, 403);
});

test('a non-loopback bind fails closed without a token', { timeout: 5000 }, async t => {
  const result = await start(t, '0.0.0.0', '');
  assert.equal(result.code, 1);
  assert.match(result.errors, /CODEX_DISPLAY_TOKEN/);
});

test('a reverse proxy and direct clients must authenticate for the usage endpoint', { timeout: 5000 }, async t => {
  const token = 'test-only-not-a-real-secret';
  const { port } = await start(t, '0.0.0.0', token);
  assert.equal((await get(port, '/api/usage')).status, 401);
  assert.equal((await get(port, '/api/usage', { Authorization: 'Bearer incorrect' })).status, 401);
  const response = await get(port, '/api/usage', {
    Host: 'usage.example.test',
    Authorization: `Bearer ${token}`,
  });
  assert.equal(response.status, 200);
  assert.deepEqual(Object.keys(JSON.parse(response.body)).sort(), ['quota', 'quotaError', 'tokenError', 'tokenUsage']);
  assert.equal((await get(port, '/', { Host: 'usage.example.test', Authorization: `Bearer ${token}` })).status, 403);
});
