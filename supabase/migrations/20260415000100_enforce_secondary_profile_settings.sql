CREATE OR REPLACE FUNCTION public.sync_pull_profile_settings_blob(
  p_profile_id INT,
  p_platform TEXT DEFAULT 'tv'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_platform TEXT := COALESCE(NULLIF(trim(p_platform), ''), 'tv');
  v_row public.profile_settings%ROWTYPE;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_id < 2 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 2 and 4 for profile settings';
  END IF;

  SELECT * INTO v_row
  FROM public.profile_settings
  WHERE user_id = v_user_id
    AND profile_id = p_profile_id
    AND platform = v_platform;

  IF NOT FOUND THEN
    RETURN jsonb_build_object(
      'user_id', v_user_id,
      'profile_id', p_profile_id,
      'platform', v_platform,
      'settings_json', '{}',
      'sync_revision', 0,
      'updated_at', NULL
    );
  END IF;

  RETURN jsonb_build_object(
    'user_id', v_row.user_id,
    'profile_id', v_row.profile_id,
    'platform', v_row.platform,
    'settings_json', v_row.settings_json::TEXT,
    'sync_revision', v_row.sync_revision,
    'updated_at', v_row.updated_at
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_profile_settings_blob(
  p_profile_id INT,
  p_settings_json JSONB,
  p_platform TEXT DEFAULT 'tv'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_platform TEXT := COALESCE(NULLIF(trim(p_platform), ''), 'tv');
  v_row public.profile_settings%ROWTYPE;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_id < 2 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 2 and 4 for profile settings';
  END IF;

  INSERT INTO public.profile_settings (user_id, profile_id, platform, settings_json, sync_revision, updated_at)
  VALUES (v_user_id, p_profile_id, v_platform, COALESCE(p_settings_json, '{}'::jsonb), 1, now())
  ON CONFLICT (user_id, profile_id, platform)
  DO UPDATE SET
    settings_json = EXCLUDED.settings_json,
    sync_revision = public.profile_settings.sync_revision + 1,
    updated_at = now()
  RETURNING * INTO v_row;

  RETURN jsonb_build_object(
    'user_id', v_row.user_id,
    'profile_id', v_row.profile_id,
    'platform', v_row.platform,
    'settings_json', v_row.settings_json::TEXT,
    'sync_revision', v_row.sync_revision,
    'updated_at', v_row.updated_at
  );
END;
$$;
