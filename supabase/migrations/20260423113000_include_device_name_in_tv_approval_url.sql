create or replace function public.url_encode(input text)
returns text
language plpgsql
immutable
as $$
declare
  bytes bytea;
  byte_value integer;
  idx integer;
  output text := '';
begin
  if input is null then
    return null;
  end if;

  bytes := convert_to(input, 'UTF8');

  for idx in 0..length(bytes) - 1 loop
    byte_value := get_byte(bytes, idx);

    if
      (byte_value between 48 and 57) or
      (byte_value between 65 and 90) or
      (byte_value between 97 and 122) or
      byte_value in (45, 46, 95, 126)
    then
      output := output || chr(byte_value);
    else
      output := output || '%' || upper(lpad(to_hex(byte_value), 2, '0'));
    end if;
  end loop;

  return output;
end;
$$;

create or replace function public.start_tv_login_session(
  p_device_nonce text,
  p_redirect_base_url text,
  p_device_name text default null
)
returns table(
  code text,
  web_url text,
  expires_at timestamptz,
  poll_interval_seconds integer
)
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_code text;
  v_expires_at timestamptz := now() + interval '10 minutes';
  v_poll_interval integer := 3;
  v_base_url text := trim(coalesce(p_redirect_base_url, ''));
  v_nonce text := trim(coalesce(p_device_nonce, ''));
  v_web_url text;
  v_device_name text := nullif(trim(coalesce(p_device_name, '')), '');
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  if v_nonce = '' then
    raise exception 'Invalid device nonce';
  end if;

  if v_base_url !~* '^https?://[^ ]+$' then
    raise exception 'Invalid TV login redirect base url';
  end if;

  update public.tv_login_sessions tls
    set status = 'expired'
  where tls.requester_user_id = auth.uid()
    and tls.status = 'pending'
    and tls.expires_at <= now();

  v_code := public.generate_unique_human_code('tv_login_sessions', 'code', 6);
  v_base_url := regexp_replace(v_base_url, '/+$', '');
  v_web_url := v_base_url || '/approve?code=' || v_code || '&nonce=' || replace(v_nonce, '+', '%2B');

  if v_device_name is not null then
    v_web_url := v_web_url || '&device_name=' || public.url_encode(v_device_name);
  end if;

  insert into public.tv_login_sessions (
    code,
    requester_user_id,
    device_nonce,
    device_name,
    redirect_base_url,
    web_url,
    status,
    poll_interval_seconds,
    expires_at
  )
  values (
    v_code,
    auth.uid(),
    v_nonce,
    v_device_name,
    v_base_url,
    v_web_url,
    'pending',
    v_poll_interval,
    v_expires_at
  );

  return query select v_code, v_web_url, v_expires_at, v_poll_interval;
end;
$$;

revoke all on function public.start_tv_login_session(text, text, text) from public;
grant all on function public.start_tv_login_session(text, text, text) to anon;
grant all on function public.start_tv_login_session(text, text, text) to authenticated;
grant all on function public.start_tv_login_session(text, text, text) to service_role;
