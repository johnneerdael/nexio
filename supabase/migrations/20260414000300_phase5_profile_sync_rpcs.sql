CREATE OR REPLACE FUNCTION public.sync_pull_profiles()
RETURNS SETOF public.profiles
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT *
  FROM public.profiles
  WHERE user_id = auth.uid()
  ORDER BY profile_index ASC;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_profiles(p_profiles JSONB)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_profile JSONB;
  v_seen_indices INT[] := ARRAY[]::INT[];
  v_profile_index INT;
  v_name TEXT;
  v_avatar_color_hex TEXT;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF jsonb_typeof(COALESCE(p_profiles, '[]'::jsonb)) <> 'array' THEN
    RAISE EXCEPTION 'p_profiles must be an array';
  END IF;

  FOR v_profile IN SELECT value FROM jsonb_array_elements(COALESCE(p_profiles, '[]'::jsonb))
  LOOP
    v_profile_index := NULLIF(v_profile->>'profile_index', '')::INT;
    IF v_profile_index < 1 OR v_profile_index > 4 THEN
      CONTINUE;
    END IF;

    v_name := NULLIF(trim(COALESCE(v_profile->>'name', '')), '');
    v_avatar_color_hex := NULLIF(trim(COALESCE(v_profile->>'avatar_color_hex', '')), '');

    INSERT INTO public.profiles (
      user_id,
      profile_index,
      name,
      avatar_color_hex,
      avatar_url,
      uses_primary_addons,
      uses_primary_plugins,
      avatar_id,
      pin_enabled,
      updated_at
    ) VALUES (
      v_user_id,
      v_profile_index,
      COALESCE(v_name, CASE WHEN v_profile_index = 1 THEN 'Default' ELSE 'Profile ' || v_profile_index::TEXT END),
      COALESCE(v_avatar_color_hex, '#1E88E5'),
      NULLIF(v_profile->>'avatar_url', ''),
      COALESCE((v_profile->>'uses_primary_addons')::BOOLEAN, false),
      COALESCE((v_profile->>'uses_primary_plugins')::BOOLEAN, false),
      NULLIF(v_profile->>'avatar_id', ''),
      COALESCE((v_profile->>'pin_enabled')::BOOLEAN, false),
      now()
    )
    ON CONFLICT (user_id, profile_index)
    DO UPDATE SET
      name = EXCLUDED.name,
      avatar_color_hex = EXCLUDED.avatar_color_hex,
      avatar_url = CASE
        WHEN v_profile ? 'avatar_url' THEN EXCLUDED.avatar_url
        ELSE public.profiles.avatar_url
      END,
      uses_primary_addons = EXCLUDED.uses_primary_addons,
      uses_primary_plugins = EXCLUDED.uses_primary_plugins,
      avatar_id = EXCLUDED.avatar_id,
      pin_enabled = EXCLUDED.pin_enabled,
      updated_at = now();

    v_seen_indices := array_append(v_seen_indices, v_profile_index);
  END LOOP;

  DELETE FROM public.profiles
  WHERE user_id = v_user_id
    AND profile_index <> 1
    AND NOT (profile_index = ANY(v_seen_indices));
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_delete_profile(p_profile_id INT)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, storage, pg_temp
AS $$
BEGIN
  PERFORM public.profile_delete(p_profile_id);
END;
$$;

DROP FUNCTION IF EXISTS public.sync_pull_profile_settings_blob(INT, TEXT);
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

  IF p_profile_id < 1 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 1 and 4';
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

DROP FUNCTION IF EXISTS public.sync_push_profile_settings_blob(INT, JSONB, TEXT);
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

  IF p_profile_id < 1 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 1 and 4';
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
