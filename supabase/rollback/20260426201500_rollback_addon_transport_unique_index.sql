-- Restore the pre-2026-04-26 dedup index. Note: any rows inserted while the
-- forward migration was active that share (user_id, lower(base_url),
-- coalesce(secret_ref, '')) with another row will block this rollback with a
-- unique-violation. Reconcile such rows manually before rolling back.

drop index if exists public.account_addons_public_user_transport_uidx;

create unique index account_addons_public_user_base_uidx
  on public.account_addons_public (
    user_id,
    lower(base_url),
    coalesce(secret_ref, '')
  );
