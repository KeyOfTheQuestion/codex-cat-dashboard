import { createServer } from 'node:http';
import { createVisit } from './cat-visit.mjs';

const port = 8767;
const visit = createVisit();
createServer((request, response) => {
  response.setHeader('Cache-Control', 'no-store');
  response.setHeader('X-Content-Type-Options', 'nosniff');
  if (request.headers.host !== `127.0.0.1:${port}`) {
    response.writeHead(403).end();
    return;
  }
  if (!request.url?.startsWith('/')) {
    response.writeHead(400).end();
    return;
  }
  const url = new URL(`http://127.0.0.1:${port}${request.url}`);
  if (request.method === 'GET' && url.pathname === '/health') {
    response.writeHead(200).end('cat-visit-v3');
    return;
  }
  if (request.method !== 'POST' || url.pathname !== '/api/cat-visit') {
    response.writeHead(404).end();
    return;
  }
  if (request.headers['x-cat-visit'] !== '1') {
    response.writeHead(403).end();
    return;
  }
  const role = url.searchParams.get('role');
  const id = url.searchParams.get('id');
  if ((role !== 'home' && role !== 'nest') || !/^[a-f0-9]{32}$/.test(id ?? '')) {
    response.writeHead(400).end();
    return;
  }

  const result = visit.exchange(role, id, {
    side: url.searchParams.get('side') === 'left' ? 'left' : 'right',
    call: url.searchParams.get('call') === '1',
    hold: url.searchParams.get('hold') === '1',
  });
  if (!result) {
    response.writeHead(409).end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify(result));
}).listen(port, '127.0.0.1', () => {
  console.log(`双屏串门服务 http://127.0.0.1:${port}/`);
});
