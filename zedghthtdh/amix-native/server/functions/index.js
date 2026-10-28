'use strict';
const functions = require('firebase-functions');
const admin = require('firebase-admin');
const { makePromoHandler } = require('./handler');
const { makeAccessHandler } = require('./access');
const { makePayHandlers } = require('./payments');

admin.initializeApp();
const db = admin.firestore();
const region = functions.region('europe-west1');
const verifyToken = (token) => admin.auth().verifyIdToken(token);
const toTimestamp = (ms) => admin.firestore.Timestamp.fromMillis(ms);

// Ключи ЮKassa берутся из переменных окружения (файл functions/.env при развёртывании), в приложении их нет.
const cfg = {
  shopId: process.env.YOOKASSA_SHOP_ID,
  secretKey: process.env.YOOKASSA_SECRET_KEY,
  returnUrl: process.env.YOOKASSA_RETURN_URL,
  receipt: process.env.YOOKASSA_RECEIPT === '1'
};
const pay = makePayHandlers({ verifyToken, db, cfg, fetchFn: (...a) => fetch(...a), toTimestamp });

exports.promo = region.https.onRequest(makePromoHandler({ verifyToken, db, toTimestamp }));
exports.access = region.https.onRequest(makeAccessHandler({ verifyToken, db, toTimestamp }));
exports.payCreate = region.https.onRequest((req, res) => pay.create(req, res));
exports.paySync = region.https.onRequest((req, res) => pay.sync(req, res));
exports.payWebhook = region.https.onRequest((req, res) => pay.webhook(req, res));
