'use strict';
// Checks index.html in a real browser. Run: node tests/check.js
// The scale frames below are copied from a real MF-BS2 log (2026-10-30), not made up.
const path = require('path');
let chromium;
for (const p of ['playwright', '/opt/node22/lib/node_modules/playwright', path.join(process.cwd(), 'node_modules/playwright')]) {
  try { ({ chromium } = require(p)); break; } catch (e) { /* try next */ }
}
if (!chromium) { console.error('Playwright not found'); process.exit(1); }

let failed = 0;
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failed++; };
const URL_ = 'file://' + path.resolve(__dirname, '../index.html');

const REAL = {
  settling: '10 0b 15 29 86 00 00 00 00 00 df',
  steady: '10 0b 15 29 86 01 01 d5 01 8b 42',
};
// Feeds frames to the app exactly as the scale's FFE1 notifications would.
const feed = (page, frames) => page.evaluate((fs) => {
  for (const f of fs) {
    const b = Uint8Array.from(f.split(' ').map((x) => parseInt(x, 16)));
    onData('0000ffe0-0000-1000-8000-00805f9b34fb', '0000ffe1-0000-1000-8000-00805f9b34fb', new DataView(b.buffer));
  }
}, frames);
// A steady frame for any weight, with the real impedance bytes.
const steadyAt = (kg) => {
  const w = Math.round(kg * 100);
  const a = [0x10, 0x0b, 0x15, w >> 8, w & 255, 1, 0x01, 0xd5, 0x01, 0x8b];
  return a.map((x) => x.toString(16).padStart(2, '0')).join(' ') + ' 00';
};
const OFF = '10 0b 15 00 00 00 00 00 00 00 30';
const stored = (page) => page.evaluate(() => JSON.parse(localStorage.getItem('scale_readings') || '[]'));

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const external = [], errors = [];
  page.on('request', (r) => { if (!/^(file|data|blob):/.test(r.url())) external.push(r.url()); });
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('dialog', (d) => d.accept());
  await page.goto(URL_);

  check(external.length === 0 && errors.length === 0, 'loads with no external requests or errors ' + JSON.stringify(external.concat(errors)));

  // --- decoding, against the real log ---
  const r = await page.evaluate((real) => {
    const dv = (s) => new DataView(Uint8Array.from(s.split(' ').map((x) => parseInt(x, 16))).buffer);
    return {
      std: decodeStandardWeight(new DataView(Uint8Array.from([0x00, 0xD0, 0x84]).buffer)),
      settling: decodeQN(dv(real.settling), 100), steady: decodeQN(dv(real.steady), 100),
      lb: Array.from(withSum(unitFrame('lb'))), kg: Array.from(withSum(unitFrame('kg'))),
    };
  }, REAL);
  check(Math.abs(r.std.kg - 170) < 0.01, 'standard 0x2A9D weight decodes');
  check(Math.abs(r.steady.kg - 106.3) < 0.001 && r.steady.stable && r.steady.ohms === 469 && r.steady.ohms2 === 395, 'real steady frame: 106.30 kg, 469 / 395 ohms');
  check(Math.abs(r.settling.kg - 106.3) < 0.001 && !r.settling.stable && r.settling.ohms === null, 'real settling frame: weight, not steady, no impedance');
  // The MF-BS2 answered exactly this, so it is the right command.
  check(r.lb.join() === [0x13, 0x09, 0x15, 2, 0x10, 0, 0, 0, 0x43].join(), 'pounds command is 13 09 15 02 10 00 00 00 43');
  check(r.kg[3] === 1 && r.kg[8] === 0x42, 'kilograms command carries unit 1');

  // --- hands-free saving: the scale repeats the steady frame every second ---
  await feed(page, [REAL.settling, REAL.settling, REAL.steady, REAL.steady, REAL.steady, REAL.steady]);
  let s = await stored(page);
  check(s.length === 1 && s[0].ohms === 469 && s[0].uid === 'p1', 'one time on the scale saves exactly one reading, with impedance');
  check(/Saved for/.test(await page.textContent('#who')), 'says who it was saved for');
  check(/Muscle/.test(await page.textContent('#breakdown')) === false, 'no breakdown numbers until height and age are set');
  await feed(page, [OFF, REAL.steady]);
  check((await stored(page)).length === 2, 'stepping off and back on saves again');
  await page.click('#who [data-undo]');
  check((await stored(page)).length === 1, '"Don\'t keep it" removes the reading');

  // --- people ---
  await page.click('[data-tab=people]');
  await page.click('[data-edit=p1]');
  await page.fill('#pName', 'Michael'); await page.fill('#pAge', '40'); await page.fill('#pHt', '70'); await page.fill('#pGoal', '210');
  await page.click('#btnPerson');
  await page.click('[data-edit=new]');
  await page.fill('#pName', 'Wife'); await page.selectOption('#pSex', 'f'); await page.fill('#pAge', '38'); await page.fill('#pHt', '64');
  await page.click('#btnPerson');
  const wifeId = await page.evaluate(() => people[1].id);
  // Give her one typed reading so the app knows her weight.
  await page.click('[data-tab=weigh]');
  await page.selectOption('#mP', wifeId); await page.fill('#mW', '140'); await page.click('#btnManual');

  await feed(page, [OFF, steadyAt(106.5)]);
  s = await stored(page);
  check(s[s.length - 1].uid === 'p1', 'a weight near Michael\'s is filed to Michael');
  await feed(page, [OFF, steadyAt(63.8)]);
  s = await stored(page);
  check(s[s.length - 1].uid === wifeId, 'a weight near hers is filed to her');
  const n = s.length;
  await feed(page, [OFF, steadyAt(85)]);
  check((await stored(page)).length === n && /Who is this/.test(await page.textContent('#who')), 'a weight near nobody asks instead of guessing');
  await page.click('#who [data-save=p1]');
  s = await stored(page);
  check(s.length === n + 1 && s[s.length - 1].uid === 'p1', 'answering "who is this" saves it to that person');
  await page.click('#who [data-move="' + wifeId + '"]');
  s = await stored(page);
  check(s[s.length - 1].uid === wifeId, '"It was ..." moves the last reading to the other person');

  // --- breakdown with real impedance ---
  await feed(page, [OFF, REAL.steady]);
  const bd = await page.textContent('#breakdown');
  check(/Muscle/.test(bd) && /Body water/.test(bd) && /Calories at rest/.test(bd), 'full breakdown shows once height and age are set');
  const e = await page.evaluate(() => bodyComp(106.3, 469, { ht: 70, age: 40, sex: 'm' }));
  check(e.fatPct > 20 && e.fatPct < 45 && e.muscleKg > 25 && e.muscleKg < 50 && e.waterKg / 106.3 > 0.4 && e.waterKg / 106.3 < 0.65,
    'real reading gives believable estimates (fat ' + e.fatPct.toFixed(1) + '%, muscle ' + e.muscleKg.toFixed(1) + ' kg, water ' + (e.waterKg / 1.063).toFixed(0) + '%)');

  // --- history ---
  await page.click('[data-tab=history]');
  await page.click('[data-who=p1]');
  check(/7-day average/.test(await page.textContent('#chart')), 'chart draws the 7-day average');
  check(/Goal/.test(await page.textContent('#hGoal')), 'goal progress shows');
  await page.click('#tbl tr[data-t] td');
  check(!(await page.isHidden('#detail')) && /Impedance 469 \/ 395/.test(await page.textContent('#detail')), 'tapping a reading opens its breakdown');

  // --- persistence and upgrade from the one-profile builds ---
  await page.reload();
  check((await stored(page)).length > 0 && (await page.evaluate(() => people.length)) === 2, 'people and readings survive a reload');

  const p2 = await ctx.newPage();
  p2.on('pageerror', (e) => errors.push(e.message));
  await p2.goto(URL_);
  await p2.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('scale_profile', JSON.stringify({ unit: 'lb', sex: 'm', age: 40, ht: 70 }));
    localStorage.setItem('scale_readings', JSON.stringify([{ t: 1761000000000, kg: 100, ohms: null, src: 'typed' }]));
  });
  await p2.reload();
  const up = await p2.evaluate(() => ({ people, r: JSON.parse(localStorage.getItem('scale_readings')) }));
  check(up.people.length === 1 && up.people[0].ht === 70 && up.r[0].uid === 'p1', 'upgrading keeps the old profile and readings');
  const n2 = await p2.evaluate(() => restore({ readings: [{ t: 1761000000000, kg: 100 }, { t: 1761100000000, kg: 99 }] }));
  check(n2 === 1, 'restoring an old backup adds only what is new');
  check(errors.length === 0, 'no page errors along the way ' + JSON.stringify(errors));

  await browser.close();
  console.log(failed ? failed + ' failed' : 'all passed');
  process.exit(failed ? 1 : 0);
})();
