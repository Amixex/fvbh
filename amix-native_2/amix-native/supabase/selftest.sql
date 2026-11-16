-- ============================================================================
-- САМОПРОВЕРКА. SQL Editor -> New query -> вставить -> Run. Запускать после schema.sql.
-- Тест сам создаёт тестовые данные и сам их удаляет. Внизу появится таблица: у каждой строки должно быть «ОК».
-- Если есть «ОШИБКА» или красное сообщение, пришлите скриншот.
-- ============================================================================
drop table if exists _amix_t;
create temp table _amix_t(n serial, name text, ok boolean, info text);

do $$
declare
  d1 constant text := 'selftest-dev-0001';
  d2 constant text := 'selftest-dev-0002';
  d3 constant text := 'selftest-dev-0003';
  r jsonb; r2 jsonb;
  k1 text; k2 text; k3 text; k4 text; kx text; kr text;
  p1 text; px text; pcodes text[]; i int; cnt int;
begin
  -- 1. бесплатный период
  r := public.amix_access(d1);
  insert into _amix_t(name, ok, info) values ('бесплатный период ровно 7 дней (168 часов)',
    r->>'status' = 'ok' and (r->>'trialEndsAt')::bigint - (r->>'serverNow')::bigint = 604800000, r::text);
  r2 := public.amix_access(d1);
  insert into _amix_t(name, ok, info) values ('таймер не сбрасывается при повторном обращении',
    r2->>'trialEndsAt' = r->>'trialEndsAt', r2::text);
  update amix.devices set trial_ends_at = now() - interval '1 hour' where hash = amix.dev_hash(d1);
  r2 := public.amix_access(d1);
  insert into _amix_t(name, ok, info) values ('после окончания периода новый не выдаётся',
    (r2->>'trialEndsAt')::bigint < (r2->>'serverNow')::bigint and r2->>'isPro' = 'false', r2::text);
  r2 := public.amix_access('x');
  insert into _amix_t(name, ok, info) values ('некорректный идентификатор устройства отклоняется', r2->>'status' = 'bad_request', r2::text);

  -- 2. лицензионные ключи
  k1 := (select new_code from amix.gen_keys(1, 'm1'));
  k2 := (select new_code from amix.gen_keys(1, 'm1'));
  k3 := (select new_code from amix.gen_keys(1, 'm6'));
  r := public.amix_activate_key(d1, 'AMIX-AAAA-BBBB-CCCC-DDDD');
  insert into _amix_t(name, ok, info) values ('неверный ключ: not_found', r->>'status' = 'not_found', r::text);
  r := public.amix_activate_key(d1, k1);
  insert into _amix_t(name, ok, info) values ('верный ключ включает PRO на 30 дней',
    r->>'status' = 'ok' and (r->>'days')::int = 30, r::text);
  r2 := public.amix_access(d1);
  insert into _amix_t(name, ok, info) values ('после активации статус isPro = true, срок совпадает',
    r2->>'isPro' = 'true' and r2->>'proExpiresAt' = r->>'proExpiresAt', r2::text);
  r2 := public.amix_activate_key(d2, k1);
  insert into _amix_t(name, ok, info) values ('тот же ключ на другом устройстве: used', r2->>'status' = 'used', r2::text);
  r2 := public.amix_activate_key(d1, k1);
  insert into _amix_t(name, ok, info) values ('повтор на том же устройстве ничего не добавляет',
    r2->>'status' = 'ok' and r2->>'already' = 'true' and r2->>'proExpiresAt' = r->>'proExpiresAt', r2::text);
  r2 := public.amix_activate_key(d1, k2);
  insert into _amix_t(name, ok, info) values ('второй ключ продлевает остаток (ровно на 30 дней)',
    r2->>'status' = 'ok' and (r2->>'proExpiresAt')::bigint - (r->>'proExpiresAt')::bigint = 2592000000, r2::text);
  r := public.amix_activate_key(d2, lower(replace(k3, '-', ' ')));
  insert into _amix_t(name, ok, info) values ('ввод в нижнем регистре и с пробелами принимается',
    r->>'status' = 'ok' and (r->>'days')::int = 180, r::text);
  r := public.amix_activate_key(d2, 'мусор');
  insert into _amix_t(name, ok, info) values ('мусорный ввод: not_found', r->>'status' = 'not_found', r::text);
  r := public.amix_activate_key(d2, '   ');
  insert into _amix_t(name, ok, info) values ('пустой ввод: bad_request', r->>'status' = 'bad_request', r::text);

  kx := (select new_code from amix.gen_keys(1, 'm1'));
  update amix.licenses set valid_until = now() - interval '1 day' where code = kx;
  r := public.amix_activate_key(d2, kx);
  insert into _amix_t(name, ok, info) values ('просроченный ключ: expired', r->>'status' = 'expired', r::text);
  kr := (select new_code from amix.gen_keys(1, 'm1'));
  perform amix.revoke_code('keys', kr);
  r := public.amix_activate_key(d2, kr);
  insert into _amix_t(name, ok, info) values ('отозванный ключ: revoked', r->>'status' = 'revoked', r::text);

  -- 3. промокоды
  p1 := (select new_code from amix.gen_promo(1, 30, 0));
  px := (select new_code from amix.gen_promo(1, 30, 5));
  r := public.amix_redeem_promo(d1, p1);
  insert into _amix_t(name, ok, info) values ('промокод: действительный, 30 дней', r->>'status' = 'ok' and (r->>'days')::int = 30, r::text);
  r := public.amix_redeem_promo(d3, p1);
  insert into _amix_t(name, ok, info) values ('промокод одноразовый: на другом устройстве used', r->>'status' = 'used', r::text);
  update amix.promos set valid_until = now() - interval '1 day' where code = px;
  r := public.amix_redeem_promo(d3, px);
  insert into _amix_t(name, ok, info) values ('просроченный промокод: expired', r->>'status' = 'expired', r::text);
  r := public.amix_redeem_promo(d3, k1);
  insert into _amix_t(name, ok, info) values ('ключ в поле промокода не принимается', r->>'status' = 'not_found', r::text);

  -- 4. защита от подбора
  k4 := (select new_code from amix.gen_keys(1, 'm1'));
  for i in 1 .. 10 loop
    perform public.amix_activate_key(d3, 'AMIX-AAAA-BBBB-CCCC-DDDD');
  end loop;
  r := public.amix_activate_key(d3, k4);
  insert into _amix_t(name, ok, info) values ('после 10 неудач даже верный ключ блокируется', r->>'status' = 'rate_limited', r::text);
  r := public.amix_activate_key('selftest-dev-0004', k4);
  insert into _amix_t(name, ok, info) values ('другое устройство не затронуто блокировкой', r->>'status' = 'ok', r::text);

  -- 5. ровно 1000 уникальных промокодов
  select array_agg(new_code) into pcodes from amix.gen_promo(1000, 30, 0);
  insert into _amix_t(name, ok, info) values ('создано ровно 1000 промокодов',
    coalesce(array_length(pcodes, 1), 0) = 1000, coalesce(array_length(pcodes, 1), 0)::text);
  insert into _amix_t(name, ok, info) values ('все 1000 уникальны',
    (select count(distinct x) from unnest(pcodes) x) = 1000, '');
  insert into _amix_t(name, ok, info) values ('формат PROMO-XXXX-XXXX-XXXX у всех',
    (select bool_and(x ~ '^PROMO(-[A-HJ-NP-Z2-9]{4}){3}$') from unnest(pcodes) x), pcodes[1]);
  select count(*) into cnt from amix.v_promos where code = any(pcodes) and state = 'unused';
  insert into _amix_t(name, ok, info) values ('все 1000 видны администратору со статусом unused', cnt = 1000, cnt::text);

  -- уборка: тестовые данные удаляются
  delete from amix.promos where code = any(pcodes) or code in (p1, px);
  delete from amix.licenses where code in (k1, k2, k3, k4, kx, kr);
  delete from amix.devices where hash in (amix.dev_hash(d1), amix.dev_hash(d2), amix.dev_hash(d3), amix.dev_hash('selftest-dev-0004'));
  delete from amix.rate_limits where key like 'fail:%' and (
    key like '%' || amix.dev_hash(d1) or key like '%' || amix.dev_hash(d2) or key like '%' || amix.dev_hash(d3)
    or key like '%' || amix.dev_hash('selftest-dev-0004'));
end $$;

select name as "Проверка", case when ok then 'ОК' else 'ОШИБКА' end as "Результат", info as "Подробности"
from _amix_t order by n;
