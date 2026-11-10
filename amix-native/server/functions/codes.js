'use strict';
/**
 * Лицензионные ключи (коллекция licenses) и промокоды (коллекция promos).
 * Оба вида: случайные коды из алфавита без похожих символов, каждый код активируется один раз,
 * срок действия кода (validUntil), длительность PRO (days) и тип (type) задаются при создании.
 * Проверка и активация только на сервере; клиент получает только результат.
 */
const crypto = require('node:crypto');
const { DAY_MS, HOUR_MS, toMillis, validDeviceId, deviceHash } = require('./common');
const { consume, isBlocked } = require('./ratelimit');

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 32 символа, без I, O, 0, 1
const SPECS = {
  keys: { collection: 'licenses', prefix: 'AMIX', groups: 4 },   // AMIX-XXXX-XXXX-XXXX-XXXX (80 бит)
  promo: { collection: 'promos', prefix: 'PROMO', groups: 3 }    // PROMO-XXXX-XXXX-XXXX (60 бит)
};
const MAX_FAILURES_PER_HOUR = 10;

const entropyBits = (kind) => Math.floor(SPECS[kind].groups * 4 * Math.log2(ALPHABET.length));

function generateCode(kind) {
  const sp = SPECS[kind];
  const groups = [];
  for (let g = 0; g < sp.groups; g++) {
    let part = '';
    for (let i = 0; i < 4; i++) part += ALPHABET[crypto.randomInt(ALPHABET.length)];
    groups.push(part);
  }
  return [sp.prefix, ...groups].join('-');
}

/** Приводит ввод пользователя к каноническому виду или возвращает null. */
function normalizeCode(kind, input) {
  const sp = SPECS[kind];
  let s = String(input == null ? '' : input).toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (!s.startsWith(sp.prefix)) return null;
  s = s.slice(sp.prefix.length);
  if (s.length !== sp.groups * 4) return null;
  for (const c of s) if (!ALPHABET.includes(c)) return null;
  const parts = [];
  for (let i = 0; i < s.length; i += 4) parts.push(s.slice(i, i + 4));
  return [sp.prefix, ...parts].join('-');
}

async function redeemCode({ db, kind, deviceId, code, now = Date.now(), toTimestamp = (ms) => ms }) {
  const sp = SPECS[kind];
  if (!validDeviceId(deviceId) || !String(code == null ? '' : code).trim()) return { status: 'bad_request' };
  const devHash = deviceHash(deviceId);
  const failKey = `fail:${kind}:${devHash}`;
  const limit = { db, key: failKey, limit: MAX_FAILURES_PER_HOUR, windowMs: HOUR_MS, now };
  if (await isBlocked(limit)) return { status: 'rate_limited' };

  const fail = async (status) => {
    await consume(limit); // неудачные попытки копятся: после 10 за час активация блокируется на час
    return { status };
  };

  const norm = normalizeCode(kind, code);
  if (!norm) return fail('not_found');

  const result = await db.runTransaction(async (tx) => {
    const ref = db.collection(sp.collection).doc(norm);
    const devRef = db.collection('nativeDevices').doc(devHash);
    const snap = await tx.get(ref);
    const devSnap = await tx.get(devRef);
    if (!snap.exists) return { status: 'not_found' };
    const c = snap.data();
    if (c.status === 'revoked') return { status: 'revoked' };
    const dev = devSnap.exists ? devSnap.data() : {};
    const current = toMillis(dev.proExpiresAt);
    if (c.activatedBy) {
      if (c.activatedBy === devHash) return { status: 'ok', already: true, days: c.days, proExpiresAt: current };
      return { status: 'used' };
    }
    if (c.validUntil && toMillis(c.validUntil) < now) return { status: 'expired' };
    const days = Number(c.days) > 0 ? Number(c.days) : 30;
    const proExpiresAt = Math.max(now, current) + days * DAY_MS; // активация продлевает остаток
    tx.set(ref, { ...c, status: 'used', activatedBy: devHash, activatedAt: toTimestamp(now) });
    tx.set(devRef, { proExpiresAt: toTimestamp(proExpiresAt), lastCode: norm }, { merge: true });
    return { status: 'ok', days, proExpiresAt };
  });
  if (result.status === 'ok') return result;
  return fail(result.status);
}

module.exports = { SPECS, ALPHABET, entropyBits, generateCode, normalizeCode, redeemCode, MAX_FAILURES_PER_HOUR };
