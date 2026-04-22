create table if not exists public.device_credentials (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_user_id uuid not null references auth.users(id) on delete cascade,
  device_public_id text not null check (length(trim(device_public_id)) > 0),
  credential_hash text not null check (length(trim(credential_hash)) > 0),
  display_name text not null check (length(trim(display_name)) > 0),
  device_name text,
  device_model text,
  device_platform text,
  status text not null default 'active'
    check (status in ('active', 'revoked', 'rotated')),
  linked_device_id uuid null references public.linked_devices(id) on delete set null,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz null
);

create unique index if not exists device_credentials_public_id_uidx
  on public.device_credentials(device_public_id);

create unique index if not exists device_credentials_device_user_uidx
  on public.device_credentials(device_user_id);

create index if not exists device_credentials_owner_last_seen_idx
  on public.device_credentials(owner_id, last_seen_at desc);

create index if not exists device_credentials_owner_status_idx
  on public.device_credentials(owner_id, status);

alter table public.device_credentials enable row level security;

create policy "device_credentials_owner_or_device_select"
  on public.device_credentials
  for select
  to authenticated
  using ((owner_id = auth.uid()) or (device_user_id = auth.uid()));

grant all on table public.device_credentials to anon;
grant all on table public.device_credentials to authenticated;
grant all on table public.device_credentials to service_role;

create or replace function public.revoke_device_credential(p_device_public_id text)
returns void
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_device_public_id text := trim(coalesce(p_device_public_id, ''));
  v_revoked_count integer := 0;
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  if v_device_public_id = '' then
    raise exception 'Invalid durable device credential';
  end if;

  update public.device_credentials
     set status = 'revoked',
         revoked_at = now()
   where owner_id = auth.uid()
     and device_public_id = v_device_public_id
     and status <> 'revoked';

  get diagnostics v_revoked_count = row_count;

  if v_revoked_count = 0 then
    raise exception 'Device credential not found or already revoked';
  end if;
end;
$$;

revoke all on function public.revoke_device_credential(text) from public;
grant execute on function public.revoke_device_credential(text) to authenticated;
