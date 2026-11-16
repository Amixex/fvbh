-- ============================================================================
-- Amix: серверная часть на Supabase. Выполняется ОДИН РАЗ: SQL Editor -> New query -> вставить -> Run.
-- Регистрации нет. Устройство опознаётся по идентификатору (в базе только хэш).
-- Все таблицы лежат в закрытой схеме amix, снаружи доступны только 4 функции (внизу).
-- ============================================================================
create extension if not exists pgcrypto with schema extensions;
create schema if not exists amix;

-- ---------- таблицы ----------
create table if not exists amix.settings (key text primary key, value text not null);
insert into amix.settings(key, value)
  values ('salt', encode(extensions.gen_random_bytes(16), 'hex'))
  on conflict (key) do nothing;

create table if not exists amix.devices (
  hash          text primary key,
  created_at    timestamptz not null default now(),
  trial_ends_at timestamptz not null,
  pro_expires_at timestamptz,
  last_code     text
);

create table if not exists amix.licenses (
  code         text primary key,
  type         text not null default 'custom',
  days         int  not null check (days > 0),
  status       text not null default 'unused' check (status in ('unused','used','revoked')),
  created_at   timestamptz not null default now(),
  valid_until  timestamptz,
  activated_by text,
  activated_at timestamptz
);
create table if not exists amix.promos (like amix.licenses including all);

create table if not exists amix.rate_limits (
  key          text primary key,
  window_start timestamptz not null,
  hits         int not null
);

alter table amix.settings    enable row level security;
alter table amix.devices     enable row level security;
alter table amix.licenses    enable row level security;
alter table amix.promos      enable row level security;
alter table amix.rate_limits enable row level security;
revoke all on schema amix from anon, authenticated;
revoke all on all tables in schema amix from anon, authenticated;

-- ---------- вспомогательные функции (снаружи недоступны) ----------
create or replace function amix.ms(t timestamptz) returns bigint
language sql immutable as $$
  select case when t is null then 0 else (extract(epoch from t) * 1000)::bigint end
$$;

create or replace function amix.valid_device(p text) returns boolean
language sql immutable as $$
  select p is not null and p ~ '^[A-Za-z0-9_-]{8,128}$'
$$;

create or replace function amix.dev_hash(p text) returns text
language sql immutable as $$
  select encode(extensions.digest('device:' || p, 'sha256'), 'hex')
$$;

create or replace function amix.salt() returns text
language sql stable as $$
  select value from amix.settings where key = 'salt'
$$;

-- Хэш адреса клиента для ограничения частоты (сам адрес не сохраняется). Если адреса нет, возвращает null.
create or replace function amix.client_key() returns text
language plpgsql stable as $$
declare hdr jsonb; ip text;
begin
  begin
    hdr := nullif(current_setting('request.headers', true), '')::jsonb;
  exception when others then
    hdr := null;
  end;
  ip := trim(split_part(coalesce(hdr->>'x-forwarded-for', hdr->>'cf-connecting-ip', hdr->>'x-real-ip', ''), ',', 1));
  if ip = '' then return null; end if;
  return left(encode(extensions.digest(amix.salt() || '|' || ip, 'sha256'), 'hex'), 32);
end $$;

-- Счётчик: не больше p_limit событий за окно p_window.
create or replace function amix.consume(p_key text, p_limit int, p_window interval) returns boolean
language plpgsql as $$
declare r amix.rate_limits%rowtype;
begin
  insert into amix.rate_limits(key, window_start, hits) values (p_key, now(), 0) on conflict (key) do nothing;
  select * into r from amix.rate_limits where key = p_key for update;
  if now() - r.window_start > p_window then
    update amix.rate_limits set window_start = now(), hits = 0 where key = p_key;
    r.hits := 0;
  end if;
  if r.hits >= p_limit then return false; end if;
  update amix.rate_limits set hits = hits + 1 where key = p_key;
  return true;
end $$;

create or replace function amix.is_blocked(p_key text, p_limit int, p_window interval) returns boolean
language sql stable as $$
  select exists (select 1 from amix.rate_limits
                 where key = p_key and now() - window_start <= p_window and hits >= p_limit)
$$;

-- Случайный код: 32 символа без I, O, 0, 1; ключ AMIX-XXXX-XXXX-XXXX-XXXX (80 бит), промокод PROMO-XXXX-XXXX-XXXX (60 бит).
create or replace function amix.rand_code(p_prefix text, p_groups int) returns text
language plpgsql as $$
declare alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
        b bytea; s text := p_prefix; i int;
begin
  b := extensions.gen_random_bytes(p_groups * 4);
  for i in 0 .. p_groups * 4 - 1 loop
    if i % 4 = 0 then s := s || '-'; end if;
    s := s || substr(alphabet, (get_byte(b, i) % 32) + 1, 1);
  end loop;
  return s;
end $$;

-- Приводит ввод пользователя к каноническому виду (регистр, пробелы, дефисы) или возвращает null.
create or replace function amix.normalize_code(p_kind text, p_input text) returns text
language plpgsql immutable as $$
declare pre text; grp int; s text; res text; i int;
begin
  pre := case p_kind when 'keys' then 'AMIX' else 'PROMO' end;
  grp := case p_kind when 'keys' then 4 else 3 end;
  s := regexp_replace(upper(coalesce(p_input, '')), '[^A-Z0-9]', '', 'g');
  if left(s, length(pre)) <> pre then return null; end if;
  s := substr(s, length(pre) + 1);
  if length(s) <> grp * 4 then return null; end if;
  if s ~ '[^A-HJ-NP-Z2-9]' then return null; end if;
  res := pre;
  for i in 0 .. grp - 1 loop
    res := res || '-' || substr(s, i * 4 + 1, 4);
  end loop;
  return res;
end $$;

-- ---------- активация кода (ключ или промокод) ----------
create or replace function amix.redeem(p_kind text, p_device_id text, p_code text) returns jsonb
language plpgsql as $$
declare
  v_now timestamptz := now();
  ip text; h text; fk text; norm text;
  c record; cur timestamptz; n_days int; newexp timestamptz;
begin
  if not amix.valid_device(p_device_id) or btrim(coalesce(p_code, '')) = '' then
    return jsonb_build_object('status', 'bad_request');
  end if;
  ip := amix.client_key();
  if ip is not null and not amix.consume('ip:' || ip, 300, interval '1 hour') then
    return jsonb_build_object('status', 'rate_limited');
  end if;
  h := amix.dev_hash(p_device_id);
  fk := 'fail:' || p_kind || ':' || h;
  if amix.is_blocked(fk, 10, interval '1 hour') then      -- 10 неудач за час: блокировка на час
    return jsonb_build_object('status', 'rate_limited');
  end if;

  norm := amix.normalize_code(p_kind, p_code);
  if norm is null then
    perform amix.consume(fk, 10, interval '1 hour');
    return jsonb_build_object('status', 'not_found');
  end if;

  if p_kind = 'keys' then
    select * into c from amix.licenses where code = norm for update;
  else
    select * into c from amix.promos where code = norm for update;
  end if;
  if not found then
    perform amix.consume(fk, 10, interval '1 hour');
    return jsonb_build_object('status', 'not_found');
  end if;
  if c.status = 'revoked' then
    perform amix.consume(fk, 10, interval '1 hour');
    return jsonb_build_object('status', 'revoked');
  end if;

  select pro_expires_at into cur from amix.devices where hash = h;
  if c.activated_by is not null then
    if c.activated_by = h then   -- то же устройство повторно: ничего не добавляем
      return jsonb_build_object('status', 'ok', 'already', true, 'days', c.days, 'proExpiresAt', amix.ms(cur));
    end if;
    perform amix.consume(fk, 10, interval '1 hour');
    return jsonb_build_object('status', 'used');
  end if;
  if c.valid_until is not null and c.valid_until < v_now then
    perform amix.consume(fk, 10, interval '1 hour');
    return jsonb_build_object('status', 'expired');
  end if;

  n_days := coalesce(nullif(c.days, 0), 30);
  newexp := greatest(v_now, coalesce(cur, v_now)) + make_interval(days => n_days);   -- продлевает остаток
  if p_kind = 'keys' then
    update amix.licenses set status = 'used', activated_by = h, activated_at = v_now where code = norm;
  else
    update amix.promos set status = 'used', activated_by = h, activated_at = v_now where code = norm;
  end if;
  insert into amix.devices(hash, trial_ends_at, pro_expires_at, last_code)
    values (h, v_now + interval '7 days', newexp, norm)
    on conflict (hash) do update set pro_expires_at = excluded.pro_expires_at, last_code = excluded.last_code;
  return jsonb_build_object('status', 'ok', 'days', n_days, 'proExpiresAt', amix.ms(newexp));
end $$;

-- ---------- ЕДИНСТВЕННОЕ, что доступно приложению ----------
create or replace function public.amix_ping() returns text
language sql security definer set search_path = amix, public, extensions as $$ select 'ok'::text $$;

create or replace function public.amix_access(p_device_id text) returns jsonb
language plpgsql security definer set search_path = amix, public, extensions as $$
declare ip text; h text; d amix.devices%rowtype; v_now timestamptz := now();
begin
  if not amix.valid_device(p_device_id) then
    return jsonb_build_object('status', 'bad_request');
  end if;
  ip := amix.client_key();
  if ip is not null and not amix.consume('ip:' || ip, 300, interval '1 hour') then
    return jsonb_build_object('status', 'rate_limited');
  end if;
  h := amix.dev_hash(p_device_id);
  select * into d from amix.devices where hash = h;
  if not found then
    if ip is not null and not amix.consume('newdev:' || ip, 30, interval '1 hour') then
      return jsonb_build_object('status', 'rate_limited');
    end if;
    -- бесплатный период: ровно 7 дней (168 часов) от первого обращения устройства, по часам сервера
    insert into amix.devices(hash, trial_ends_at) values (h, v_now + interval '7 days') on conflict (hash) do nothing;
    select * into d from amix.devices where hash = h;
  end if;
  return jsonb_build_object(
    'status', 'ok',
    'serverNow', amix.ms(v_now),
    'trialEndsAt', amix.ms(d.trial_ends_at),
    'proExpiresAt', amix.ms(d.pro_expires_at),
    'isPro', (d.pro_expires_at is not null and d.pro_expires_at > v_now));
end $$;

create or replace function public.amix_activate_key(p_device_id text, p_key text) returns jsonb
language sql security definer set search_path = amix, public, extensions as $$
  select amix.redeem('keys', p_device_id, p_key)
$$;

create or replace function public.amix_redeem_promo(p_device_id text, p_code text) returns jsonb
language sql security definer set search_path = amix, public, extensions as $$
  select amix.redeem('promo', p_device_id, p_code)
$$;

revoke all on function public.amix_ping(), public.amix_access(text),
  public.amix_activate_key(text, text), public.amix_redeem_promo(text, text) from public;
grant execute on function public.amix_ping(), public.amix_access(text),
  public.amix_activate_key(text, text), public.amix_redeem_promo(text, text) to anon, authenticated;

-- ---------- инструменты администратора (запускаются только вручную в SQL Editor) ----------
create or replace function amix.gen_promo(p_count int default 1000, p_days int default 30, p_valid_days int default 0)
returns table(new_code text) language plpgsql as $$
declare n int := 0; c text; vu timestamptz;
begin
  if p_count < 1 or p_count > 5000 then raise exception 'count must be 1..5000'; end if;
  vu := case when p_valid_days > 0 then now() + make_interval(days => p_valid_days) else null end;
  while n < p_count loop
    c := amix.rand_code('PROMO', 3);
    insert into amix.promos(code, type, days, valid_until) values (c, 'promo', p_days, vu) on conflict (code) do nothing;
    if found then n := n + 1; new_code := c; return next; end if;
  end loop;
end $$;

create or replace function amix.gen_keys(p_count int default 1, p_type text default 'm1', p_days int default 0, p_valid_days int default 0)
returns table(new_code text, key_type text, key_days int) language plpgsql as $$
declare n int := 0; c text; vu timestamptz; d int;
begin
  if p_count < 1 or p_count > 5000 then raise exception 'count must be 1..5000'; end if;
  d := case when p_days > 0 then p_days
            else case p_type when 'm1' then 30 when 'm6' then 180 when 'm12' then 365 when 'forever' then 36500 else 30 end end;
  vu := case when p_valid_days > 0 then now() + make_interval(days => p_valid_days) else null end;
  while n < p_count loop
    c := amix.rand_code('AMIX', 4);
    insert into amix.licenses(code, type, days, valid_until) values (c, p_type, d, vu) on conflict (code) do nothing;
    if found then n := n + 1; new_code := c; key_type := p_type; key_days := d; return next; end if;
  end loop;
end $$;

create or replace view amix.v_promos as
  select code, type, days,
         case when status = 'revoked' then 'revoked'
              when activated_by is not null then 'used'
              when valid_until is not null and valid_until < now() then 'expired'
              else 'unused' end as state,
         created_at, valid_until, activated_at, left(activated_by, 8) as device
  from amix.promos;

create or replace view amix.v_licenses as
  select code, type, days,
         case when status = 'revoked' then 'revoked'
              when activated_by is not null then 'used'
              when valid_until is not null and valid_until < now() then 'expired'
              else 'unused' end as state,
         created_at, valid_until, activated_at, left(activated_by, 8) as device
  from amix.licenses;

create or replace function amix.revoke_code(p_kind text, p_code text) returns text
language plpgsql as $$
declare norm text; cnt int;
begin
  norm := amix.normalize_code(p_kind, p_code);
  if norm is null then return 'Неверный формат кода'; end if;
  if p_kind = 'keys' then
    update amix.licenses set status = 'revoked' where code = norm;
  else
    update amix.promos set status = 'revoked' where code = norm;
  end if;
  get diagnostics cnt = row_count;
  return case when cnt > 0 then 'Код отозван' else 'Код не найден' end;
end $$;
