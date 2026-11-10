'use strict';
const functions = require('firebase-functions');
const admin = require('firebase-admin');
const { makeHandlers } = require('./handlers');

admin.initializeApp();
const db = admin.firestore();
const region = functions.region('europe-west1');

// Соль для хэшей адресов (необязательно): переменная RATE_SALT в файле functions/.env
const h = makeHandlers({
  db,
  salt: process.env.RATE_SALT || '',
  toTimestamp: (ms) => admin.firestore.Timestamp.fromMillis(ms)
});

exports.access = region.https.onRequest(h.access);          // статус и пробный период
exports.activateKey = region.https.onRequest(h.activateKey); // лицензионный ключ
exports.promo = region.https.onRequest(h.promo);            // промокод
