# Project Context

## Purpose
Nexio is an Android TV media client with account-scoped settings and addon sync backed by Supabase. The web portal and Android app share the account configuration contract.

## Relevant Constraints
- Keep account-config sync backward compatible across contract versions when possible.
- Treat Supabase SQL as the source of truth for sync payload and secret allowlists.
- Keep contract-scaffolding changes isolated from runtime feature work unless the runtime hook is required for compilation.
- Preserve focused tests around sync payload construction, routing, and serialization.
