# Account

This page explains how Nexio treats account access and why the web portal feels consistent across devices.

## What account mode means
- The portal is tied to your account, not to one TV.
- Sign-in state controls whether you are editing synced account data or just viewing local browser state.
- When you make a change in the portal and save it, that change is meant to follow the account everywhere it is used.

## Sign-in options
- Sign in with email and password.
- Create a new account if you are setting Nexio up for the first time.
- Use Google sign-in when your deployment supports it.

## What happens in the browser
- Your browser keeps the current session so you do not need to log in again every time.
- If you sign out, the portal returns to the sign-in flow and stops using that session.
- If you are not signed in, the portal can still show local browser data for exploration, but it is not the same as synced account state.

## Recommended account workflow
1. Confirm the email or identity shown after sign-in.
2. Review the current account settings before editing addons, catalogs, or formatter rules.
3. Make changes in one place at a time so you can tell what actually improved.
4. Recheck the Android app or portal preview after saving.

## Good things to verify
- You are editing the right account before making changes.
- The portal still opens after sign-in and sign-out.
- Shared settings are visible on every device that uses the same account.

## Related pages
- [Web Get Started](./get-started.md)
- [Security and Data](./security-and-data.md)
- [Integrations](./admin-workspaces/integrations.md)
