CREATE TABLE IF NOT EXISTS public.profile_settings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  profile_id INT NOT NULL CHECK (profile_id BETWEEN 1 AND 4),
  platform TEXT NOT NULL DEFAULT 'tv',
  settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  sync_revision BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, profile_id, platform)
);

ALTER TABLE public.profile_settings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read own profile settings" ON public.profile_settings;
CREATE POLICY "Users can read own profile settings"
  ON public.profile_settings FOR SELECT
  USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert own profile settings" ON public.profile_settings;
CREATE POLICY "Users can insert own profile settings"
  ON public.profile_settings FOR INSERT
  WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own profile settings" ON public.profile_settings;
CREATE POLICY "Users can update own profile settings"
  ON public.profile_settings FOR UPDATE
  USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete own profile settings" ON public.profile_settings;
CREATE POLICY "Users can delete own profile settings"
  ON public.profile_settings FOR DELETE
  USING (auth.uid() = user_id);

CREATE INDEX IF NOT EXISTS idx_profile_settings_user_profile_platform
  ON public.profile_settings(user_id, profile_id, platform);

CREATE OR REPLACE FUNCTION public.sync_pull_profile_settings_blob(
  p_profile_id INT,
  p_platform TEXT DEFAULT 'tv'
)
RETURNS TABLE(settings_json JSONB, sync_revision BIGINT, updated_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_platform TEXT := COALESCE(NULLIF(trim(p_platform), ''), 'tv');
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_id < 1 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 1 and 4';
  END IF;

  RETURN QUERY
  SELECT ps.settings_json, ps.sync_revision, ps.updated_at
  FROM public.profile_settings ps
  WHERE ps.user_id = v_user_id
    AND ps.profile_id = p_profile_id
    AND ps.platform = v_platform;

  IF NOT FOUND THEN
    RETURN QUERY SELECT '{}'::jsonb, 0::bigint, NULL::timestamptz;
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_profile_settings_blob(
  p_profile_id INT,
  p_settings_json JSONB,
  p_platform TEXT DEFAULT 'tv'
)
RETURNS TABLE(settings_json JSONB, sync_revision BIGINT, updated_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_platform TEXT := COALESCE(NULLIF(trim(p_platform), ''), 'tv');
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  IF p_profile_id < 1 OR p_profile_id > 4 THEN
    RAISE EXCEPTION 'profile_id must be between 1 and 4';
  END IF;

  RETURN QUERY
  INSERT INTO public.profile_settings (user_id, profile_id, platform, settings_json, sync_revision, updated_at)
  VALUES (v_user_id, p_profile_id, v_platform, COALESCE(p_settings_json, '{}'::jsonb), 1, now())
  ON CONFLICT (user_id, profile_id, platform)
  DO UPDATE SET
    settings_json = EXCLUDED.settings_json,
    sync_revision = public.profile_settings.sync_revision + 1,
    updated_at = now()
  RETURNING public.profile_settings.settings_json, public.profile_settings.sync_revision, public.profile_settings.updated_at;
END;
$$;
