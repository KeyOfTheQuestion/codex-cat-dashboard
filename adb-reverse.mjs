import { execFile } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { promisify } from 'node:util';

const [adb, initialTransport, deviceSerial, port] = process.argv.slice(2);
const endpoint = `tcp:${port}`;
const exec = promisify(execFile);
let preferredTransport = initialTransport;

async function runAdb(...args) {
  try {
    const { stdout } = await exec(adb, args, { timeout: 3_000 });
    return stdout;
  } catch {
    return null;
  }
}

async function restoreReverse() {
  const output = await runAdb('devices');
  if (output === null) {
    return;
  }
  const transports = output.split('\n').map(line => line.trim().split(/\s+/))
    .filter(([, state]) => state === 'device').map(([transport]) => transport);
  if (transports.includes(preferredTransport)) {
    transports.splice(transports.indexOf(preferredTransport), 1);
    transports.unshift(preferredTransport);
  }
  for (const transport of transports) {
    // The selected phone can reappear with a new wireless address or USB transport.
    const serial = await runAdb('-s', transport, 'shell', 'getprop', 'ro.serialno');
    if (serial?.trim() !== deviceSerial) {
      continue;
    }
    preferredTransport = transport;
    const mappings = await runAdb('-s', transport, 'reverse', '--list');
    if (mappings !== null && !mappings.split('\n').some(line => {
      const [, source, destination] = line.trim().split(/\s+/);
      return source === endpoint && destination === endpoint;
    })) {
      await runAdb('-s', transport, 'reverse', endpoint, endpoint);
    }
    return;
  }
  if (preferredTransport.includes(':') && !transports.includes(preferredTransport)) {
    await runAdb('connect', preferredTransport);
  }
}

while (true) {
  await restoreReverse();
  await delay(5_000);
}
