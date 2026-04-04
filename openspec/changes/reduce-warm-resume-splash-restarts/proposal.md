# Change: Reduce warm-resume splash restarts

Nexio currently behaves too much like a cold boot when users return from Home, Recents, wake/sleep flows, HDMI/input switching, or other foreground re-entry paths. Two user-visible problems follow:

- the startup splash can replay even when the process is still alive
- startup-only deferred work can run again on warm resumes, creating unnecessary "cold boot" behavior

This change tightens startup behavior so warm process-alive resumes can bypass splash when critical state is already ready, while keeping genuine cold boots safe.

## What changes

- Classify launches as cold process starts vs warm process resumes.
- Skip the app-owned startup splash when the process is still alive and critical bootstrap state is already ready.
- Stop replaying startup-only deferred work on warm resumes.
- Reuse the existing task/activity more aggressively for launcher-style relaunches.
- Add observability for cold vs warm launch decisions.

## Why

- Returning to the app from Home should feel like a resume, not a reboot.
- Users should not see branding/splash friction when the app is already alive in memory.
- Startup-only sync and cache work should not be re-triggered on every foreground return.

## Scope

- Android / Android TV activity startup and resume behavior
- launcher/Home returns
- Recents returns
- wake/sleep and input-switch style foreground returns
- warm process relaunch handling

## Non-goals

- Guaranteeing process survival under OS memory pressure
- Re-architecting Home caching or Trakt startup behavior broadly
- Redesigning the splash asset itself
