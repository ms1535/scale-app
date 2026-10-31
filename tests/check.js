'use strict';
// Checks index.html in a real browser. Run: node tests/check.js
const path = require('path');
let chromium;
for (const p of ['playwright', '/opt/node22/lib/node_modules/playwright', path.join(process.cwd(), 'node_modules/playwright')]) {
  try { ({ chromium } = require(p)); break; } catch (e) { /* try next */ }
}
if (!chromium) { console.error('Playwright not found'); process.exit(1); }

let failed = 0;
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failed++; };

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const external = [], errors = [];
  page.on('request', (r) => { if (!/^(file|data|blob):/.test(r.url())) external.push(r.url()); });
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('file://' + path.resolve(__dirname, '../index.html'));

  check(external.length === 0 && errors.length === 0, 'loads with no external requests or errors ' + JSON.stringify(external.concat(errors)));

  const r = await page.evaluate(() => {
    const dv = (a) => new DataView(Uint8Array.from(a).buffer);
    const std = decodeStandardWeight(dv([0x00, 0xD0, 0x84])); // 0x84D0=34000*0.005 = 170 kg
    const qn = decodeQN(dv([0x10, 0x0B, 0x1B, 0x1B, 0x58, 0x01, 0x01, 0xF4, 0x00, 0x00]), 100); // 0x1B58=7000 -> 70.00
    const live = decodeQN(dv([0x10, 0x0B, 0x1B, 0x1B, 0x58, 0x00, 0, 0, 0, 0]), 100);
    return { std, qn, live, other: decodeQN(dv([0x12, 1, 2, 3, 4, 5, 6, 7, 8, 9]), 100), sum: Array.from(withSum([1, 2, 3])),
      lb: Array.from(withSum(unitFrame('lb'))), kg: Array.from(withSum(unitFrame('kg'))) };
  });
  check(Math.abs(r.std.kg - 170) < 0.01, 'standard 0x2A9D weight decodes');
  check(Math.abs(r.qn.kg - 70) < 0.01 && r.qn.stable && r.qn.ohms === 500, 'QN stable frame gives weight + impedance');
  check(r.live.stable === false && r.live.ohms === null, 'QN settling frame is not saveable');
  check(r.other === null, 'non-weight frames are ignored');
  check(r.sum[3] === 6, 'checksum byte appended');
  // The scale screen gets stuck on kg without this: unit byte 2 = lb, 1 = kg, checksum last.
  check(r.lb.join() === [0x13, 0x09, 0x15, 2, 0x10, 0, 0, 0, 0x43].join(), 'pounds command is 13 09 15 02 10 00 00 00 43');
  check(r.kg[3] === 1 && r.kg[8] === 0x42, 'kilograms command carries unit 1');

  // Save a typed reading in pounds, reload, and make sure it is still there.
  await page.click('[data-tab=weigh]');
  await page.fill('#mW', '180.5');
  await page.click('#btnManual');
  await page.reload();
  await page.click('[data-tab=history]');
  const row = await page.textContent('#tbl');
  check(/180\.5 lb/.test(row), 'a saved reading survives a reload (' + row.replace(/\s+/g, ' ').slice(0, 60) + ')');

  // Estimate sanity: 70 kg, 175 cm, 40 y male with 500 ohms stays in a believable range.
  const e = await page.evaluate(() => bodyEstimate(70, 500, { ht: 175 / 2.54, age: 40, sex: 'm' }));
  check(e.fatPct > 5 && e.fatPct < 40, 'body estimate is in a believable range (' + e.fatPct.toFixed(1) + '%)');

  check(/pounds/.test(await page.textContent('#btnUnit')) && await page.isDisabled('#btnUnit'), 'unit button names pounds and waits for a connection');

  await browser.close();
  console.log(failed ? failed + ' failed' : 'all passed');
  process.exit(failed ? 1 : 0);
})();
