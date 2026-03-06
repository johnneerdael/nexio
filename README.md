# Nexio

Nexio is an Android TV media player built around the Stremio addon ecosystem, a synced web account portal, and a playback stack tuned for premium TV devices.

## Highlights

- Android TV and Fire OS focused UX
- Account-based settings and addon sync through Supabase
- Web portal for managing settings, addons, catalogs, and TV QR login approval
- Native Trakt integration for auth, scrobble, check-in, and account-managed catalogs
- Native MDBList integration for ratings and list-backed catalogs
- TMDB metadata integration with shared account configuration
- Advanced Media3-based playback pipeline with Dolby Vision work and Fire OS compatibility improvements
- In-app updater backed by GitHub Releases

## Project Structure

- `app/`: Android TV application
- `nexio-web/`: web portal and landing site
- `supabase/`: schema, functions, and setup SQL
- `docs/`: setup guides and integration notes
- `media/`: local Media3 fork and playback libraries

## Android Development

Requirements:

- Android Studio
- JDK 11+
- Android SDK

Build:

```bash
./gradlew :app:assembleDebug
```

Install on a connected device:

```bash
./gradlew :app:installDebug
```

## Web Portal

The web portal lives in `nexio-web/` and is a Nuxt server app, not a static site.

Build:

```bash
cd nexio-web
npm install
npm run build
```

## Supabase

Clean-install schema and setup documentation:

- `supabase/account_settings_sync.sql`
- `supabase/tmdb_secret_upgrade_patch.sql`
- `docs/supabase-settings-sync-guide.md`

## Releases

App updates are resolved from GitHub Releases for this repository:

- `https://github.com/johnneerdael/nexio/releases`

## Legal

Nexio is a client application. It does not host or distribute media content. Media access depends on user-installed addons, services, and sources the user is authorized to use.
