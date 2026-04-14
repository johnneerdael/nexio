# Phase 5: nexio-web Integration - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 05-nexio-web-integration
**Areas discussed:** Scope boundary, Sync mechanism, Web auth flow, Photo upload

---

## Scope Boundary

| Option | Description | Selected |
|--------|-------------|----------|
| Android + Supabase only | Build Supabase RPCs/tables plus Android sync layer. nexio-web frontend separate. | |
| Full stack | Includes nexio-web frontend, Supabase backend, and Android sync layer end-to-end. | ✓ |
| Android sync only | Only Android-side code to pull changes from Supabase. | |

**User's choice:** Full stack
**Notes:** None

| Option | Description | Selected |
|--------|-------------|----------|
| Separate repo | nexio-web as its own repo | |
| Monorepo subfolder | /web directory inside Android repo | |
| Existing project | nexio-web already exists somewhere | ✓ |

**User's choice:** nexio-web repo in the ~/Scripts/nexio repo
**Notes:** nexio-web already exists at `nexio-web/` as a Nuxt.js project with existing pages, components, and design system.

| Option | Description | Selected |
|--------|-------------|----------|
| Next.js (Recommended) | React-based, SSR/SSG, strong Supabase integration | |
| Nuxt.js | Vue-based, similar SSR capabilities | ✓ |
| SvelteKit | Svelte-based, lightweight | |

**User's choice:** Nuxt.js
**Notes:** Already in use by existing nexio-web project.

---

## Sync Mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Next-launch sync (Recommended) | TV app pulls from Supabase on app launch and profile switch | ✓ |
| Realtime subscriptions | Supabase Realtime pushes changes immediately | |
| Periodic polling | TV app polls every N minutes | |

**User's choice:** Next-launch sync
**Notes:** Matches existing sync pattern. Simple and reliable.

| Option | Description | Selected |
|--------|-------------|----------|
| Last-write wins | Most recent change by timestamp wins | ✓ |
| Web always wins | Web changes always overwrite TV-side | |
| TV always wins | TV is source of truth | |

**User's choice:** Last-write wins
**Notes:** None

| Option | Description | Selected |
|--------|-------------|----------|
| Per-profile RPCs (Recommended) | Independent push/pull per profile ID | ✓ |
| Extend v7 contract | Add profile_id to existing v7 push/pull | |

**User's choice:** Per-profile RPCs
**Notes:** Consistent with Phase 4 SYNC-02 decision.

| Option | Description | Selected |
|--------|-------------|----------|
| Direct apply | Non-default profiles self-manage without master approval | ✓ |
| Master approval | All web changes require master account approval | |
| Tiered | CRUD requires master, settings self-service | |

**User's choice:** Direct apply
**Notes:** Master only controls profile CRUD.

| Option | Description | Selected |
|--------|-------------|----------|
| Master login + profile select | Master logs in, selects profile to manage | ✓ |
| Per-profile web login | Each profile gets own web credentials | |
| Shared link with PIN | Master shares link, profiles enter PIN | |

**User's choice:** Master login + profile select
**Notes:** Non-default profiles don't have their own web login.

| Option | Description | Selected |
|--------|-------------|----------|
| Dashboard | All profiles in grid/list overview | ✓ |
| Single profile | Profile selector dropdown, one at a time | |
| You decide | Claude's discretion | |

**User's choice:** Dashboard
**Notes:** Click into a profile to manage its settings.

| Option | Description | Selected |
|--------|-------------|----------|
| Minimal functional | Clean utility-focused UI | |
| Branded | Nexio branding, dark theme matching TV app | |
| You decide | Claude's discretion | |

**User's choice:** Same theme/branding as currently used in nexio-web
**Notes:** Keep existing "Obsidian Lens" design system from DESIGN.md.

---

## Web Auth Flow

| Option | Description | Selected |
|--------|-------------|----------|
| Web OAuth redirect (Recommended) | OAuth in browser, tokens in Supabase, TV pulls on sync | |
| TV-initiated code flow | Web generates code, TV does OAuth | |
| Hybrid | Both web and TV can independently do OAuth | ✓ |

**User's choice:** Hybrid
**Notes:** Both paths result in tokens in Supabase, TV syncs from there.

| Option | Description | Selected |
|--------|-------------|----------|
| Supabase is source of truth | TV always pulls from Supabase | |
| Most recent wins | Compare timestamps, latest auth wins | ✓ |
| TV is source of truth | On-device tokens authoritative | |

**User's choice:** Most recent wins
**Notes:** Consistent with last-write-wins sync decision.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, full revoke | Delete tokens AND call provider revoke endpoint | ✓ |
| No, just delete tokens | Remove from Supabase only | |
| You decide | Claude's discretion | |

**User's choice:** Yes, full revoke
**Notes:** Clean break on unlink.

| Option | Description | Selected |
|--------|-------------|----------|
| Extend AuthPanel | Add profile context to existing component | ✓ |
| New component | Create ProfileAuthPanel.vue | |
| You decide | Claude's discretion | |

**User's choice:** Extend AuthPanel
**Notes:** Reuse existing Trakt/Simkl link/unlink UI with profile selector.

---

## Photo Upload

| Option | Description | Selected |
|--------|-------------|----------|
| Supabase Storage (Recommended) | Public bucket, serve via URL, add Storage plugin | ✓ |
| Base64 in profile row | Store as base64 in SupabaseProfile table | |
| External CDN | Upload to Cloudflare R2, S3, etc. | |

**User's choice:** Supabase Storage
**Notes:** Requires adding Storage plugin to Android SupabaseModule.

| Option | Description | Selected |
|--------|-------------|----------|
| Public bucket | Anyone with URL can view, long random UUIDs | ✓ |
| Signed URLs | Short-lived signed URLs, more secure | |
| You decide | Claude's discretion | |

**User's choice:** Public bucket
**Notes:** Simple, no token needed for TV app to fetch.

| Option | Description | Selected |
|--------|-------------|----------|
| Photo with color fallback (Recommended) | Photo when available, ProfileAvatarColors circle otherwise | ✓ |
| Photo replaces color | Once uploaded, color avatar is gone | |
| You decide | Claude's discretion | |

**User's choice:** Photo with color fallback
**Notes:** Graceful degradation.

| Option | Description | Selected |
|--------|-------------|----------|
| Resize server-side | Resize on edge function/web server before storage | ✓ |
| Client-side resize | Resize in browser before upload | |
| Accept as-is | Store whatever user uploads | |
| You decide | Claude's discretion | |

**User's choice:** Resize server-side
**Notes:** Saves bandwidth for TV app.

| Option | Description | Selected |
|--------|-------------|----------|
| Cache with URL-based invalidation | Cache locally, re-fetch when URL changes in sync payload | ✓ |
| Always fetch | No local caching | |
| You decide | Claude's discretion | |

**User's choice:** Cache with URL-based invalidation
**Notes:** Efficient, URL change triggers re-fetch.

---

## Claude's Discretion

- Dashboard grid vs list layout for profile overview
- Exact Supabase Storage bucket naming and path structure
- Server-side resize implementation (edge function vs web server middleware)
- Coil vs Glide for TV-side image loading
- Order of implementation plans
- Per-profile RPC naming conventions
- Catalog ordering data model in Supabase

## Deferred Ideas

None — discussion stayed within phase scope
