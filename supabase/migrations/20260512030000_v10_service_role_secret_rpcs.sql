-- Contract v10: service-role secret RPCs with stale-base guard.
--
-- The user-role sync_set_account_secret / sync_delete_account_secret RPCs
-- (added in 20260512000400_contract_v10_secret_ops.sql) are what the Android
-- client uses — they read auth.uid() from the JWT and own the user's check.
--
-- nexio-web writes secrets through a separate flow: the server endpoint
-- /api/account/secrets/set holds the Supabase service-role key and calls
-- public.service_set_account_secret(p_user_id, ...) — a function with
-- SECURITY DEFINER and explicit user_id, used because Supabase auth context
-- isn't available in the service-role-key request path. This bypassed v10
-- entirely until now: any web secret rotation would silently clobber a fresher
-- write from any other client.
--
-- These wrappers add the same p_base_updated_at_ms check that the user-role
-- v10 RPCs do, against MAX(updated_at) over the target user's secrets.

CREATE OR REPLACE FUNCTION public.service_set_account_secret_v10(
  p_user_id uuid,
  p_base_updated_at_ms bigint,
  p_secret_type text,
  p_secret_ref text,
  p_secret_payload jsonb,
  p_masked_preview text,
  p_status text DEFAULT 'configured',
  p_source text DEFAULT 'web'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_current_ms bigint;
  v_new_ms bigint;
BEGIN
  IF p_user_id IS NULL THEN
    RAISE EXCEPTION 'p_user_id is required for service-role secret writes';
  END IF;

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), 0)
    INTO v_current_ms
    FROM public.account_secrets
   WHERE user_id = p_user_id;

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  PERFORM public.service_set_account_secret(
    p_user_id, p_secret_type, p_secret_ref, p_secret_payload,
    p_masked_preview, p_status, p_source
  );

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), public.sync_now_ms())
    INTO v_new_ms
    FROM public.account_secrets
   WHERE user_id = p_user_id;

  RETURN public.sync_applied_result(v_new_ms);
END;
$$;

CREATE OR REPLACE FUNCTION public.service_delete_account_secret_v10(
  p_user_id uuid,
  p_base_updated_at_ms bigint,
  p_secret_type text,
  p_secret_ref text,
  p_source text DEFAULT 'web'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_current_ms bigint;
  v_new_ms bigint;
BEGIN
  IF p_user_id IS NULL THEN
    RAISE EXCEPTION 'p_user_id is required for service-role secret writes';
  END IF;

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), 0)
    INTO v_current_ms
    FROM public.account_secrets
   WHERE user_id = p_user_id;

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  PERFORM public.service_delete_account_secret(p_user_id, p_secret_type, p_secret_ref, p_source);

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), public.sync_now_ms())
    INTO v_new_ms
    FROM public.account_secrets
   WHERE user_id = p_user_id;

  RETURN public.sync_applied_result(v_new_ms);
END;
$$;

-- Service-role only — never grant to authenticated.
REVOKE ALL ON FUNCTION public.service_set_account_secret_v10(uuid, bigint, text, text, jsonb, text, text, text) FROM public;
REVOKE ALL ON FUNCTION public.service_delete_account_secret_v10(uuid, bigint, text, text, text) FROM public;
