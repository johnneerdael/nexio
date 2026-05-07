# Portal Management Hero Design

Date: 2026-03-22

## Goal

Unify the top-of-page management headers in the Nexio web portal so Addons, Catalogs, Integrations, and Formatter all follow the same visual design principles as the current formatter hero, while allowing each page to present different contextual actions or stats.

This pass also removes web-side formatter enablement controls, because formatter activation is owned by the Android TV app. The web app should always allow editing the formatter configuration regardless of whether uniform formatting is currently enabled on a device.

## Current Problems

- The formatter page has the strongest header design, but it includes controls that imply the web controls whether the formatter is enabled.
- There is too much empty space above the formatter hero, caused by stacked shell and page padding.
- Addons, Catalogs, and Integrations use different top-of-page header treatments, so the management workspace feels inconsistent.
- The formatter header still includes a manual sync CTA, even though formatter changes now auto-sync immediately.

## Desired Outcome

- All four management pages use one shared hero system with the same shell, spacing, gradient treatment, typography rhythm, and responsive structure.
- The content inside each hero can vary by page.
- Formatter has no enable toggle and no sync button.
- Top spacing is tightened so the hero sits intentionally below the top nav instead of floating in excessive empty space.

## Recommended Approach

Create a shared `PortalManagementHero` component and migrate all four pages to it.

This is preferable to copying styles across each page because:

- it keeps the management workspace visually consistent
- it makes future visual changes low-risk and centralized
- it allows page-specific right-side content without duplicating layout code

## Component Design

### Shared Hero Shell

The shared hero component should provide:

- an eyebrow label
- a large headline
- a supporting description
- a two-column responsive layout
- a left content area for page identity
- an optional right content area for actions or stats
- the same glass/gradient/rounded styling used by the formatter page today

The right area is optional but follows the same base proportions and alignment whenever present.

### Page-Specific Content

#### Formatter

- Eyebrow remains personalization-oriented.
- Title remains stream formatter.
- Description stays focused on AIO-style formatter configuration.
- Remove the web enable toggle.
- Remove the sync button.
- The header becomes informational only.

#### Addons

- Reuse the shared hero shell.
- Move the “Install from URL” action into the right-side hero area.
- Keep the addon list and search below the hero.

#### Catalogs

- Reuse the shared hero shell.
- Show summary metrics in the right-side hero area.
- Metrics should at least include total catalogs and visible catalogs.

#### Integrations

- Reuse the shared hero shell.
- Show an `Add Integration` action in the right-side hero area.

## Layout and Spacing

The wasted space above the formatter header should be fixed in two places:

1. Reduce the main content top padding in `PortalShell.vue`.
2. Reduce or remove extra top padding added by page-level wrappers where the hero is the first element.

The result should keep healthy breathing room under the fixed top nav but avoid the current “floating too low” look.

## Behavior

### Formatter

- The web formatter page no longer exposes any enable/disable state.
- Formatter selection and custom template editing remain available at all times.
- Changes continue to auto-sync immediately.
- Any copy that implies manual syncing or web-owned enablement should be removed or rewritten.

### Other Pages

- Existing page behavior should remain unchanged aside from header placement or moving existing controls into the hero area.
- This is a presentation and layout consolidation pass, not a workflow rewrite.

## Implementation Notes

- Introduce a reusable hero component under `nexio-web/components/portal/`.
- Prefer slots or narrowly scoped props for right-side content so each page can inject its own controls or metrics.
- Keep the existing formatter visual language as the source style reference.
- Avoid changing list, form, or modal behavior outside of what is necessary to move header controls into the new hero area.

## Testing

Verify:

- Formatter no longer shows an enable toggle or manual sync button.
- Formatter still allows editing and auto-sync behavior remains intact.
- Addons hero shows install-from-URL controls correctly on desktop and mobile.
- Catalogs hero shows total and visible catalog metrics.
- Integrations hero shows an add-integration action.
- Top spacing is visibly reduced without causing overlap with the fixed nav.
- All four pages remain responsive and visually consistent.

## Risks

- Moving controls into hero areas can accidentally change spacing or alignment on narrow screens.
- Reducing shell padding globally can affect other portal views if they rely on the old vertical rhythm.

These risks are manageable if the hero system is introduced first and the spacing changes are kept small and validated across the affected pages.
