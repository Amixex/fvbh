'use strict';
/**
 * Таймеры доступа считает сервер (по его часам), поэтому перевод времени на телефоне и переустановка
 * приложения их не сбрасывают:
 *  - гость: 24 часа с первого обращения устройства (ключ: SHA-256 от идентификатора устройства);
 *  - аккаунт: 7 дней с первого входа (поле users/{uid}.trialEndsAt; если его уже записала веб-версия, берётся оно);
 *  - Pro: users/{uid}.isPro + proExpiresAt (пишет только сервер).
 */
const crypto = require('node:crypto');

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const GUEST_TRIAL_MS = 24 * HOUR_MS;
const USER_TRIAL_MS = 7 * DAY_MS;

function toMillis(v) {
  if (v == null) return 0;
  if (typeof v === 'number') return v;
  if (typeof v.toMillis === 'function') return v.toMillis();
  if (v instanceof Date) return v.getTime();
  if (typeof v.seconds === 'number') return v.seconds * 1000;
  return 0;
}

function hashDeviceId(id) {
  return crypto.createHash('sha256').update(String(id)).digest('hex');
}

async function getAccess({ db, deviceId, uid, now = Date.now(), toTimestamp = (ms) => ms }) {
  const out = { status: 'ok', serverNow: now, guestEndsAt: 0, user: null };

  if (typeof deviceId === 'string' && /^[A-Za-z0-9_-]{8,128}$/.test(deviceId)) {
    const ref = db.collection('nativeDevices').doc(hashDeviceId(deviceId));
    out.guestEndsAt = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const existing = snap.exists ? toMillis(snap.data().guestTrialEndsAt) : 0;
      if (existing > 0) return existing;
      const ends = now + GUEST_TRIAL_MS;
      tx.set(ref, { createdAt: toTimestamp(now), guestTrialEndsAt: toTimestamp(ends) }, { merge: true });
      return ends;
    });
  }

  if (uid) {
    const ref = db.collection('users').doc(uid);
    out.user = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const d = snap.exists ? snap.data() : {};
      let trialEndsAt = toMillis(d.trialEndsAt);
      if (!trialEndsAt) {
        trialEndsAt = now + USER_TRIAL_MS;
        tx.set(ref, { uid, trialStartedAt: toTimestamp(now), trialEndsAt: toTimestamp(trialEndsAt) }, { merge: true });
      }
      const proExpiresAt = toMillis(d.proExpiresAt);
      return { uid, trialEndsAt, isPro: d.isPro === true && proExpiresAt > now, proExpiresAt };
    });
  }
  return out;
}

function makeAccessHandler({ verifyToken, db, now = () => Date.now(), toTimestamp }) {
  return async function accessHandler(req, res) {
    res.set('Cache-Control', 'no-store');
    if (req.method !== 'POST') return res.status(405).json({ status: 'method_not_allowed' });
    let uid = null;
    let authError = false;
    const m = /^Bearer (.+)$/.exec(req.get('Authorization') || '');
    if (m) {
      try {
        uid = (await verifyToken(m[1])).uid;
      } catch (e) {
        authError = true;
      }
    }
    try {
      const out = await getAccess({ db, deviceId: req.body && req.body.deviceId, uid, now: now(), toTimestamp });
      if (authError) out.authError = true;
      return res.status(200).json(out);
    } catch (e) {
      console.error('access failed:', e);
      return res.status(500).json({ status: 'server_error' });
    }
  };
}

module.exports = { getAccess, makeAccessHandler, toMillis, hashDeviceId, GUEST_TRIAL_MS, USER_TRIAL_MS, DAY_MS };
