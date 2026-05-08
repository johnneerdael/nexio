# 2026-05-08 TV Artwork and Premium Poster Regression RCA

## Scope

This is root cause analysis only. The branch-level raw evidence was removed from
the repository; the retained verification artifact is the sanitized postfix
summary with counts and booleans only.

History exposure note:

Current HEAD no longer tracks the raw TV artwork capture artifacts. A separate
Git history check still found that prior reachable branch history and the
remote-tracking main history contain this raw evidence class from earlier
commits. This task intentionally does not rewrite repository history; a separate
repository-history purge is required if privacy cleanup must remove those
historical objects.

User-reported symptoms:

- TV show posters, backdrops, and logos were still broken after recent TVDB artwork changes.
- Premium poster behavior regressed: premium decisions existed, but cards could show blank, non-premium, or wrong-shaped artwork.
- Some poster slots appeared to use backdrop-shaped artwork.
- Built-in TV rows were more visibly affected than movie rows because first paint often lacked complete artwork.
- The regression affected TV content broadly and could appear on other content types when the same projection path was used.

## Sanitized Evidence Basis

The evidence packet was reduced to the sanitized postfix summary:

- `review-dossier/2026-05-08-tv-artwork-display-projection-postfix-summary.json`

That summary contains only counts and booleans. It confirms that:

- Device verification steps completed.
- Home snapshot, hydrated overlay, artwork decisions, asset records, and logcat were captured before sanitization.
- Durable poster references existed in both snapshot and overlay data.
- Provider-tag mismatch counts were zero in the sanitized run after the fixes under review.
- Wrong-slot counts were zero in the sanitized postfix run after the fixes under review.
- Raw premium URL counts were zero for the retained display-state inputs summarized by the postfix report. Removed catalog/cache raw evidence remains represented by the history exposure and purge-required fields.

No raw titles, raw content IDs, raw device identifiers, raw URLs, or raw capture paths are required to understand the architecture failure.

## Executive Verdict

Primary root cause:

Hydrated artwork overlays were being created, but the resolved Home display path still built artwork and rating from first-paint preview fields. Overlay fields were not merged before producing resolved display items.

Secondary root cause:

Premium poster selection and premium poster materialization were not treated as the same invariant. A premium decision could persist without a corresponding premium asset, and fallback materialization could produce a non-premium asset under a premium decision context.

Tertiary root cause:

Portrait poster card selection allowed backdrop-shaped artwork as a fallback when poster data was absent or unmaterialized, turning missing poster data into wrong-shaped poster display.

This was not primarily a TVDB fetching issue. The sanitized packet showed downstream display projection and materialization were the blockers.

## Finding 1: Hydrated Overlay Fields Existed But Were Not Applied To Resolved Display Items

Hydration produced overlay data for affected TV rows, including artwork fields that should have been projected into the resolved display model.

The resolved Home mapper path looked up the overlay but continued to build display artwork from first-paint fields. That means:

- Rows with complete first-paint artwork could look partially correct.
- Rows with sparse first-paint preview data could render blank or incomplete cards.
- Hydrated logos and backdrops could exist in overlay state but not reach Home or downstream display surfaces.

The separate overlay-to-display conversion path used overlay fields, but the normal row projection path did not consistently merge them before artwork and rating projection.

Why this matched the visible symptoms:

- Movie rows often had usable first-paint poster or backdrop data.
- TV rows often depended on hydration to fill missing artwork.
- Ignoring the overlay disproportionately affected TV rows and screensaver candidates sourced from the same resolved display model.

Confidence: high.

## Finding 2: Premium Decisions Were Persisted More Broadly Than Premium Assets

The sanitized summary shows a large decision count and a much smaller asset-record count. That shape is expected for a cache, but it also shows why rendering could depend on on-demand materialization.

The regression class was:

- Premium provider decision wins in routing.
- Durable decision reference is stored in display/cache state.
- The corresponding materialized premium asset may be missing at render time.
- The UI path must either materialize the asset reliably or preserve explicit fallback state.

Without that invariant, a card can hold a premium decision reference yet still fail to render the premium poster.

Confidence: high.

## Finding 3: Fallback Materialization Could Break The Premium Asset Invariant

The asset repository attempted selected materialization first, then fallback candidates. The fallback path copied the original decision context while swapping only the selected candidate.

That allowed this class of state:

- Decision context still represented premium selection.
- Materialized asset could come from a non-premium fallback provider.
- Downstream traces or provider tags could imply premium, while visible artwork came from fallback.

The important invariant is:

Premium decision reference must not silently resolve to a non-premium asset unless the fallback state is explicit and projected consistently.

Confidence: high.

## Finding 4: Poster Slots Could Use Backdrops As Normal Fallbacks

The poster card selection path allowed poster mode to fall back to backdrop-shaped artwork when a poster was absent or unavailable.

That makes missing poster data visible as a wrong-shaped poster rather than as a controlled missing-artwork state. The problem is amplified when:

- First paint lacks poster data.
- Overlay projection is not applied.
- Premium poster materialization is delayed or fails.
- Backdrop assets are available earlier than poster assets.

Confidence: medium-high. Code proved the fallback rule; per-card rendered-source tracing would be required to prove each visible instance.

## Finding 5: TVDB Fetching Was Not The Main Blocker

The provider-side TVDB work was not the primary failure point for this packet. The architecture-level evidence indicated that downstream display projection and materialization were the main blockers:

- Hydration could produce overlay artwork.
- Resolved display mapping could ignore that hydrated artwork.
- Asset materialization could lag or fall back under the wrong durable context.
- Card selection could display the wrong artwork shape after poster failure.

Therefore, additional TVDB fetch changes alone would not resolve the observed Home and screensaver symptoms.

Confidence: high.

## Combined Failure Chain

Sparse first paint path:

1. A TV row starts with stable identity and limited preview fields.
2. Hydration produces overlay artwork.
3. Resolved display projection does not merge overlay fields.
4. Display item still has missing artwork.
5. Card or downstream surface renders blank, placeholder, or stale preview artwork.

Premium poster path:

1. Premium decision wins in the shared artwork pipeline.
2. Durable decision reference is persisted.
3. Premium asset is not materialized yet or fails materialization.
4. Fallback may materialize a non-premium asset under the premium decision context.
5. UI can show a non-premium or blank poster while metadata still suggests premium.

Wrong-shaped poster path:

1. Poster slot requests poster-shaped artwork.
2. Poster is absent or unavailable.
3. Card selection falls back to backdrop-shaped artwork.
4. Landscape artwork appears in a portrait poster slot.

## Rating Side Note

The reported zero-rating symptom is a separate rating-quality issue, not the artwork root cause.

Range validation alone does not reject a zero rating because zero can be a valid numeric value. Fixing that requires source-aware rating semantics, not an artwork pipeline fix.

## Diagnostic Follow-Up

The next useful traces should be sanitized at source and should emit only counts, booleans, hashes, or bounded enums that are not copied into committed artifacts.

Useful trace points:

- Whether first-paint poster, backdrop, and logo were present.
- Whether overlay poster, backdrop, and logo were present.
- Whether overlay fields were merged into resolved display projection.
- Whether selected decision provider matched materialized asset provider.
- Whether fallback materialization occurred.
- Whether poster mode selected poster-shaped or backdrop-shaped artwork.

## Root Cause Classification

Category:

Shared display projection regression.

Blast radius:

Home rows, resolved display surfaces, screensaver candidates, TV rows, and premium artwork rows.

Broken invariants:

- Hydrated overlay fields must be applied before display projection.
- Premium decision references must not silently resolve to non-premium assets without explicit fallback state.
- Portrait poster slots must not use backdrops as normal poster fallback.
- Provider tags must be derived from durable artwork references rather than stale preview metadata.
- Raw premium URLs must not be persisted in legacy cache paths.

## RCA Conclusion

The provider-side TVDB work appears to have repaired part of the upstream artwork pipeline. The regression was downstream:

1. Resolved display projection looked up overlays but continued using first-paint fields for artwork and rating.
2. Premium decision references could persist when premium assets were not materialized.
3. Fallback materialization could bind a non-premium asset to a premium decision context.
4. Poster card selection could fall back to backdrops when poster materialization failed.
5. Legacy cache paths had to be guarded against raw premium URL persistence as a class of issue.

That combination explains why rows with richer first-paint data looked better, why sparse TV rows rendered poorly, why hydrated logos did not appear reliably, why premium posters appeared lost, and why backdrop-shaped artwork could appear in poster slots.
