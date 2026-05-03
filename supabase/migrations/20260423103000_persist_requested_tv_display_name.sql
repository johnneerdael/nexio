alter table public.tv_login_sessions
  add column if not exists requested_display_name text;

create or replace function public.approve_tv_login_session(
  p_code text,
  p_device_nonce text,
  p_display_name text default null
)
returns table(message text)
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_row public.tv_login_sessions%rowtype;
  v_requested_display_name text := nullif(trim(coalesce(p_display_name, '')), '');
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

  update public.tv_login_sessions
    set approved_by_user_id = auth.uid(),
        approved_at = now(),
        status = 'approved',
        requested_display_name = v_requested_display_name
  where id = v_row.id;

  return query select 'TV login approved.';
end;
$$;

revoke all on function public.approve_tv_login_session(text, text, text) from public;
grant all on function public.approve_tv_login_session(text, text, text) to anon;
grant all on function public.approve_tv_login_session(text, text, text) to authenticated;
grant all on function public.approve_tv_login_session(text, text, text) to service_role;
