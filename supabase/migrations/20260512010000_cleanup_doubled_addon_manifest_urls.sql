-- Heal historical corruption in account_addons_public.manifest_url where one or
-- more /manifest.json suffixes were appended on top of an already-correct value
-- (e.g. https://v3-cinemeta.strem.io/manifest.json/manifest.json). Caused 404s
-- in the browser console on every page load and broke server-side manifest
-- inspection. The nexio-web normalizeAddonManifestUrl helper now self-heals on
-- read so consumers no longer observe doubled values, but this migration cleans
-- the underlying rows so future writes from clients don't have to keep rebuilding
-- the canonical form from corrupted state.
--
-- The regex collapses any consecutive run of /manifest.json suffixes back to a
-- single one. Single-suffix rows (the vast majority) match `LIKE` filter zero
-- times and are skipped — only corrupted rows are touched.

UPDATE public.account_addons_public
   SET manifest_url = regexp_replace(manifest_url, '(/manifest\.json)+$', '/manifest.json', 'i')
 WHERE manifest_url ~* '/manifest\.json/manifest\.json';
