'use strict';
/**
 * Статус доступа устройства. Регистрации нет: устройство опознаётся по идентификатору (хэш на сервере).
 * Пробный период 7 дней считается по часам сервера от первого обращения устройства, поэтому перевод времени,
 * перезапуск и удаление данных приложения его не сбрасывают.
 */
const { TRIAL_MS, toMillis, validDeviceId, deviceHash } = require('./common');

async function getAccess({ db, deviceId, now = Date.now(), toTimestamp = (ms) => ms }) {
  if (!validDeviceId(deviceId)) return { status: 'bad_request' };
  const ref = db.collection('nativeDevices').doc(deviceHash(deviceId));
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const d = snap.exists ? snap.data() : {};
    let trialEndsAt = toMillis(d.trialEndsAt);
    let created = false;
    if (!trialEndsAt) {
      trialEndsAt = now + TRIAL_MS;
      created = true;
      tx.set(ref, { createdAt: toTimestamp(now), trialEndsAt: toTimestamp(trialEndsAt) }, { merge: true });
    }
    const proExpiresAt = toMillis(d.proExpiresAt);
    return { status: 'ok', serverNow: now, trialEndsAt, proExpiresAt, isPro: proExpiresAt > now, created };
  });
}

async function deviceExists({ db, deviceId }) {
  if (!validDeviceId(deviceId)) return false;
  const snap = await db.collection('nativeDevices').doc(deviceHash(deviceId)).get();
  return snap.exists && toMillis(snap.data().trialEndsAt) > 0;
}

module.exports = { getAccess, deviceExists };
