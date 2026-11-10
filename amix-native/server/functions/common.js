'use strict';
const crypto = require('node:crypto');

const DAY_MS = 24 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const TRIAL_MS = 7 * DAY_MS; // бесплатный период: ровно 7 дней (168 часов) с первого запуска

const sha256 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');

function toMillis(v) {
  if (v == null) return 0;
  if (typeof v === 'number') return v;
  if (typeof v.toMillis === 'function') return v.toMillis();
  if (v instanceof Date) return v.getTime();
  if (typeof v.seconds === 'number') return v.seconds * 1000;
  return 0;
}

const validDeviceId = (id) => typeof id === 'string' && /^[A-Za-z0-9_-]{8,128}$/.test(id);

/** Идентификатор устройства хранится только в виде хэша. */
const deviceHash = (id) => sha256('device:' + id);

/** Ключ клиента для ограничения частоты: хэш IP с солью, сам адрес не сохраняется. */
function clientKey(req, salt) {
  const forwarded = (req.headers && (req.headers['x-forwarded-for'] || req.headers['X-Forwarded-For'])) || req.ip || '';
  const ip = String(forwarded).split(',')[0].trim();
  return sha256((salt || '') + '|' + ip).slice(0, 32);
}

module.exports = { DAY_MS, HOUR_MS, TRIAL_MS, sha256, toMillis, validDeviceId, deviceHash, clientKey };
