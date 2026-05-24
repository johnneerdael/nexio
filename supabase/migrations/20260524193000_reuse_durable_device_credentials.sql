alter table public.tv_login_sessions
  add column if not exists reuse_device_credential_id uuid null references public.device_credentials(id) on delete set null;

alter table public.device_credential_handoffs
  add column if not exists reuse_device_credential_id uuid null references public.device_credentials(id) on delete set null;

drop function if exists public.approve_tv_login_session(text, text, text);

create or replace function public.approve_tv_login_session(
  p_code text,
  p_device_nonce text,
  p_display_name text default null,
  p_reuse_device_credential_id uuid default null
)
returns table(message text)
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_row public.tv_login_sessions%rowtype;
  v_requested_display_name text := nullif(trim(coalesce(p_display_name, '')), '');
  v_reuse_credential public.device_credentials%rowtype;
  v_session_device_name text;
  v_session_device_model text;
  v_session_device_platform text;
  v_credential_device_name text;
  v_credential_device_model text;
  v_credential_device_platform text;
  v_platform_matches boolean := false;
  v_model_matches boolean := false;
  v_name_matches boolean := false;
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  select *
    into v_row
  from public.tv_login_sessions
  where code = upper(trim(coalesce(p_code, '')))
    and device_nonce = trim(coalesce(p_device_nonce, ''))
  for update;

  if not found then
    raise exception 'Invalid TV login code or nonce';
  end if;

  if v_row.expires_at <= now() then
    update public.tv_login_sessions
      set status = 'expired'
    where id = v_row.id;
    raise exception 'TV login expired';
  end if;

  if v_row.status = 'used' then
    raise exception 'TV login already used';
  end if;

  if v_row.status = 'cancelled' then
    raise exception 'TV login cancelled';
  end if;

  if p_reuse_device_credential_id is not null then
    select *
      into v_reuse_credential
    from public.device_credentials
    where id = p_reuse_device_credential_id
      and owner_id = auth.uid()
      and status = 'active'
      and revoked_at is null;

    if not found then
      raise exception 'Reusable device credential not found';
    end if;

    v_session_device_name := lower(nullif(trim(coalesce(v_row.device_name, '')), ''));
    v_session_device_model := lower(nullif(trim(coalesce(v_row.device_model, '')), ''));
    v_session_device_platform := lower(nullif(trim(coalesce(v_row.device_platform, '')), ''));
    v_credential_device_name := lower(nullif(trim(coalesce(v_reuse_credential.device_name, '')), ''));
    v_credential_device_model := lower(nullif(trim(coalesce(v_reuse_credential.device_model, '')), ''));
    v_credential_device_platform := lower(nullif(trim(coalesce(v_reuse_credential.device_platform, '')), ''));

    v_platform_matches := (
      v_session_device_platform is null
      or v_credential_device_platform is null
      or v_session_device_platform = v_credential_device_platform
    );
    v_model_matches := (
      v_session_device_model is not null
      and v_credential_device_model is not null
      and v_session_device_model = v_credential_device_model
    );
    v_name_matches := (
      v_session_device_model is null
      and v_credential_device_model is null
      and v_session_device_name is not null
      and v_credential_device_name is not null
      and v_session_device_name = v_credential_device_name
    );

    if not v_platform_matches or not (v_model_matches or v_name_matches) then
      raise exception 'Reusable device credential does not match this device';
    end if;
  end if;

  update public.tv_login_sessions
    set approved_by_user_id = auth.uid(),
        approved_at = now(),
        status = 'approved',
        requested_display_name = v_requested_display_name,
        reuse_device_credential_id = p_reuse_device_credential_id
  where id = v_row.id;

  return query select 'TV login approved.';
end;
$$;

revoke all on function public.approve_tv_login_session(text, text, text, uuid) from public;
grant all on function public.approve_tv_login_session(text, text, text, uuid) to anon;
grant all on function public.approve_tv_login_session(text, text, text, uuid) to authenticated;
grant all on function public.approve_tv_login_session(text, text, text, uuid) to service_role;

create or replace function public.activate_device_credential_handoff(
    p_device_user_id uuid,
    p_device_public_id text,
    p_credential_hash text
) returns boolean
language plpgsql
as $$
declare
    v_handoff public.device_credential_handoffs%rowtype;
begin
    select *
    into v_handoff
    from public.device_credential_handoffs
    where device_user_id = p_device_user_id
      and device_public_id = trim(p_device_public_id)
      and credential_hash = trim(p_credential_hash)
      and used_at is null
      and expires_at > now()
    for update;

    if not found then
        return false;
    end if;

    if v_handoff.reuse_device_credential_id is not null then
        update public.device_credentials
        set owner_id = v_handoff.owner_id,
            device_user_id = v_handoff.device_user_id,
            linked_device_id = v_handoff.linked_device_id,
            device_public_id = v_handoff.device_public_id,
            credential_hash = v_handoff.credential_hash,
            display_name = v_handoff.display_name,
            device_name = v_handoff.device_name,
            device_model = v_handoff.device_model,
            device_platform = v_handoff.device_platform,
            status = 'active',
            last_seen_at = now(),
            revoked_at = null
        where id = v_handoff.reuse_device_credential_id
          and owner_id = v_handoff.owner_id
          and status = 'active'
          and revoked_at is null;

        if not found then
            return false;
        end if;
    else
        insert into public.device_credentials (
            owner_id,
            device_user_id,
            linked_device_id,
            device_public_id,
            credential_hash,
            display_name,
            device_name,
            device_model,
            device_platform,
            status,
            last_seen_at,
            revoked_at
        ) values (
            v_handoff.owner_id,
            v_handoff.device_user_id,
            v_handoff.linked_device_id,
            v_handoff.device_public_id,
            v_handoff.credential_hash,
            v_handoff.display_name,
            v_handoff.device_name,
            v_handoff.device_model,
            v_handoff.device_platform,
            'active',
            now(),
            null
        )
        on conflict (device_user_id) do update
        set owner_id = excluded.owner_id,
            linked_device_id = excluded.linked_device_id,
            device_public_id = excluded.device_public_id,
            credential_hash = excluded.credential_hash,
            display_name = excluded.display_name,
            device_name = excluded.device_name,
            device_model = excluded.device_model,
            device_platform = excluded.device_platform,
            status = 'active',
            last_seen_at = now(),
            revoked_at = null;
    end if;

    update public.device_credential_handoffs
    set used_at = now()
    where id = v_handoff.id
      and used_at is null;

    return found;
end;
$$;
