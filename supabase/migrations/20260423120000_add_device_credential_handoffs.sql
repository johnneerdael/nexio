CREATE TABLE IF NOT EXISTS public.device_credential_handoffs (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    linked_device_id uuid NULL REFERENCES public.linked_devices(id) ON DELETE SET NULL,
    device_public_id text NOT NULL CHECK (length(trim(device_public_id)) > 0),
    credential_hash text NOT NULL CHECK (length(trim(credential_hash)) > 0),
    display_name text NOT NULL CHECK (length(trim(display_name)) > 0),
    device_name text NULL,
    device_model text NULL,
    device_platform text NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS device_credential_handoffs_device_user_unused_uidx
    ON public.device_credential_handoffs(device_user_id)
    WHERE used_at IS NULL;

CREATE INDEX IF NOT EXISTS device_credential_handoffs_owner_created_idx
    ON public.device_credential_handoffs(owner_id, created_at DESC);
