REVOKE ALL ON FUNCTION public.profile_upsert(INT, TEXT, TEXT, TEXT, BOOLEAN) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_delete(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_auth_status(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.sync_pull_profiles() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.sync_push_profiles(JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.sync_delete_profile(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_set_pin(INT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_clear_pin(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.profile_verify_pin(INT, TEXT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.profile_upsert(INT, TEXT, TEXT, TEXT, BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.profile_delete(INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.profile_auth_status(INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_profiles() TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_profiles(JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_delete_profile(INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.profile_set_pin(INT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.profile_clear_pin(INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.profile_verify_pin(INT, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
