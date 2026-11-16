'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createFakeDb } = require('./fakeFirestore');
const { makeHandlers } = require('../handlers');
const { generate } = require('../admin');

const NOW = 1_800_000_000_000;
const res = () => { const r = { code: 200, body: null, headers: {} }; r.set = (k, v) => { r.headers[k] = v; return r; }; r.status = (c) => { r.code = c; return r; }; r.json = (b) => { r.body = b; return r; }; return r; };
const req = (body, ip = '1.2.3.4', method = 'POST') => ({ method, body, headers: { 'x-forwarded-for': ip } });

test('GET запрещён, ответы не кэшируются', async () => {
  const h = makeHandlers({ db: createFakeDb(), now: () => NOW });
  const r = res(); await h.access(req({}, '1.1.1.1', 'GET'), r);
  assert.strictEqual(r.code, 405);
  const r2 = res(); await h.access(req({ deviceId: 'a1-device-0000000001' }), r2);
  assert.strictEqual(r2.headers['Cache-Control'], 'no-store');
  assert.strictEqual(r2.body.status, 'ok');
});

test('access: плохой идентификатор даёт 400', async () => {
  const h = makeHandlers({ db: createFakeDb(), now: () => NOW });
  const r = res(); await h.access(req({ deviceId: 'x' }), r);
  assert.strictEqual(r.code, 400);
});

test('activateKey и promo: полный сценарий через HTTP-обработчики', async () => {
  const db = createFakeDb();
  const h = makeHandlers({ db, now: () => NOW });
  const [key] = await generate({ db, kind: 'keys', count: 1, type: 'm1', now: NOW });
  const [promo] = await generate({ db, kind: 'promo', count: 1, days: 30, now: NOW });
  const dev = 'a1-device-0000000002';
  let r = res(); await h.activateKey(req({ deviceId: dev, key: 'AMIX-AAAA-AAAA-AAAA-AAAA' }), r);
  assert.strictEqual(r.body.status, 'not_found');
  r = res(); await h.activateKey(req({ deviceId: dev, key }), r);
  assert.strictEqual(r.code, 200); assert.strictEqual(r.body.status, 'ok');
  r = res(); await h.promo(req({ deviceId: dev, code: promo }), r);
  assert.strictEqual(r.body.status, 'ok');
  r = res(); await h.promo(req({ deviceId: dev, code: '' }), r);
  assert.strictEqual(r.code, 400);
  r = res(); await h.access(req({ deviceId: dev }), r);
  assert.strictEqual(r.body.isPro, true);
});

test('защита от потока запросов: после 300 запросов в час с адреса ответ 429', async () => {
  const h = makeHandlers({ db: createFakeDb(), now: () => NOW });
  let last;
  for (let i = 0; i < 301; i++) { last = res(); await h.activateKey(req({ deviceId: 'a1-device-0000000003', key: 'x' }, '9.9.9.9'), last); }
  assert.strictEqual(last.code, 429);
  const other = res(); await h.access(req({ deviceId: 'a1-device-0000000004' }, '8.8.8.8'), other);
  assert.strictEqual(other.code, 200, 'другой адрес не затронут');
});

test('не больше 30 новых устройств в час с одного адреса', async () => {
  const h = makeHandlers({ db: createFakeDb(), now: () => NOW });
  let blocked = 0;
  for (let i = 0; i < 35; i++) { const r = res(); await h.access(req({ deviceId: `a1-device-new-${String(i).padStart(8, '0')}` }, '7.7.7.7'), r); if (r.code === 429) blocked++; }
  assert.strictEqual(blocked, 5);
});

test('ошибка базы: 500 без утечки подробностей', async () => {
  const db = createFakeDb(); db.runTransaction = async () => { throw new Error('secret db detail'); };
  const h = makeHandlers({ db, now: () => NOW });
  const r = res(); await h.access(req({ deviceId: 'a1-device-0000000005' }), r);
  assert.strictEqual(r.code, 500);
  assert.ok(!JSON.stringify(r.body).includes('secret'));
});
