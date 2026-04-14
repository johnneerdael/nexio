CREATE TABLE IF NOT EXISTS public.profile_auth_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  profile_index INT NOT NULL CHECK (profile_index BETWEEN 1 AND 4),
  token_type TEXT NOT NULL CHECK (token_type IN ('trakt_access_token', 'trakt_refresh_token', 'simkl_access_token')),
  token_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  masked_preview TEXT DEFAULT '',
  source TEXT DEFAULT 'web',
  linked BOOLEAN NOT NULL DEFAULT true,
  revoked_at TIMESTAMPTZ DEFAULT NULL,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now(),
  UNIQUE (user_id, profile_index, token_type)
);

ALTER TABLE public.profile_auth_tokens ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read own profile tokens" ON public.profile_auth_tokens;
CREATE POLICY "Users can read own profile tokens"
  ON public.profile_auth_tokens FOR SELECT
  USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert own profile tokens" ON public.profile_auth_tokens;
CREATE POLICY "Users can insert own profile tokens"
  ON public.profile_auth_tokens FOR INSERT
  WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own profile tokens" ON public.profile_auth_tokens;
CREATE POLICY "Users can update own profile tokens"
  ON public.profile_auth_tokens FOR UPDATE
  USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete own profile tokens" ON public.profile_auth_tokens;
CREATE POLICY "Users can delete own profile tokens"
  ON public.profile_auth_tokens FOR DELETE
  USING (auth.uid() = user_id);

CREATE INDEX IF NOT EXISTS idx_profile_auth_tokens_user_profile
  ON public.profile_auth_tokens(user_id, profile_index);

CREATE OR REPLACE FUNCTION public.service_set_profile_auth_token(
  p_user_id UUID,
  p_profile_index INT,
  p_token_type TEXT,
  p_token_payload JSONB,
  p_masked_preview TEXT DEFAULT '',
  p_source TEXT DEFAULT 'web'
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  INSERT INTO public.profile_auth_tokens (user_id, profile_index, token_type, token_payload, masked_preview, source, linked, revoked_at, updated_at)
  VALUES (p_user_id, p_profile_index, p_token_type, p_token_payload, p_masked_preview, p_source, true, NULL, now())
  ON CONFLICT (user_id, profile_index, token_type)
  DO UPDATE SET
    token_payload = EXCLUDED.token_payload,
    masked_preview = EXCLUDED.masked_preview,
    source = EXCLUDED.source,
    linked = true,
    revoked_at = NULL,
    updated_at = now();
END;
$$;

CREATE OR REPLACE FUNCTION public.service_delete_profile_auth_tokens(
  p_user_id UUID,
  p_profile_index INT,
  p_token_type_prefix TEXT DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  IF p_token_type_prefix IS NOT NULL THEN
    UPDATE public.profile_auth_tokens
    SET token_payload = '{}'::jsonb,
        masked_preview = '',
        source = 'web',
        linked = false,
        revoked_at = now(),
        updated_at = now()
    WHERE user_id = p_user_id
      AND profile_index = p_profile_index
      AND token_type LIKE p_token_type_prefix || '%';
  ELSE
    UPDATE public.profile_auth_tokens
    SET token_payload = '{}'::jsonb,
        masked_preview = '',
        source = 'web',
        linked = false,
        revoked_at = now(),
        updated_at = now()
    WHERE user_id = p_user_id
      AND profile_index = p_profile_index;
  END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.service_set_profile_auth_token(UUID, INT, TEXT, JSONB, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.service_set_profile_auth_token(UUID, INT, TEXT, JSONB, TEXT, TEXT) FROM anon;
REVOKE ALL ON FUNCTION public.service_set_profile_auth_token(UUID, INT, TEXT, JSONB, TEXT, TEXT) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.service_set_profile_auth_token(UUID, INT, TEXT, JSONB, TEXT, TEXT) TO service_role;

REVOKE ALL ON FUNCTION public.service_delete_profile_auth_tokens(UUID, INT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.service_delete_profile_auth_tokens(UUID, INT, TEXT) FROM anon;
REVOKE ALL ON FUNCTION public.service_delete_profile_auth_tokens(UUID, INT, TEXT) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.service_delete_profile_auth_tokens(UUID, INT, TEXT) TO service_role;
