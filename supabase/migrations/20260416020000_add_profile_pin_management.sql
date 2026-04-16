CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pin_failed_attempts INT NOT NULL DEFAULT 0;

CREATE OR REPLACE FUNCTION public.profile_set_pin(
  p_profile_index INT,
  p_pin TEXT
)
RETURNS TABLE(profile_index INT, pin_enabled BOOLEAN, pin_locked_until TIMESTAMPTZ, updated_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_pin TEXT := trim(COALESCE(p_pin, ''));
  v_profile public.profiles%ROWTYPE;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_index < 1 OR p_profile_index > 4 THEN
    RAISE EXCEPTION 'profile_index must be between 1 and 4';
  END IF;

  IF v_pin !~ '^[0-9]{4}$' THEN
    RAISE EXCEPTION 'PIN must be exactly 4 digits';
  END IF;

  INSERT INTO public.profiles (
    user_id,
    profile_index,
    name,
    avatar_color_hex,
    pin_hash,
    pin_enabled,
    pin_locked_until,
    pin_failed_attempts,
    updated_at
  )
  VALUES (
    v_user_id,
    p_profile_index,
    CASE WHEN p_profile_index = 1 THEN 'Default' ELSE 'Profile ' || p_profile_index::TEXT END,
    '#1E88E5',
    crypt(v_pin, gen_salt('bf')),
    true,
    NULL,
    0,
    now()
  )
  ON CONFLICT (user_id, profile_index)
  DO UPDATE SET
    pin_hash = crypt(v_pin, gen_salt('bf')),
    pin_enabled = true,
    pin_locked_until = NULL,
    pin_failed_attempts = 0,
    updated_at = now()
  RETURNING * INTO v_profile;

  RETURN QUERY
  SELECT
    v_profile.profile_index,
    v_profile.pin_enabled,
    v_profile.pin_locked_until,
    v_profile.updated_at;
END;
$$;

CREATE OR REPLACE FUNCTION public.profile_clear_pin(
  p_profile_index INT
)
RETURNS TABLE(profile_index INT, pin_enabled BOOLEAN, pin_locked_until TIMESTAMPTZ, updated_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_profile public.profiles%ROWTYPE;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_index < 1 OR p_profile_index > 4 THEN
    RAISE EXCEPTION 'profile_index must be between 1 and 4';
  END IF;

  UPDATE public.profiles
  SET
    pin_hash = NULL,
    pin_enabled = false,
    pin_locked_until = NULL,
    pin_failed_attempts = 0,
    updated_at = now()
  WHERE user_id = v_user_id
    AND profile_index = p_profile_index
  RETURNING * INTO v_profile;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Profile not found';
  END IF;

  RETURN QUERY
  SELECT
    v_profile.profile_index,
    v_profile.pin_enabled,
    v_profile.pin_locked_until,
    v_profile.updated_at;
END;
$$;

CREATE OR REPLACE FUNCTION public.profile_verify_pin(
  p_profile_index INT,
  p_pin TEXT
)
RETURNS TABLE(unlocked BOOLEAN, retry_after_seconds INT, pin_enabled BOOLEAN)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_pin TEXT := trim(COALESCE(p_pin, ''));
  v_profile public.profiles%ROWTYPE;
  v_next_failed_attempts INT;
  v_lock_seconds INT := 300;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_index < 1 OR p_profile_index > 4 THEN
    RAISE EXCEPTION 'profile_index must be between 1 and 4';
  END IF;

  SELECT * INTO v_profile
  FROM public.profiles
  WHERE user_id = v_user_id
    AND profile_index = p_profile_index;

  IF NOT FOUND THEN
    RETURN QUERY SELECT true, 0, false;
    RETURN;
  END IF;

  IF v_profile.pin_enabled IS NOT TRUE OR v_profile.pin_hash IS NULL THEN
    RETURN QUERY SELECT true, 0, false;
    RETURN;
  END IF;

  IF v_profile.pin_locked_until IS NOT NULL AND v_profile.pin_locked_until > now() THEN
    RETURN QUERY
    SELECT
      false,
      GREATEST(0, CEIL(EXTRACT(EPOCH FROM (v_profile.pin_locked_until - now())))::INT),
      true;
    RETURN;
  END IF;

  IF v_profile.pin_hash = crypt(v_pin, v_profile.pin_hash) THEN
    UPDATE public.profiles
    SET
      pin_failed_attempts = 0,
      pin_locked_until = NULL,
      updated_at = now()
    WHERE user_id = v_user_id
      AND profile_index = p_profile_index;

    RETURN QUERY SELECT true, 0, true;
    RETURN;
  END IF;

  v_next_failed_attempts := COALESCE(v_profile.pin_failed_attempts, 0) + 1;

  IF v_next_failed_attempts >= 5 THEN
    UPDATE public.profiles
    SET
      pin_failed_attempts = 0,
      pin_locked_until = now() + make_interval(secs => v_lock_seconds),
      updated_at = now()
    WHERE user_id = v_user_id
      AND profile_index = p_profile_index;

    RETURN QUERY SELECT false, v_lock_seconds, true;
    RETURN;
  END IF;

  UPDATE public.profiles
  SET
    pin_failed_attempts = v_next_failed_attempts,
    pin_locked_until = NULL,
    updated_at = now()
  WHERE user_id = v_user_id
    AND profile_index = p_profile_index;

  RETURN QUERY SELECT false, 0, true;
END;
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
      updated_at = now();

    v_seen_indices := array_append(v_seen_indices, v_profile_index);
  END LOOP;

  DELETE FROM public.profiles
  WHERE user_id = v_user_id
    AND profile_index <> 1
    AND NOT (profile_index = ANY(v_seen_indices));
END;
$$;

REVOKE ALL ON FUNCTION public.profile_set_pin(INT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_clear_pin(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_verify_pin(INT, TEXT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.profile_set_pin(INT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.profile_clear_pin(INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.profile_verify_pin(INT, TEXT) TO authenticated;
