// Сверяет серверный список промокодов (seed) со списком в PARITY.md (взят из index.html).
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const parity = fs.readFileSync(path.join(root, 'PARITY.md'), 'utf8');
const block = parity.split('<!-- PROMO-CODES-START -->')[1].split('<!-- PROMO-CODES-END -->')[0];
const parityCodes = block.split('\n').map((s) => s.trim()).filter(Boolean);
const seed = JSON.parse(fs.readFileSync(path.join(root, 'server', 'seed', 'promocodes.json'), 'utf8'));

test('в PARITY.md ровно 100 уникальных кодов', () => {
  assert.strictEqual(parityCodes.length, 100);
  assert.strictEqual(new Set(parityCodes).size, 100);
});

test('seed-файл содержит те же коды в том же порядке', () => {
  assert.deepStrictEqual(seed.codes, parityCodes);
});

test('награда 30 дней, как PROMO_DAYS в index.html', () => {
  assert.strictEqual(seed.days, 30);
});

test('все коды в верхнем регистре и без пробелов (как сравнивает index.html)', () => {
  for (const c of seed.codes) assert.strictEqual(c, c.trim().toUpperCase());
});
