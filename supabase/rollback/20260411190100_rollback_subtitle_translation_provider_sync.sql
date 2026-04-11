-- Roll back provider-agnostic subtitle translation public settings to legacy Gemini-compatible fields.
--
-- Rollback procedure:
-- 1. Stop web/app v6 rollout.
-- 2. Run the rollback migration against the affected Supabase project.
-- 3. Redeploy the previous SQL functions from supabase/account_settings_sync.sql.
-- 4. Keep translation_api_key secrets in place during rollback; do not delete them until users are
--    safely back on a stable provider-agnostic release or a separate cleanup plan exists.
--
-- Verification queries after rollback:
-- select count(*) from public.account_settings_public where settings_payload#>'{integrations,subtitleTranslation}' is not null;
-- select count(*) from public.account_secrets where secret_type = 'translation_api_key';
-- select settings_payload#>'{integrations,gemini,enabled}' from public.account_settings_public limit 10;

update public.account_settings_public
set settings_payload = jsonb_set(
    settings_payload - 'integrations',
    '{integrations}',
    (
      coalesce(settings_payload->'integrations', '{}'::jsonb) - 'subtitleTranslation'
    ) || jsonb_build_object(
      'gemini',
      jsonb_build_object(
        'enabled',
        case
          when settings_payload#>'{integrations,subtitleTranslation}' is not null then
            case
              when settings_payload#>'{integrations,subtitleTranslation,enabled}' = 'true'::jsonb
                and upper(coalesce(settings_payload#>>'{integrations,subtitleTranslation,provider}', '')) = 'GEMINI'
              then 'true'::jsonb
              else 'false'::jsonb
            end
          else coalesce(settings_payload#>'{integrations,gemini,enabled}', 'false'::jsonb)
        end
      )
    ),
    true
  ),
  updated_at = now(),
  updated_from = 'rollback:subtitle-translation'
where settings_payload#>'{integrations,subtitleTranslation}' is not null;
