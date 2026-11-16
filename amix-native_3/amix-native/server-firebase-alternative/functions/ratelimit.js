'use strict';
/** Счётчики в Firestore (работают между экземплярами функций): окно windowMs, не больше limit событий. */
async function consume({ db, key, limit, windowMs, now }) {
  const ref = db.collection('rateLimits').doc(key);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    let d = snap.exists ? snap.data() : { windowStart: now, count: 0 };
    if (now - d.windowStart > windowMs) d = { windowStart: now, count: 0 };
    if (d.count >= limit) return { ok: false };
    tx.set(ref, { windowStart: d.windowStart, count: d.count + 1 });
    return { ok: true };
  });
}

/** Заблокирован ли ключ (без увеличения счётчика). */
async function isBlocked({ db, key, limit, windowMs, now }) {
  const snap = await db.collection('rateLimits').doc(key).get();
  if (!snap.exists) return false;
  const d = snap.data();
  return now - d.windowStart <= windowMs && d.count >= limit;
}

module.exports = { consume, isBlocked };
