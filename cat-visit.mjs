import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';

export const LEASE_MS = 4_200;
const START_LEAD_MS = 230;
const HESITATION_MS = 650;
const WALK_MS = 750;
const GAP_MS = 90;
const ARRIVE_MS = 980;
const other = role => role === 'home' ? 'nest' : 'home';

export function createVisit({ now = () => performance.now(), random = Math.random } = {}) {
  const devices = new Map();
  let location = 'home';
  let side = 'right';
  let nextWalk = Infinity;
  let trip = null;

  function schedule(at) {
    nextWalk = location === 'home' ? at + 45_000 + random() * 30_000 : at + 36_000 + random() * 17_000;
  }

  function exchange(role, id, { call = false, side: homeSide = 'right', hold = false } = {}) {
    const at = now();
    for (const [key, device] of devices) {
      if (at - device.seenAt > LEASE_MS || (key !== role && device.id === id)) {
        devices.delete(key);
      }
    }
    if (devices.size < 2) {
      location = 'home';
      nextWalk = Infinity;
      trip = null;
    }
    if (devices.has(role) && devices.get(role).id !== id) {
      return null;
    }
    const wasHeld = devices.get('home')?.hold;
    devices.set(role, { id, seenAt: at, hold: role === 'home' && hold });

    if (role === 'home' && !trip) {
      side = homeSide;
    }
    const paired = devices.size === 2;
    if (paired) {
      const held = devices.get('home').hold;
      if (held && trip?.from === 'home' && at < trip.starts) {
        trip = null;
      }
      if (trip && at >= trip.starts + WALK_MS + GAP_MS + ARRIVE_MS) {
        location = other(trip.from);
        trip = null;
        schedule(at);
      }
      if (nextWalk === Infinity || (wasHeld && !held)) {
        schedule(at);
      }
      const called = !held && role === 'nest' && call && location === 'home';
      const returnHome = held && location === 'nest';
      if (!trip && (returnHome || called || (!held && at >= nextWalk))) {
        const hesitant = called && random() < 0.30;
        trip = {
          id: randomUUID(), from: location, hesitant,
          starts: at + START_LEAD_MS + (hesitant ? HESITATION_MS : 0),
        };
      }
    }

    const route = trip ? {
      id: trip.id, from: trip.from, hesitant: trip.hesitant, elapsed: Math.floor(at - trip.starts),
      walk: WALK_MS, gap: GAP_MS, arrive: ARRIVE_MS,
    } : null;
    return { paired, location, side, trip: route };
  }

  return { exchange };
}
