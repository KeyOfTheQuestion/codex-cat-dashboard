import assert from 'node:assert/strict';
import test from 'node:test';

import * as reminders from './reminders.mjs';

const at = value => Date.parse(`${value}:00+08:00`);

test('喝水从设置时刻开始，确认后走到下一个时段，当天漏掉的时段只显示最近一次', () => {
  const plan = reminders.defaultReminders(at('2026-11-17T10:30'));
  assert.deepEqual(reminders.reminderStatus(plan, at('2026-11-17T10:30')).due, []);
  assert.equal(reminders.reminderStatus(plan, at('2026-11-17T10:30')).next.at, at('2026-11-17T11:00'));
  const { due } = reminders.reminderStatus(plan, at('2026-11-17T11:00'));
  assert.equal(due[0].title, '喝水');
  reminders.acknowledge(plan, due[0]);
  assert.deepEqual(reminders.reminderStatus(plan, at('2026-11-17T11:01')).due, []);
  const evening = reminders.reminderStatus(plan, at('2026-11-17T22:00'));
  assert.equal(evening.due[0].at, at('2026-11-17T19:00'));
  assert.equal(evening.next.at, at('2026-11-18T09:00'));
  assert.deepEqual(reminders.reminderStatus(plan, at('2026-11-18T08:59')).due, []);
});

test('单次待办跨天仍待确认，确认后移除，每日待办只确认当次', () => {
  const start = at('2026-11-17T23:30');
  const plan = reminders.defaultReminders(start);
  plan.water.enabled = false;
  plan.tasks.push(
    { id: 'once', title: '发材料', startsAt: start, daily: false, acknowledged: 0 },
    { id: 'daily', title: '记工时', startsAt: start, daily: true, acknowledged: 0 },
  );
  let due = reminders.reminderStatus(plan, at('2026-11-18T00:15')).due;
  assert.deepEqual(due.map(item => item.title), ['发材料', '记工时']);
  reminders.acknowledge(plan, due[0]);
  reminders.acknowledge(plan, due[1]);
  const restored = JSON.parse(JSON.stringify(plan));
  assert.equal(restored.tasks.length, 1);
  assert.deepEqual(reminders.reminderStatus(restored, at('2026-11-18T00:15')).due, []);
  due = reminders.reminderStatus(restored, at('2026-11-18T23:30')).due;
  assert.equal(due[0].title, '记工时');
});

test('每日待办的首个触发时间按北京时间计算', () => {
  const now = at('2026-11-17T17:00');
  assert.equal(reminders.firstDailyOccurrence('17:45', now), at('2026-11-17T17:45'));
  assert.equal(reminders.firstDailyOccurrence('09:00', now), at('2026-11-18T09:00'));
});
