


SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;


COMMENT ON SCHEMA "public" IS 'standard public schema';



CREATE EXTENSION IF NOT EXISTS "pg_graphql" WITH SCHEMA "graphql";






CREATE EXTENSION IF NOT EXISTS "pg_stat_statements" WITH SCHEMA "extensions";






CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA "extensions";






CREATE EXTENSION IF NOT EXISTS "supabase_vault" WITH SCHEMA "vault";






CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA "extensions";






CREATE OR REPLACE FUNCTION "public"."approve_tv_login_session"("p_code" "text", "p_device_nonce" "text") RETURNS TABLE("message" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_row public.tv_login_sessions%rowtype;
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
        status = 'approved'
  where id = v_row.id;

  return query select 'TV login approved.';
end;
$$;


ALTER FUNCTION "public"."approve_tv_login_session"("p_code" "text", "p_device_nonce" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."claim_sync_code"("p_code" "text", "p_pin" "text", "p_device_name" "text" DEFAULT NULL::"text") RETURNS TABLE("result_owner_id" "uuid", "success" boolean, "message" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_row public.sync_codes%rowtype;
  v_code text := upper(trim(coalesce(p_code, '')));
  v_pin text := trim(coalesce(p_pin, ''));
  v_device_name text := nullif(trim(coalesce(p_device_name, '')), '');
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  if v_code = '' then
    raise exception 'Invalid sync code';
  end if;

  if exists (
    select 1
    from public.linked_devices
    where device_user_id = auth.uid()
  ) then
    raise exception 'Device is already linked';
  end if;

  select *
    into v_row
  from public.sync_codes
  where code = v_code
  for update;

  if not found then
    raise exception 'Sync code not found';
  end if;

  if v_row.expires_at <= now() then
    delete from public.sync_codes where id = v_row.id;
    raise exception 'Sync code has expired';
  end if;

  if v_row.claimed_at is not null then
    raise exception 'Sync code already claimed';
  end if;

  if v_row.pin_hash <> crypt(v_pin, v_row.pin_hash) then
    raise exception 'Incorrect pin';
  end if;

  insert into public.linked_devices (
    owner_id,
    device_user_id,
    device_name,
    linked_at,
    last_seen_at,
    status
  )
  values (
    v_row.owner_id,
    auth.uid(),
    v_device_name,
    now(),
    now(),
    'online'
  );

  update public.sync_codes
    set claimed_at = now(),
        claimed_by = auth.uid()
  where id = v_row.id;

  return query select v_row.owner_id, true, 'Device linked successfully';
end;
$$;


ALTER FUNCTION "public"."claim_sync_code"("p_code" "text", "p_pin" "text", "p_device_name" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."generate_human_code"("p_length" integer DEFAULT 6) RETURNS "text"
    LANGUAGE "plpgsql"
    SET "search_path" TO 'public', 'extensions'
    AS $$
declare
  v_code text := '';
  v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_index integer;
  v_target_length integer := greatest(4, least(coalesce(p_length, 6), 12));
begin
  while char_length(v_code) < v_target_length loop
    v_index := get_byte(gen_random_bytes(1), 0) % char_length(v_alphabet);
    v_code := v_code || substr(v_alphabet, v_index + 1, 1);
  end loop;

  return v_code;
end;
$$;


ALTER FUNCTION "public"."generate_human_code"("p_length" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."generate_sync_code"("p_pin" "text") RETURNS TABLE("code" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_code text;
  v_pin text := trim(coalesce(p_pin, ''));
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  if char_length(v_pin) < 4 then
    raise exception 'Invalid pin';
  end if;

  delete from public.sync_codes
  where owner_id = auth.uid();

  v_code := public.generate_unique_human_code('sync_codes', 'code', 6);

  insert into public.sync_codes (
    owner_id,
    code,
    pin_hash,
    expires_at
  )
  values (
    auth.uid(),
    v_code,
    crypt(v_pin, gen_salt('bf')),
    now() + interval '10 minutes'
  );

  return query select v_code;
end;
$$;


ALTER FUNCTION "public"."generate_sync_code"("p_pin" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."generate_unique_human_code"("p_table_name" "text", "p_column_name" "text", "p_length" integer DEFAULT 6) RETURNS "text"
    LANGUAGE "plpgsql"
    SET "search_path" TO 'public'
    AS $_$
declare
  v_code text;
  v_exists boolean;
  v_sql text;
begin
  v_sql := format('select exists(select 1 from public.%I where %I = $1)', p_table_name, p_column_name);

  loop
    v_code := public.generate_human_code(p_length);
    execute v_sql into v_exists using v_code;
    exit when not v_exists;
  end loop;

  return v_code;
end;
$_$;


ALTER FUNCTION "public"."generate_unique_human_code"("p_table_name" "text", "p_column_name" "text", "p_length" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_sync_code"("p_pin" "text") RETURNS TABLE("code" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_row public.sync_codes%rowtype;
  v_pin text := trim(coalesce(p_pin, ''));
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  select *
    into v_row
  from public.sync_codes
  where owner_id = auth.uid()
    and claimed_at is null
    and expires_at > now()
  order by created_at desc
  limit 1;

  if not found then
    raise exception 'No sync code found';
  end if;

  if v_row.pin_hash <> crypt(v_pin, v_row.pin_hash) then
    raise exception 'Incorrect pin';
  end if;

  return query select v_row.code;
end;
$$;


ALTER FUNCTION "public"."get_sync_code"("p_pin" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."next_sync_revision"() RETURNS bigint
    LANGUAGE "sql" STABLE
    AS $$
  select floor(extract(epoch from clock_timestamp()) * 1000)::bigint
$$;


ALTER FUNCTION "public"."next_sync_revision"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."poll_tv_login_session"("p_code" "text", "p_device_nonce" "text") RETURNS TABLE("status" "text", "expires_at" timestamp with time zone, "poll_interval_seconds" integer)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_row public.tv_login_sessions%rowtype;
  v_status text;
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  select *
    into v_row
  from public.tv_login_sessions
  where code = upper(trim(coalesce(p_code, '')))
    and device_nonce = trim(coalesce(p_device_nonce, ''))
    and requester_user_id = auth.uid()
  order by created_at desc
  limit 1;

  if not found then
    raise exception 'Invalid TV login code or nonce';
  end if;

  v_status := v_row.status;

  if v_row.expires_at <= now() and v_row.status = 'pending' then
    update public.tv_login_sessions
      set status = 'expired'
    where id = v_row.id;
    v_status := 'expired';
  end if;

  return query select v_status, v_row.expires_at, v_row.poll_interval_seconds;
end;
$$;


ALTER FUNCTION "public"."poll_tv_login_session"("p_code" "text", "p_device_nonce" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."publish_account_sync_event"("p_user_id" "uuid", "p_revision" bigint, "p_event_type" "text", "p_source" "text" DEFAULT 'web'::"text") RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
begin
  insert into public.account_sync_events (user_id, revision, event_type, source)
  values (p_user_id, p_revision, p_event_type, coalesce(nullif(trim(p_source), ''), 'web'));
end;
$$;


ALTER FUNCTION "public"."publish_account_sync_event"("p_user_id" "uuid", "p_revision" bigint, "p_event_type" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."record_account_secret_audit"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_event_type" "text", "p_source" "text" DEFAULT 'web'::"text") RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
begin
  insert into public.account_secret_audit (user_id, secret_type, secret_ref, event_type, source)
  values (p_user_id, p_secret_type, p_secret_ref, p_event_type, coalesce(nullif(trim(p_source), ''), 'web'));
end;
$$;


ALTER FUNCTION "public"."record_account_secret_audit"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_event_type" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."rls_auto_enable"() RETURNS "event_trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'pg_catalog'
    AS $$
DECLARE
  cmd record;
BEGIN
  FOR cmd IN
    SELECT *
    FROM pg_event_trigger_ddl_commands()
    WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      AND object_type IN ('table','partitioned table')
  LOOP
     IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public') AND cmd.schema_name NOT IN ('pg_catalog','information_schema') AND cmd.schema_name NOT LIKE 'pg_toast%' AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
      BEGIN
        EXECUTE format('alter table if exists %s enable row level security', cmd.object_identity);
        RAISE LOG 'rls_auto_enable: enabled RLS on %', cmd.object_identity;
      EXCEPTION
        WHEN OTHERS THEN
          RAISE LOG 'rls_auto_enable: failed to enable RLS on %', cmd.object_identity;
      END;
     ELSE
        RAISE LOG 'rls_auto_enable: skip % (either system schema or not in enforced list: %.)', cmd.object_identity, cmd.schema_name;
     END IF;
  END LOOP;
END;
$$;


ALTER FUNCTION "public"."rls_auto_enable"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."service_delete_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text" DEFAULT 'web'::"text") RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_secret public.account_secrets%rowtype;
  v_revision bigint := public.next_sync_revision();
begin
  select *
    into v_secret
  from public.account_secrets
  where user_id = p_user_id
    and secret_type = trim(p_secret_type)
    and secret_ref = trim(p_secret_ref)
  for update;

  if not found then
    return;
  end if;

  delete from vault.secrets where id = v_secret.vault_secret_id;
  delete from public.account_secrets where id = v_secret.id;

  perform public.record_account_secret_audit(p_user_id, v_secret.secret_type, v_secret.secret_ref, 'delete', p_source);
  perform public.publish_account_sync_event(p_user_id, v_revision, 'secret', p_source);
end;
$$;


ALTER FUNCTION "public"."service_delete_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."service_get_account_addon_transport"("p_user_id" "uuid", "p_addon_id" "uuid", "p_source" "text" DEFAULT 'web'::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_addon public.account_addons_public%rowtype;
  v_secret_payload jsonb := '{}'::jsonb;
begin
  select *
    into v_addon
  from public.account_addons_public
  where id = p_addon_id
    and user_id = p_user_id;

  if not found then
    return '{}'::jsonb;
  end if;

  if v_addon.secret_ref is not null then
    v_secret_payload := public.service_resolve_account_secret(p_user_id, 'addon_credential', v_addon.secret_ref, p_source);
  end if;

  return jsonb_build_object(
    'addon_id', v_addon.id,
    'addon_name', v_addon.name,
    'base_url', v_addon.base_url,
    'manifest_url', coalesce(v_addon.manifest_url, v_addon.base_url || '/manifest.json'),
    'public_query_params', v_addon.public_query_params,
    'secret_payload', v_secret_payload
  );
end;
$$;


ALTER FUNCTION "public"."service_get_account_addon_transport"("p_user_id" "uuid", "p_addon_id" "uuid", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."service_resolve_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text" DEFAULT 'web'::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_secret public.account_secrets%rowtype;
  v_decrypted text;
begin
  select *
    into v_secret
  from public.account_secrets
  where user_id = p_user_id
    and secret_type = trim(p_secret_type)
    and secret_ref = trim(p_secret_ref);

  if not found then
    return '{}'::jsonb;
  end if;

  select decrypted_secret
    into v_decrypted
  from vault.decrypted_secrets
  where id = v_secret.vault_secret_id;

  perform public.record_account_secret_audit(p_user_id, v_secret.secret_type, v_secret.secret_ref, 'resolve', p_source);

  return coalesce(v_decrypted::jsonb, '{}'::jsonb);
end;
$$;


ALTER FUNCTION "public"."service_resolve_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."service_set_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text" DEFAULT 'configured'::"text", "p_source" "text" DEFAULT 'web'::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_secret public.account_secrets%rowtype;
  v_secret_body text := coalesce(p_secret_payload::text, '{}'::text);
  v_revision bigint := public.next_sync_revision();
  v_updated_at timestamptz := now();
  v_description text := format('Nexio %s %s', trim(p_secret_type), trim(p_secret_ref));
begin
  if p_user_id is null then
    raise exception 'p_user_id is required';
  end if;

  if trim(coalesce(p_secret_type, '')) = '' or trim(coalesce(p_secret_ref, '')) = '' then
    raise exception 'secret_type and secret_ref are required';
  end if;

  select *
    into v_secret
  from public.account_secrets
  where user_id = p_user_id
    and secret_type = trim(p_secret_type)
    and secret_ref = trim(p_secret_ref)
  for update;

  if found then
    perform vault.update_secret(v_secret.vault_secret_id, v_secret_body, null, v_description);

    update public.account_secrets
      set masked_preview = nullif(trim(p_masked_preview), ''),
          status = coalesce(nullif(trim(p_status), ''), 'configured'),
          version = version + 1,
          updated_at = v_updated_at,
          updated_from = coalesce(nullif(trim(p_source), ''), 'web')
    where id = v_secret.id
    returning * into v_secret;
  else
    insert into public.account_secrets (
      user_id,
      secret_type,
      secret_ref,
      vault_secret_id,
      masked_preview,
      status,
      version,
      updated_at,
      updated_from
    )
    values (
      p_user_id,
      trim(p_secret_type),
      trim(p_secret_ref),
      vault.create_secret(v_secret_body, null, v_description),
      nullif(trim(p_masked_preview), ''),
      coalesce(nullif(trim(p_status), ''), 'configured'),
      1,
      v_updated_at,
      coalesce(nullif(trim(p_source), ''), 'web')
    )
    returning * into v_secret;
  end if;

  perform public.record_account_secret_audit(p_user_id, v_secret.secret_type, v_secret.secret_ref, 'set', p_source);
  perform public.publish_account_sync_event(p_user_id, v_revision, 'secret', p_source);

  return jsonb_build_object(
    'id', v_secret.id,
    'secret_type', v_secret.secret_type,
    'secret_ref', v_secret.secret_ref,
    'masked_preview', v_secret.masked_preview,
    'status', v_secret.status,
    'version', v_secret.version,
    'updated_at', v_secret.updated_at
  );
end;
$$;


ALTER FUNCTION "public"."service_set_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."start_tv_login_session"("p_device_nonce" "text", "p_redirect_base_url" "text", "p_device_name" "text" DEFAULT NULL::"text") RETURNS TABLE("code" "text", "web_url" "text", "expires_at" timestamp with time zone, "poll_interval_seconds" integer)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $_$
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

  -- IMPORTANT: qualify expires_at with table alias to avoid collision
  -- with function output column name "expires_at".
  update public.tv_login_sessions tls
    set status = 'expired'
  where tls.requester_user_id = auth.uid()
    and tls.status = 'pending'
    and tls.expires_at <= now();

  v_code := public.generate_unique_human_code('tv_login_sessions', 'code', 6);
  v_base_url := regexp_replace(v_base_url, '/+$', '');
  v_web_url := v_base_url || '/approve?code=' || v_code || '&nonce=' || replace(v_nonce, '+', '%2B');

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
$_$;


ALTER FUNCTION "public"."start_tv_login_session"("p_device_nonce" "text", "p_redirect_base_url" "text", "p_device_name" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_delete_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text" DEFAULT 'app'::"text") RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
begin
  perform public.service_delete_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_source
  );
end;
$$;


ALTER FUNCTION "public"."sync_delete_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_owner_id"() RETURNS "uuid"
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
  select coalesce(
    (select owner_id from public.linked_devices where device_user_id = auth.uid()),
    auth.uid()
  )
$$;


ALTER FUNCTION "public"."sync_owner_id"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_pull_account_snapshot"() RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_settings jsonb := '{}'::jsonb;
  v_revision bigint := 0;
  v_updated_at timestamptz := null;
  v_addons jsonb := '[]'::jsonb;
  v_settings_updated_at timestamptz := null;
  v_addons_updated_at timestamptz := null;
begin
  select settings_payload, updated_at
    into v_settings, v_settings_updated_at
  from public.account_settings_public
  where user_id = v_user_id;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'id', id,
        'url', base_url,
        'manifest_url', coalesce(manifest_url, base_url || '/manifest.json'),
        'parser_preset', parser_preset,
        'name', name,
        'description', description,
        'enabled', enabled,
        'sort_order', sort_order,
        'public_query_params', public_query_params,
        'install_kind', install_kind,
        'secret_ref', secret_ref
      ) order by sort_order asc
    ),
    '[]'::jsonb
  ), max(updated_at)
    into v_addons, v_addons_updated_at
  from public.account_addons_public
  where user_id = v_user_id;

  select revision, created_at
    into v_revision, v_updated_at
  from public.account_sync_events
  where user_id = v_user_id
  order by created_at desc
  limit 1;

  v_updated_at := coalesce(v_updated_at, greatest(v_settings_updated_at, v_addons_updated_at), v_settings_updated_at, v_addons_updated_at);

  return jsonb_build_object(
    'user_id', v_user_id,
    'revision', coalesce(v_revision, 0),
    'updated_at', v_updated_at,
    'settings', coalesce(v_settings, '{}'::jsonb),
    'addons', v_addons
  );
end;
$$;


ALTER FUNCTION "public"."sync_pull_account_snapshot"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_push_account_addons"("p_addons" "jsonb", "p_source" "text" DEFAULT 'app'::"text") RETURNS TABLE("sync_revision" bigint, "updated_at" timestamp with time zone)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_revision bigint := public.next_sync_revision();
  v_updated_at timestamptz := now();
begin
  delete from public.account_addons_public where user_id = v_user_id;

  insert into public.account_addons_public (
    user_id,
    base_url,
    manifest_url,
    parser_preset,
    name,
    description,
    enabled,
    sort_order,
    public_query_params,
    install_kind,
    secret_ref,
    updated_at
  )
  select
    v_user_id,
    trim(entry->>'url'),
    nullif(trim(entry->>'manifest_url'), ''),
    coalesce(nullif(trim(entry->>'parser_preset'), ''), 'GENERIC'),
    nullif(trim(entry->>'name'), ''),
    nullif(trim(entry->>'description'), ''),
    coalesce((entry->>'enabled')::boolean, true),
    coalesce((entry->>'sort_order')::integer, ordinal - 1),
    coalesce(entry->'public_query_params', '{}'::jsonb),
    coalesce(nullif(trim(entry->>'install_kind'), ''), 'manifest'),
    nullif(trim(entry->>'secret_ref'), ''),
    v_updated_at
  from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) with ordinality as rows(entry, ordinal)
  where trim(entry->>'url') <> '';

  perform public.publish_account_sync_event(v_user_id, v_revision, 'addons_public', p_source);

  return query select v_revision, v_updated_at;
end;
$$;


ALTER FUNCTION "public"."sync_push_account_addons"("p_addons" "jsonb", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_push_account_settings"("p_settings_payload" "jsonb", "p_source" "text" DEFAULT 'app'::"text") RETURNS TABLE("sync_revision" bigint, "updated_at" timestamp with time zone)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
declare
  v_user_id uuid := public.sync_owner_id();
  v_revision bigint := public.next_sync_revision();
  v_updated_at timestamptz := now();
begin
  insert into public.account_settings_public (user_id, settings_payload, sync_revision, updated_at, updated_from)
  values (v_user_id, coalesce(p_settings_payload, '{}'::jsonb), v_revision, v_updated_at, coalesce(nullif(trim(p_source), ''), 'app'))
  on conflict (user_id) do update
    set settings_payload = excluded.settings_payload,
        sync_revision = excluded.sync_revision,
        updated_at = excluded.updated_at,
        updated_from = excluded.updated_from;

  perform public.publish_account_sync_event(v_user_id, v_revision, 'settings_public', p_source);

  return query select v_revision, v_updated_at;
end;
$$;


ALTER FUNCTION "public"."sync_push_account_settings"("p_settings_payload" "jsonb", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_resolve_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text" DEFAULT 'app'::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
begin
  return public.service_resolve_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_source
  );
end;
$$;


ALTER FUNCTION "public"."sync_resolve_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_set_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text" DEFAULT 'configured'::"text", "p_source" "text" DEFAULT 'app'::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
begin
  return public.service_set_account_secret(
    public.sync_owner_id(),
    p_secret_type,
    p_secret_ref,
    p_secret_payload,
    p_masked_preview,
    p_status,
    p_source
  );
end;
$$;


ALTER FUNCTION "public"."sync_set_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."unlink_device"("p_device_user_id" "uuid") RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
  end if;

  delete from public.linked_devices
  where owner_id = auth.uid()
    and device_user_id = p_device_user_id;
end;
$$;


ALTER FUNCTION "public"."unlink_device"("p_device_user_id" "uuid") OWNER TO "postgres";

SET default_tablespace = '';

SET default_table_access_method = "heap";


CREATE TABLE IF NOT EXISTS "public"."account_addons_public" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "base_url" "text" NOT NULL,
    "manifest_url" "text",
    "name" "text",
    "description" "text",
    "enabled" boolean DEFAULT true NOT NULL,
    "sort_order" integer DEFAULT 0 NOT NULL,
    "public_query_params" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "install_kind" "text" DEFAULT 'manifest'::"text" NOT NULL,
    "secret_ref" "text",
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "parser_preset" "text" DEFAULT 'GENERIC'::"text" NOT NULL,
    CONSTRAINT "account_addons_public_install_kind_check" CHECK (("install_kind" = ANY (ARRAY['manifest'::"text", 'configured'::"text"]))),
    CONSTRAINT "account_addons_public_parser_preset_check" CHECK (("parser_preset" = ANY (ARRAY['GENERIC'::"text", 'STREMTHRU'::"text", 'TORRENTIO'::"text", 'WEBSTREAMR'::"text"])))
);


ALTER TABLE "public"."account_addons_public" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."account_secret_audit" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "secret_type" "text" NOT NULL,
    "secret_ref" "text" NOT NULL,
    "event_type" "text" NOT NULL,
    "source" "text" DEFAULT 'web'::"text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "account_secret_audit_event_type_check" CHECK (("event_type" = ANY (ARRAY['set'::"text", 'delete'::"text", 'resolve'::"text"])))
);


ALTER TABLE "public"."account_secret_audit" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."account_secrets" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "secret_type" "text" NOT NULL,
    "secret_ref" "text" NOT NULL,
    "vault_secret_id" "uuid" NOT NULL,
    "masked_preview" "text",
    "status" "text" DEFAULT 'configured'::"text" NOT NULL,
    "version" integer DEFAULT 1 NOT NULL,
    "updated_from" "text" DEFAULT 'web'::"text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "account_secrets_secret_type_check" CHECK (("secret_type" = ANY (ARRAY['addon_credential'::"text", 'tmdb_api_key'::"text", 'mdblist_api_key'::"text", 'premiumize_api_key'::"text", 'gemini_api_key'::"text", 'rpdb_api_key'::"text", 'top_posters_api_key'::"text", 'realdebrid_access_token'::"text", 'realdebrid_refresh_token'::"text", 'trakt_access_token'::"text", 'trakt_refresh_token'::"text"]))),
    CONSTRAINT "account_secrets_status_check" CHECK (("status" = ANY (ARRAY['configured'::"text", 'missing'::"text", 'error'::"text"])))
);


ALTER TABLE "public"."account_secrets" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."account_settings_public" (
    "user_id" "uuid" NOT NULL,
    "settings_payload" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "sync_revision" bigint DEFAULT 0 NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_from" "text" DEFAULT 'web'::"text" NOT NULL
);


ALTER TABLE "public"."account_settings_public" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."account_sync_events" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "revision" bigint NOT NULL,
    "event_type" "text" NOT NULL,
    "source" "text" DEFAULT 'web'::"text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "account_sync_events_event_type_check" CHECK (("event_type" = ANY (ARRAY['settings_public'::"text", 'addons_public'::"text", 'secret'::"text", 'snapshot'::"text"])))
);


ALTER TABLE "public"."account_sync_events" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."linked_devices" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "owner_id" "uuid" NOT NULL,
    "device_user_id" "uuid" NOT NULL,
    "device_name" "text",
    "device_model" "text",
    "device_platform" "text",
    "status" "text" DEFAULT 'idle'::"text" NOT NULL,
    "linked_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "last_seen_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "linked_devices_status_check" CHECK (("status" = ANY (ARRAY['online'::"text", 'idle'::"text", 'offline'::"text"])))
);


ALTER TABLE "public"."linked_devices" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."sync_codes" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "owner_id" "uuid" NOT NULL,
    "code" "text" NOT NULL,
    "pin_hash" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "claimed_at" timestamp with time zone,
    "claimed_by" "uuid"
);


ALTER TABLE "public"."sync_codes" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."tv_login_sessions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "code" "text" NOT NULL,
    "requester_user_id" "uuid" NOT NULL,
    "approved_by_user_id" "uuid",
    "device_nonce" "text" NOT NULL,
    "device_name" "text",
    "device_model" "text",
    "device_platform" "text",
    "redirect_base_url" "text" NOT NULL,
    "web_url" "text" NOT NULL,
    "status" "text" DEFAULT 'pending'::"text" NOT NULL,
    "poll_interval_seconds" integer DEFAULT 3 NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "approved_at" timestamp with time zone,
    "used_at" timestamp with time zone,
    "expires_at" timestamp with time zone NOT NULL,
    CONSTRAINT "tv_login_sessions_status_check" CHECK (("status" = ANY (ARRAY['pending'::"text", 'approved'::"text", 'used'::"text", 'expired'::"text", 'cancelled'::"text"])))
);


ALTER TABLE "public"."tv_login_sessions" OWNER TO "postgres";


ALTER TABLE ONLY "public"."account_addons_public"
    ADD CONSTRAINT "account_addons_public_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."account_secret_audit"
    ADD CONSTRAINT "account_secret_audit_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."account_secrets"
    ADD CONSTRAINT "account_secrets_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."account_secrets"
    ADD CONSTRAINT "account_secrets_vault_secret_id_key" UNIQUE ("vault_secret_id");



ALTER TABLE ONLY "public"."account_settings_public"
    ADD CONSTRAINT "account_settings_public_pkey" PRIMARY KEY ("user_id");



ALTER TABLE ONLY "public"."account_sync_events"
    ADD CONSTRAINT "account_sync_events_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."linked_devices"
    ADD CONSTRAINT "linked_devices_device_user_id_key" UNIQUE ("device_user_id");



ALTER TABLE ONLY "public"."linked_devices"
    ADD CONSTRAINT "linked_devices_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."sync_codes"
    ADD CONSTRAINT "sync_codes_code_key" UNIQUE ("code");



ALTER TABLE ONLY "public"."sync_codes"
    ADD CONSTRAINT "sync_codes_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."tv_login_sessions"
    ADD CONSTRAINT "tv_login_sessions_code_key" UNIQUE ("code");



ALTER TABLE ONLY "public"."tv_login_sessions"
    ADD CONSTRAINT "tv_login_sessions_pkey" PRIMARY KEY ("id");



CREATE UNIQUE INDEX "account_addons_public_user_base_uidx" ON "public"."account_addons_public" USING "btree" ("user_id", "lower"("base_url"), COALESCE("secret_ref", ''::"text"));



CREATE INDEX "account_addons_public_user_sort_idx" ON "public"."account_addons_public" USING "btree" ("user_id", "sort_order");



CREATE INDEX "account_secret_audit_user_created_idx" ON "public"."account_secret_audit" USING "btree" ("user_id", "created_at" DESC);



CREATE INDEX "account_secrets_user_ref_idx" ON "public"."account_secrets" USING "btree" ("user_id", "secret_ref");



CREATE UNIQUE INDEX "account_secrets_user_type_ref_uidx" ON "public"."account_secrets" USING "btree" ("user_id", "secret_type", "secret_ref");



CREATE INDEX "account_sync_events_user_created_idx" ON "public"."account_sync_events" USING "btree" ("user_id", "created_at" DESC);



CREATE INDEX "linked_devices_last_seen_idx" ON "public"."linked_devices" USING "btree" ("owner_id", "last_seen_at" DESC);



CREATE INDEX "linked_devices_owner_idx" ON "public"."linked_devices" USING "btree" ("owner_id");



CREATE INDEX "sync_codes_code_idx" ON "public"."sync_codes" USING "btree" ("code");



CREATE INDEX "sync_codes_owner_idx" ON "public"."sync_codes" USING "btree" ("owner_id", "expires_at" DESC);



CREATE INDEX "tv_login_sessions_approved_idx" ON "public"."tv_login_sessions" USING "btree" ("approved_by_user_id", "created_at" DESC);



CREATE UNIQUE INDEX "tv_login_sessions_code_nonce_uidx" ON "public"."tv_login_sessions" USING "btree" ("code", "device_nonce");



CREATE INDEX "tv_login_sessions_requester_idx" ON "public"."tv_login_sessions" USING "btree" ("requester_user_id", "created_at" DESC);



ALTER TABLE ONLY "public"."account_addons_public"
    ADD CONSTRAINT "account_addons_public_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."account_secret_audit"
    ADD CONSTRAINT "account_secret_audit_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."account_secrets"
    ADD CONSTRAINT "account_secrets_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."account_settings_public"
    ADD CONSTRAINT "account_settings_public_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."account_sync_events"
    ADD CONSTRAINT "account_sync_events_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."linked_devices"
    ADD CONSTRAINT "linked_devices_device_user_id_fkey" FOREIGN KEY ("device_user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."linked_devices"
    ADD CONSTRAINT "linked_devices_owner_id_fkey" FOREIGN KEY ("owner_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."sync_codes"
    ADD CONSTRAINT "sync_codes_claimed_by_fkey" FOREIGN KEY ("claimed_by") REFERENCES "auth"."users"("id") ON DELETE SET NULL;



ALTER TABLE ONLY "public"."sync_codes"
    ADD CONSTRAINT "sync_codes_owner_id_fkey" FOREIGN KEY ("owner_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."tv_login_sessions"
    ADD CONSTRAINT "tv_login_sessions_approved_by_user_id_fkey" FOREIGN KEY ("approved_by_user_id") REFERENCES "auth"."users"("id") ON DELETE SET NULL;



ALTER TABLE ONLY "public"."tv_login_sessions"
    ADD CONSTRAINT "tv_login_sessions_requester_user_id_fkey" FOREIGN KEY ("requester_user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE "public"."account_addons_public" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "account_addons_public_owner_select" ON "public"."account_addons_public" FOR SELECT TO "authenticated" USING (("user_id" = "public"."sync_owner_id"()));



CREATE POLICY "account_addons_public_owner_write" ON "public"."account_addons_public" TO "authenticated" USING (("user_id" = "auth"."uid"())) WITH CHECK (("user_id" = "auth"."uid"()));



ALTER TABLE "public"."account_secret_audit" ENABLE ROW LEVEL SECURITY;


ALTER TABLE "public"."account_secrets" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "account_secrets_owner_select" ON "public"."account_secrets" FOR SELECT TO "authenticated" USING (("user_id" = "public"."sync_owner_id"()));



ALTER TABLE "public"."account_settings_public" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "account_settings_public_owner_select" ON "public"."account_settings_public" FOR SELECT TO "authenticated" USING (("user_id" = "public"."sync_owner_id"()));



CREATE POLICY "account_settings_public_owner_write" ON "public"."account_settings_public" TO "authenticated" USING (("user_id" = "auth"."uid"())) WITH CHECK (("user_id" = "auth"."uid"()));



ALTER TABLE "public"."account_sync_events" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "account_sync_events_owner_insert" ON "public"."account_sync_events" FOR INSERT TO "authenticated" WITH CHECK (("user_id" = "auth"."uid"()));



CREATE POLICY "account_sync_events_owner_select" ON "public"."account_sync_events" FOR SELECT TO "authenticated" USING (("user_id" = "public"."sync_owner_id"()));



ALTER TABLE "public"."linked_devices" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "linked_devices_owner_or_device_select" ON "public"."linked_devices" FOR SELECT TO "authenticated" USING ((("owner_id" = "auth"."uid"()) OR ("device_user_id" = "auth"."uid"())));



ALTER TABLE "public"."sync_codes" ENABLE ROW LEVEL SECURITY;


ALTER TABLE "public"."tv_login_sessions" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "tv_login_sessions_requester_select" ON "public"."tv_login_sessions" FOR SELECT TO "authenticated" USING ((("requester_user_id" = "auth"."uid"()) OR ("approved_by_user_id" = "auth"."uid"())));





ALTER PUBLICATION "supabase_realtime" OWNER TO "postgres";






ALTER PUBLICATION "supabase_realtime" ADD TABLE ONLY "public"."account_sync_events";



GRANT USAGE ON SCHEMA "public" TO "postgres";
GRANT USAGE ON SCHEMA "public" TO "anon";
GRANT USAGE ON SCHEMA "public" TO "authenticated";
GRANT USAGE ON SCHEMA "public" TO "service_role";

























































































































































REVOKE ALL ON FUNCTION "public"."approve_tv_login_session"("p_code" "text", "p_device_nonce" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."approve_tv_login_session"("p_code" "text", "p_device_nonce" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."approve_tv_login_session"("p_code" "text", "p_device_nonce" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."approve_tv_login_session"("p_code" "text", "p_device_nonce" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."claim_sync_code"("p_code" "text", "p_pin" "text", "p_device_name" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."claim_sync_code"("p_code" "text", "p_pin" "text", "p_device_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."claim_sync_code"("p_code" "text", "p_pin" "text", "p_device_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."claim_sync_code"("p_code" "text", "p_pin" "text", "p_device_name" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."generate_human_code"("p_length" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."generate_human_code"("p_length" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."generate_human_code"("p_length" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."generate_human_code"("p_length" integer) TO "service_role";



REVOKE ALL ON FUNCTION "public"."generate_sync_code"("p_pin" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."generate_sync_code"("p_pin" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."generate_sync_code"("p_pin" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."generate_sync_code"("p_pin" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."generate_unique_human_code"("p_table_name" "text", "p_column_name" "text", "p_length" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."generate_unique_human_code"("p_table_name" "text", "p_column_name" "text", "p_length" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."generate_unique_human_code"("p_table_name" "text", "p_column_name" "text", "p_length" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."generate_unique_human_code"("p_table_name" "text", "p_column_name" "text", "p_length" integer) TO "service_role";



REVOKE ALL ON FUNCTION "public"."get_sync_code"("p_pin" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."get_sync_code"("p_pin" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."get_sync_code"("p_pin" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_sync_code"("p_pin" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."next_sync_revision"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."next_sync_revision"() TO "anon";
GRANT ALL ON FUNCTION "public"."next_sync_revision"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."next_sync_revision"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."poll_tv_login_session"("p_code" "text", "p_device_nonce" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."poll_tv_login_session"("p_code" "text", "p_device_nonce" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."poll_tv_login_session"("p_code" "text", "p_device_nonce" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."poll_tv_login_session"("p_code" "text", "p_device_nonce" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."publish_account_sync_event"("p_user_id" "uuid", "p_revision" bigint, "p_event_type" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."publish_account_sync_event"("p_user_id" "uuid", "p_revision" bigint, "p_event_type" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."publish_account_sync_event"("p_user_id" "uuid", "p_revision" bigint, "p_event_type" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."publish_account_sync_event"("p_user_id" "uuid", "p_revision" bigint, "p_event_type" "text", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."record_account_secret_audit"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_event_type" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."record_account_secret_audit"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_event_type" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."record_account_secret_audit"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_event_type" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."record_account_secret_audit"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_event_type" "text", "p_source" "text") TO "service_role";



GRANT ALL ON FUNCTION "public"."rls_auto_enable"() TO "anon";
GRANT ALL ON FUNCTION "public"."rls_auto_enable"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."rls_auto_enable"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."service_delete_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."service_delete_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."service_delete_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."service_delete_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."service_get_account_addon_transport"("p_user_id" "uuid", "p_addon_id" "uuid", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."service_get_account_addon_transport"("p_user_id" "uuid", "p_addon_id" "uuid", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."service_get_account_addon_transport"("p_user_id" "uuid", "p_addon_id" "uuid", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."service_get_account_addon_transport"("p_user_id" "uuid", "p_addon_id" "uuid", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."service_resolve_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."service_resolve_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."service_resolve_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."service_resolve_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."service_set_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."service_set_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."service_set_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."service_set_account_secret"("p_user_id" "uuid", "p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."start_tv_login_session"("p_device_nonce" "text", "p_redirect_base_url" "text", "p_device_name" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."start_tv_login_session"("p_device_nonce" "text", "p_redirect_base_url" "text", "p_device_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."start_tv_login_session"("p_device_nonce" "text", "p_redirect_base_url" "text", "p_device_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."start_tv_login_session"("p_device_nonce" "text", "p_redirect_base_url" "text", "p_device_name" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."sync_delete_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."sync_delete_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."sync_delete_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."sync_delete_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."sync_owner_id"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."sync_owner_id"() TO "anon";
GRANT ALL ON FUNCTION "public"."sync_owner_id"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."sync_owner_id"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."sync_pull_account_snapshot"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."sync_pull_account_snapshot"() TO "anon";
GRANT ALL ON FUNCTION "public"."sync_pull_account_snapshot"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."sync_pull_account_snapshot"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."sync_push_account_addons"("p_addons" "jsonb", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."sync_push_account_addons"("p_addons" "jsonb", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."sync_push_account_addons"("p_addons" "jsonb", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."sync_push_account_addons"("p_addons" "jsonb", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."sync_push_account_settings"("p_settings_payload" "jsonb", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."sync_push_account_settings"("p_settings_payload" "jsonb", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."sync_push_account_settings"("p_settings_payload" "jsonb", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."sync_push_account_settings"("p_settings_payload" "jsonb", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."sync_resolve_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."sync_resolve_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."sync_resolve_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."sync_resolve_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."sync_set_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."sync_set_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."sync_set_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."sync_set_account_secret"("p_secret_type" "text", "p_secret_ref" "text", "p_secret_payload" "jsonb", "p_masked_preview" "text", "p_status" "text", "p_source" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."unlink_device"("p_device_user_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."unlink_device"("p_device_user_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."unlink_device"("p_device_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."unlink_device"("p_device_user_id" "uuid") TO "service_role";


















GRANT ALL ON TABLE "public"."account_addons_public" TO "anon";
GRANT ALL ON TABLE "public"."account_addons_public" TO "authenticated";
GRANT ALL ON TABLE "public"."account_addons_public" TO "service_role";



GRANT ALL ON TABLE "public"."account_secret_audit" TO "anon";
GRANT ALL ON TABLE "public"."account_secret_audit" TO "authenticated";
GRANT ALL ON TABLE "public"."account_secret_audit" TO "service_role";



GRANT ALL ON TABLE "public"."account_secrets" TO "anon";
GRANT ALL ON TABLE "public"."account_secrets" TO "authenticated";
GRANT ALL ON TABLE "public"."account_secrets" TO "service_role";



GRANT ALL ON TABLE "public"."account_settings_public" TO "anon";
GRANT ALL ON TABLE "public"."account_settings_public" TO "authenticated";
GRANT ALL ON TABLE "public"."account_settings_public" TO "service_role";



GRANT ALL ON TABLE "public"."account_sync_events" TO "anon";
GRANT ALL ON TABLE "public"."account_sync_events" TO "authenticated";
GRANT ALL ON TABLE "public"."account_sync_events" TO "service_role";



GRANT ALL ON TABLE "public"."linked_devices" TO "anon";
GRANT ALL ON TABLE "public"."linked_devices" TO "authenticated";
GRANT ALL ON TABLE "public"."linked_devices" TO "service_role";



GRANT ALL ON TABLE "public"."sync_codes" TO "anon";
GRANT ALL ON TABLE "public"."sync_codes" TO "authenticated";
GRANT ALL ON TABLE "public"."sync_codes" TO "service_role";



GRANT ALL ON TABLE "public"."tv_login_sessions" TO "anon";
GRANT ALL ON TABLE "public"."tv_login_sessions" TO "authenticated";
GRANT ALL ON TABLE "public"."tv_login_sessions" TO "service_role";









ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "service_role";






ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "service_role";






ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "service_role";



































drop extension if exists "pg_net";


