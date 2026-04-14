CREATE TABLE IF NOT EXISTS public.profiles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  profile_index INT NOT NULL CHECK (profile_index BETWEEN 1 AND 4),
  name TEXT NOT NULL DEFAULT '',
  avatar_color_hex TEXT NOT NULL DEFAULT '#1E88E5',
  avatar_url TEXT DEFAULT NULL,
  uses_primary_addons BOOLEAN NOT NULL DEFAULT false,
  uses_primary_plugins BOOLEAN NOT NULL DEFAULT false,
  avatar_id TEXT DEFAULT NULL,
  pin_hash TEXT DEFAULT NULL,
  pin_enabled BOOLEAN NOT NULL DEFAULT false,
  pin_locked_until TIMESTAMPTZ DEFAULT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, profile_index)
);

ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT DEFAULT NULL;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS uses_primary_addons BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS uses_primary_plugins BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_id TEXT DEFAULT NULL;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pin_hash TEXT DEFAULT NULL;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pin_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS pin_locked_until TIMESTAMPTZ DEFAULT NULL;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'public.profiles'::regclass
      AND conname = 'profiles_user_id_profile_index_key'
  ) THEN
    ALTER TABLE public.profiles
      ADD CONSTRAINT profiles_user_id_profile_index_key UNIQUE (user_id, profile_index);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'profiles'
      AND policyname = 'Users can read own profiles'
  ) THEN
    CREATE POLICY "Users can read own profiles"
      ON public.profiles FOR SELECT
      USING (auth.uid() = user_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'profiles'
      AND policyname = 'Users can insert own profiles'
  ) THEN
    CREATE POLICY "Users can insert own profiles"
      ON public.profiles FOR INSERT
      WITH CHECK (auth.uid() = user_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'profiles'
      AND policyname = 'Users can update own profiles'
  ) THEN
    CREATE POLICY "Users can update own profiles"
      ON public.profiles FOR UPDATE
      USING (auth.uid() = user_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'profiles'
      AND policyname = 'Users can delete own profiles'
  ) THEN
    CREATE POLICY "Users can delete own profiles"
      ON public.profiles FOR DELETE
      USING (auth.uid() = user_id);
  END IF;
END;
$$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'profiles') THEN
    ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT DEFAULT NULL;
  END IF;
END;
$$;

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('profile-avatars', 'profile-avatars', true, 5242880, ARRAY['image/jpeg', 'image/png', 'image/webp'])
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'storage'
      AND tablename = 'objects'
      AND policyname = 'Users can upload own avatars'
  ) THEN
    CREATE POLICY "Users can upload own avatars"
      ON storage.objects FOR INSERT
      WITH CHECK (
        bucket_id = 'profile-avatars'
        AND (storage.foldername(name))[1] = auth.uid()::text
      );
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'storage'
      AND tablename = 'objects'
      AND policyname = 'Public can read avatars'
  ) THEN
    CREATE POLICY "Public can read avatars"
      ON storage.objects FOR SELECT
      USING (bucket_id = 'profile-avatars');
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'storage'
      AND tablename = 'objects'
      AND policyname = 'Users can update own avatars'
  ) THEN
    CREATE POLICY "Users can update own avatars"
      ON storage.objects FOR UPDATE
      USING (
        bucket_id = 'profile-avatars'
        AND (storage.foldername(name))[1] = auth.uid()::text
      );
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'storage'
      AND tablename = 'objects'
      AND policyname = 'Users can delete own avatars'
  ) THEN
    CREATE POLICY "Users can delete own avatars"
      ON storage.objects FOR DELETE
      USING (
        bucket_id = 'profile-avatars'
        AND (storage.foldername(name))[1] = auth.uid()::text
      );
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.profile_upsert(
  p_profile_index INT,
  p_name TEXT,
  p_avatar_color_hex TEXT DEFAULT NULL,
  p_avatar_url TEXT DEFAULT NULL,
  p_clear_avatar BOOLEAN DEFAULT FALSE
)
RETURNS SETOF public.profiles
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

  IF p_profile_index < 1 OR p_profile_index > 4 THEN
    RAISE EXCEPTION 'profile_index must be between 1 and 4';
  END IF;

  IF length(trim(p_name)) < 1 OR length(trim(p_name)) > 30 THEN
    RAISE EXCEPTION 'Profile name must be 1-30 characters';
  END IF;

  SELECT count(*) INTO v_profile_count
  FROM public.profiles
  WHERE user_id = v_user_id AND profile_index != p_profile_index;

  IF v_profile_count >= 4 THEN
    RAISE EXCEPTION 'Maximum 4 profiles per account';
  END IF;

  RETURN QUERY
  INSERT INTO public.profiles (user_id, profile_index, name, avatar_color_hex, avatar_url, updated_at)
  VALUES (v_user_id, p_profile_index, trim(p_name), COALESCE(p_avatar_color_hex, '#1E88E5'), p_avatar_url, now())
  ON CONFLICT (user_id, profile_index)
  DO UPDATE SET
    name = EXCLUDED.name,
    avatar_color_hex = COALESCE(EXCLUDED.avatar_color_hex, profiles.avatar_color_hex),
    avatar_url = CASE
      WHEN p_clear_avatar THEN NULL
      WHEN p_avatar_url IS NOT NULL THEN p_avatar_url
      ELSE profiles.avatar_url
    END,
    updated_at = now()
  RETURNING *;
END;
$$;

CREATE OR REPLACE FUNCTION public.profile_delete(
  p_profile_index INT
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, storage, pg_temp
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

  DELETE FROM storage.objects
  WHERE bucket_id = 'profile-avatars'
    AND name = v_user_id::text || '/' || p_profile_index::text || '.jpg';

  DELETE FROM public.profiles
  WHERE user_id = v_user_id AND profile_index = p_profile_index;
END;
$$;

CREATE OR REPLACE FUNCTION public.profile_auth_status(
  p_profile_index INT
)
RETURNS TABLE(token_type TEXT, masked_preview TEXT, source TEXT, linked BOOLEAN, revoked_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
  RETURN QUERY
  SELECT pat.token_type, pat.masked_preview, pat.source, pat.linked, pat.revoked_at, pat.updated_at
  FROM public.profile_auth_tokens pat
  WHERE pat.user_id = auth.uid()
    AND pat.profile_index = p_profile_index;
END;
$$;
