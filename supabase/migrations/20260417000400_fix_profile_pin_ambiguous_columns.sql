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

  IF p_profile_index IS NULL OR p_profile_index < 1 OR p_profile_index > 4 THEN
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
  ON CONFLICT ON CONSTRAINT profiles_user_id_profile_index_key
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

  IF p_profile_index IS NULL OR p_profile_index < 1 OR p_profile_index > 4 THEN
    RAISE EXCEPTION 'profile_index must be between 1 and 4';
  END IF;

  UPDATE public.profiles
  SET
    pin_hash = NULL,
    pin_enabled = false,
    pin_locked_until = NULL,
    pin_failed_attempts = 0,
    updated_at = now()
  WHERE public.profiles.user_id = v_user_id
    AND public.profiles.profile_index = p_profile_index
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

REVOKE ALL ON FUNCTION public.profile_set_pin(INT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_set_pin(INT, TEXT) FROM anon;
GRANT EXECUTE ON FUNCTION public.profile_set_pin(INT, TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.profile_clear_pin(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_clear_pin(INT) FROM anon;
GRANT EXECUTE ON FUNCTION public.profile_clear_pin(INT) TO authenticated;

NOTIFY pgrst, 'reload schema';
