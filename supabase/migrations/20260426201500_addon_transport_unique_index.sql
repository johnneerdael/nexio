-- Replace the addon dedup unique index so two installs of the same FQDN can
-- coexist when they target different transport suffixes. Identity becomes
-- (user_id, base_url, transport_secret_ref) where transport_secret_ref is
-- already per-suffix-hashed by addonTransportSecretRef on web/Android.
--
-- The previous index keyed on COALESCE(secret_ref, '') which collides for two
-- configured installs of the same origin (e.g. Torrentio with Real-Debrid vs
-- Torrentio with Premiumize both produce secret_ref =
-- 'addon:torrentio_strem_fun').
--
-- transport_secret_ref was backfilled for every existing row by
-- 20260420231500_add_addon_transport_v2.sql, so no NULLs are expected in
-- practice. The COALESCE('') guard preserves the legacy
-- "one-row-per-origin" behaviour for any unmigrated v1 rows that may exist.

drop index if exists public.account_addons_public_user_base_uidx;

create unique index account_addons_public_user_transport_uidx
  on public.account_addons_public (
    user_id,
    lower(base_url),
    coalesce(transport_secret_ref, '')
  );
