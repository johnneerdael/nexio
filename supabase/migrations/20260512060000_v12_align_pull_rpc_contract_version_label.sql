-- Contract v12 cosmetic alignment: bump the `contract_version` literal that
-- the three pull RPCs include in their response JSON from 10 to 12 so the
-- label matches the payload schema clients now use.
--
-- The RPC function names stay `_v10` — that's the RPC envelope/routing
-- version, deliberately distinct from the payload schema. Nothing in the
-- client validates this field, so the change is purely cosmetic: future
-- readers shouldn't see "contract_version: 10" in a response and wonder
-- whether the server is behind. Function bodies are otherwise unchanged.

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
    'contract_version', 12,
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


CREATE OR REPLACE FUNCTION public.sync_pull_profile_settings_blob_v10(
  p_profile_id int,
  p_platform text DEFAULT 'tv'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_platform text := COALESCE(NULLIF(trim(p_platform), ''), 'tv');
  v_settings_json jsonb;
  v_sync_revision bigint;
  v_updated_at_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;
  IF p_profile_id < 1 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 1 and 4';
  END IF;

  SELECT ps.settings_json, ps.sync_revision, public.sync_to_ms(ps.updated_at)
    INTO v_settings_json, v_sync_revision, v_updated_at_ms
    FROM public.profile_settings ps
   WHERE ps.user_id = v_user_id
     AND ps.profile_id = p_profile_id
     AND ps.platform = v_platform;

  RETURN jsonb_build_object(
    'contract_version', 12,
    'settings_json', COALESCE(v_settings_json, '{}'::jsonb),
    'sync_revision', COALESCE(v_sync_revision, 0),
    'updated_at_ms', COALESCE(v_updated_at_ms, 0)
  );
END;
$$;


CREATE OR REPLACE FUNCTION public.sync_pull_profile_auth_tokens_v10(
  p_profile_index int
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_items jsonb;
  v_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'token_type', t.token_type,
            'token_payload', t.token_payload,
            'masked_preview', t.masked_preview,
            'linked', t.linked,
            'revoked_at_ms',
              CASE WHEN t.revoked_at IS NULL THEN NULL ELSE public.sync_to_ms(t.revoked_at) END,
            'updated_at_ms', public.sync_to_ms(t.updated_at)
          )), '[]'::jsonb),
         COALESCE(MAX(public.sync_to_ms(t.updated_at)), 0)
    INTO v_items, v_ms
    FROM public.profile_auth_tokens t
   WHERE t.user_id = v_user_id
     AND t.profile_index = p_profile_index;

  RETURN jsonb_build_object(
    'contract_version', 12,
    'items', v_items,
    'updated_at_ms', v_ms
  );
END;
$$;

-- Verification after deploy:
-- select public.sync_pull_account_snapshot_v10()->>'contract_version';      -- "12"
-- select public.sync_pull_profile_settings_blob_v10(1)->>'contract_version'; -- "12"
-- select public.sync_pull_profile_auth_tokens_v10(1)->>'contract_version';   -- "12"
