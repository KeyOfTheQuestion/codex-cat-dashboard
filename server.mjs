import { spawn } from 'node:child_process';
import { timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { scanToday } from './usage.mjs';

const port = Number(process.env.CODEX_DISPLAY_PORT ?? 8765);
const host = process.env.CODEX_DISPLAY_HOST ?? '127.0.0.1';
const accessToken = process.env.CODEX_DISPLAY_TOKEN ?? '';
const loopbackHosts = ['127.0.0.1', '::1', 'localhost'];
if (!loopbackHosts.includes(host) && !accessToken) {
  console.error('通过非回环地址提供用量时，请设置 CODEX_DISPLAY_TOKEN。');
  process.exit(1);
}
if (accessToken && !/^[A-Za-z0-9._~+\/=-]+$/.test(accessToken)) {
  console.error('CODEX_DISPLAY_TOKEN 必须是有效的 Bearer 令牌。');
  process.exit(1);
}
const expectedAuthorization = Buffer.from(`Bearer ${accessToken}`);
const codex = process.env.CODEX_DISPLAY_CLI ?? 'codex';
const codexEnvironment = { ...process.env };
delete codexEnvironment.CODEX_DISPLAY_TOKEN;
const codexHome = process.env.CODEX_HOME ?? join(homedir(), '.codex');
const cache = new Map();
const [html, reminders] = await Promise.all([
  readFile(new URL('./index.html', import.meta.url)),
  readFile(new URL('./reminders.mjs', import.meta.url)),
]);

function readAccountLimits() {
  return new Promise((resolve, reject) => {
    const child = spawn(codex, ['app-server', '--stdio'], {
      stdio: ['pipe', 'pipe', 'ignore'],
      env: codexEnvironment,
    });
    const lines = createInterface({ input: child.stdout });
    const send = message => child.stdin.write(JSON.stringify(message) + '\n');
    const timeout = setTimeout(() => finish(new Error('额度查询超时')), 15_000);
    let finished = false;

    function finish(error, limits) {
      if (finished) {
        return;
      }
      finished = true;
      clearTimeout(timeout);
      lines.close();
      child.kill();
      if (error) {
        reject(error);
      } else {
        resolve(limits);
      }
    }

    child.once('error', error => finish(error));
    child.once('close', () => finish(new Error('Codex App Server 退出')));
    child.stdin.on('error', error => finish(error));
    lines.on('line', line => {
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        return;
      }
      if (message.id === 0 && message.result) {
        send({ method: 'initialized' });
        send({ id: 1, method: 'account/rateLimits/read' });
      }
      if (message.id === 0 && message.error) {
        finish(new Error('Codex App Server 初始化失败'));
      }
      if (message.id === 1) {
        const limits = message.result?.rateLimitsByLimitId?.codex ?? message.result?.rateLimits;
        if (limits && (limits.limitId == null || limits.limitId === 'codex')) {
          finish(null, limits);
        } else {
          finish(new Error('Codex 未返回账户额度'));
        }
      }
    });
    send({ id: 0, method: 'initialize', params: {
      clientInfo: { name: 'codex_usage_display', version: '1.0.0' },
    } });
  });
}

let quota = null;
let tokenUsage = null;
let quotaError = false;
let tokenError = false;

async function refreshQuota() {
  try {
    const limits = await readAccountLimits();
    const windows = [limits.primary, limits.secondary];
    function windowFor(minutes) {
      const window = windows.find(item => item?.windowDurationMins === minutes);
      if (!Number.isFinite(window?.usedPercent)) {
        return null;
      }
      return {
        remaining: Math.round(Math.max(0, Math.min(100, 100 - window.usedPercent)) * 10) / 10,
        resetsAt: Number.isFinite(window.resetsAt) ? window.resetsAt * 1000 : null,
      };
    }
    quota = { fiveHours: windowFor(300), weekly: windowFor(10080), updatedAt: Date.now() };
    quotaError = false;
  } catch (error) {
    quotaError = true;
    console.error(new Date().toISOString(), '额度查询失败', error.message);
  }
  setTimeout(refreshQuota, 30_000);
}

async function refreshTokens() {
  try {
    tokenUsage = await scanToday(codexHome, new Date(), cache);
    tokenError = false;
  } catch (error) {
    tokenError = true;
    console.error(new Date().toISOString(), '本机 token 汇总失败', error.code ?? error.name);
  }
  setTimeout(refreshTokens, 5_000);
}

const server = createServer((req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cross-Origin-Resource-Policy', 'same-origin');

  const activePort = server.address().port;
  const localNames = ['127.0.0.1', '[::1]', 'localhost'];
  const localHost = localNames.some(name => {
    return req.headers.host === `${name}:${activePort}` || (activePort === 80 && req.headers.host === name);
  });
  const local = localHost && ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
  if (req.method === 'GET' && req.url === '/api/usage') {
    if (accessToken) {
      const provided = Buffer.from(req.headers.authorization ?? '');
      if (provided.length !== expectedAuthorization.length || !timingSafeEqual(provided, expectedAuthorization)) {
        res.writeHead(401, { 'WWW-Authenticate': 'Bearer' }).end();
        return;
      }
    } else if (!local) {
      res.writeHead(403).end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      .end(JSON.stringify({ quota, tokenUsage, quotaError, tokenError }));
  } else if (!local) {
    res.writeHead(403).end();
  } else if (req.method === 'GET' && req.url === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(html);
  } else if (req.method === 'GET' && req.url === '/reminders.mjs') {
    res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8' }).end(reminders);
  } else {
    res.writeHead(404).end();
  }
});

server.listen(port, host, () => {
  const urlHost = host.includes(':') ? `[${host}]` : host;
  console.log(`Codex 用量看板 http://${urlHost}:${server.address().port}/`);
  void refreshQuota();
  void refreshTokens();
});
