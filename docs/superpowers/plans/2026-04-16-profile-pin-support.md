# Profile PIN Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish profile PIN support so users can set and clear profile PINs in nexio-web, Android can verify PIN-locked profile selection, and normal profile sync cannot accidentally corrupt PIN state.

**Architecture:** Treat profile PINs as server-owned security state. Supabase stores only hashed PINs and exposes narrow RPCs for set, clear, and verify; nexio-web calls set/clear through authenticated server endpoints; Android calls verify directly through `ProfileSyncService` and switches profiles only after the RPC unlocks the profile.

**Tech Stack:** Supabase Postgres RPCs with `crypt`/`gen_salt`, Nuxt server routes and Vue composables, Android Kotlin/Hilt/ViewModel, Node `node:test`, Robolectric/JUnit/MockK.

---

## File Structure

- Create: `supabase/migrations/20260416020000_add_profile_pin_management.sql`
  - Add `pin_failed_attempts`.
  - Add `profile_set_pin`, `profile_clear_pin`, and `profile_verify_pin`.
  - Replace `sync_push_profiles` so profile metadata sync preserves server PIN fields.
- Create: `nexio-web/tests/profile-pin.test.ts`
  - Source-level regression tests for the SQL migration, web API routes, profile store, and editor UI.
- Create: `nexio-web/server/api/account/profiles/pin.post.ts`
  - Validate a 4-digit PIN and call `profile_set_pin`.
- Create: `nexio-web/server/api/account/profiles/pin.delete.ts`
  - Clear a profile PIN through `profile_clear_pin`.
- Modify: `nexio-web/composables/useProfileStore.ts`
  - Add profile PIN fields and `setProfilePin` / `clearProfilePin`.
- Modify: `nexio-web/components/portal/ProfileEditorSection.vue`
  - Add the profile PIN management UI.
- Modify: `nexio-web/server/api/account/profiles/index.get.ts`
  - Include PIN fields on the virtual default profile fallback.
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt`
  - Add `SupabaseProfilePinVerifyResult`.
- Modify: `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt`
  - Add `verifyProfilePin(profileId, pin)`.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt`
  - Replace the hardcoded failing stub with real verification.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinOverlay.kt`
  - Display server-driven error text.
- Create: `app/src/test/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModelTest.kt`
  - Verify successful unlock, wrong PIN, rate limit, and failed RPC behavior.

---

### Task 1: Supabase PIN RPCs and Sync Safety

**Files:**
- Create: `supabase/migrations/20260416020000_add_profile_pin_management.sql`
- Create: `nexio-web/tests/profile-pin.test.ts`

- [ ] **Step 1: Write the failing SQL source test**

Create `nexio-web/tests/profile-pin.test.ts` with this content:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const migrationPath = '../supabase/migrations/20260416020000_add_profile_pin_management.sql'

function source(path: string): string {
  return readFileSync(path, 'utf8')
}

test('profile PIN migration adds server-owned PIN RPCs', () => {
  const sql = source(migrationPath)

  assert.match(sql, /ALTER TABLE public\.profiles ADD COLUMN IF NOT EXISTS pin_failed_attempts INT NOT NULL DEFAULT 0/)
  assert.match(sql, /CREATE OR REPLACE FUNCTION public\.profile_set_pin/)
  assert.match(sql, /CREATE OR REPLACE FUNCTION public\.profile_clear_pin/)
  assert.match(sql, /CREATE OR REPLACE FUNCTION public\.profile_verify_pin/)
  assert.match(sql, /crypt\(v_pin, gen_salt\('bf'\)\)/)
  assert.match(sql, /v_profile\.pin_hash = crypt\(v_pin, v_profile\.pin_hash\)/)
  assert.match(sql, /retry_after_seconds INT/)
})

test('profile metadata sync preserves server-owned PIN state', () => {
  const sql = source(migrationPath)

  assert.match(sql, /CREATE OR REPLACE FUNCTION public\.sync_push_profiles/)
  assert.doesNotMatch(sql, /pin_enabled\s*=\s*EXCLUDED\.pin_enabled/)
  assert.doesNotMatch(sql, /pin_hash\s*=\s*EXCLUDED\.pin_hash/)
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd nexio-web
npx tsx --test tests/profile-pin.test.ts
```

Expected: FAIL with `ENOENT` for `../supabase/migrations/20260416020000_add_profile_pin_management.sql`.

- [ ] **Step 3: Create the Supabase migration**

Create `supabase/migrations/20260416020000_add_profile_pin_management.sql` with this content:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS pin_failed_attempts INT NOT NULL DEFAULT 0;

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
    pin_hash = EXCLUDED.pin_hash,
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
```

- [ ] **Step 4: Run the SQL source test**

Run:

```bash
cd nexio-web
npx tsx --test tests/profile-pin.test.ts
```

Expected: PASS for both SQL tests.

- [ ] **Step 5: Commit**

Run:

```bash
git add supabase/migrations/20260416020000_add_profile_pin_management.sql nexio-web/tests/profile-pin.test.ts
git commit -m "feat: add profile pin management rpcs"
```

---

### Task 2: Web PIN Configuration

**Files:**
- Modify: `nexio-web/tests/profile-pin.test.ts`
- Create: `nexio-web/server/api/account/profiles/pin.post.ts`
- Create: `nexio-web/server/api/account/profiles/pin.delete.ts`
- Modify: `nexio-web/composables/useProfileStore.ts`
- Modify: `nexio-web/components/portal/ProfileEditorSection.vue`
- Modify: `nexio-web/server/api/account/profiles/index.get.ts`

- [ ] **Step 1: Add failing web source tests**

Append these tests to `nexio-web/tests/profile-pin.test.ts`:

```ts
test('web profile types and store expose PIN state and mutations', () => {
  const store = source('composables/useProfileStore.ts')

  assert.match(store, /pin_enabled: boolean/)
  assert.match(store, /pin_locked_until: string \| null/)
  assert.match(store, /async function setProfilePin\(profileIndex: number, pin: string\)/)
  assert.match(store, /async function clearProfilePin\(profileIndex: number\)/)
  assert.match(store, /\/api\/account\/profiles\/pin/)
})

test('web profile PIN routes call narrow Supabase RPCs', () => {
  const setRoute = source('server/api/account/profiles/pin.post.ts')
  const clearRoute = source('server/api/account/profiles/pin.delete.ts')

  assert.match(setRoute, /profile_set_pin/)
  assert.match(setRoute, /PIN must be exactly 4 digits\./)
  assert.match(setRoute, /p_profile_index: body\.profileIndex/)
  assert.match(setRoute, /p_pin: pin/)
  assert.match(clearRoute, /profile_clear_pin/)
  assert.match(clearRoute, /p_profile_index: body\.profileIndex/)
})

test('profile editor exposes PIN management controls', () => {
  const editor = source('components/portal/ProfileEditorSection.vue')

  assert.match(editor, /Profile PIN/)
  assert.match(editor, /Set PIN/)
  assert.match(editor, /Clear PIN/)
  assert.match(editor, /profileStore\.setProfilePin/)
  assert.match(editor, /profileStore\.clearProfilePin/)
})

test('virtual default profile includes disabled PIN state', () => {
  const route = source('server/api/account/profiles/index.get.ts')

  assert.match(route, /pin_enabled: false/)
  assert.match(route, /pin_locked_until: null/)
})
```

- [ ] **Step 2: Run the web tests to verify they fail**

Run:

```bash
cd nexio-web
npx tsx --test tests/profile-pin.test.ts
```

Expected: FAIL because `server/api/account/profiles/pin.post.ts` and `pin.delete.ts` do not exist yet.

- [ ] **Step 3: Create the set-PIN web endpoint**

Create `nexio-web/server/api/account/profiles/pin.post.ts` with this content:

```ts
import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'

type PinBody = {
  profileIndex?: number
  pin?: string
}

function validateProfileIndex(profileIndex: unknown): number {
  if (!Number.isInteger(profileIndex) || (profileIndex as number) < 1 || (profileIndex as number) > 4) {
    throw createError({ statusCode: 400, statusMessage: 'profileIndex must be 1-4.' })
  }
  return profileIndex as number
}

function validatePin(value: unknown): string {
  const pin = typeof value === 'string' ? value.trim() : ''
  if (!/^[0-9]{4}$/.test(pin)) {
    throw createError({ statusCode: 400, statusMessage: 'PIN must be exactly 4 digits.' })
  }
  return pin
}

export default defineEventHandler(async (event) => {
  const body = (await readJsonBody<PinBody | null>(event)) ?? {}
  const token = bearerToken(event)
  await supabaseUser(event)

  const profileIndex = validateProfileIndex(body.profileIndex)
  const pin = validatePin(body.pin)

  const result = await supabaseFetch<unknown[]>(
    '/rest/v1/rpc/profile_set_pin',
    {
      method: 'POST',
      body: JSON.stringify({
        p_profile_index: profileIndex,
        p_pin: pin
      })
    },
    token
  )

  return okJson(Array.isArray(result) ? result[0] : result)
})
```

- [ ] **Step 4: Create the clear-PIN web endpoint**

Create `nexio-web/server/api/account/profiles/pin.delete.ts` with this content:

```ts
import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'

type PinBody = {
  profileIndex?: number
}

function validateProfileIndex(profileIndex: unknown): number {
  if (!Number.isInteger(profileIndex) || (profileIndex as number) < 1 || (profileIndex as number) > 4) {
    throw createError({ statusCode: 400, statusMessage: 'profileIndex must be 1-4.' })
  }
  return profileIndex as number
}

export default defineEventHandler(async (event) => {
  const body = (await readJsonBody<PinBody | null>(event)) ?? {}
  const token = bearerToken(event)
  await supabaseUser(event)

  const profileIndex = validateProfileIndex(body.profileIndex)

  const result = await supabaseFetch<unknown[]>(
    '/rest/v1/rpc/profile_clear_pin',
    {
      method: 'POST',
      body: JSON.stringify({
        p_profile_index: profileIndex
      })
    },
    token
  )

  return okJson(Array.isArray(result) ? result[0] : result)
})
```

- [ ] **Step 5: Update the web profile store**

Modify `nexio-web/composables/useProfileStore.ts`.

Update `ProfileRow` to include PIN fields:

```ts
export type ProfileRow = {
  id: string
  user_id: string
  profile_index: number
  name: string
  avatar_color_hex: string
  avatar_url: string | null
  uses_primary_addons: boolean
  uses_primary_plugins: boolean
  avatar_id: string | null
  pin_enabled: boolean
  pin_locked_until: string | null
  created_at: string
  updated_at: string
}
```

Add these functions after `upsertProfile`:

```ts
  async function setProfilePin(profileIndex: number, pin: string) {
    validateProfileIndex(profileIndex)
    const normalizedPin = pin.trim()
    if (!/^[0-9]{4}$/.test(normalizedPin)) {
      throw new Error('PIN must be exactly 4 digits.')
    }

    state.value.saving = true
    state.value.error = null

    try {
      await $fetch('/api/account/profiles/pin', {
        method: 'POST',
        headers: authHeaders(),
        body: {
          profileIndex,
          pin: normalizedPin
        }
      })
      await fetchProfiles()
    } catch (error) {
      state.value.error = normalizeError(error, 'Failed to set profile PIN.')
      throw error
    } finally {
      state.value.saving = false
    }
  }

  async function clearProfilePin(profileIndex: number) {
    validateProfileIndex(profileIndex)
    state.value.saving = true
    state.value.error = null

    try {
      await $fetch('/api/account/profiles/pin', {
        method: 'DELETE',
        headers: authHeaders(),
        body: {
          profileIndex
        }
      })
      await fetchProfiles()
    } catch (error) {
      state.value.error = normalizeError(error, 'Failed to clear profile PIN.')
      throw error
    } finally {
      state.value.saving = false
    }
  }
```

Add both functions to the returned object near the existing profile actions:

```ts
    upsertProfile,
    setProfilePin,
    clearProfilePin,
    uploadProfilePhoto,
```

- [ ] **Step 6: Update the virtual default profile fallback**

Modify the fallback object in `nexio-web/server/api/account/profiles/index.get.ts` so it includes the server-owned PIN fields:

```ts
    {
      id: `default-${user.id}`,
      user_id: user.id,
      profile_index: 1,
      name: 'Default',
      avatar_color_hex: '#1E88E5',
      avatar_url: null,
      uses_primary_addons: false,
      uses_primary_plugins: false,
      avatar_id: null,
      pin_enabled: false,
      pin_locked_until: null,
      created_at: now,
      updated_at: now
    },
```

- [ ] **Step 7: Replace the profile editor with PIN controls**

Replace `nexio-web/components/portal/ProfileEditorSection.vue` with this content:

```vue
<template>
  <div class="flex flex-col gap-4 md:flex-row md:items-start md:gap-6">
    <ProfilePhotoUpload
      :avatar-url="profile.avatar_url"
      :avatar-color-hex="profile.avatar_color_hex"
      :name="profile.name"
      :uploading="profileStore.state.value.photoUploading"
      :error="profileStore.state.value.photoError"
      @upload="handleUpload"
      @remove="handleRemove"
    />

    <div class="min-w-0 flex-1 space-y-6">
      <div class="space-y-2">
        <div class="flex flex-col gap-3 lg:flex-row lg:items-center">
          <input
            v-model="editingName"
            class="bg-transparent border-b-2 border-transparent focus:border-primary/50 outline-none text-2xl font-black font-display text-on-surface transition-colors"
            maxlength="30"
            @blur="handleNameSave"
            @keydown.enter="handleNameSave"
          >
          <button
            v-if="editingName.trim() !== profile.name"
            type="button"
            class="rounded-xl bg-primary/10 border border-primary/20 px-4 py-1.5 text-sm font-semibold text-primary transition hover:bg-primary/20 disabled:cursor-wait disabled:opacity-60"
            :disabled="savingName"
            @click="handleNameSave"
          >
            Save Name
          </button>
        </div>
      </div>

      <section class="border-t border-outline-variant/10 pt-5">
        <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div class="max-w-xl">
            <p class="text-xs font-bold uppercase tracking-[0.18em] text-zinc-500">Profile PIN</p>
            <h3 class="mt-2 text-base font-bold text-on-surface">
              {{ profile.pin_enabled ? 'PIN protection is on' : 'PIN protection is off' }}
            </h3>
            <p class="mt-1 text-sm leading-6 text-on-surface-variant">
              Use a 4-digit PIN before this profile can be opened on Android TV.
            </p>
          </div>

          <form class="flex flex-col gap-3 sm:flex-row sm:items-center" @submit.prevent="handlePinSave">
            <input
              v-model="editingPin"
              inputmode="numeric"
              pattern="[0-9]{4}"
              maxlength="4"
              placeholder="4 digits"
              class="w-32 rounded-xl border border-outline-variant/20 bg-surface-container-high px-4 py-2 text-sm text-on-surface outline-none focus:border-primary/50"
              @input="normalizePinInput"
            >
            <button
              type="submit"
              class="rounded-xl bg-primary/10 border border-primary/20 px-4 py-2 text-sm font-semibold text-primary transition hover:bg-primary/20 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="savingPin || editingPin.length !== 4"
            >
              Set PIN
            </button>
            <button
              v-if="profile.pin_enabled"
              type="button"
              class="rounded-xl border border-outline-variant/20 px-4 py-2 text-sm font-semibold text-on-surface-variant transition hover:text-danger hover:border-danger/30 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="savingPin"
              @click="handlePinClear"
            >
              Clear PIN
            </button>
          </form>
        </div>

        <p v-if="pinError" class="mt-3 text-sm text-error">{{ pinError }}</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import ProfilePhotoUpload from '~/components/portal/ProfilePhotoUpload.vue'
import { useProfileStore, type ProfileRow } from '~/composables/useProfileStore'

const props = defineProps<{
  profile: ProfileRow
}>()

const emit = defineEmits<{
  'name-updated': [name: string]
}>()

const profileStore = useProfileStore()
const editingName = ref(props.profile.name)
const editingPin = ref('')
const pinError = ref<string | null>(null)
const savingName = ref(false)
const savingPin = ref(false)

watch(
  () => props.profile.name,
  (name) => {
    editingName.value = name
  }
)

watch(
  () => props.profile.profile_index,
  () => {
    editingPin.value = ''
    pinError.value = null
  }
)

async function handleNameSave() {
  if (savingName.value) {
    return
  }

  const trimmed = editingName.value.trim()
  if (trimmed === props.profile.name || trimmed.length < 1 || trimmed.length > 30) {
    editingName.value = props.profile.name
    return
  }

  savingName.value = true
  try {
    await profileStore.upsertProfile(props.profile.profile_index, trimmed, props.profile.avatar_color_hex)
    emit('name-updated', trimmed)
  } finally {
    savingName.value = false
  }
}

function normalizePinInput() {
  editingPin.value = editingPin.value.replace(/\D/g, '').slice(0, 4)
  pinError.value = null
}

async function handlePinSave() {
  if (savingPin.value) {
    return
  }

  const pin = editingPin.value.trim()
  if (!/^[0-9]{4}$/.test(pin)) {
    pinError.value = 'PIN must be exactly 4 digits.'
    return
  }

  savingPin.value = true
  pinError.value = null
  try {
    await profileStore.setProfilePin(props.profile.profile_index, pin)
    editingPin.value = ''
  } catch (error) {
    pinError.value = error instanceof Error ? error.message : 'Failed to set profile PIN.'
  } finally {
    savingPin.value = false
  }
}

async function handlePinClear() {
  if (savingPin.value) {
    return
  }

  savingPin.value = true
  pinError.value = null
  try {
    await profileStore.clearProfilePin(props.profile.profile_index)
    editingPin.value = ''
  } catch (error) {
    pinError.value = error instanceof Error ? error.message : 'Failed to clear profile PIN.'
  } finally {
    savingPin.value = false
  }
}

async function handleUpload(file: File) {
  await profileStore.uploadProfilePhoto(props.profile.profile_index, file)
}

async function handleRemove() {
  await profileStore.removeProfilePhoto(props.profile.profile_index)
}
</script>
```

- [ ] **Step 8: Run the web PIN tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/profile-pin.test.ts
```

Expected: PASS for all profile PIN tests.

- [ ] **Step 9: Run the existing profile settings web tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/profile-settings-blob.test.ts
```

Expected: PASS for all existing profile settings tests.

- [ ] **Step 10: Commit**

Run:

```bash
git add nexio-web/tests/profile-pin.test.ts nexio-web/server/api/account/profiles/pin.post.ts nexio-web/server/api/account/profiles/pin.delete.ts nexio-web/composables/useProfileStore.ts nexio-web/components/portal/ProfileEditorSection.vue nexio-web/server/api/account/profiles/index.get.ts
git commit -m "feat: add web profile pin controls"
```

---

### Task 3: Android PIN Verification

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModelTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinOverlay.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModelTest.kt` with this content:

```kt
package com.nexio.tv.ui.screens.profile

import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.ProfileSyncService
import com.nexio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nexio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelHarness(
        verifyResult: Result<SupabaseProfilePinVerifyResult>
    ): Triple<ProfileSelectionViewModel, ProfileManager, ProfileSyncService> {
        val profiles = MutableStateFlow(
            listOf(
                UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Kids", avatarColorHex = "#E53935", pinEnabled = true)
            )
        )
        val activeProfileId = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        val profileSyncService = mockk<ProfileSyncService>()

        every { profileManager.profiles } returns profiles
        every { profileManager.activeProfileId } returns activeProfileId
        coEvery { profileManager.setActiveProfile(any()) } coAnswers {
            activeProfileId.value = firstArg()
        }
        coEvery { profileSyncService.verifyProfilePin(2, "1234") } returns verifyResult

        return Triple(ProfileSelectionViewModel(profileManager, profileSyncService), profileManager, profileSyncService)
    }

    @Test
    fun `verifyPin switches profile when server unlocks`() = runTest {
        val (viewModel, profileManager, profileSyncService) = viewModelHarness(
            Result.success(
                SupabaseProfilePinVerifyResult(
                    unlocked = true,
                    retryAfterSeconds = 0,
                    pinEnabled = true
                )
            )
        )

        viewModel.verifyPin(2, "1234")
        advanceUntilIdle()

        coVerify(exactly = 1) { profileSyncService.verifyProfilePin(2, "1234") }
        coVerify(exactly = 1) { profileManager.setActiveProfile(2) }
        assertFalse(viewModel.pinState.value.isError)
    }

    @Test
    fun `verifyPin shows wrong PIN without switching profile when server rejects`() = runTest {
        val (viewModel, profileManager, profileSyncService) = viewModelHarness(
            Result.success(
                SupabaseProfilePinVerifyResult(
                    unlocked = false,
                    retryAfterSeconds = 0,
                    pinEnabled = true
                )
            )
        )

        viewModel.verifyPin(2, "1234")
        advanceUntilIdle()

        coVerify(exactly = 1) { profileSyncService.verifyProfilePin(2, "1234") }
        coVerify(exactly = 0) { profileManager.setActiveProfile(2) }
        assertTrue(viewModel.pinState.value.isError)
        assertEquals("Wrong PIN", viewModel.pinState.value.errorMessage)
    }

    @Test
    fun `verifyPin exposes retry countdown when server rate limits`() = runTest {
        val (viewModel, profileManager, profileSyncService) = viewModelHarness(
            Result.success(
                SupabaseProfilePinVerifyResult(
                    unlocked = false,
                    retryAfterSeconds = 300,
                    pinEnabled = true
                )
            )
        )

        viewModel.verifyPin(2, "1234")
        advanceUntilIdle()

        coVerify(exactly = 1) { profileSyncService.verifyProfilePin(2, "1234") }
        coVerify(exactly = 0) { profileManager.setActiveProfile(2) }
        assertFalse(viewModel.pinState.value.isError)
        assertTrue(viewModel.pinState.value.retryAfterSeconds > 0)
    }

    @Test
    fun `verifyPin shows unlock error when RPC fails`() = runTest {
        val (viewModel, profileManager, profileSyncService) = viewModelHarness(
            Result.failure(IllegalStateException("network down"))
        )

        viewModel.verifyPin(2, "1234")
        advanceUntilIdle()

        coVerify(exactly = 1) { profileSyncService.verifyProfilePin(2, "1234") }
        coVerify(exactly = 0) { profileManager.setActiveProfile(2) }
        assertTrue(viewModel.pinState.value.isError)
        assertEquals("Unable to verify PIN", viewModel.pinState.value.errorMessage)
    }
}
```

- [ ] **Step 2: Run the ViewModel test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.profile.ProfileSelectionViewModelTest"
```

Expected: FAIL because `ProfileSelectionViewModel` does not accept `ProfileSyncService` and `SupabaseProfilePinVerifyResult` does not exist.

- [ ] **Step 3: Add the Supabase PIN verify result model**

Add this data class after `SupabaseProfile` in `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt`:

```kt
@Serializable
data class SupabaseProfilePinVerifyResult(
    val unlocked: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int = 0,
    @SerialName("pin_enabled") val pinEnabled: Boolean = false
)
```

- [ ] **Step 4: Add `ProfileSyncService.verifyProfilePin`**

Modify `app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt`.

Add this import:

```kt
import com.nexio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
```

Add this method before `pullFromRemote()`:

```kt
    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<SupabaseProfilePinVerifyResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!authManager.hasSyncSession) {
                    return@withContext Result.failure(IllegalStateException("Sign in before unlocking profile PINs"))
                }

                if (profileId !in 1..4) {
                    return@withContext Result.failure(IllegalArgumentException("profileId must be 1-4"))
                }

                val normalizedPin = pin.trim()
                if (!normalizedPin.matches(Regex("^[0-9]{4}$"))) {
                    return@withContext Result.success(
                        SupabaseProfilePinVerifyResult(
                            unlocked = false,
                            retryAfterSeconds = 0,
                            pinEnabled = true
                        )
                    )
                }

                val params = buildJsonObject {
                    put("p_profile_index", profileId)
                    put("p_pin", normalizedPin)
                }

                val result = withJwtRefreshRetry {
                    postgrest.rpc("profile_verify_pin", params)
                        .decodeList<SupabaseProfilePinVerifyResult>()
                        .firstOrNull()
                } ?: return@withContext Result.failure(Exception("Empty response from profile_verify_pin"))

                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify profile PIN for profile $profileId", e)
                Result.failure(e)
            }
        }
```

- [ ] **Step 5: Replace the ViewModel verification stub**

Replace `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt` with this content:

```kt
package com.nexio.tv.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.ProfileSyncService
import com.nexio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService
) : ViewModel() {

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId

    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = profileManager.profiles
        .map { list -> list.associate { it.id to it.pinEnabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _pinState = MutableStateFlow(PinVerificationState())
    val pinState: StateFlow<PinVerificationState> = _pinState.asStateFlow()

    fun selectProfile(profileId: Int) {
        viewModelScope.launch {
            profileManager.setActiveProfile(profileId)
        }
    }

    fun verifyPin(profileId: Int, pin: String) {
        if (_pinState.value.isVerifying) return
        viewModelScope.launch {
            _pinState.update { it.copy(isVerifying = true, isError = false, errorMessage = null) }

            val result = profileSyncService.verifyProfilePin(profileId, pin).getOrElse {
                _pinState.update {
                    it.copy(
                        isVerifying = false,
                        isError = true,
                        errorMessage = "Unable to verify PIN"
                    )
                }
                return@launch
            }

            if (result.unlocked) {
                _pinState.update { PinVerificationState() }
                profileManager.setActiveProfile(profileId)
            } else if (result.retryAfterSeconds > 0) {
                _pinState.update {
                    it.copy(
                        isVerifying = false,
                        isError = false,
                        errorMessage = null,
                        retryAfterSeconds = result.retryAfterSeconds
                    )
                }
                startRateLimitCountdown(result.retryAfterSeconds)
            } else {
                _pinState.update {
                    it.copy(
                        isVerifying = false,
                        isError = true,
                        errorMessage = "Wrong PIN"
                    )
                }
            }
        }
    }

    private var countdownJob: Job? = null

    private fun startRateLimitCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _pinState.update { it.copy(retryAfterSeconds = remaining) }
                delay(1000)
            }
            _pinState.update { it.copy(retryAfterSeconds = 0) }
        }
    }

    fun consumePinError() {
        _pinState.update { it.copy(isError = false, errorMessage = null) }
    }

    fun resetPinState() {
        countdownJob?.cancel()
        _pinState.value = PinVerificationState()
    }

    data class PinVerificationState(
        val isVerifying: Boolean = false,
        val isError: Boolean = false,
        val errorMessage: String? = null,
        val retryAfterSeconds: Int = 0
    )
}
```

- [ ] **Step 6: Display server-driven PIN errors**

In `app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinOverlay.kt`, replace the error text block:

```kt
                isError -> Text(
                    text = "Wrong PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.Error
                )
```

with:

```kt
                isError -> Text(
                    text = errorMessage ?: "Wrong PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.Error
                )
```

Update the parameter documentation line for `errorMessage` to:

```kt
 * @param errorMessage Optional server or network error message. Falls back to "Wrong PIN".
```

- [ ] **Step 7: Run the ViewModel tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.profile.ProfileSelectionViewModelTest"
```

Expected: PASS.

- [ ] **Step 8: Run existing profile tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.ProfileDataStoreTest" --tests "com.nexio.tv.domain.model.UserProfileTest" --tests "com.nexio.tv.core.profile.ProfileManagerTest"
```

Expected: PASS.

- [ ] **Step 9: Commit**

Run:

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModelTest.kt app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/profile/ProfilePinOverlay.kt
git commit -m "feat: verify profile pins on android"
```

---

### Task 4: Final Verification

**Files:**
- No new files.
- Verify all files changed in Tasks 1-3.

- [ ] **Step 1: Run focused web tests**

Run:

```bash
cd nexio-web
npx tsx --test tests/profile-pin.test.ts tests/profile-settings-blob.test.ts
```

Expected: PASS.

- [ ] **Step 2: Run focused Android tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.profile.ProfileSelectionViewModelTest" --tests "com.nexio.tv.data.local.ProfileDataStoreTest" --tests "com.nexio.tv.domain.model.UserProfileTest" --tests "com.nexio.tv.core.profile.ProfileManagerTest"
```

Expected: PASS.

- [ ] **Step 3: Inspect profile PIN source paths**

Run:

```bash
rg -n "profile_set_pin|profile_clear_pin|profile_verify_pin|setProfilePin|clearProfilePin|verifyProfilePin|pin_failed_attempts" supabase nexio-web app/src/main/java app/src/test/java
```

Expected: output includes the migration, web routes/store/editor, `ProfileSyncService`, and `ProfileSelectionViewModelTest`.

- [ ] **Step 4: Inspect for forbidden direct PIN hash exposure**

Run:

```bash
rg -n "pin_hash" nexio-web app/src/main/java app/src/test/java
```

Expected: no output. Client code must not reference or expose `pin_hash`.

- [ ] **Step 5: Inspect git diff**

Run:

```bash
git diff --stat
git diff -- supabase/migrations/20260416020000_add_profile_pin_management.sql nexio-web app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt app/src/main/java/com/nexio/tv/core/sync/ProfileSyncService.kt app/src/main/java/com/nexio/tv/ui/screens/profile app/src/test/java/com/nexio/tv/ui/screens/profile/ProfileSelectionViewModelTest.kt
```

Expected:
- Migration owns hashing, verification, lockout, and sync preservation.
- Web can set and clear PINs without handling hashes.
- Android verification calls `profile_verify_pin`.
- No unrelated resource or branding changes are included.

- [ ] **Step 6: Commit final verification notes if any files changed**

If no files changed during verification, do not create a commit.

If verification required small source fixes, run:

```bash
git add supabase nexio-web app/src/main/java app/src/test/java
git commit -m "test: cover profile pin flows"
```

---

## Self-Review

**Spec coverage:** The plan covers every missing item from the investigation: Android verification replaces the always-failing stub, web profile types include PIN state, web store and server routes can set and clear PINs, Supabase has PIN set/clear/verify RPCs, and normal profile sync no longer writes server-owned PIN fields.

**Placeholder scan:** The plan contains no placeholder tasks, no vague edge-case instructions, and no references to undefined functions without a task that creates them.

**Type consistency:** Web uses `profileIndex` in API bodies and `profile_index` in Supabase rows. Android uses `profileId` internally and sends `p_profile_index` to Supabase. RPC response fields use `retry_after_seconds` and map to `retryAfterSeconds`.

**Sync decision:** Stable Android and web paths are both updated because PIN configuration and verification need cross-client support. There is no beta counterpart for these app files.
