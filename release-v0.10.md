# Nexio 0.10

Nexio 0.10 is the first GitHub release baseline for the new Nexio identity and sync model.

## Highlights

- One account portal for every Nexio screen
- Full web-to-addon settings sync
- Secret-backed account handling for synced integrations and credentialed addons
- Native Trakt integration for scrobble, check-in, continue watching, and custom catalogs
- Native MDBList integration for ratings, personal lists, and top-list catalogs
- RPDB and TOP Posters support for custom poster pipelines
- Custom Media3 playback stack with Dolby Vision conversion work and Fire OS compatibility focus
- Advanced streaming stack with cache and parallel downloading controls
- Multilingual support for English, German, French, Spanish, Dutch, and Mandarin

## What changed in this release

- Reworked account sync around Supabase-backed settings, addons, and secret-managed integrations
- Added synced web portal support for addon management and account-level settings control
- Moved sensitive integration values out of the public sync payload
- Improved Android account pull/push flows and startup refresh behavior
- Fixed catalog ordering consistency between Android Home, Android Catalogs, and the web portal
- Fixed MDBList catalog discovery and list-item loading
- Retargeted the in-app updater to GitHub Releases on `johnneerdael/nexio`

## Install / update

1. Download `app-release-universal.apk` from this release.
2. Install it on Android TV / Google TV / Fire TV:
   - fresh install: sideload the APK
   - update install: install over the existing Nexio app
3. Open Nexio and sign in to your account.
4. The app will immediately pull your account snapshot from the web portal on first full auth and on startup.
5. Any later setting or addon changes on either side should sync back through Nexio Live.

## Notes

- App version: `0.10`
- Android version code: `28`
- The in-app updater now checks GitHub Releases for newer versions after this baseline
- Device-specific settings remain local and are not account-synced

## Known rollout requirement

For instant app-to-web reflection in the portal, the deployed `nexio-web` instance must include:

- `NUXT_PUBLIC_SUPABASE_URL`
- `NUXT_PUBLIC_SUPABASE_ANON_KEY`

and must be rebuilt after the portal realtime changes.
