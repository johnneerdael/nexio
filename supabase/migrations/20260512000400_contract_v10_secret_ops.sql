-- Contract v10: sync_set_account_secret_v10 / sync_delete_account_secret_v10

CREATE OR REPLACE FUNCTION public.sync_set_account_secret_v10(
  p_base_updated_at_ms bigint,
  p_secret_type text,
  p_secret_ref text,
  p_secret_payload jsonb,
  p_masked_preview text,
  p_status text DEFAULT 'configured',
  p_source text DEFAULT 'app'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_current_ms bigint;
  v_new_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), 0)
    INTO v_current_ms
    FROM public.account_secrets
   WHERE user_id = v_user_id;

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  PERFORM public.sync_set_account_secret(
    p_secret_type, p_secret_ref, p_secret_payload, p_masked_preview, p_status, p_source
  );

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), public.sync_now_ms())
    INTO v_new_ms
    FROM public.account_secrets
   WHERE user_id = v_user_id;

  RETURN public.sync_applied_result(v_new_ms);
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_delete_account_secret_v10(
  p_base_updated_at_ms bigint,
  p_secret_type text,
  p_secret_ref text,
  p_source text DEFAULT 'app'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_current_ms bigint;
  v_new_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), 0)
    INTO v_current_ms
    FROM public.account_secrets
   WHERE user_id = v_user_id;

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  PERFORM public.sync_delete_account_secret(p_secret_type, p_secret_ref, p_source);

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), public.sync_now_ms())
    INTO v_new_ms
    FROM public.account_secrets
   WHERE user_id = v_user_id;

  RETURN public.sync_applied_result(v_new_ms);
END;
$$;

REVOKE ALL ON FUNCTION public.sync_set_account_secret_v10(bigint, text, text, jsonb, text, text, text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_set_account_secret_v10(bigint, text, text, jsonb, text, text, text) TO authenticated;
REVOKE ALL ON FUNCTION public.sync_delete_account_secret_v10(bigint, text, text, text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_delete_account_secret_v10(bigint, text, text, text) TO authenticated;
