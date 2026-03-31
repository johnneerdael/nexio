# First-run Sync and Cache

## What this helps with
This page explains why Nexio can look incomplete on the first run and why the same account usually feels faster and fuller on later launches.

## How first-run loading works
The TV app loads in phases. It signs in, reads account state, pulls in catalog and integration data, hydrates metadata, and then writes the results into the disk-backed cache. Those steps do not always finish at the same time.

That means Home can fill in over time. A row that is missing at launch may appear a little later once sync and hydration catch up.

## Why the cache matters
The disk-backed cache lets Nexio restore previously fetched rows and metadata before network refresh finishes. On later runs, that usually means:
- Home becomes useful sooner.
- Known rows and artwork are restored instead of rebuilt from scratch.
- Sync can refine the existing view instead of starting from an empty screen.

## Normal symptoms
- Home starts small and grows as sync completes.
- Artwork or metadata appears after the row is already visible.
- Trakt-backed content shows up later than local or already-cached content.
- A refresh or restart makes the same account look more complete once the cache has warmed.

## Symptoms that usually mean misconfiguration
- Home stays empty after plenty of time for sync to run.
- Trakt never appears even though the account is signed in.
- Catalog rows do not show up after you already prepared them on the website.
- The same error repeats every launch instead of settling as sync completes.

## Related guides
- [Recommended Setup](./recommended-setup.md)
- [Account and Sign In](./account-and-sign-in.md)
- [Security and Data](./security-and-data.md)
- [Home and Continue Watching](/watch/home-and-continue-watching)
- [Troubleshooting](/troubleshooting/)
