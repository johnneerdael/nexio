-- Replace the addon dedup unique index so two installs of the same FQDN can
-- coexist when they target different transport suffixes.

drop index if exists public.account_addons_public_user_base_uidx;

create unique index account_addons_public_user_transport_uidx
  on public.account_addons_public (
    user_id,
    lower(base_url),
    coalesce(transport_secret_ref, '')
  );
