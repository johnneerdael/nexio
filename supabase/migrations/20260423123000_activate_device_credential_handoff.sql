CREATE OR REPLACE FUNCTION public.activate_device_credential_handoff(
    p_device_user_id uuid,
    p_device_public_id text,
    p_credential_hash text
) RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    v_handoff public.device_credential_handoffs%ROWTYPE;
BEGIN
    SELECT *
    INTO v_handoff
    FROM public.device_credential_handoffs
    WHERE device_user_id = p_device_user_id
      AND device_public_id = trim(p_device_public_id)
      AND credential_hash = trim(p_credential_hash)
      AND used_at IS NULL
      AND expires_at > now()
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    UPDATE public.device_credential_handoffs
    SET used_at = now()
    WHERE id = v_handoff.id
      AND used_at IS NULL;

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    INSERT INTO public.device_credentials (
        owner_id,
        device_user_id,
        linked_device_id,
        device_public_id,
        credential_hash,
        display_name,
        device_name,
        device_model,
        device_platform,
        status,
        last_seen_at,
        revoked_at
    ) VALUES (
        v_handoff.owner_id,
        v_handoff.device_user_id,
        v_handoff.linked_device_id,
        v_handoff.device_public_id,
        v_handoff.credential_hash,
        v_handoff.display_name,
        v_handoff.device_name,
        v_handoff.device_model,
        v_handoff.device_platform,
        'active',
        now(),
        null
    )
    ON CONFLICT (device_user_id) DO UPDATE
    SET owner_id = EXCLUDED.owner_id,
        linked_device_id = EXCLUDED.linked_device_id,
        device_public_id = EXCLUDED.device_public_id,
        credential_hash = EXCLUDED.credential_hash,
        display_name = EXCLUDED.display_name,
        device_name = EXCLUDED.device_name,
        device_model = EXCLUDED.device_model,
        device_platform = EXCLUDED.device_platform,
        status = 'active',
        last_seen_at = now(),
        revoked_at = null;

    RETURN true;
END;
$$;
