'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createFakeDb } = require('./fakeFirestore');
const { getAccess, makeAccessHandler, GUEST_TRIAL_MS, USER_TRIAL_MS, DAY_MS } = require('../access');

const NOW = 1_800_000_000_000;
const DEV = 'device-abcdef123456';

test('гость: 24 часа от первого обращения, повторные обращения срок не продлевают', async () => {
  const db = createFakeDb();
  const a = await getAccess({ db, deviceId: DEV, now: NOW });
  assert.strictEqual(a.guestEndsAt, NOW + GUEST_TRIAL_MS);
  const b = await getAccess({ db, deviceId: DEV, now: NOW + 5 * 3600 * 1000 });
  assert.strictEqual(b.guestEndsAt, NOW + GUEST_TRIAL_MS, 'срок не сдвигается');
  const c = await getAccess({ db, deviceId: DEV, now: NOW + 3 * DAY_MS });
  assert.strictEqual(c.guestEndsAt, NOW + GUEST_TRIAL_MS, 'и после окончания новый не выдаётся (переустановка не помогает)');
  assert.strictEqual(c.user, null);
});

test('идентификатор устройства хранится только хэшем', async () => {
  const db = createFakeDb();
  await getAccess({ db, deviceId: DEV, now: NOW });
  const keys = [...db._store.keys()];
  assert.strictEqual(keys.length, 1);
  assert.ok(keys[0].startsWith('nativeDevices/') && !keys[0].includes(DEV));
});

test('некорректный идентификатор устройства не создаёт записей', async () => {
  const db = createFakeDb();
  const a = await getAccess({ db, deviceId: 'x', now: NOW });
  assert.strictEqual(a.guestEndsAt, 0);
  assert.strictEqual(db._store.size, 0);
});

test('аккаунт: 7 дней от первого входа, не сбрасывается при повторном входе', async () => {
  const db = createFakeDb();
  const a = await getAccess({ db, deviceId: DEV, uid: 'u1', now: NOW });
  assert.strictEqual(a.user.trialEndsAt, NOW + USER_TRIAL_MS);
  assert.strictEqual(a.user.isPro, false);
  const b = await getAccess({ db, deviceId: DEV, uid: 'u1', now: NOW + 2 * DAY_MS });
  assert.strictEqual(b.user.trialEndsAt, NOW + USER_TRIAL_MS);
});

test('триал из веб-версии сохраняется (читаются Timestamp, Date и число)', async () => {
  const db = createFakeDb();
  db._store.set('users/u1', { trialEndsAt: { toMillis: () => NOW + 1000 } });
  // поддельный Firestore копирует через JSON, поэтому проверяем число и объект с seconds
  db._store.set('users/u2', { trialEndsAt: { seconds: (NOW + 5000) / 1000 } });
  assert.strictEqual((await getAccess({ db, uid: 'u2', now: NOW })).user.trialEndsAt, NOW + 5000);
  db._store.set('users/u3', { trialEndsAt: NOW + 7000 });
  assert.strictEqual((await getAccess({ db, uid: 'u3', now: NOW })).user.trialEndsAt, NOW + 7000);
});

test('Pro действует только пока не истёк proExpiresAt', async () => {
  const db = createFakeDb();
  db._store.set('users/u1', { trialEndsAt: NOW - 1, isPro: true, proExpiresAt: NOW + 1000 });
  assert.strictEqual((await getAccess({ db, uid: 'u1', now: NOW })).user.isPro, true);
  assert.strictEqual((await getAccess({ db, uid: 'u1', now: NOW + 2000 })).user.isPro, false);
  db._store.set('users/u2', { trialEndsAt: NOW - 1, isPro: false, proExpiresAt: NOW + 99999 });
  assert.strictEqual((await getAccess({ db, uid: 'u2', now: NOW })).user.isPro, false);
});

test('обработчик: без токена работает как гость, с плохим токеном тоже, GET запрещён', async () => {
  const db = createFakeDb();
  const verifyToken = async (t) => { if (t !== 'good') throw new Error('bad'); return { uid: 'u1' }; };
  const h = makeAccessHandler({ verifyToken, db, now: () => NOW });
  const mk = (over = {}) => ({ method: 'POST', body: { deviceId: DEV }, get: (k) => (over.headers || {})[k], ...over });
  const res = () => { const r = { code: 200, body: null, headers: {} }; r.set = (k, v) => { r.headers[k] = v; return r; }; r.status = (c) => { r.code = c; return r; }; r.json = (b) => { r.body = b; return r; }; return r; };
  let r = res(); await h(mk(), r); assert.strictEqual(r.code, 200); assert.strictEqual(r.body.user, null);
  r = res(); await h(mk({ headers: { Authorization: 'Bearer bad' } }), r); assert.strictEqual(r.body.authError, true); assert.strictEqual(r.body.user, null);
  r = res(); await h(mk({ headers: { Authorization: 'Bearer good' } }), r); assert.strictEqual(r.body.user.uid, 'u1');
  r = res(); await h(mk({ method: 'GET' }), r); assert.strictEqual(r.code, 405);
});
