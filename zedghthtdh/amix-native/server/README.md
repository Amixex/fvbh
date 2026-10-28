# Сервер Amix: таймеры, промокоды, оплата ЮKassa

Что внутри: `functions/promo.js` (правила активации), `functions/handler.js` (HTTP, проверка входа),
`functions/index.js` (Firebase Function `promo`), `functions/scripts/seed-promocodes.js` (загрузка 100 кодов),
`firestore.rules` (клиент не может писать Pro-поля), `seed/promocodes.json` (коды из index.html).

## Проверка без Firebase
    cd functions
    node --test "test/*.test.js"      (28 тестов на поддельном Firestore и поддельной ЮKassa)
    cd .. && node --test test/promo_parity.test.js     (коды совпадают с PARITY.md)

## Функции
| Функция | Что делает |
|---|---|
| `access` | таймеры: гость 24 часа (по устройству), аккаунт 7 дней, статус Pro |
| `promo` | активация промокода (30 дней Pro) |
| `payCreate` | создаёт платёж ЮKassa по тарифу `m1` 200 ₽, `m6` 900 ₽ (−25%), `m12` 1500 ₽ (−37%) |
| `paySync` | проверка оплаты после возврата в приложение |
| `payWebhook` | уведомления ЮKassa (Pro выдаётся только после проверки платежа через API ЮKassa) |

## Подключение ЮKassa (делаете вы)
1. В личном кабинете ЮKassa: **Настройки → Ключи API**: скопируйте **shopId** и создайте **секретный ключ**.
2. Разместите страницу `paid.html` (лежит рядом) по любому адресу https, например на GitHub Pages.
3. Создайте файл `functions/.env` (он в `.gitignore`, в репозиторий не попадёт):
       YOOKASSA_SHOP_ID=ваш_shopId
       YOOKASSA_SECRET_KEY=ваш_секретный_ключ
       YOOKASSA_RETURN_URL=https://ваш-адрес/paid.html
   Если магазин работает с чеками ЮKassa (54-ФЗ), добавьте `YOOKASSA_RECEIPT=1` (для входа через Google чек уйдёт на почту аккаунта; самозанятым чек формирует «Мой налог»).
4. Разверните: `firebase deploy --only functions:access,functions:promo,functions:payCreate,functions:paySync,functions:payWebhook`
5. В ЮKassa: **Интеграция → HTTP-уведомления**, URL: `https://europe-west1-<ПРОЕКТ>.cloudfunctions.net/payWebhook`, события `payment.succeeded` и `payment.canceled`.
6. Проверьте тестовым платежом (в ЮKassa есть тестовый магазин) до боевого режима.

## Развёртывание (делаете вы)
1. Нужен тариф **Blaze** в Firebase (Cloud Functions на бесплатном тарифе не работают). Бесплатная квота у Blaze есть.
2. Установите Node 20 и `npm i -g firebase-tools`, затем `firebase login`.
3. В папке `server`: `firebase use --add` (выберите проект Amix) и `cd functions && npm install`.
4. **Не удаляйте** уже работающие функции (например, `yookassa`): при `firebase deploy` на вопрос
   «Delete function?» отвечайте **No**. Безопаснее деплоить по именам:
       firebase deploy --only functions:promo
5. Загрузите коды в базу: в консоли Google Cloud/Firebase создайте ключ сервисного аккаунта (JSON), затем
       set GOOGLE_APPLICATION_CREDENTIALS=C:\путь\service-account.json
       cd functions && node scripts/seed-promocodes.js
   Повторный запуск безопасен.
6. Правила: сначала проверьте `firestore.rules` в «Симуляторе» консоли, потом `firebase deploy --only firestore:rules`.

## ВАЖНО: правила сломают текущую веб-версию
Сейчас index.html сам пишет `isPro` и `proExpiresAt` в Firestore (кнопка «Купить Pro» и промокоды).
После публикации правил эти действия в вебе перестанут работать. Веб нужно перевести на вызов функции
`promo` (запрос ниже). Сначала переведите веб, потом публикуйте правила.

## Запрос к функции
    POST https://europe-west1-<ПРОЕКТ>.cloudfunctions.net/promo
    Authorization: Bearer <Firebase ID token>
    {"code": "AMIX-..."}
Ответ: `{"status":"ok","days":30,"proExpiresAt":<мс>}`, либо `not_found`, `used`, `bad_request` (400),
`rate_limited` (429, больше 10 неудач в час), `unauthorized` (401).
