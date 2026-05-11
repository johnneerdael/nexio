-- Contract v10: sync_pull_account_snapshot_v10
-- Returns the same payload as v9 (sync_pull_account_snapshot) plus an
-- envelope of per-surface updated_at_ms watermarks.

CREATE OR REPLACE FUNCTION public.sync_pull_account_snapshot_v10()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_settings_row record;
  v_settings_payload jsonb;
  v_settings_revision bigint;
  v_settings_ms bigint;
  v_addons jsonb;
  v_addons_ms bigint;
  v_secrets jsonb;
  v_secrets_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  -- Settings (one row per user; absent → empty payload, ms=0)
  SELECT settings_payload, sync_revision, public.sync_to_ms(updated_at)
    INTO v_settings_payload, v_settings_revision, v_settings_ms
    FROM public.account_settings_public
   WHERE user_id = v_user_id;

  IF v_settings_payload IS NULL THEN
    v_settings_payload := public.account_settings_v1_default_payload();
    v_settings_revision := 0;
    v_settings_ms := 0;
  END IF;

  -- Addons (collection; ms = max over user's rows, 0 if empty)
  SELECT COALESCE(jsonb_agg(row_to_json(a)::jsonb ORDER BY a.sort_order), '[]'::jsonb),
         COALESCE(MAX(public.sync_to_ms(a.updated_at)), 0)
    INTO v_addons, v_addons_ms
    FROM public.account_addons_public a
   WHERE a.user_id = v_user_id;

  -- Secrets (collection; ms = max over user's rows, 0 if empty)
  SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'secret_type', s.secret_type,
            'secret_ref',  s.secret_ref,
            'masked_preview', s.masked_preview,
            'status', s.status,
            'updated_at_ms', public.sync_to_ms(s.updated_at)
          )), '[]'::jsonb),
         COALESCE(MAX(public.sync_to_ms(s.updated_at)), 0)
    INTO v_secrets, v_secrets_ms
    FROM public.account_secrets s
   WHERE s.user_id = v_user_id;

  RETURN jsonb_build_object(
    'contract_version', 10,
    'settings', jsonb_build_object(
      'payload', v_settings_payload,
      'sync_revision', v_settings_revision,
      'updated_at_ms', v_settings_ms
    ),
    'addons', jsonb_build_object(
      'items', v_addons,
      'updated_at_ms', v_addons_ms
    ),
    'secrets', jsonb_build_object(
      'items', v_secrets,
      'updated_at_ms', v_secrets_ms
    )
  );
END;
$$;

REVOKE ALL ON FUNCTION public.sync_pull_account_snapshot_v10() FROM public;
GRANT EXECUTE ON FUNCTION public.sync_pull_account_snapshot_v10() TO authenticated;
