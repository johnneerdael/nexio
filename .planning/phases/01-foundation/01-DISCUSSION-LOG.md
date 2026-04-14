# Phase 1: Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 01-foundation
**Areas discussed:** Serialization choice, UserProfile model shape, First-launch migration

---

## Serialization Choice

| Option | Description | Selected |
|--------|-------------|----------|
| Gson (Recommended) | Already used across Nexio codebase. Keeps consistency. | ✓ |
| Moshi | Matches NuvioTV reference exactly. Already on classpath. | |
| kotlinx.serialization | Modern Kotlin-native approach. New pattern in codebase. | |
| You decide | Claude picks based on codebase fit. | |

**User's choice:** Gson (Recommended)
**Notes:** Consistency with existing Nexio patterns prioritized over exact NuvioTV match.

---

## UserProfile Model Shape

| Option | Description | Selected |
|--------|-------------|----------|
| avatarId + pinEnabled | avatarId for Supabase avatar catalog. pinEnabled as local reflection of server PIN state. Skip usesPrimaryPlugins. | ✓ |
| avatarId only | Keep PIN state purely server-side. Don't store pinEnabled locally. | |
| avatarId + pinEnabled + avatarUrl | Add resolved URL field for avatar image. | |

**User's choice:** avatarId + pinEnabled
**Notes:** usesPrimaryPlugins explicitly excluded — no plugin concept in Nexio.

---

## First-Launch Migration

| Option | Description | Selected |
|--------|-------------|----------|
| Silent migration (Recommended) | Auto-create Profile 1 with name 'Default'. No UI prompt. Zero friction. | ✓ |
| Welcome prompt | One-time 'Profiles are now available' message. Let user name profile. | |
| Fully transparent | No migration — Profile 1 implicit. ProfileDataStore initializes with default on first read. | |

**User's choice:** Silent migration (Recommended)
**Notes:** Existing users should never notice the profile system exists until they create a second profile.

---

## Claude's Discretion

- Default avatar color cycling for new profiles
- Internal ProfileJson DTO naming conventions
- Error handling for corrupted profile JSON

## Deferred Ideas

None
