'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createFakeDb } = require('./fakeFirestore');
const { redeemCode, normalizeCode, generateCode, entropyBits, MAX_FAILURES_PER_HOUR, SPECS } = require('../codes');
const { generate, list, stats, revoke, toCsv } = require('../admin');
const { getAccess } = require('../access');
const { DAY_MS, HOUR_MS } = require('../common');

const NOW = 1_800_000_000_000;
const D1 = 'a1-device-one-1234567';
const D2 = 'a1-device-two-7654321';

async function oneKey(db, extra = {}) {
  const [k] = await generate({ db, kind: 'keys', count: 1, type: 'm1', now: NOW, ...extra });
  return k;
}

test('ровно 1000 уникальных промокодов, нужный формат, достаточная энтропия', async () => {
  const db = createFakeDb();
  const codes = await generate({ db, kind: 'promo', count: 1000, days: 30, type: 'promo', now: NOW });
  assert.strictEqual(codes.length, 1000);
  assert.strictEqual(new Set(codes).size, 1000);
  assert.strictEqual(db._store.size, 1000);
  for (const c of codes) assert.match(c, /^PROMO(-[A-HJ-NP-Z2-9]{4}){3}$/);
  assert.ok(entropyBits('promo') >= 60, 'промокод: не меньше 60 бит');
  assert.ok(entropyBits('keys') >= 80, 'ключ: не меньше 80 бит');
  // крайне грубая проверка случайности: символы распределены, а не повторяются
  const used = new Set(codes.join('').replace(/PROMO|-/g, ''));
  assert.ok(used.size >= 30);
});

test('повторная генерация не создаёт дублей с уже существующими кодами', async () => {
  const db = createFakeDb();
  const a = await generate({ db, kind: 'promo', count: 300, days: 30, now: NOW });
  const b = await generate({ db, kind: 'promo', count: 300, days: 30, now: NOW });
  assert.strictEqual(new Set([...a, ...b]).size, 600);
  assert.strictEqual(db._store.size, 600);
});

test('нормализация ввода: регистр, пробелы, дефисы, неверные символы', () => {
  const k = generateCode('keys');
  assert.strictEqual(normalizeCode('keys', k.toLowerCase()), k);
  assert.strictEqual(normalizeCode('keys', ' ' + k.replace(/-/g, ' ') + ' '), k);
  assert.strictEqual(normalizeCode('keys', k.replace(/-/g, '')), k);
  assert.strictEqual(normalizeCode('keys', 'AMIX-0000-1111-OOOO-IIII'), null);
  assert.strictEqual(normalizeCode('keys', 'PROMO-AAAA-BBBB-CCCC'), null);
  assert.strictEqual(normalizeCode('promo', k), null);
  assert.strictEqual(normalizeCode('keys', ''), null);
});

test('действительный ключ включает PRO на срок ключа', async () => {
  const db = createFakeDb(); const k = await oneKey(db);
  const r = await redeemCode({ db, kind: 'keys', deviceId: D1, code: k, now: NOW });
  assert.deepStrictEqual(r, { status: 'ok', days: 30, proExpiresAt: NOW + 30 * DAY_MS });
  const acc = await getAccess({ db, deviceId: D1, now: NOW + 1000 });
  assert.strictEqual(acc.isPro, true);
  assert.strictEqual(acc.proExpiresAt, NOW + 30 * DAY_MS);
});

test('недействительный ключ: not_found, PRO не включается', async () => {
  const db = createFakeDb(); await oneKey(db);
  const r = await redeemCode({ db, kind: 'keys', deviceId: D1, code: 'AMIX-AAAA-BBBB-CCCC-DDDD', now: NOW });
  assert.strictEqual(r.status, 'not_found');
  assert.strictEqual((await getAccess({ db, deviceId: D1, now: NOW })).isPro, false);
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: 'мусор', now: NOW })).status, 'not_found');
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: '   ', now: NOW })).status, 'bad_request');
});

test('ключ активируется один раз: другое устройство получает used, то же устройство повторно ничего не получает', async () => {
  const db = createFakeDb(); const k = await oneKey(db);
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: k, now: NOW })).status, 'ok');
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D2, code: k, now: NOW + 5 })).status, 'used');
  const again = await redeemCode({ db, kind: 'keys', deviceId: D1, code: k, now: NOW + 10 });
  assert.strictEqual(again.status, 'ok'); assert.strictEqual(again.already, true);
  assert.strictEqual((await getAccess({ db, deviceId: D1, now: NOW })).proExpiresAt, NOW + 30 * DAY_MS, 'срок не удвоился');
  assert.strictEqual((await getAccess({ db, deviceId: D2, now: NOW })).isPro, false);
});

test('срок действия кода: просроченный ключ и промокод отклоняются', async () => {
  const db = createFakeDb();
  const k = await oneKey(db, { validDays: 10 });
  const [p] = await generate({ db, kind: 'promo', count: 1, days: 30, validDays: 5, now: NOW });
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: k, now: NOW + 11 * DAY_MS })).status, 'expired');
  assert.strictEqual((await redeemCode({ db, kind: 'promo', deviceId: D1, code: p, now: NOW + 6 * DAY_MS })).status, 'expired');
  assert.strictEqual((await redeemCode({ db, kind: 'promo', deviceId: D1, code: p, now: NOW + 4 * DAY_MS })).status, 'ok');
});

test('тип и длительность лицензии задаются при создании', async () => {
  const db = createFakeDb();
  const [y] = await generate({ db, kind: 'keys', count: 1, type: 'm12', now: NOW });
  const [f] = await generate({ db, kind: 'keys', count: 1, type: 'forever', now: NOW });
  const [c] = await generate({ db, kind: 'keys', count: 1, type: 'custom', days: 7, now: NOW });
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: y, now: NOW })).days, 365);
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D2, code: c, now: NOW })).days, 7);
  assert.ok((await redeemCode({ db, kind: 'keys', deviceId: 'a1-device-three-111111', code: f, now: NOW })).days >= 36500);
});

test('новая активация продлевает остаток PRO, а не обнуляет его', async () => {
  const db = createFakeDb();
  const [a, b] = await generate({ db, kind: 'keys', count: 2, type: 'm1', now: NOW });
  await redeemCode({ db, kind: 'keys', deviceId: D1, code: a, now: NOW });
  const r = await redeemCode({ db, kind: 'keys', deviceId: D1, code: b, now: NOW + 10 * DAY_MS });
  assert.strictEqual(r.proExpiresAt, NOW + 60 * DAY_MS);
});

test('промокод: действительный, повторный (used), чужое устройство (used), отозванный (revoked)', async () => {
  const db = createFakeDb();
  const [p, q] = await generate({ db, kind: 'promo', count: 2, days: 30, now: NOW });
  const ok = await redeemCode({ db, kind: 'promo', deviceId: D1, code: p, now: NOW });
  assert.strictEqual(ok.status, 'ok'); assert.strictEqual(ok.days, 30);
  assert.strictEqual((await redeemCode({ db, kind: 'promo', deviceId: D2, code: p, now: NOW })).status, 'used');
  assert.strictEqual(await revoke({ db, kind: 'promo', code: q }), true);
  assert.strictEqual((await redeemCode({ db, kind: 'promo', deviceId: D2, code: q, now: NOW })).status, 'revoked');
});

test('защита от подбора: после 10 неудач за час блокировка, у других устройств нет, через час снова можно', async () => {
  const db = createFakeDb(); const k = await oneKey(db);
  for (let i = 0; i < MAX_FAILURES_PER_HOUR; i++) {
    assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: 'AMIX-AAAA-BBBB-CCCC-DDDD', now: NOW + i })).status, 'not_found');
  }
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: k, now: NOW + 1000 })).status, 'rate_limited', 'даже верный ключ блокируется');
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D2, code: k, now: NOW + 1000 })).status, 'ok', 'другое устройство не затронуто');
  const [k2] = await generate({ db, kind: 'keys', count: 1, type: 'm1', now: NOW });
  assert.strictEqual((await redeemCode({ db, kind: 'keys', deviceId: D1, code: k2, now: NOW + HOUR_MS + 5000 })).status, 'ok');
});

test('администратор: список со статусами, статистика, фильтр, CSV (1000 строк + заголовок)', async () => {
  const db = createFakeDb();
  const codes = await generate({ db, kind: 'promo', count: 1000, days: 30, type: 'promo', validDays: 90, now: NOW });
  await redeemCode({ db, kind: 'promo', deviceId: D1, code: codes[0], now: NOW + 1 });
  await revoke({ db, kind: 'promo', code: codes[1] });
  const st = await stats({ db, kind: 'promo', now: NOW + 2 });
  assert.deepStrictEqual(st, { total: 1000, unused: 998, used: 1, revoked: 1, expired: 0 });
  assert.strictEqual((await list({ db, kind: 'promo', status: 'used', now: NOW + 2 })).length, 1);
  const expired = await stats({ db, kind: 'promo', now: NOW + 100 * DAY_MS });
  assert.strictEqual(expired.expired, 998);
  const csv = toCsv(await list({ db, kind: 'promo', now: NOW + 2 }));
  const lines = csv.trim().split('\r\n');
  assert.strictEqual(lines.length, 1001);
  assert.strictEqual(lines[0], 'code,type,days,status,createdAt,validUntil,activatedAt,activatedBy');
  assert.ok(lines.some((l) => l.includes(',used,')) && lines.some((l) => l.includes(',revoked,')));
});

test('CSV экранирует запятые и кавычки', () => {
  const csv = toCsv([{ code: 'A', type: 'a,b', days: 1, status: 'x"y', createdAt: '', validUntil: '', activatedAt: '', activatedBy: '' }]);
  assert.ok(csv.includes('"a,b"') && csv.includes('"x""y"'));
});

test('в клиентский код секретов не попадает: сервер хранит только хэш устройства', async () => {
  const db = createFakeDb(); const k = await oneKey(db);
  await redeemCode({ db, kind: 'keys', deviceId: D1, code: k, now: NOW });
  const doc = db._store.get(`${SPECS.keys.collection}/${k}`);
  assert.ok(doc.activatedBy && !doc.activatedBy.includes(D1));
});
