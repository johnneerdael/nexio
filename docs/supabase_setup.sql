-- ============================================================================
-- NuvioTV Supabase Setup Script
-- ============================================================================
-- Run this in a fresh Supabase project's SQL Editor to set up the complete
-- database schema, RPC functions, triggers, and security policies.
--
-- Prerequisites:
--   1. Enable "Allow anonymous sign-ins" in Authentication > Providers
--   2. Enable pgcrypto extension (done below)
-- ============================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================================
-- TABLES
-- ============================================================================

-- Temporary codes for device linking, protected by a bcrypt-hashed PIN.
CREATE TABLE sync_codes (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    code TEXT NOT NULL,
    pin_hash TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ DEFAULT 'infinity'::TIMESTAMPTZ
);

ALTER TABLE sync_codes ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage own sync codes"
    ON sync_codes FOR ALL
    USING (auth.uid() = owner_id)
    WITH CHECK (auth.uid() = owner_id);

-- Maps a child device's user ID to a parent (owner) user ID.
CREATE TABLE linked_devices (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_name TEXT,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(owner_id, device_user_id)
);

ALTER TABLE linked_devices ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Owners can read their linked devices"
    ON linked_devices FOR SELECT
    USING (auth.uid() = owner_id);

CREATE POLICY "Devices can read their own link"
    ON linked_devices FOR SELECT
    USING (auth.uid() = device_user_id);

-- Plugin repository URLs synced across devices.
CREATE TABLE plugins (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    name TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_plugins_user_id ON plugins(user_id);
ALTER TABLE plugins ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage own plugins"
    ON plugins FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Addon manifest URLs synced across devices.
CREATE TABLE addons (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    name TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_addons_user_id ON addons(user_id);
ALTER TABLE addons ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage own addons"
    ON addons FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Per-movie or per-episode playback progress.
CREATE TABLE watch_progress (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    video_id TEXT NOT NULL,
    season INTEGER,
    episode INTEGER,
    position BIGINT NOT NULL DEFAULT 0,
    duration BIGINT NOT NULL DEFAULT 0,
    last_watched BIGINT NOT NULL DEFAULT 0,
    progress_key TEXT NOT NULL
);

CREATE INDEX idx_watch_progress_user_id ON watch_progress(user_id);
ALTER TABLE watch_progress ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage own watch progress"
    ON watch_progress FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Saved movies and TV shows (bookmarks/favorites).
CREATE TABLE library_items (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    poster TEXT,
    poster_shape TEXT NOT NULL DEFAULT 'POSTER',
    background TEXT,
    description TEXT,
    release_info TEXT,
    imdb_rating REAL,
    genres TEXT[] DEFAULT '{}',
    addon_base_url TEXT,
    added_at BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, content_id, content_type)
);

CREATE INDEX idx_library_items_user_id ON library_items(user_id);
ALTER TABLE library_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage own library items"
    ON library_items FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Permanent watched history.
CREATE TABLE watched_items (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    season INTEGER,
    episode INTEGER,
    watched_at BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE UNIQUE INDEX idx_watched_items_unique
    ON watched_items (user_id, content_id, COALESCE(season, -1), COALESCE(episode, -1));

CREATE INDEX idx_watched_items_user_id ON watched_items(user_id);

ALTER TABLE watched_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage own watched items"
    ON watched_items FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- ============================================================================
-- TRIGGERS
-- ============================================================================

-- Auto-update updated_at timestamp
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER set_updated_at BEFORE UPDATE ON plugins FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_updated_at BEFORE UPDATE ON addons FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_updated_at BEFORE UPDATE ON sync_codes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- CORE RPC FUNCTIONS
-- ============================================================================

-- Resolves the effective user ID. If the current user is a linked device,
-- returns the owner's ID. Otherwise returns the caller's own ID.
CREATE OR REPLACE FUNCTION get_sync_owner()
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_owner_id uuid;
BEGIN
    SELECT owner_id INTO v_owner_id
    FROM linked_devices
    WHERE device_user_id = auth.uid()
    LIMIT 1;

    RETURN COALESCE(v_owner_id, auth.uid());
END;
$$;

GRANT EXECUTE ON FUNCTION get_sync_owner() TO authenticated;

-- Helper to check if the current user can access another user's data.
CREATE OR REPLACE FUNCTION can_access_user_data(p_user_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF auth.uid() = p_user_id THEN
        RETURN true;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.linked_devices
        WHERE owner_id = p_user_id
          AND device_user_id = auth.uid()
    ) THEN
        RETURN true;
    END IF;

    RETURN false;
END;
$$;

GRANT EXECUTE ON FUNCTION can_access_user_data(UUID) TO authenticated;

-- ============================================================================
-- DEVICE LINKING RPC FUNCTIONS
-- ============================================================================

-- Generate a sync code for the current user.
CREATE OR REPLACE FUNCTION generate_sync_code(p_pin TEXT)
RETURNS TABLE(code TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid;
    v_existing_code text;
    v_new_code text;
    v_pin_hash text;
BEGIN
    v_user_id := auth.uid();

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT sc.code INTO v_existing_code
    FROM sync_codes sc
    WHERE sc.owner_id = v_user_id
    ORDER BY sc.created_at DESC
    LIMIT 1;

    IF v_existing_code IS NOT NULL THEN
        v_pin_hash := crypt(p_pin, gen_salt('bf'));
        UPDATE sync_codes
        SET pin_hash = v_pin_hash
        WHERE sync_codes.owner_id = v_user_id
          AND sync_codes.code = v_existing_code;
        RETURN QUERY SELECT v_existing_code;
        RETURN;
    END IF;

    v_new_code := upper(
        substr(md5(random()::text || clock_timestamp()::text), 1, 4) || '-' ||
        substr(md5(random()::text || clock_timestamp()::text), 5, 4) || '-' ||
        substr(md5(random()::text || clock_timestamp()::text), 9, 4) || '-' ||
        substr(md5(random()::text || clock_timestamp()::text), 13, 4) || '-' ||
        substr(md5(random()::text || clock_timestamp()::text), 17, 4)
    );

    v_pin_hash := crypt(p_pin, gen_salt('bf'));

    INSERT INTO sync_codes (owner_id, code, pin_hash)
    VALUES (v_user_id, v_new_code, v_pin_hash);

    RETURN QUERY SELECT v_new_code;
END;
$$;

GRANT EXECUTE ON FUNCTION generate_sync_code(TEXT) TO authenticated;

-- Retrieve the existing sync code for the current user, validated by PIN.
CREATE OR REPLACE FUNCTION get_sync_code(p_pin TEXT)
RETURNS TABLE(code TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id uuid;
    v_existing_code text;
    v_existing_pin_hash text;
BEGIN
    v_user_id := auth.uid();

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT sc.code, sc.pin_hash
    INTO v_existing_code, v_existing_pin_hash
    FROM sync_codes sc
    WHERE sc.owner_id = v_user_id
    ORDER BY sc.created_at DESC
    LIMIT 1;

    IF v_existing_code IS NULL THEN
        RAISE EXCEPTION 'No sync code found. Generate one first.';
    END IF;

    IF v_existing_pin_hash != crypt(p_pin, v_existing_pin_hash) THEN
        RAISE EXCEPTION 'Incorrect PIN';
    END IF;

    RETURN QUERY SELECT v_existing_code;
END;
$$;

GRANT EXECUTE ON FUNCTION get_sync_code(TEXT) TO authenticated;

-- Link a device to the owner of the sync code.
CREATE OR REPLACE FUNCTION claim_sync_code(p_code TEXT, p_pin TEXT, p_device_name TEXT DEFAULT NULL)
RETURNS TABLE(result_owner_id UUID, success BOOLEAN, message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_owner_id uuid;
    v_pin_hash text;
BEGIN
    SELECT sc.owner_id, sc.pin_hash
    INTO v_owner_id, v_pin_hash
    FROM sync_codes sc
    WHERE sc.code = p_code;

    IF v_owner_id IS NULL THEN
        RETURN QUERY SELECT NULL::uuid, false, 'Sync code not found'::text;
        RETURN;
    END IF;

    IF crypt(p_pin, v_pin_hash) != v_pin_hash THEN
        RETURN QUERY SELECT NULL::uuid, false, 'Incorrect PIN'::text;
        RETURN;
    END IF;

    INSERT INTO linked_devices (owner_id, device_user_id, device_name)
    VALUES (v_owner_id, auth.uid(), p_device_name)
    ON CONFLICT (owner_id, device_user_id) DO UPDATE
    SET device_name = EXCLUDED.device_name;

    RETURN QUERY SELECT v_owner_id, true, 'Device linked successfully'::text;
END;
$$;

GRANT EXECUTE ON FUNCTION claim_sync_code(TEXT, TEXT, TEXT) TO authenticated;

-- Remove a linked device.
CREATE OR REPLACE FUNCTION unlink_device(p_device_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    DELETE FROM linked_devices
    WHERE (owner_id = auth.uid() AND device_user_id = p_device_user_id)
       OR (device_user_id = auth.uid() AND device_user_id = p_device_user_id);
END;
$$;

GRANT EXECUTE ON FUNCTION unlink_device(UUID) TO authenticated;

-- ============================================================================
-- SYNC RPC FUNCTIONS
-- ============================================================================

-- Full-replace push of plugin repository URLs.
CREATE OR REPLACE FUNCTION sync_push_plugins(p_plugins JSONB)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id uuid;
    v_plugin jsonb;
BEGIN
    SELECT get_sync_owner() INTO v_effective_user_id;

    DELETE FROM plugins WHERE user_id = v_effective_user_id;

    FOR v_plugin IN SELECT * FROM jsonb_array_elements(p_plugins)
    LOOP
        INSERT INTO plugins (user_id, url, name, enabled, sort_order)
        VALUES (
            v_effective_user_id,
            v_plugin->>'url',
            v_plugin->>'name',
            COALESCE((v_plugin->>'enabled')::boolean, true),
            (v_plugin->>'sort_order')::int
        );
    END LOOP;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_push_plugins(JSONB) TO authenticated;

-- Full-replace push of addon manifest URLs.
CREATE OR REPLACE FUNCTION sync_push_addons(p_addons JSONB)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id uuid;
    v_addon jsonb;
BEGIN
    SELECT get_sync_owner() INTO v_effective_user_id;

    DELETE FROM addons WHERE user_id = v_effective_user_id;

    FOR v_addon IN SELECT * FROM jsonb_array_elements(p_addons)
    LOOP
        INSERT INTO addons (user_id, url, sort_order)
        VALUES (
            v_effective_user_id,
            v_addon->>'url',
            (v_addon->>'sort_order')::int
        );
    END LOOP;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_push_addons(JSONB) TO authenticated;

-- Full-replace push of watch progress entries.
CREATE OR REPLACE FUNCTION sync_push_watch_progress(p_entries JSONB)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id UUID;
BEGIN
    v_effective_user_id := get_sync_owner();

    DELETE FROM watch_progress WHERE user_id = v_effective_user_id;

    INSERT INTO watch_progress (
        user_id, content_id, content_type, video_id,
        season, episode, position, duration, last_watched, progress_key
    )
    SELECT
        v_effective_user_id,
        (entry->>'content_id'),
        (entry->>'content_type'),
        (entry->>'video_id'),
        (entry->>'season')::INTEGER,
        (entry->>'episode')::INTEGER,
        (entry->>'position')::BIGINT,
        (entry->>'duration')::BIGINT,
        (entry->>'last_watched')::BIGINT,
        (entry->>'progress_key')
    FROM jsonb_array_elements(p_entries) AS entry;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_push_watch_progress(JSONB) TO authenticated;

-- Returns all watch progress for the effective user.
CREATE OR REPLACE FUNCTION sync_pull_watch_progress()
RETURNS SETOF watch_progress
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id UUID;
BEGIN
    v_effective_user_id := get_sync_owner();
    RETURN QUERY SELECT * FROM watch_progress WHERE user_id = v_effective_user_id;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_pull_watch_progress() TO authenticated;

-- Full-replace push of library items.
CREATE OR REPLACE FUNCTION sync_push_library(p_items JSONB)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id UUID;
BEGIN
    v_effective_user_id := get_sync_owner();

    DELETE FROM library_items WHERE user_id = v_effective_user_id;

    INSERT INTO library_items (
        user_id, content_id, content_type, name, poster, poster_shape,
        background, description, release_info, imdb_rating, genres,
        addon_base_url, added_at
    )
    SELECT
        v_effective_user_id,
        (item->>'content_id'),
        (item->>'content_type'),
        COALESCE(item->>'name', ''),
        (item->>'poster'),
        COALESCE(item->>'poster_shape', 'POSTER'),
        (item->>'background'),
        (item->>'description'),
        (item->>'release_info'),
        (item->>'imdb_rating')::REAL,
        COALESCE(
            (SELECT array_agg(g::TEXT) FROM jsonb_array_elements_text(item->'genres') AS g),
            '{}'
        ),
        (item->>'addon_base_url'),
        COALESCE((item->>'added_at')::BIGINT, EXTRACT(EPOCH FROM now())::BIGINT * 1000)
    FROM jsonb_array_elements(p_items) AS item;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_push_library(JSONB) TO authenticated;

-- Returns all library items for the effective user.
CREATE OR REPLACE FUNCTION sync_pull_library()
RETURNS SETOF library_items
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id UUID;
BEGIN
    v_effective_user_id := get_sync_owner();
    RETURN QUERY SELECT * FROM library_items WHERE user_id = v_effective_user_id;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_pull_library() TO authenticated;

-- Full-replace push of watched items (permanent watched history).
CREATE OR REPLACE FUNCTION sync_push_watched_items(p_items JSONB)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id UUID;
BEGIN
    v_effective_user_id := get_sync_owner();
    DELETE FROM watched_items WHERE user_id = v_effective_user_id;
    INSERT INTO watched_items (user_id, content_id, content_type, title, season, episode, watched_at)
    SELECT
        v_effective_user_id,
        (item->>'content_id'),
        (item->>'content_type'),
        COALESCE(item->>'title', ''),
        (item->>'season')::INTEGER,
        (item->>'episode')::INTEGER,
        (item->>'watched_at')::BIGINT
    FROM jsonb_array_elements(p_items) AS item;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_push_watched_items(JSONB) TO authenticated;

-- Returns all watched items for the effective user.
CREATE OR REPLACE FUNCTION sync_pull_watched_items()
RETURNS SETOF watched_items
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_effective_user_id UUID;
BEGIN
    v_effective_user_id := get_sync_owner();
    RETURN QUERY SELECT * FROM watched_items WHERE user_id = v_effective_user_id;
END;
$$;

GRANT EXECUTE ON FUNCTION sync_pull_watched_items() TO authenticated;
