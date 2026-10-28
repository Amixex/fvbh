'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createFakeDb } = require('./fakeFirestore');
const { redeemPromo, DAY_MS, HOUR_MS, MAX_FAILED_PER_HOUR } = require('../promo');
const { makePromoHandler } = require('../handler');
const { seedPromoCodes } = require('../scripts/seed-promocodes');
const seed = require('../../seed/promocodes.json');

const NOW = 1_800_000_000_000;
async function freshDb() {
  const db = createFakeDb();
  await seedPromoCodes(db, seed);
  return db;
}
const CODE = seed.codes[0];

test('seed: 100 кодов, 30 дней, активны; повторный запуск ничего не сбрасывает', async () => {
  const db = createFakeDb();
  const first = await seedPromoCodes(db, seed);
  assert.deepStrictEqual(first, { created: 100, skipped: 0 });
  await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW });
  const second = await seedPromoCodes(db, seed);
  assert.deepStrictEqual(second, { created: 0, skipped: 100 });
  assert.strictEqual(db._store.get(`promocodes/${CODE}`).activations, 1);
  assert.strictEqual(db._store.get(`promocodes/${CODE}`).days, 30);
});

test('верный код даёт Pro на 30 дней от момента активации', async () => {
  const db = await freshDb();
  const r = await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW });
  assert.deepStrictEqual(r, { status: 'ok', days: 30, proExpiresAt: NOW + 30 * DAY_MS });
  const user = db._store.get('users/u1');
  assert.strictEqual(user.isPro, true);
  assert.strictEqual(user.proExpiresAt, NOW + 30 * DAY_MS);
  assert.strictEqual(user.promoUsed, true);
  assert.strictEqual(user.promoCode, CODE);
});

test('регистр и пробелы по краям не важны', async () => {
  const db = await freshDb();
  const r = await redeemPromo({ db, uid: 'u1', code: `  ${CODE.toLowerCase()}  `, now: NOW });
  assert.strictEqual(r.status, 'ok');
});

test('неизвестный и выключенный код: not_found, Pro не выдаётся', async () => {
  const db = await freshDb();
  assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: 'AMIX-NOPE-0000', now: NOW })).status, 'not_found');
  db._store.set(`promocodes/${CODE}`, { ...db._store.get(`promocodes/${CODE}`), active: false });
  assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW })).status, 'not_found');
  assert.strictEqual(db._store.has('users/u1'), false);
});

test('тот же пользователь не может активировать код второй раз', async () => {
  const db = await freshDb();
  assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW })).status, 'ok');
  assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW + 1000 })).status, 'used');
});

test('разные пользователи могут использовать один код (как в index.html, лимита нет)', async () => {
  const db = await freshDb();
  assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW })).status, 'ok');
  assert.strictEqual((await redeemPromo({ db, uid: 'u2', code: CODE, now: NOW })).status, 'ok');
  assert.strictEqual(db._store.get(`promocodes/${CODE}`).activations, 2);
  assert.strictEqual(db._store.get(`promocodes/${CODE}`).lastUsedBy, 'u2');
});

test('пустой код и недопустимые символы: bad_request', async () => {
  const db = await freshDb();
  for (const bad of ['', '   ', null, undefined, 'a b', 'КОД-РУС', '<script>']) {
    assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: bad, now: NOW })).status, 'bad_request', String(bad));
  }
  assert.strictEqual((await redeemPromo({ db, uid: '', code: CODE, now: NOW })).status, 'bad_request');
});

test('защита от подбора: после 10 неудач даже верный код блокируется на час', async () => {
  const db = await freshDb();
  for (let i = 0; i < MAX_FAILED_PER_HOUR; i++) {
    assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: `AMIX-FAKE-${i}${i}${i}${i}`, now: NOW })).status, 'not_found');
  }
  assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW + 1000 })).status, 'rate_limited');
  assert.strictEqual((await redeemPromo({ db, uid: 'u2', code: CODE, now: NOW + 1000 })).status, 'ok', 'другой пользователь не затронут');
  assert.strictEqual((await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW + HOUR_MS + 2000 })).status, 'ok', 'через час снова можно');
});

test('срок записывается через toTimestamp (в Functions это Firestore Timestamp)', async () => {
  const db = await freshDb();
  await redeemPromo({ db, uid: 'u1', code: CODE, now: NOW, toTimestamp: (ms) => ({ __ts: ms }) });
  assert.deepStrictEqual(db._store.get('users/u1').proExpiresAt, { __ts: NOW + 30 * DAY_MS });
});

// ---------- HTTP-обработчик ----------
function fakeRes() {
  const res = { code: 200, body: null, headers: {} };
  res.set = (k, v) => { res.headers[k] = v; return res; };
  res.status = (c) => { res.code = c; return res; };
  res.json = (b) => { res.body = b; return res; };
  return res;
}
const req = (over = {}) => ({ method: 'POST', body: { code: CODE }, get: (h) => (over.headers || {})[h], ...over });

test('обработчик: без токена 401, с плохим токеном 401, GET 405', async () => {
  const db = await freshDb();
  const verifyToken = async (t) => { if (t !== 'good') throw new Error('bad'); return { uid: 'u1' }; };
  const h = makePromoHandler({ verifyToken, db, now: () => NOW });
  let res = fakeRes(); await h(req(), res); assert.strictEqual(res.code, 401);
  res = fakeRes(); await h(req({ headers: { Authorization: 'Bearer bad' } }), res); assert.strictEqual(res.code, 401);
  res = fakeRes(); await h(req({ method: 'GET', headers: { Authorization: 'Bearer good' } }), res); assert.strictEqual(res.code, 405);
});

test('обработчик: успех, повтор, блокировка, ошибка сервера', async () => {
  const db = await freshDb();
  const verifyToken = async () => ({ uid: 'u1' });
  const auth = { headers: { Authorization: 'Bearer good' } };
  const h = makePromoHandler({ verifyToken, db, now: () => NOW });
  let res = fakeRes(); await h(req(auth), res);
  assert.strictEqual(res.code, 200); assert.strictEqual(res.body.status, 'ok'); assert.strictEqual(res.body.days, 30);
  assert.strictEqual(res.headers['Cache-Control'], 'no-store');
  res = fakeRes(); await h(req(auth), res); assert.strictEqual(res.body.status, 'used');
  res = fakeRes(); await h(req({ ...auth, body: { code: '' } }), res); assert.strictEqual(res.code, 400);
  const broken = makePromoHandler({ verifyToken, db: { runTransaction: async () => { throw new Error('boom'); }, collection: db.collection }, now: () => NOW });
  res = fakeRes(); await broken(req(auth), res); assert.strictEqual(res.code, 500);
});
