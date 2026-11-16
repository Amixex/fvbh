'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createFakeDb } = require('./fakeFirestore');
const { PLANS, createPayment, verifyAndGrant, syncPayments, makePayHandlers } = require('../payments');
const { DAY_MS } = require('../access');

const NOW = 1_800_000_000_000;
const cfg = { shopId: 'shop1', secretKey: 'secret1', returnUrl: 'https://example.com/paid.html' };

// поддельная ЮKassa: хранит платежи, отвечает как API
function fakeYooKassa() {
  const payments = new Map();
  let n = 0;
  const calls = [];
  const fetchFn = async (url, opts = {}) => {
    calls.push({ url, opts });
    if (opts.method === 'POST') {
      const body = JSON.parse(opts.body);
      const id = `2f5a1c00-0000-4000-8000-${String(++n).padStart(12, '0')}`;
      payments.set(id, { id, status: 'pending', paid: false, amount: body.amount, metadata: body.metadata });
      return { ok: true, status: 200, json: async () => ({ id, status: 'pending', confirmation: { type: 'redirect', confirmation_url: `https://yoomoney.ru/checkout/${id}` } }) };
    }
    const id = decodeURIComponent(url.split('/').pop());
    const p = payments.get(id);
    return p ? { ok: true, status: 200, json: async () => p } : { ok: false, status: 404, json: async () => ({}) };
  };
  return { fetchFn, payments, calls, pay: (id) => { const p = payments.get(id); p.status = 'succeeded'; p.paid = true; } };
}

test('тарифы: 200, 900 (−25%) и 1500 (−37%)', () => {
  assert.strictEqual(PLANS.m1.rub, '200.00');
  assert.strictEqual(PLANS.m6.rub, '900.00');
  assert.strictEqual(PLANS.m12.rub, '1500.00');
  assert.strictEqual(Math.round((1 - 900 / (6 * 200)) * 100), 25);
  assert.strictEqual(Math.floor((1 - 1500 / (12 * 200)) * 100), 37);
});

test('создание платежа: цена берётся с сервера, клиент её не задаёт, ключи уходят только в заголовок ЮKassa', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  const r = await createPayment({ db, uid: 'u1', plan: 'm6', cfg, fetchFn: yk.fetchFn, now: NOW, idempotenceKey: 'k1' });
  assert.strictEqual(r.status, 'ok');
  assert.ok(r.url.startsWith('https://yoomoney.ru/'));
  const sent = JSON.parse(yk.calls[0].opts.body);
  assert.deepStrictEqual(sent.amount, { value: '900.00', currency: 'RUB' });
  assert.deepStrictEqual(sent.metadata, { uid: 'u1', plan: 'm6' });
  assert.strictEqual(sent.confirmation.return_url, cfg.returnUrl);
  assert.strictEqual(yk.calls[0].opts.headers['Idempotence-Key'], 'k1');
  assert.ok(yk.calls[0].opts.headers.Authorization.startsWith('Basic '));
  assert.strictEqual(db._store.get(`payments/${r.paymentId}`).status, 'pending');
});

test('неизвестный тариф и пустая настройка отклоняются', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  assert.strictEqual((await createPayment({ db, uid: 'u1', plan: 'free', cfg, fetchFn: yk.fetchFn })).status, 'bad_request');
  assert.strictEqual((await createPayment({ db, uid: 'u1', plan: 'm1', cfg: {}, fetchFn: yk.fetchFn })).status, 'not_configured');
  assert.strictEqual(yk.calls.length, 0);
});

test('оплаченный платёж даёт Pro на срок тарифа; повторный вебхук ничего не добавляет', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  const { paymentId } = await createPayment({ db, uid: 'u1', plan: 'm12', cfg, fetchFn: yk.fetchFn, now: NOW, idempotenceKey: 'k' });
  assert.strictEqual((await verifyAndGrant({ db, paymentId, cfg, fetchFn: yk.fetchFn, now: NOW })).status, 'pending');
  assert.strictEqual(db._store.has('users/u1'), false, 'до оплаты Pro нет');
  yk.pay(paymentId);
  const g = await verifyAndGrant({ db, paymentId, cfg, fetchFn: yk.fetchFn, now: NOW });
  assert.deepStrictEqual(g, { status: 'granted', proExpiresAt: NOW + 365 * DAY_MS });
  const again = await verifyAndGrant({ db, paymentId, cfg, fetchFn: yk.fetchFn, now: NOW + 1000 });
  assert.strictEqual(again.status, 'already');
  assert.strictEqual(db._store.get('users/u1').proExpiresAt, NOW + 365 * DAY_MS);
  assert.strictEqual(db._store.get('users/u1').isPro, true);
});

test('оплата продлевает остаток, а не обнуляет его', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  db._store.set('users/u1', { isPro: true, proExpiresAt: NOW + 10 * DAY_MS });
  const { paymentId } = await createPayment({ db, uid: 'u1', plan: 'm1', cfg, fetchFn: yk.fetchFn, now: NOW, idempotenceKey: 'k' });
  yk.pay(paymentId);
  const g = await verifyAndGrant({ db, paymentId, cfg, fetchFn: yk.fetchFn, now: NOW });
  assert.strictEqual(g.proExpiresAt, NOW + 40 * DAY_MS);
});

test('ЗАЩИТА: поддельный вебхук без реальной оплаты Pro не выдаёт', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  const { paymentId } = await createPayment({ db, uid: 'u1', plan: 'm1', cfg, fetchFn: yk.fetchFn, now: NOW, idempotenceKey: 'k' });
  const h = makePayHandlers({ verifyToken: async () => ({ uid: 'x' }), db, cfg, fetchFn: yk.fetchFn, now: () => NOW });
  const res = { code: 0, body: null, status(c) { this.code = c; return this; }, send(b) { this.body = b; return this; } };
  await h.webhook({ method: 'POST', body: { event: 'payment.succeeded', object: { id: paymentId } } }, res);
  assert.strictEqual(res.code, 200);
  assert.strictEqual(db._store.has('users/u1'), false);
  // платёж, которого сервер не создавал, тоже не принимается
  yk.payments.set('2f5a1c00-0000-4000-8000-999999999999', { id: '2f5a1c00-0000-4000-8000-999999999999', status: 'succeeded', paid: true, amount: { value: '200.00', currency: 'RUB' }, metadata: { uid: 'u2', plan: 'm1' } });
  const r = await verifyAndGrant({ db, paymentId: '2f5a1c00-0000-4000-8000-999999999999', cfg, fetchFn: yk.fetchFn, now: NOW });
  assert.strictEqual(r.status, 'invalid');
  assert.strictEqual(db._store.has('users/u2'), false);
});

test('ЗАЩИТА: неверная сумма или чужой пользователь не дают Pro', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  const { paymentId } = await createPayment({ db, uid: 'u1', plan: 'm12', cfg, fetchFn: yk.fetchFn, now: NOW, idempotenceKey: 'k' });
  yk.pay(paymentId);
  yk.payments.get(paymentId).amount.value = '1.00';
  assert.strictEqual((await verifyAndGrant({ db, paymentId, cfg, fetchFn: yk.fetchFn, now: NOW })).status, 'invalid');
  yk.payments.get(paymentId).amount.value = '1500.00';
  yk.payments.get(paymentId).metadata.uid = 'u2';
  assert.strictEqual((await verifyAndGrant({ db, paymentId, cfg, fetchFn: yk.fetchFn, now: NOW })).status, 'invalid');
  assert.strictEqual(db._store.has('users/u1'), false);
  assert.strictEqual(db._store.has('users/u2'), false);
});

test('отмена платежа и ошибка API не дают Pro', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  const { paymentId } = await createPayment({ db, uid: 'u1', plan: 'm1', cfg, fetchFn: yk.fetchFn, now: NOW, idempotenceKey: 'k' });
  yk.payments.get(paymentId).status = 'canceled';
  assert.strictEqual((await verifyAndGrant({ db, paymentId, cfg, fetchFn: yk.fetchFn, now: NOW })).status, 'canceled');
  assert.strictEqual((await verifyAndGrant({ db, paymentId: '2f5a1c00-0000-4000-8000-000000000404', cfg, fetchFn: yk.fetchFn, now: NOW })).status, 'error');
  assert.strictEqual(db._store.has('users/u1'), false);
});

test('синхронизация после возврата в приложение: ждёт оплату, потом выдаёт Pro', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  const { paymentId } = await createPayment({ db, uid: 'u1', plan: 'm6', cfg, fetchFn: yk.fetchFn, now: NOW, idempotenceKey: 'k' });
  assert.deepStrictEqual(await syncPayments({ db, uid: 'u1', cfg, fetchFn: yk.fetchFn, now: NOW }), { status: 'pending' });
  yk.pay(paymentId);
  assert.deepStrictEqual(await syncPayments({ db, uid: 'u1', cfg, fetchFn: yk.fetchFn, now: NOW }), { status: 'ok', proExpiresAt: NOW + 180 * DAY_MS });
  assert.deepStrictEqual(await syncPayments({ db, uid: 'u1', cfg, fetchFn: yk.fetchFn, now: NOW }), { status: 'none' });
  assert.strictEqual((await syncPayments({ db, uid: 'nobody', cfg, fetchFn: yk.fetchFn, now: NOW })).status, 'none');
});

test('обработчики: без токена 401, плохой тариф 400', async () => {
  const db = createFakeDb(); const yk = fakeYooKassa();
  const h = makePayHandlers({ verifyToken: async () => ({ uid: 'u1' }), db, cfg, fetchFn: yk.fetchFn, now: () => NOW });
  const res = () => { const r = { code: 200, body: null, headers: {} }; r.set = (k, v) => { r.headers[k] = v; return r; }; r.status = (c) => { r.code = c; return r; }; r.json = (b) => { r.body = b; return r; }; return r; };
  const get = (headers) => (k) => headers[k];
  let r = res(); await h.create({ method: 'POST', body: { plan: 'm1' }, get: get({}) }, r); assert.strictEqual(r.code, 401);
  r = res(); await h.create({ method: 'POST', body: { plan: 'zzz' }, get: get({ Authorization: 'Bearer t' }) }, r); assert.strictEqual(r.code, 400);
  r = res(); await h.create({ method: 'POST', body: { plan: 'm1' }, get: get({ Authorization: 'Bearer t' }) }, r); assert.strictEqual(r.code, 200); assert.ok(r.body.url);
});
