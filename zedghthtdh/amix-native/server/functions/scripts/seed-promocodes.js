'use strict';
/**
 * Загружает 100 промокодов из ../../seed/promocodes.json в Firestore (коллекция promocodes).
 * Повторный запуск безопасен: уже существующие коды не сбрасываются (счётчики активаций сохраняются).
 *
 * Запуск (нужен ключ сервисного аккаунта):
 *   set GOOGLE_APPLICATION_CREDENTIALS=C:\путь\service-account.json   (Windows)
 *   node scripts/seed-promocodes.js
 */
async function seedPromoCodes(db, seed) {
  let created = 0;
  let skipped = 0;
  for (const code of seed.codes) {
    const ref = db.collection('promocodes').doc(code);
    const snap = await ref.get();
    if (snap.exists) {
      skipped++;
      continue;
    }
    await ref.set({ code, days: seed.days, active: true, activations: 0 });
    created++;
  }
  return { created, skipped };
}

module.exports = { seedPromoCodes };

if (require.main === module) {
  const admin = require('firebase-admin');
  admin.initializeApp();
  const seed = require('../../seed/promocodes.json');
  seedPromoCodes(admin.firestore(), seed)
    .then((r) => console.log(`Готово: создано ${r.created}, уже были ${r.skipped}`))
    .catch((e) => {
      console.error(e);
      process.exit(1);
    });
}
