'use strict';
/**
 * Администрирование ключей и промокодов (нужен ключ сервисного аккаунта Firebase):
 *   set GOOGLE_APPLICATION_CREDENTIALS=C:\путь\service-account.json
 *   node scripts/admin.js generate promo --count 1000 --days 30 [--valid-days 90] [--out promo.csv]
 *   node scripts/admin.js generate keys  --count 50 --type m1|m6|m12|forever [--days N] [--valid-days N] [--out keys.csv]
 *   node scripts/admin.js list promo|keys [--status unused|used|revoked|expired]
 *   node scripts/admin.js export promo|keys [--status ...] [--out файл.csv]
 *   node scripts/admin.js stats promo|keys
 *   node scripts/admin.js revoke promo|keys КОД
 */
const fs = require('node:fs');
const admin = require('firebase-admin');
const ops = require('../admin');

function parse(argv) {
  const pos = []; const opt = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) opt[argv[i].slice(2)] = argv[++i]; else pos.push(argv[i]);
  }
  return { pos, opt };
}

async function main() {
  const { pos, opt } = parse(process.argv.slice(2));
  const [cmd, kind, extra] = pos;
  if (!cmd || !['keys', 'promo'].includes(kind)) { console.log(fs.readFileSync(__filename, 'utf8').split('*/')[0]); process.exit(1); }
  admin.initializeApp();
  const db = admin.firestore();
  const toTimestamp = (ms) => admin.firestore.Timestamp.fromMillis(ms);
  const writeCsv = (rows, name) => {
    fs.writeFileSync(name, '\uFEFF' + ops.toCsv(rows), 'utf8'); // BOM, чтобы Excel открыл кириллицу и даты
    console.log(`Файл сохранён: ${name} (${rows.length} строк)`);
  };

  if (cmd === 'generate') {
    const count = parseInt(opt.count || (kind === 'promo' ? '1000' : '1'), 10);
    const codes = await ops.generate({
      db, kind, count, days: opt.days ? parseInt(opt.days, 10) : 0, type: opt.type || (kind === 'promo' ? 'promo' : 'm1'),
      validDays: opt['valid-days'] ? parseInt(opt['valid-days'], 10) : 0, toTimestamp
    });
    console.log(`Создано кодов: ${codes.length}`);
    writeCsv(await ops.list({ db, kind }).then((rows) => rows.filter((r) => codes.includes(r.code))), opt.out || `${kind}-${Date.now()}.csv`);
  } else if (cmd === 'list') {
    for (const r of await ops.list({ db, kind, status: opt.status })) console.log(`${r.code}  ${r.status}  ${r.type} ${r.days}д  ${r.activatedAt}`);
  } else if (cmd === 'export') {
    writeCsv(await ops.list({ db, kind, status: opt.status }), opt.out || `${kind}-export.csv`);
  } else if (cmd === 'stats') {
    console.log(await ops.stats({ db, kind }));
  } else if (cmd === 'revoke') {
    console.log((await ops.revoke({ db, kind, code: extra })) ? 'Код отозван' : 'Код не найден');
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
