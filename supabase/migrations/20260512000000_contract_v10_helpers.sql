-- Contract v10: shared timestamp helpers.
-- Every v10 push RPC accepts p_base_updated_at_ms BIGINT and rejects with
-- reason='stale_base' when any row it would mutate has a newer updated_at.
-- These helpers normalize timestamp↔ms conversion and the result envelope.

CREATE OR REPLACE FUNCTION public.sync_to_ms(p_ts timestamptz)
RETURNS bigint
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT (EXTRACT(EPOCH FROM p_ts) * 1000)::bigint
$$;

CREATE OR REPLACE FUNCTION public.sync_now_ms()
RETURNS bigint
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
  SELECT public.sync_to_ms(now())
$$;

CREATE OR REPLACE FUNCTION public.sync_stale_base_result(p_current_ms bigint)
RETURNS jsonb
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT jsonb_build_object(
    'applied', false,
    'reason', 'stale_base',
    'current_updated_at_ms', p_current_ms
  )
$$;

CREATE OR REPLACE FUNCTION public.sync_applied_result(p_current_ms bigint)
RETURNS jsonb
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT jsonb_build_object(
    'applied', true,
    'current_updated_at_ms', p_current_ms
  )
$$;

GRANT EXECUTE ON FUNCTION public.sync_to_ms(timestamptz) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_now_ms() TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_stale_base_result(bigint) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_applied_result(bigint) TO authenticated;
