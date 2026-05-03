-- Restore the pre-2026-04-26 dedup index.

drop index if exists public.account_addons_public_user_transport_uidx;

create unique index account_addons_public_user_base_uidx
  on public.account_addons_public (
    user_id,
    lower(base_url),
    coalesce(secret_ref, '')
  );
