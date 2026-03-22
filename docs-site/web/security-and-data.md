# Security and Data

Nexio’s web portal is designed around authenticated account access and separate handling for visible settings versus sensitive service credentials.

## What to know
- Administrative changes should be made while signed in.
- Account settings are meant to stay with the account, not in a shared note or one-off device configuration.
- Service secrets are managed separately from the normal account settings you see in the portal.
- If you sign in on a shared device, remember to sign out when you are done.

## Safe operating habits
1. Confirm the account identity before changing anything sensitive.
2. Change one area at a time: addons, catalogs, integrations, then formatter.
3. Save and verify the result before moving to the next layer.
4. Keep credentials out of chat logs, tickets, and documentation.
5. Refresh or replace a secret from the portal when a service stops working instead of reusing an old value elsewhere.

## What this protects
- Your account state stays consistent across devices.
- Secrets are not mixed into the visible layout and catalog choices.
- It is easier to tell whether a change came from an account update or from a local browser session.

## Common mistakes to avoid
- Editing the wrong account after sign-in.
- Leaving a shared browser signed in after administration work.
- Assuming a working saved browser session is the same as a synced account state.

## Related pages
- [Account](./account.md)
- [Integrations](./admin-workspaces/integrations.md)
- [Deployment](../dev/deployment.md)
