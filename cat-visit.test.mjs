import assert from 'node:assert/strict';
import test from 'node:test';
import { createVisit } from "./cat-visit.mjs";

const home = '0'.repeat(32);
const nest = '1'.repeat(32);

function fixture(roll) {
  let time = 0;
  const visit = createVisit({ now: () => time, random: () => roll });
  return {
    advance: ms => { time += ms; },
    home: (side = 'right', hold = false) => visit.exchange('home', home, { side, hold }),
    nest: (call = false) => visit.exchange('nest', nest, { call }),
    visit,
  };
}

test('点小窝后按离开、间隔、抵达顺序过屏，重复召唤不重置行程', () => {
  const f = fixture(0.5);
  assert.equal(f.home().paired, false);
  const leaving = f.nest(true);
  assert.equal(leaving.trip.from, 'home');
  assert.ok(leaving.trip.elapsed < 0);
  f.advance(900);
  const source = f.home();
  const destination = f.nest(true);
  assert.deepEqual(source.trip, destination.trip);
  assert.equal(destination.trip.elapsed, leaving.trip.elapsed + 900);
  const { walk, gap, arrive } = destination.trip;
  assert.ok(gap > 0);
  f.advance(walk + gap + arrive - destination.trip.elapsed);
  assert.equal(f.home().location, 'nest');
  assert.equal(f.nest().trip, null);
});

test('磨蹭倒计时不会延后，左侧摆放由用量看板指定', () => {
  const f = fixture(0.2);
  f.home('left');
  const waiting = f.nest(true);
  assert.ok(waiting.trip.hesitant);
  assert.ok(waiting.trip.elapsed >= -1_000 && waiting.trip.elapsed <= -500);
  assert.equal(waiting.side, 'left');
  f.advance(500);
  assert.equal(f.nest(true).trip.elapsed, waiting.trip.elapsed + 500);
});

test('无人点按时自动串门，停留一会后返回', () => {
  const f = fixture(0);
  f.home();
  f.nest();
  let sample;
  for (let i = 0; i < 50; i++) {
    f.advance(1_000);
    f.home();
    sample = f.nest();
  }
  assert.equal(sample.location, 'nest');
  for (let i = 0; i < 45; i++) {
    f.advance(1_000);
    f.home();
    sample = f.nest();
  }
  assert.equal(sample.location, 'home');
  assert.equal(sample.trip, null);
});

test('一端掉线会复位；重新连接时不会生成两个橘猫', () => {
  const f = fixture(0.5);
  f.home();
  f.nest(true);
  f.advance(3_500);
  f.home();
  f.nest();
  f.advance(2_000);
  assert.equal(f.home().location, 'nest');
  f.advance(2_500);
  const homeAgain = f.home();
  assert.equal(homeAgain.paired, false);
  assert.equal(homeAgain.location, 'home');
  assert.equal(homeAgain.trip, null);
  assert.equal(f.nest().location, 'home');
});

test('两台设备不能抢占同一角色，单一设备也不能同时扮演两端', () => {
  const f = fixture(0);
  f.home();
  assert.equal(f.visit.exchange('home', nest), null);
  assert.equal(f.visit.exchange('nest', home).paired, false);
});

test('两端提前收到同一行程，跨屏空白小于 150 毫秒', () => {
  const f = fixture(0.5);
  f.home('left');
  const first = f.nest(true).trip;
  assert.ok(first.id);
  assert.ok(first.elapsed >= -300 && first.elapsed <= -180);
  assert.ok(first.gap <= 150);
  assert.ok(first.walk + first.gap + first.arrive - first.elapsed <= 2_200);
  f.advance(160);
  assert.equal(f.home('left').trip.id, first.id);
  assert.equal(f.nest(true).trip.elapsed, first.elapsed + 160);
});

test('喝水提醒期间橘猫留在看板，确认后可再次串门', () => {
  const f = fixture(0.5);
  f.home();
  assert.equal(f.nest(true).trip.from, 'home');
  assert.equal(f.home('right', true).trip, null);
  for (let i = 0; i < 60; i++) {
    f.advance(1_000);
    f.home('right', true);
    assert.equal(f.nest(true).trip, null);
  }
  assert.equal(f.home().trip, null);
  assert.equal(f.nest(true).trip.from, 'home');
});

test('喝水提醒等出发动画结束再把橘猫叫回，不中断两屏行程', () => {
  const f = fixture(0.5);
  f.home();
  const outward = f.nest(true).trip;
  f.advance(500);
  assert.equal(f.home('right', true).trip.id, outward.id);
  f.advance(1_600);
  const returnLeg = f.home('right', true);
  assert.equal(returnLeg.location, 'nest');
  assert.equal(returnLeg.trip.from, 'nest');
  assert.equal(f.nest().trip.id, returnLeg.trip.id);
  f.advance(2_100);
  assert.equal(f.home('right', true).location, 'home');
  assert.equal(f.nest(true).trip, null);
});
