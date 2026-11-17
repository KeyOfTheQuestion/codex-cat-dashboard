import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { scanToday } from './usage.mjs';

test('北京时间午夜只累计当日增量，重复事件和复制的会话不重复计数', async () => {
  const home = await mkdtemp(join(tmpdir(), 'codex-display-test-'));
  try {
    const sessionDir = join(home, 'sessions', '2026', '11', '16');
    const archiveDir = join(home, 'archived_sessions');
    await Promise.all([mkdir(sessionDir, { recursive: true }), mkdir(archiveDir)]);
    const event = (timestamp, total, last) => JSON.stringify({
      type: 'event_msg', timestamp,
      payload: { type: 'token_count', info: {
        total_token_usage: { total_tokens: total },
        last_token_usage: { total_tokens: last },
      } },
    });
    const before = event('2026-11-16T15:59:59Z', 1000, 1000);
    const after = event('2026-11-16T16:00:00Z', 1200, 200);
    const repeated = event('2026-11-16T16:00:01Z', 1200, 200);
    const next = event('2026-11-17T15:59:59Z', 50, 50);
    const tomorrow = event('2026-11-17T16:00:00Z', 500, 450);
    await writeFile(join(sessionDir, 'active.jsonl'), [before, after, repeated, next, tomorrow].join('\n'));
    await writeFile(join(archiveDir, 'copy.jsonl'), [before, after].join('\n'));
    const result = await scanToday(home, new Date('2026-11-17T15:59:59Z'));
    assert.equal(result.day, '2026-11-17');
    assert.equal(result.tokens, 250);
  } finally {
    await rm(home, { recursive: true, force: true });
  }
});
