import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { lstat, readFile } from 'node:fs/promises';
import { createConnection } from 'node:net';
import { homedir } from 'node:os';
import { isAbsolute, join } from 'node:path';

function creditNumber(value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !value.trim())) {
    throw new Error('Missing WorkBuddy credit amount');
  }
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount < 0) {
    throw new Error('Invalid WorkBuddy credit amount');
  }
  return amount;
}

export function summarizeWorkBuddyCredits(response) {
  const data = response?.data;
  if (response?.code !== 0 || !Array.isArray(data?.Packages)) {
    throw new Error('WorkBuddy did not return a credit summary');
  }
  let used = 0;
  for (const creditPackage of data.Packages) {
    if (creditPackage?.CapacityUnit !== 'credits') {
      throw new Error('Unexpected WorkBuddy credit unit');
    }
    used += creditNumber(creditPackage.CycleUsedCapacity);
  }
  return { remaining: creditNumber(data.TotalCredits), used: creditNumber(used) };
}

async function* frames(socket) {
  let buffer = '';
  socket.setEncoding('utf8');
  for await (const chunk of socket) {
    buffer += chunk;
    if (buffer.length > 1_000_000) {
      throw new Error('WorkBuddy IPC response too large');
    }
    let newline = buffer.indexOf('\n');
    while (newline !== -1) {
      const line = buffer.slice(0, newline);
      buffer = buffer.slice(newline + 1);
      yield JSON.parse(line);
      newline = buffer.indexOf('\n');
    }
  }
}

export async function readWorkBuddyCredits() {
  const config = process.env.WORKBUDDY_CONFIG_DIR?.trim() || join(homedir(), '.workbuddy');
  const discovery = join(config, 'wbipc', 'endpoint.json');
  const discoveryInfo = await lstat(discovery);
  const uid = process.getuid?.();
  if (!discoveryInfo.isFile() || discoveryInfo.uid !== uid || (discoveryInfo.mode & 0o077) !== 0) {
    throw new Error('WorkBuddy IPC discovery file is not private');
  }
  const { endpoint, ticket } = JSON.parse(await readFile(discovery, 'utf8'));
  if (typeof endpoint !== 'string' || !isAbsolute(endpoint) || typeof ticket !== 'string' || !ticket) {
    throw new Error('Invalid WorkBuddy IPC discovery file');
  }
  const socketInfo = await lstat(endpoint);
  if (!socketInfo.isSocket() || socketInfo.uid !== uid || (socketInfo.mode & 0o077) !== 0) {
    throw new Error('WorkBuddy IPC socket is not private');
  }

  const socket = createConnection({ path: endpoint });
  const messages = frames(socket);
  const timeout = setTimeout(() => socket.destroy(new Error('WorkBuddy IPC timed out')), 8_000);
  async function exchange(message) {
    socket.write(JSON.stringify(message) + '\n');
    const { value } = await messages.next();
    if (!value || value.error || (message.id !== undefined && value.id !== message.id)) {
      throw new Error('WorkBuddy IPC request failed');
    }
    return value;
  }

  try {
    const nonce = randomBytes(24).toString('base64url');
    const challenge = await exchange({
      type: 'session_hello',
      protocol_min: 1,
      protocol_max: 1,
      ticket_id: createHash('sha256').update(ticket).digest('hex').slice(0, 16),
      client_nonce: nonce,
      client: { kind: 'local', id: 'codex-cat-dashboard' },
    });
    if (challenge.type !== 'session_challenge' || challenge.protocol !== 1
        || typeof challenge.server_nonce !== 'string' || typeof challenge.server_proof !== 'string') {
      throw new Error('WorkBuddy IPC handshake failed');
    }
    function proof(role) {
      const digest = createHmac('sha256', ticket);
      for (const part of [role, '1', endpoint, nonce, challenge.server_nonce]) {
        const content = Buffer.from(part);
        const size = Buffer.alloc(4);
        size.writeUInt32BE(content.length);
        digest.update(size).update(content);
      }
      return digest.digest('base64url');
    }
    const expected = Buffer.from(proof('wbipc-s'));
    const actual = Buffer.from(challenge.server_proof);
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
      throw new Error('WorkBuddy IPC could not authenticate the desktop app');
    }
    const ready = await exchange({ type: 'session_prove', client_proof: proof('wbipc-c') });
    if (ready.type !== 'session_hello_ack' || !ready.pipes?.includes('wb.request')) {
      throw new Error('WorkBuddy is not logged in');
    }
    const pipe = await exchange({
      jsonrpc: '2.0', id: 1, method: 'broker/GetPipe', params: { pipe: 'wb.request' },
    });
    const channel = pipe.result?.channel;
    if (typeof channel !== 'string') {
      throw new Error('WorkBuddy request pipe is unavailable');
    }
    const { result } = await exchange({
      jsonrpc: '2.0',
      id: 2,
      method: channel + '/http.fetch',
      mode: 'call',
      params: {
        method: 'POST',
        path: '/billing/meter/get-user-resource-summary',
        headers: { 'content-type': 'application/json' },
        body_b64: Buffer.from('{}').toString('base64'),
      },
    });
    if (result?.status !== 200 || typeof result.body_b64 !== 'string') {
      throw new Error('WorkBuddy credit summary is unavailable');
    }
    return summarizeWorkBuddyCredits(JSON.parse(Buffer.from(result.body_b64, 'base64').toString('utf8')));
  } finally {
    clearTimeout(timeout);
    socket.destroy();
    await messages.return();
  }
}
