import assert from 'node:assert/strict';
import test from 'node:test';
import { summarizeWorkBuddyCredits } from './workbuddy.mjs';

test('WorkBuddy credit usage sums the reported debits and keeps frozen credits separate', () => {
  assert.deepEqual(summarizeWorkBuddyCredits({
    code: 0,
    data: {
      TotalCredits: '700.25',
      Packages: [
        { CapacityUnit: 'credits', CycleUsedCapacity: '79.75', CycleFrozenCapacity: '20' },
        { CapacityUnit: 'credits', CycleUsedCapacity: '200', CycleFrozenCapacity: '0' },
      ],
    },
  }), { remaining: 700.25, used: 279.75 });
});

test('zero is a valid balance, and missing credit data is an error', () => {
  assert.deepEqual(summarizeWorkBuddyCredits({
    code: 0, data: { TotalCredits: '0', Packages: [] },
  }), { remaining: 0, used: 0 });
  assert.throws(() => summarizeWorkBuddyCredits({
    code: 0, data: { TotalCredits: null, Packages: [] },
  }));
  assert.throws(() => summarizeWorkBuddyCredits({
    code: 0, data: { TotalCredits: '0', Packages: [{ CapacityUnit: 'credits' }] },
  }));
});
