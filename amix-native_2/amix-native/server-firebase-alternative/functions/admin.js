'use strict';
/** Операции администратора: создание, список, статистика, отзыв и выгрузка в CSV. Работают с Admin SDK, снаружи недоступны. */
const { DAY_MS, toMillis, deviceHash } = require('./common');
const { SPECS, generateCode, normalizeCode } = require('./codes');

const TYPE_DAYS = { m1: 30, m6: 180, m12: 365, forever: 36500 };

async function generate({ db, kind, count, days, type = 'custom', validDays = 0, now = Date.now(), toTimestamp = (ms) => ms }) {
  const sp = SPECS[kind];
  if (!sp) throw new Error('kind must be keys or promo');
  if (!Number.isInteger(count) || count < 1 || count > 5000) throw new Error('count must be 1..5000');
  const d = days || TYPE_DAYS[type] || 30;
  const existing = new Set((await db.collection(sp.collection).get()).docs.map((x) => x.id));
  const fresh = new Set();
  while (fresh.size < count) {
    const code = generateCode(kind);
    if (!existing.has(code)) fresh.add(code);
  }
  const codes = [...fresh];
  for (let i = 0; i < codes.length; i += 400) {
    const batch = db.batch();
    for (const code of codes.slice(i, i + 400)) {
      batch.create(db.collection(sp.collection).doc(code), {
        code, type, days: d, status: 'unused',
        createdAt: toTimestamp(now),
        validUntil: validDays > 0 ? toTimestamp(now + validDays * DAY_MS) : null
      });
    }
    await batch.commit();
  }
  return codes;
}

const iso = (ms) => (ms ? new Date(ms).toISOString() : '');

function rowOf(doc, now) {
  const c = doc.data();
  const validUntil = toMillis(c.validUntil);
  let status = 'unused';
  if (c.status === 'revoked') status = 'revoked';
  else if (c.activatedBy) status = 'used';
  else if (validUntil && validUntil < now) status = 'expired';
  return {
    code: doc.id, type: c.type || '', days: c.days || '', status,
    createdAt: iso(toMillis(c.createdAt)), validUntil: iso(validUntil),
    activatedAt: iso(toMillis(c.activatedAt)), activatedBy: c.activatedBy ? String(c.activatedBy).slice(0, 8) : ''
  };
}

async function list({ db, kind, status, now = Date.now() }) {
  const docs = (await db.collection(SPECS[kind].collection).get()).docs;
  const rows = docs.map((d) => rowOf(d, now)).sort((a, b) => a.code.localeCompare(b.code));
  return status ? rows.filter((r) => r.status === status) : rows;
}

async function stats({ db, kind, now = Date.now() }) {
  const out = { total: 0, unused: 0, used: 0, revoked: 0, expired: 0 };
  for (const r of await list({ db, kind, now })) { out.total++; out[r.status]++; }
  return out;
}

async function revoke({ db, kind, code }) {
  const norm = normalizeCode(kind, code);
  if (!norm) return false;
  const ref = db.collection(SPECS[kind].collection).doc(norm);
  const snap = await ref.get();
  if (!snap.exists) return false;
  await ref.set({ ...snap.data(), status: 'revoked' });
  return true;
}

const COLS = ['code', 'type', 'days', 'status', 'createdAt', 'validUntil', 'activatedAt', 'activatedBy'];
const esc = (v) => { const s = String(v == null ? '' : v); return /[",\n\r;]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
function toCsv(rows) {
  return [COLS.join(','), ...rows.map((r) => COLS.map((c) => esc(r[c])).join(','))].join('\r\n') + '\r\n';
}

module.exports = { generate, list, stats, revoke, toCsv, TYPE_DAYS, rowOf, deviceHash };
