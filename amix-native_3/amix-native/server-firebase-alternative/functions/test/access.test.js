'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createFakeDb } = require('./fakeFirestore');
const { getAccess } = require('../access');
const { TRIAL_MS, DAY_MS } = require('../common');

const NOW = 1_800_000_000_000;
const DEV = 'a1-device1234567890';

test('пробный период ровно 7 дней (168 часов) от первого обращения', async () => {
  assert.strictEqual(TRIAL_MS, 168 * 3600 * 1000);
  const a = await getAccess({ db: createFakeDb(), deviceId: DEV, now: NOW });
  assert.strictEqual(a.status, 'ok');
  assert.strictEqual(a.trialEndsAt, NOW + 7 * DAY_MS);
  assert.strictEqual(a.isPro, false);
});

test('таймер не сбрасывается: повторные обращения, перезапуск и «чистые» данные приложения дают тот же срок', async () => {
  const db = createFakeDb();
  const first = await getAccess({ db, deviceId: DEV, now: NOW });
  const later = await getAccess({ db, deviceId: DEV, now: NOW + 3 * DAY_MS });
  const after = await getAccess({ db, deviceId: DEV, now: NOW + 30 * DAY_MS });
  assert.strictEqual(later.trialEndsAt, first.trialEndsAt);
  assert.strictEqual(after.trialEndsAt, first.trialEndsAt, 'после окончания новый период не выдаётся');
});

test('идентификатор устройства хранится только хэшем', async () => {
  const db = createFakeDb();
  await getAccess({ db, deviceId: DEV, now: NOW });
  const keys = [...db._store.keys()];
  assert.strictEqual(keys.length, 1);
  assert.ok(keys[0].startsWith('nativeDevices/') && !keys[0].includes(DEV));
});

test('окончание пробного периода: через 7 дней и секунду trialEndsAt уже в прошлом', async () => {
  const db = createFakeDb();
  await getAccess({ db, deviceId: DEV, now: NOW });
  const end = await getAccess({ db, deviceId: DEV, now: NOW + 7 * DAY_MS + 1000 });
  assert.ok(end.trialEndsAt < end.serverNow);
  assert.strictEqual(end.isPro, false);
});

test('Pro действует, пока не истёк proExpiresAt', async () => {
  const db = createFakeDb();
  await getAccess({ db, deviceId: DEV, now: NOW });
  const path = [...db._store.keys()][0];
  db._store.set(path, { ...db._store.get(path), proExpiresAt: NOW + 1000 });
  assert.strictEqual((await getAccess({ db, deviceId: DEV, now: NOW })).isPro, true);
  assert.strictEqual((await getAccess({ db, deviceId: DEV, now: NOW + 2000 })).isPro, false);
});

test('некорректный идентификатор отклоняется и ничего не записывает', async () => {
  const db = createFakeDb();
  for (const bad of [undefined, '', 'x', 'a b c d e f g h', '<script>']) {
    assert.strictEqual((await getAccess({ db, deviceId: bad, now: NOW })).status, 'bad_request');
  }
  assert.strictEqual(db._store.size, 0);
});
