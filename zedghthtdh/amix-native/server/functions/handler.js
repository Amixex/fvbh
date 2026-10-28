'use strict';
const { redeemPromo } = require('./promo');

/**
 * HTTP-обработчик активации промокода. Клиент шлёт POST с заголовком
 * Authorization: Bearer <Firebase ID token> и JSON {"code": "..."}.
 * verifyToken(token) возвращает {uid} или бросает ошибку.
 */
function makePromoHandler({ verifyToken, db, now = () => Date.now(), toTimestamp }) {
  return async function promoHandler(req, res) {
    res.set('Cache-Control', 'no-store');
    if (req.method !== 'POST') return res.status(405).json({ status: 'method_not_allowed' });

    const m = /^Bearer (.+)$/.exec(req.get('Authorization') || '');
    if (!m) return res.status(401).json({ status: 'unauthorized' });
    let decoded;
    try {
      decoded = await verifyToken(m[1]);
    } catch (e) {
      return res.status(401).json({ status: 'unauthorized' });
    }

    try {
      const result = await redeemPromo({
        db,
        uid: decoded.uid,
        code: req.body && req.body.code,
        now: now(),
        toTimestamp
      });
      const http = result.status === 'rate_limited' ? 429 : result.status === 'bad_request' ? 400 : 200;
      return res.status(http).json(result);
    } catch (e) {
      console.error('promo failed:', e);
      return res.status(500).json({ status: 'server_error' });
    }
  };
}

module.exports = { makePromoHandler };
