'use strict';
/**
 * Активация промокода на сервере. Правила взяты из index.html (см. PARITY.md, раздел 5):
 *  - награда: Pro на `days` дней (у всех кодов из seed 30), срок считается от момента активации;
 *  - у кода нет ограничения на число активаций (любой пользователь может использовать любой код);
 *  - один и тот же пользователь не может активировать один код дважды;
 *  - код сравнивается без учёта регистра, пробелы по краям обрезаются.
 * Дополнительно от подбора кодов: не больше 10 неудачных попыток в час на пользователя.
 *
 * db — Firestore-совместимый объект (collection().doc(), runTransaction). В тестах подставляется поддельный.
 */
const DAY_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const MAX_FAILED_PER_HOUR = 10;
const DEFAULT_DAYS = 30;

function normalizeCode(code) {
  return String(code == null ? '' : code).trim().toUpperCase();
}

async function redeemPromo({ db, uid, code, now = Date.now(), toTimestamp = (ms) => ms }) {
  const c = normalizeCode(code);
  if (!uid || !/^[A-Z0-9-]{4,64}$/.test(c)) return { status: 'bad_request' };

  return db.runTransaction(async (tx) => {
    const attemptsRef = db.collection('promoAttempts').doc(uid);
    const codeRef = db.collection('promocodes').doc(c);
    const redemptionRef = db.collection('promoRedemptions').doc(`${uid}__${c}`);
    const userRef = db.collection('users').doc(uid);

    const attemptsSnap = await tx.get(attemptsRef);
    const codeSnap = await tx.get(codeRef);
    const redemptionSnap = await tx.get(redemptionRef);

    let attempts = attemptsSnap.exists ? attemptsSnap.data() : { windowStart: now, failed: 0 };
    if (now - attempts.windowStart > HOUR_MS) attempts = { windowStart: now, failed: 0 };
    if (attempts.failed >= MAX_FAILED_PER_HOUR) return { status: 'rate_limited' };

    const fail = (status) => {
      tx.set(attemptsRef, { windowStart: attempts.windowStart, failed: attempts.failed + 1 });
      return { status };
    };

    if (!codeSnap.exists || codeSnap.data().active === false) return fail('not_found');
    if (redemptionSnap.exists) return fail('used');

    const codeData = codeSnap.data();
    const days = Number(codeData.days) > 0 ? Number(codeData.days) : DEFAULT_DAYS;
    const proExpiresAt = now + days * DAY_MS;

    tx.set(codeRef, {
      ...codeData,
      activations: (codeData.activations || 0) + 1,
      firstUsedAt: codeData.firstUsedAt || toTimestamp(now),
      lastUsedAt: toTimestamp(now),
      lastUsedBy: uid
    });
    tx.set(redemptionRef, { uid, code: c, days, redeemedAt: toTimestamp(now) });
    tx.set(
      userRef,
      {
        isPro: true,
        proStartedAt: toTimestamp(now),
        proExpiresAt: toTimestamp(proExpiresAt),
        promoUsed: true,
        promoCode: c
      },
      { merge: true }
    );
    return { status: 'ok', days, proExpiresAt };
  });
}

module.exports = { redeemPromo, normalizeCode, MAX_FAILED_PER_HOUR, DAY_MS, HOUR_MS };
