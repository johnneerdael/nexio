-- Contract v10 hotfix: two critical defects in the initial v10 RPCs.
--
-- (1) sync_pull_account_snapshot_v10 emitted addon rows via row_to_json(a),
--     which produced the raw column name `base_url`. Both clients require a
--     `url` field — Android's Kotlin AccountAddonPayload decoder raises
--     MissingFieldException, and nexio-web's bootstrap mapper silently drops
--     every addon via `.filter((a) => a.url)`. Replace with an explicit
--     jsonb_build_object that aliases base_url -> url, matching the v9 pull
--     shape (cf. 20260506000000 line 297-313).
--
-- (2) sync_push_account_settings_v10 read v7's scalar jsonb return value into
--     a `record` and accessed `.applied` / `.conflict_paths` / `.sync_revision`
--     as if they were record fields. plpgsql raised "record has no field" on
--     every settings push. Switch to a `jsonb` local and access via ->/->>.

CREATE OR REPLACE FUNCTION public.sync_pull_account_snapshot_v10()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
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

  SELECT settings_payload, sync_revision, public.sync_to_ms(updated_at)
    INTO v_settings_payload, v_settings_revision, v_settings_ms
    FROM public.account_settings_public
   WHERE user_id = v_user_id;

  IF v_settings_payload IS NULL THEN
    v_settings_payload := public.account_settings_v1_default_payload();
    v_settings_revision := 0;
    v_settings_ms := 0;
  END IF;

  SELECT COALESCE(jsonb_agg(
            jsonb_build_object(
              'id', a.id,
              'url', a.base_url,
              'manifest_url', COALESCE(a.manifest_url, a.base_url || '/manifest.json'),
              'parser_preset', a.parser_preset,
              'is_anime', COALESCE(a.is_anime, false),
              'name', a.name,
              'description', a.description,
              'enabled', a.enabled,
              'sort_order', a.sort_order,
              'public_query_params', a.public_query_params,
              'install_kind', a.install_kind,
              'secret_ref', a.secret_ref,
              'transport_schema_version', COALESCE(a.transport_schema_version, 1),
              'transport_base_url', COALESCE(a.transport_base_url, a.base_url),
              'transport_secret_ref', a.transport_secret_ref
            ) ORDER BY a.sort_order
          ), '[]'::jsonb),
         COALESCE(MAX(public.sync_to_ms(a.updated_at)), 0)
    INTO v_addons, v_addons_ms
    FROM public.account_addons_public a
   WHERE a.user_id = v_user_id;

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
  v_v7_result jsonb;
  v_post_ms bigint;
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

  -- Delegate to v7. v7 returns jsonb (scalar), not a record — read via ->/->>.
  v_v7_result := public.sync_push_account_settings_v7(
    p_settings_payload,
    p_base_revision,
    p_changed_paths,
    p_source
  );

  SELECT public.sync_to_ms(updated_at) INTO v_post_ms
    FROM public.account_settings_public
   WHERE user_id = v_user_id;

  IF NOT COALESCE((v_v7_result->>'applied')::boolean, false) THEN
    RETURN jsonb_build_object(
      'applied', false,
      'reason', 'field_conflict',
      'conflict_paths', COALESCE(v_v7_result->'conflict_paths', '[]'::jsonb),
      'sync_revision', COALESCE((v_v7_result->>'sync_revision')::bigint, 0),
      'current_updated_at_ms', COALESCE(v_post_ms, v_current_ms)
    );
  END IF;

  RETURN jsonb_build_object(
    'applied', true,
    'sync_revision', COALESCE((v_v7_result->>'sync_revision')::bigint, 0),
    'current_updated_at_ms', COALESCE(v_post_ms, public.sync_now_ms())
  );
END;
$$;

REVOKE ALL ON FUNCTION public.sync_push_account_settings_v10(bigint, jsonb, bigint, text[], text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_push_account_settings_v10(bigint, jsonb, bigint, text[], text) TO authenticated;
