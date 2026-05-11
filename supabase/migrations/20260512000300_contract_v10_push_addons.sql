-- Contract v10: sync_push_account_addons_v10
-- Replaces the user's addon list atomically, but only if the caller's
-- base_updated_at_ms is >= MAX(updated_at_ms) over the user's rows.

CREATE OR REPLACE FUNCTION public.sync_push_account_addons_v10(
  p_base_updated_at_ms bigint,
  p_addons jsonb,
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
    FROM public.account_addons_public
   WHERE user_id = v_user_id;

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  -- Delegate to v9's writer for the actual mutation (preserves secret_ref
  -- handling, parser_preset validation, etc.).
  PERFORM public.sync_push_account_addons(p_addons, p_source);

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), public.sync_now_ms())
    INTO v_new_ms
    FROM public.account_addons_public
   WHERE user_id = v_user_id;

  RETURN public.sync_applied_result(v_new_ms);
END;
$$;

REVOKE ALL ON FUNCTION public.sync_push_account_addons_v10(bigint, jsonb, text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_push_account_addons_v10(bigint, jsonb, text) TO authenticated;
