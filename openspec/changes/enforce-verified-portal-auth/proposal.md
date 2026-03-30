# Change: Enforce verified portal auth and password recovery

## Why
Nexio web currently allows email/password signup without requiring email verification, does not expose password recovery, and does not let Google-authenticated users add a password for alternate sign-in.

## What Changes
- Require verified email before an email/password signup can access the portal.
- Add portal routes and API endpoints for verification resend, password reset, and email confirmation completion.
- Allow authenticated users, including Google-authenticated accounts, to set or change a password from the portal.

## Impact
- Affected app: `nexio-web`
- Affected config: `supabase/config.toml`
- Affected specs: `portal-auth`
