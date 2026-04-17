REVOKE ALL ON FUNCTION public.profile_upsert(INT, TEXT, TEXT, TEXT, BOOLEAN) FROM anon;
REVOKE ALL ON FUNCTION public.profile_delete(INT) FROM anon;
REVOKE ALL ON FUNCTION public.profile_auth_status(INT) FROM anon;
REVOKE ALL ON FUNCTION public.sync_pull_profiles() FROM anon;
REVOKE ALL ON FUNCTION public.sync_push_profiles(JSONB) FROM anon;
REVOKE ALL ON FUNCTION public.sync_delete_profile(INT) FROM anon;
REVOKE ALL ON FUNCTION public.profile_set_pin(INT, TEXT) FROM anon;
REVOKE ALL ON FUNCTION public.profile_clear_pin(INT) FROM anon;
REVOKE ALL ON FUNCTION public.profile_verify_pin(INT, TEXT) FROM anon;

NOTIFY pgrst, 'reload schema';
