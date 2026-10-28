'use strict';
/**
 * Оплата Pro через ЮKassa. Цену и срок определяет сервер по тарифу, а не клиент.
 * Pro выдаётся только после того, как сервер сам запросил платёж у ЮKassa и убедился, что он оплачен,
 * сумма совпала с тарифом, а платёж создан этим же сервером для этого же пользователя.
 * Поэтому поддельный вызов вебхука ничего не даёт.
 */
const { toMillis, DAY_MS } = require('./access');

const PLANS = {
  m1: { rub: '200.00', days: 30, label: 'Amix Pro, 1 месяц' },
  m6: { rub: '900.00', days: 180, label: 'Amix Pro, 6 месяцев' },
  m12: { rub: '1500.00', days: 365, label: 'Amix Pro, 12 месяцев' }
};
const API = 'https://api.yookassa.ru/v3/payments';

function basic(shopId, secretKey) {
  return 'Basic ' + Buffer.from(`${shopId}:${secretKey}`).toString('base64');
}

async function createPayment({ db, uid, email, plan, cfg, fetchFn = fetch, now = Date.now(), toTimestamp = (ms) => ms, idempotenceKey }) {
  const def = PLANS[plan];
  if (!uid || !def) return { status: 'bad_request' };
  if (!cfg.shopId || !cfg.secretKey || !cfg.returnUrl) return { status: 'not_configured' };

  const body = {
    amount: { value: def.rub, currency: 'RUB' },
    capture: true,
    confirmation: { type: 'redirect', return_url: cfg.returnUrl },
    description: `${def.label} (${uid.slice(0, 6)})`,
    metadata: { uid, plan }
  };
  if (cfg.receipt && email) {
    body.receipt = {
      customer: { email },
      items: [{
        description: def.label, quantity: '1.00', amount: { value: def.rub, currency: 'RUB' },
        vat_code: 1, payment_mode: 'full_payment', payment_subject: 'service'
      }]
    };
  }
  const resp = await fetchFn(API, {
    method: 'POST',
    headers: {
      Authorization: basic(cfg.shopId, cfg.secretKey),
      'Idempotence-Key': idempotenceKey,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(body)
  });
  if (!resp.ok) return { status: 'provider_error', http: resp.status };
  const data = await resp.json();
  const url = data && data.confirmation && data.confirmation.confirmation_url;
  if (!data.id || !url) return { status: 'provider_error' };

  await db.runTransaction(async (tx) => {
    const payRef = db.collection('payments').doc(data.id);
    const pendRef = db.collection('pendingPayments').doc(uid);
    const pend = await tx.get(pendRef);
    const ids = pend.exists ? (pend.data().ids || []) : [];
    tx.set(payRef, { uid, plan, amount: def.rub, status: 'pending', createdAt: toTimestamp(now) });
    tx.set(pendRef, { ids: [...ids, data.id].slice(-5) });
  });
  return { status: 'ok', paymentId: data.id, url };
}

async function verifyAndGrant({ db, paymentId, cfg, fetchFn = fetch, now = Date.now(), toTimestamp = (ms) => ms }) {
  if (!paymentId || !/^[A-Za-z0-9-]{8,64}$/.test(String(paymentId))) return { status: 'invalid' };
  const resp = await fetchFn(`${API}/${encodeURIComponent(paymentId)}`, {
    headers: { Authorization: basic(cfg.shopId, cfg.secretKey) }
  });
  if (!resp.ok) return { status: 'error' };
  const p = await resp.json();
  if (p.status === 'canceled') return { status: 'canceled' };
  if (p.status !== 'succeeded' || p.paid !== true) return { status: 'pending' };

  const uid = p.metadata && p.metadata.uid;
  const def = PLANS[p.metadata && p.metadata.plan];
  if (!uid || !def || !p.amount || p.amount.currency !== 'RUB' || p.amount.value !== def.rub) return { status: 'invalid' };

  return db.runTransaction(async (tx) => {
    const payRef = db.collection('payments').doc(paymentId);
    const userRef = db.collection('users').doc(uid);
    const paySnap = await tx.get(payRef);
    const userSnap = await tx.get(userRef);
    if (!paySnap.exists || paySnap.data().uid !== uid) return { status: 'invalid' };
    const current = toMillis(userSnap.exists ? userSnap.data().proExpiresAt : 0);
    if (paySnap.data().grantedAt) return { status: 'already', proExpiresAt: current };
    const proExpiresAt = Math.max(now, current) + def.days * DAY_MS; // оплата продлевает, а не обнуляет остаток
    tx.set(payRef, { ...paySnap.data(), status: 'succeeded', grantedAt: toTimestamp(now) });
    tx.set(userRef, {
      isPro: true,
      proStartedAt: toTimestamp(now),
      proExpiresAt: toTimestamp(proExpiresAt),
      lastPaymentId: paymentId
    }, { merge: true });
    return { status: 'granted', proExpiresAt };
  });
}

async function syncPayments({ db, uid, cfg, fetchFn, now, toTimestamp }) {
  const pendRef = db.collection('pendingPayments').doc(uid);
  const snap = await pendRef.get();
  const ids = snap.exists ? (snap.data().ids || []) : [];
  let granted = 0;
  let pending = false;
  const stillPending = [];
  for (const id of ids.slice().reverse().slice(0, 3)) {
    const r = await verifyAndGrant({ db, paymentId: id, cfg, fetchFn, now, toTimestamp });
    if (r.status === 'granted' || r.status === 'already') granted = Math.max(granted, r.proExpiresAt || 0);
    else if (r.status === 'pending') { pending = true; stillPending.push(id); }
  }
  if (ids.length) await pendRef.set({ ids: stillPending });
  if (granted) return { status: 'ok', proExpiresAt: granted };
  return { status: pending ? 'pending' : 'none' };
}

function bearer(req) {
  const m = /^Bearer (.+)$/.exec(req.get('Authorization') || '');
  return m ? m[1] : null;
}

function makePayHandlers({ verifyToken, db, cfg, fetchFn, now = () => Date.now(), toTimestamp, newKey = () => require('node:crypto').randomUUID() }) {
  async function authed(req, res) {
    res.set('Cache-Control', 'no-store');
    if (req.method !== 'POST') { res.status(405).json({ status: 'method_not_allowed' }); return null; }
    const token = bearer(req);
    if (!token) { res.status(401).json({ status: 'unauthorized' }); return null; }
    try { return await verifyToken(token); } catch (e) { res.status(401).json({ status: 'unauthorized' }); return null; }
  }
  return {
    async create(req, res) {
      const user = await authed(req, res); if (!user) return;
      try {
        const r = await createPayment({ db, uid: user.uid, email: user.email, plan: req.body && req.body.plan, cfg, fetchFn, now: now(), toTimestamp, idempotenceKey: newKey() });
        const http = r.status === 'ok' ? 200 : r.status === 'bad_request' ? 400 : r.status === 'not_configured' ? 500 : 502;
        return res.status(http).json(r);
      } catch (e) { console.error('payCreate failed:', e); return res.status(500).json({ status: 'server_error' }); }
    },
    async sync(req, res) {
      const user = await authed(req, res); if (!user) return;
      try { return res.status(200).json(await syncPayments({ db, uid: user.uid, cfg, fetchFn, now: now(), toTimestamp })); }
      catch (e) { console.error('paySync failed:', e); return res.status(500).json({ status: 'server_error' }); }
    },
    async webhook(req, res) {
      if (req.method !== 'POST') return res.status(405).send('method not allowed');
      const id = req.body && req.body.object && req.body.object.id;
      const event = req.body && req.body.event;
      if (!id) return res.status(400).send('bad request');
      if (event === 'payment.succeeded' || event === 'payment.canceled' || event === 'payment.waiting_for_capture') {
        try { await verifyAndGrant({ db, paymentId: id, cfg, fetchFn, now: now(), toTimestamp }); }
        catch (e) { console.error('webhook failed:', e); return res.status(500).send('error'); }
      }
      return res.status(200).send('ok');
    }
  };
}

module.exports = { PLANS, createPayment, verifyAndGrant, syncPayments, makePayHandlers };
