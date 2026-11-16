-- ============================================================================
-- КОМАНДЫ АДМИНИСТРАТОРА. Каждую вставляйте в SQL Editor по одной и нажимайте Run.
-- Результат внизу: кнопка «Export» / «Download CSV» сохраняет его в файл для Excel.
-- ============================================================================

-- 1) Создать 1000 промокодов на 30 дней PRO (повторный запуск создаёт ещё 1000 новых, дублей не будет)
select * from amix.gen_promo(1000, 30, 0);

-- 1б) То же, но коды перестают действовать через 90 дней:
-- select * from amix.gen_promo(1000, 30, 90);

-- 2) Создать лицензионные ключи для продажи: количество, тип (m1 = 30 дней, m6 = 180, m12 = 365, forever)
select * from amix.gen_keys(20, 'm1');
-- select * from amix.gen_keys(10, 'm6');
-- select * from amix.gen_keys(5, 'm12');
-- select * from amix.gen_keys(5, 'custom', 14);          -- свой срок: 14 дней
-- select * from amix.gen_keys(5, 'm1', 0, 365);          -- ключи, которые можно активировать только в течение 365 дней

-- 3) Посмотреть все промокоды и ключи со статусами (unused, used, revoked, expired), датой и устройством
select * from amix.v_promos   order by state, code;
select * from amix.v_licenses order by state, code;

-- 4) Только неиспользованные ключи (чтобы взять для продажи)
select code, type, days from amix.v_licenses where state = 'unused' order by created_at, code;

-- 5) Сводка: сколько кодов в каком состоянии
select state, count(*) from amix.v_promos   group by state;
select state, count(*) from amix.v_licenses group by state;

-- 6) Отозвать код (после этого он не активируется)
select amix.revoke_code('keys',  'AMIX-XXXX-XXXX-XXXX-XXXX');
select amix.revoke_code('promo', 'PROMO-XXXX-XXXX-XXXX');

-- 7) Сколько устройств сейчас в бесплатном периоде / с PRO
select
  count(*) filter (where pro_expires_at > now())                          as pro,
  count(*) filter (where pro_expires_at is null or pro_expires_at <= now()) as without_pro,
  count(*) filter (where trial_ends_at > now())                           as trial_active,
  count(*)                                                                 as devices_total
from amix.devices;
