-- Contract v10: sync_push_account_settings_v10
-- Wraps v7's revision/field-version conflict detection with a coarse
-- stale-base guard. If the caller's base_updated_at_ms is older than
-- account_settings_public.updated_at for this user, refuse without calling v7.

CREATE OR REPLACE FUNCTION public.sync_push_account_settings_v10(
  p_base_updated_at_ms bigint,
  p_settings_payload jsonb,
  p_base_revision bigint,
  p_changed_paths text[],
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
  v_v7_result record;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  SELECT public.sync_to_ms(updated_at) INTO v_current_ms
    FROM public.account_settings_public
   WHERE user_id = v_user_id;

  v_current_ms := COALESCE(v_current_ms, 0);

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  -- Delegate to v7 for the actual write + field-version conflict resolution.
  SELECT * INTO v_v7_result
    FROM public.sync_push_account_settings_v7(
      p_settings_payload,
      p_base_revision,
      p_changed_paths,
      p_source
    );

  IF NOT v_v7_result.applied THEN
    -- v7 detected a finer-grained per-field conflict — surface it through the
    -- v10 envelope with reason='field_conflict' so clients can pick between
    -- merge strategies.
    RETURN jsonb_build_object(
      'applied', false,
      'reason', 'field_conflict',
      'conflict_paths', to_jsonb(v_v7_result.conflict_paths),
      'sync_revision', v_v7_result.sync_revision,
      'current_updated_at_ms',
        public.sync_to_ms((SELECT updated_at FROM public.account_settings_public WHERE user_id = v_user_id))
    );
  END IF;

  RETURN jsonb_build_object(
    'applied', true,
    'sync_revision', v_v7_result.sync_revision,
    'current_updated_at_ms',
      public.sync_to_ms((SELECT updated_at FROM public.account_settings_public WHERE user_id = v_user_id))
  );
END;
$$;

REVOKE ALL ON FUNCTION public.sync_push_account_settings_v10(bigint, jsonb, bigint, text[], text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_push_account_settings_v10(bigint, jsonb, bigint, text[], text) TO authenticated;
