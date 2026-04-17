CREATE OR REPLACE FUNCTION public.profile_upsert(
  p_profile_index INT,
  p_name TEXT,
  p_avatar_color_hex TEXT DEFAULT NULL,
  p_avatar_url TEXT DEFAULT NULL,
  p_clear_avatar BOOLEAN DEFAULT FALSE
)
RETURNS TABLE(
  id UUID,
  user_id UUID,
  profile_index INT,
  name TEXT,
  avatar_color_hex TEXT,
  avatar_url TEXT,
  uses_primary_addons BOOLEAN,
  uses_primary_plugins BOOLEAN,
  avatar_id TEXT,
  pin_enabled BOOLEAN,
  pin_locked_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, storage, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_profile_count INT;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_index IS NULL OR p_profile_index < 1 OR p_profile_index > 4 THEN
    RAISE EXCEPTION 'profile_index must be between 1 and 4';
  END IF;

  IF length(trim(COALESCE(p_name, ''))) < 1 OR length(trim(COALESCE(p_name, ''))) > 30 THEN
    RAISE EXCEPTION 'Profile name must be 1-30 characters';
  END IF;

  SELECT count(*) INTO v_profile_count
  FROM public.profiles
  WHERE public.profiles.user_id = v_user_id
    AND public.profiles.profile_index != p_profile_index;

  IF v_profile_count >= 4 THEN
    RAISE EXCEPTION 'Maximum 4 profiles per account';
  END IF;

  INSERT INTO public.profiles (
    user_id,
    profile_index,
    name,
    avatar_color_hex,
    avatar_url,
    updated_at
  )
  VALUES (
    v_user_id,
    p_profile_index,
    trim(p_name),
    COALESCE(NULLIF(trim(COALESCE(p_avatar_color_hex, '')), ''), '#1E88E5'),
    p_avatar_url,
    now()
  )
  ON CONFLICT ON CONSTRAINT profiles_user_id_profile_index_key
  DO UPDATE SET
    name = EXCLUDED.name,
    avatar_color_hex = COALESCE(EXCLUDED.avatar_color_hex, profiles.avatar_color_hex),
    avatar_url = CASE
      WHEN p_clear_avatar THEN NULL
      WHEN p_avatar_url IS NOT NULL THEN p_avatar_url
      ELSE profiles.avatar_url
    END,
    updated_at = now();

  RETURN QUERY
  SELECT
    p.id,
    p.user_id,
    p.profile_index,
    p.name,
    p.avatar_color_hex,
    p.avatar_url,
    p.uses_primary_addons,
    p.uses_primary_plugins,
    p.avatar_id,
    p.pin_enabled,
    p.pin_locked_until,
    p.created_at,
    p.updated_at
  FROM public.profiles p
  WHERE p.user_id = v_user_id
    AND p.profile_index = p_profile_index;
END;
$$;

REVOKE ALL ON FUNCTION public.profile_upsert(INT, TEXT, TEXT, TEXT, BOOLEAN) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_upsert(INT, TEXT, TEXT, TEXT, BOOLEAN) FROM anon;
GRANT EXECUTE ON FUNCTION public.profile_upsert(INT, TEXT, TEXT, TEXT, BOOLEAN) TO authenticated;

NOTIFY pgrst, 'reload schema';
