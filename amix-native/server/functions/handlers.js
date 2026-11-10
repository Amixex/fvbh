'use strict';
const { HOUR_MS, clientKey } = require('./common');
const { getAccess, deviceExists } = require('./access');
const { redeemCode } = require('./codes');
const { consume } = require('./ratelimit');

const HTTP = { ok: 200, not_found: 200, used: 200, expired: 200, revoked: 200, bad_request: 400, rate_limited: 429 };

/**
 * Три открытые функции (без регистрации): access (статус и пробный период), activateKey (лицензионный ключ),
 * promo (промокод). Защита: не больше 300 запросов в час с одного адреса, 30 новых устройств в час с адреса,
 * 10 неудачных попыток активации в час с устройства (см. codes.js).
 */
function makeHandlers({ db, now = () => Date.now(), salt = '', toTimestamp }) {
  async function guard(req, res) {
    res.set('Cache-Control', 'no-store');
    if (req.method !== 'POST') { res.status(405).json({ status: 'method_not_allowed' }); return null; }
    const key = clientKey(req, salt);
    const t = now();
    const r = await consume({ db, key: `ip:${key}`, limit: 300, windowMs: HOUR_MS, now: t });
    if (!r.ok) { res.status(429).json({ status: 'rate_limited' }); return null; }
    return { key, t, body: req.body && typeof req.body === 'object' ? req.body : {} };
  }
  const wrap = (fn) => async (req, res) => {
    try {
      const g = await guard(req, res);
      if (!g) return;
      return await fn(g, res);
    } catch (e) {
      console.error('handler failed:', e);
      return res.status(500).json({ status: 'server_error' });
    }
  };
  const redeem = (kind) => wrap(async (g, res) => {
    const out = await redeemCode({ db, kind, deviceId: g.body.deviceId, code: g.body.key !== undefined ? g.body.key : g.body.code, now: g.t, toTimestamp });
    return res.status(HTTP[out.status] || 200).json(out);
  });
  return {
    access: wrap(async (g, res) => {
      if (!(await deviceExists({ db, deviceId: g.body.deviceId }))) {
        const n = await consume({ db, key: `newdev:${g.key}`, limit: 30, windowMs: HOUR_MS, now: g.t });
        if (!n.ok) return res.status(429).json({ status: 'rate_limited' });
      }
      const out = await getAccess({ db, deviceId: g.body.deviceId, now: g.t, toTimestamp });
      return res.status(HTTP[out.status] || 200).json(out);
    }),
    activateKey: redeem('keys'),
    promo: redeem('promo')
  };
}

module.exports = { makeHandlers };
