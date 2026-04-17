CREATE OR REPLACE FUNCTION public.profile_delete(
  p_profile_index INT
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_index = 1 THEN
    RAISE EXCEPTION 'Cannot delete the primary profile';
  END IF;

  IF p_profile_index < 2 OR p_profile_index > 4 THEN
    RAISE EXCEPTION 'Invalid profile index';
  END IF;

  DELETE FROM public.profile_auth_tokens
  WHERE user_id = v_user_id AND profile_index = p_profile_index;

  IF to_regclass('public.profile_settings') IS NOT NULL THEN
    EXECUTE 'DELETE FROM public.profile_settings WHERE user_id = $1 AND profile_id = $2'
    USING v_user_id, p_profile_index;
  END IF;

  DELETE FROM public.profiles
  WHERE user_id = v_user_id AND profile_index = p_profile_index;
END;
$$;

REVOKE ALL ON FUNCTION public.profile_delete(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_delete(INT) FROM anon;
GRANT EXECUTE ON FUNCTION public.profile_delete(INT) TO authenticated;

NOTIFY pgrst, 'reload schema';
