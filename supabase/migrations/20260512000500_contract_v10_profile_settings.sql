-- Contract v10: profile_settings pull/push

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
    'contract_version', 10,
    'settings_json', COALESCE(v_settings_json, '{}'::jsonb),
    'sync_revision', COALESCE(v_sync_revision, 0),
    'updated_at_ms', COALESCE(v_updated_at_ms, 0)
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_profile_settings_blob_v10(
  p_base_updated_at_ms bigint,
  p_profile_id int,
  p_settings_json jsonb,
  p_platform text DEFAULT 'tv',
  p_source text DEFAULT 'app'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_platform text := COALESCE(NULLIF(trim(p_platform), ''), 'tv');
  v_current_ms bigint;
  v_new_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;
  IF p_profile_id < 1 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 1 and 4';
  END IF;

  SELECT public.sync_to_ms(updated_at) INTO v_current_ms
    FROM public.profile_settings
   WHERE user_id = v_user_id
     AND profile_id = p_profile_id
     AND platform = v_platform;

  v_current_ms := COALESCE(v_current_ms, 0);

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  INSERT INTO public.profile_settings (user_id, profile_id, platform, settings_json, sync_revision, updated_at)
       VALUES (v_user_id, p_profile_id, v_platform, p_settings_json, 1, now())
  ON CONFLICT (user_id, profile_id, platform)
       DO UPDATE SET
         settings_json = EXCLUDED.settings_json,
         sync_revision = public.profile_settings.sync_revision + 1,
         updated_at = now();

  SELECT public.sync_to_ms(updated_at) INTO v_new_ms
    FROM public.profile_settings
   WHERE user_id = v_user_id AND profile_id = p_profile_id AND platform = v_platform;

  RETURN public.sync_applied_result(v_new_ms);
END;
$$;

REVOKE ALL ON FUNCTION public.sync_pull_profile_settings_blob_v10(int, text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_pull_profile_settings_blob_v10(int, text) TO authenticated;
REVOKE ALL ON FUNCTION public.sync_push_profile_settings_blob_v10(bigint, int, jsonb, text, text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_push_profile_settings_blob_v10(bigint, int, jsonb, text, text) TO authenticated;
