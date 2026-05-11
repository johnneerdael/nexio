CREATE OR REPLACE FUNCTION public.sync_pull_profile_auth_tokens_v10(
  p_profile_index int
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_items jsonb;
  v_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'token_type', t.token_type,
            'token_payload', t.token_payload,
            'masked_preview', t.masked_preview,
            'linked', t.linked,
            'revoked_at_ms',
              CASE WHEN t.revoked_at IS NULL THEN NULL ELSE public.sync_to_ms(t.revoked_at) END,
            'updated_at_ms', public.sync_to_ms(t.updated_at)
          )), '[]'::jsonb),
         COALESCE(MAX(public.sync_to_ms(t.updated_at)), 0)
    INTO v_items, v_ms
    FROM public.profile_auth_tokens t
   WHERE t.user_id = v_user_id
     AND t.profile_index = p_profile_index;

  RETURN jsonb_build_object(
    'contract_version', 10,
    'items', v_items,
    'updated_at_ms', v_ms
  );
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_set_profile_auth_token_v10(
  p_base_updated_at_ms bigint,
  p_profile_index int,
  p_token_type text,
  p_token_payload jsonb,
  p_masked_preview text DEFAULT '',
  p_source text DEFAULT 'app'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_current_ms bigint;
  v_new_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), 0)
    INTO v_current_ms
    FROM public.profile_auth_tokens
   WHERE user_id = v_user_id AND profile_index = p_profile_index;

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  INSERT INTO public.profile_auth_tokens (user_id, profile_index, token_type, token_payload, masked_preview, source, linked, revoked_at, updated_at)
       VALUES (v_user_id, p_profile_index, p_token_type, p_token_payload, p_masked_preview, p_source, true, NULL, now())
  ON CONFLICT (user_id, profile_index, token_type)
       DO UPDATE SET
         token_payload = EXCLUDED.token_payload,
         masked_preview = EXCLUDED.masked_preview,
         source = EXCLUDED.source,
         linked = true,
         revoked_at = NULL,
         updated_at = now();

  SELECT MAX(public.sync_to_ms(updated_at)) INTO v_new_ms
    FROM public.profile_auth_tokens
   WHERE user_id = v_user_id AND profile_index = p_profile_index;

  RETURN public.sync_applied_result(v_new_ms);
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_revoke_profile_auth_token_v10(
  p_base_updated_at_ms bigint,
  p_profile_index int,
  p_token_type text,
  p_source text DEFAULT 'app'
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_user_id uuid := auth.uid();
  v_current_ms bigint;
  v_new_ms bigint;
BEGIN
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required';
  END IF;

  SELECT COALESCE(MAX(public.sync_to_ms(updated_at)), 0)
    INTO v_current_ms
    FROM public.profile_auth_tokens
   WHERE user_id = v_user_id AND profile_index = p_profile_index;

  IF p_base_updated_at_ms < v_current_ms THEN
    RETURN public.sync_stale_base_result(v_current_ms);
  END IF;

  UPDATE public.profile_auth_tokens
     SET linked = false,
         revoked_at = now(),
         source = p_source,
         updated_at = now()
   WHERE user_id = v_user_id
     AND profile_index = p_profile_index
     AND token_type = p_token_type;

  SELECT MAX(public.sync_to_ms(updated_at)) INTO v_new_ms
    FROM public.profile_auth_tokens
   WHERE user_id = v_user_id AND profile_index = p_profile_index;

  RETURN public.sync_applied_result(COALESCE(v_new_ms, public.sync_now_ms()));
END;
$$;

REVOKE ALL ON FUNCTION public.sync_pull_profile_auth_tokens_v10(int) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_pull_profile_auth_tokens_v10(int) TO authenticated;
REVOKE ALL ON FUNCTION public.sync_set_profile_auth_token_v10(bigint, int, text, jsonb, text, text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_set_profile_auth_token_v10(bigint, int, text, jsonb, text, text) TO authenticated;
REVOKE ALL ON FUNCTION public.sync_revoke_profile_auth_token_v10(bigint, int, text, text) FROM public;
GRANT EXECUTE ON FUNCTION public.sync_revoke_profile_auth_token_v10(bigint, int, text, text) TO authenticated;
