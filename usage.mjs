import { createReadStream } from 'node:fs';
import { readdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { createInterface } from 'node:readline';

const dateFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
});

async function listSessions(directory) {
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') {
      return [];
    }
    throw error;
  }

  const paths = await Promise.all(entries.map(async entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      return listSessions(path);
    }
    return entry.isFile() && entry.name.endsWith('.jsonl') ? [path] : [];
  }));
  return paths.flat();
}

async function readEvents(path, start, end) {
  const events = new Map();
  let previous = 0;
  const lines = createInterface({ input: createReadStream(path), crlfDelay: Infinity });

  for await (const line of lines) {
    if (!line.includes('"token_count"')) {
      continue;
    }
    let event;
    try {
      event = JSON.parse(line);
    } catch {
      // The writer can be appending the last line while we are reading.
      continue;
    }
    if (event.type !== 'event_msg' || event.payload?.type !== 'token_count') {
      continue;
    }
    const usage = event.payload.info;
    const total = usage?.total_token_usage?.total_tokens;
    if (!Number.isSafeInteger(total) || total < 0 || total === previous) {
      continue;
    }
    const last = usage.last_token_usage?.total_tokens;
    const increment = total > previous ? total - previous : last;
    previous = total;
    const timestamp = Date.parse(event.timestamp);
    if (timestamp >= start && timestamp < end && Number.isSafeInteger(increment) && increment > 0) {
      events.set(`${timestamp}/${total}/${increment}`, increment);
    }
  }
  return events;
}

export async function scanToday(home, now = new Date(), cache = new Map()) {
  const day = dateFormat.format(now);
  const start = Date.parse(`${day}T00:00:00+08:00`);
  const end = start + 86_400_000;
  const roots = await Promise.all([
    listSessions(join(home, 'sessions')),
    listSessions(join(home, 'archived_sessions')),
  ]);
  const activeFiles = new Set();
  const events = new Map();

  for (const path of roots.flat()) {
    try {
      const file = await stat(path);
      if (file.mtimeMs < start) {
        continue;
      }
      activeFiles.add(path);
      const version = `${day}/${file.mtimeMs}/${file.size}`;
      let recorded = cache.get(path);
      if (recorded?.version !== version) {
        recorded = { version, events: await readEvents(path, start, end) };
        cache.set(path, recorded);
      }
      for (const [key, value] of recorded.events) {
        events.set(key, value);
      }
    } catch (error) {
      if (error.code !== 'ENOENT') {
        throw error;
      }
    }
  }
  for (const path of cache.keys()) {
    if (!activeFiles.has(path)) {
      cache.delete(path);
    }
  }
  return {
    day,
    tokens: [...events.values()].reduce((sum, count) => sum + count, 0),
    updatedAt: Date.now(),
  };
}
