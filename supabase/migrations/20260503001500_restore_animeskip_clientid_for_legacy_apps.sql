-- Resurrect the animeSkip.clientId field in account_settings_public.settings_payload
-- for any account that has an animeskip_api_key secret stored. This is a one-shot
-- backwards-compatibility rescue for older app versions still in the wild that
-- read clientId from the public JSON payload (they never learned about the
-- secrets table).
--
-- New app versions (Android commit 6e03ba68b, web PR #3) read the clientId
-- exclusively from the secrets table and write only to the secrets table —
-- they never touch the JSON field. Over time the JSON value will drift from
-- the secret value (if the user updates the key on a new client), but old
-- apps that haven't upgraded keep working with whatever they had.
--
-- Idempotent: re-running this against a row that already has the right
-- clientId is a no-op (jsonb_set overwrites with the same value).

do $$
declare
  rec record;
  v_secret jsonb;
  v_client_id text;
begin
  for rec in
    select s.user_id
    from public.account_secrets s
    where s.secret_type = 'animeskip_api_key'
      and s.secret_ref = 'integration:animeSkip'
  loop
    -- Read the decrypted secret payload via the same path the apps use.
    v_secret := public.service_resolve_account_secret(
      rec.user_id,
      'animeskip_api_key',
      'integration:animeSkip',
      'migration'
    );
    v_client_id := nullif(coalesce(v_secret#>>'{apiKey}', ''), '');
    if v_client_id is null then
      continue;
    end if;

    update public.account_settings_public
    set settings_payload = jsonb_set(
      coalesce(settings_payload, '{}'::jsonb),
      '{integrations,animeSkip,clientId}',
      to_jsonb(v_client_id),
      true
    )
    where user_id = rec.user_id;
  end loop;
end $$;

-- Verification after deploy:
-- select user_id, settings_payload#>>'{integrations,animeSkip,clientId}' as restored_client_id
--   from public.account_settings_public
--   where (settings_payload#>'{integrations,animeSkip}') ? 'clientId';
