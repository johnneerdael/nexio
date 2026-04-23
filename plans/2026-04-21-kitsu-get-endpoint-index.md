# Kitsu GET Endpoint Index

Internal engineering reference derived from `kitsu.apib` in this repo.

## Current auth model from the blueprint

- The auth introduction says authentication is not required for most public-facing `GET` endpoints.
- The same section says NSFW/R18 content is hidden for unauthenticated requests and for accounts with NSFW disabled.
- Endpoint-level access control in this document comes from each resource section's `Authorisation` table when present.
- `Public, auth-enhanced` means unauthenticated reads are allowed, but auth changes what data is visible.
- `Public? (no local auth table)` means the local section does not declare GET access; treat those routes as verify-before-shipping.

## Immediate Nexio takeaways

- Nexio currently uses only `GET /anime/{id}` and `GET /anime/{id}/episodes` for anime enrichment. Both are documented as public GET routes.
- There is a much larger public metadata surface available now: mappings, relationships, franchises, installments, characters, staff, productions, streamers, and streaming links are all documented as public GET resources.
- The clearest auth-enhanced metadata route is `GET /library-entries` and `GET /library-entries/{id}`. Those are public for non-private entries, but auth unlocks private-owner visibility and adult-content behavior.
- The clearest auth-required user-value routes for future work are `GET /linked-accounts`, `GET /list-imports`, and user-account moderation / group inbox style routes. These are not needed for base metadata enrichment.
- `includeNsfw` makes sense only as an auth-side enhancement. The current blueprint does not suggest a separate NSFW query flag; the visibility change comes from the authenticated account context.

## Recommended expansion order

1. Stay on public GET metadata first: anime details, episodes, mappings, relationships, franchises, installments, staff, characters, productions, and streaming links.
2. Add public social/discovery data only if the product needs it: trending anime, reviews, reactions, favorites, follows, comments, posts, and stats.
3. Add auth-enhanced reads next: library entries for user watch state / shelves / private entry awareness.
4. Add auth-required account/job routes later only if Nexio plans true Kitsu account features such as import monitoring or linked-account introspection.

## High-value public metadata routes

| GET path | Why it matters for Nexio |
|---|---|
| `/anime` | Search/list anime records if we ever need Kitsu-native discovery. |
| `/anime/{id}` | Core anime title enrichment. |
| `/episodes` | Direct episode lookup surface. |
| `/episodes/{id}` | Episode detail lookup. |
| `/mappings` | Cross-ID linking across providers. |
| `/mappings/{id}` | Read a concrete mapping record. |
| `/media-relationships` | Sequels, prequels, alternates, side stories. |
| `/media-relationships/{id}` | Relationship detail lookup. |
| `/franchises` | Series/franchise grouping. |
| `/franchises/{id}` | Franchise detail lookup. |
| `/installments` | Installment ordering within a franchise. |
| `/installments/{id}` | Installment detail lookup. |
| `/categories` | Genre/category metadata. |
| `/categories/{id}` | Category detail lookup. |
| `/streaming-links` | Per-title external streaming destinations. |
| `/streaming-links/{id}` | Streaming link detail lookup. |
| `/anime-characters` | Character-role associations for anime. |
| `/anime-characters/{id}` | Anime-character relation detail. |
| `/characters` | Character metadata. |
| `/characters/{id}` | Character detail lookup. |
| `/anime-productions` | Studios / production entities tied to anime. |
| `/anime-productions/{id}` | Production record detail. |
| `/anime-staff` | Staff credits per anime. |
| `/anime-staff/{id}` | Anime-staff relation detail. |
| `/castings` | Voice/actor cast associations. |
| `/castings/{id}` | Casting detail lookup. |
| `/people` | People / staff / cast entities. |
| `/people/{id}` | Person detail lookup. |
| `/trending/anime` | Public discovery / trending rails. |

## Public social/discovery routes available now

| GET path | Potential use |
|---|---|
| `/favorites` | List favorites. Useful only if Nexio wants community context beyond core metadata. |
| `/favorites/{id}` | Read favorites. Useful only if Nexio wants community context beyond core metadata. |
| `/follows` | List follows. Useful only if Nexio wants community context beyond core metadata. |
| `/follows/{id}` | Read follows. Useful only if Nexio wants community context beyond core metadata. |
| `/reviews` | List reviews. Useful only if Nexio wants community context beyond core metadata. |
| `/reviews/{id}` | Read reviews. Useful only if Nexio wants community context beyond core metadata. |
| `/posts` | List posts. Useful only if Nexio wants community context beyond core metadata. |
| `/posts/{id}` | Read posts. Useful only if Nexio wants community context beyond core metadata. |
| `/comments` | List comments. Useful only if Nexio wants community context beyond core metadata. |
| `/comments/{id}` | Read comments. Useful only if Nexio wants community context beyond core metadata. |
| `/stats` | List stats. Useful only if Nexio wants community context beyond core metadata. |
| `/stats/{id}` | Read stats. Useful only if Nexio wants community context beyond core metadata. |
| `/media-reactions` | List media reactions. Useful only if Nexio wants community context beyond core metadata. |
| `/media-reactions/{id}` | Read media reactions. Useful only if Nexio wants community context beyond core metadata. |

## Routes that become more valuable after auth

| GET path | Access | Why auth matters |
|---|---|---|
| `/library-entries` | Public, auth-enhanced | Auth changes visibility: private entries and adult-content behavior become user-context aware. |
| `/library-entries/{id}` | Public, auth-enhanced | Same as collection, but for a concrete user library item. |
| `/linked-accounts` | Auth required | Pure account introspection; only useful once Nexio offers Kitsu-account features. |
| `/linked-accounts/{id}` | Auth required | Concrete linked-account inspection. |
| `/list-imports` | Auth required | Import-job status surface for authenticated users. |
| `/list-imports/{id}` | Auth required | Single import-job status lookup. |

## Candidate public catalog queries to verify next

These are the most plausible public query shapes for catalog-style anime rows based on the generic JSON:API sorting rules plus the anime fields exposed in the blueprint. They are not all explicitly guaranteed by `kitsu.apib`, so they should be treated as live-verification candidates rather than shipped assumptions.

| Catalog idea | Candidate public query | Confidence | Why |
|---|---|---|---|
| Trending anime | `/trending/anime` | High | Explicit dedicated public endpoint in the blueprint. |
| Highest rated anime | `/anime?sort=ratingRank` | Medium | `ratingRank` is exposed on anime objects and generic sorting is documented. |
| Highest rated anime | `/anime?sort=-averageRating` | Medium | `averageRating` is exposed on anime objects and generic sorting is documented. |
| Most popular anime | `/anime?sort=popularityRank` | Medium | `popularityRank` is exposed on anime objects and generic sorting is documented. |
| Most popular anime | `/anime?sort=-userCount,-favoritesCount` | Medium | `userCount` and `favoritesCount` are exposed on anime objects and generic sorting is documented. |
| Top upcoming anime | `/anime?filter[status]=upcoming&sort=ratingRank` | Low | `status` exists on anime objects, but anime-specific status filtering is not explicitly documented. |
| Top upcoming anime | `/anime?filter[status]=upcoming&sort=popularityRank` | Low | Same caveat: likely shape, but filter support needs live confirmation. |
| Top airing anime | `/anime?filter[status]=current&sort=ratingRank` | Low | `current` is a documented anime status value, but not a documented anime filter. |
| Top airing anime | `/anime?filter[status]=current&sort=popularityRank` | Low | Same caveat: candidate only until verified live. |

### Recommended live verification order

1. Verify `/trending/anime` as the baseline discovery rail.
2. Verify whether `/anime?sort=ratingRank` and `/anime?sort=popularityRank` return stable ordered collections.
3. Verify whether `/anime?sort=-averageRating` and `/anime?sort=-userCount,-favoritesCount` behave better than rank-based sorts.
4. Verify whether `filter[status]=upcoming` and `filter[status]=current` are accepted on `/anime` before planning upcoming / airing rails.

## Full documented GET appendix

- Total documented GET actions in `kitsu.apib`: 126
- Public: 98
- Public, auth-enhanced: 2
- Auth required: 22
- Public? (no local auth table): 2
- Closed to non-admin: 2

| Access | GET path | Group | Resource | Function | Notes |
|---|---|---|---|---|---|
| Public | `/anime` | Anime | Anime | List anime | Unauthenticated GET is allowed in the resource table. |
| Public | `/anime/{id}` | Anime | Anime | Read anime | Unauthenticated GET is allowed in the resource table. |
| Public | `/episodes` | Anime | Episodes | List episodes | Unauthenticated GET is allowed in the resource table. |
| Public | `/episodes/{id}` | Anime | Episodes | Read episodes | Unauthenticated GET is allowed in the resource table. |
| Public | `/trending/anime` | Anime | Trending Anime | List trending anime | Unauthenticated GET is allowed in the resource table. |
| Public | `/manga` | Manga | Manga | List manga | Unauthenticated GET is allowed in the resource table. |
| Public | `/manga/{id}` | Manga | Manga | Read manga | Unauthenticated GET is allowed in the resource table. |
| Public | `/chapters` | Manga | Chapters | List chapters | Unauthenticated GET is allowed in the resource table. |
| Public | `/chapters/{id}` | Manga | Chapters | Read chapters | Unauthenticated GET is allowed in the resource table. |
| Public | `/trending/manga` | Manga | Trending Manga | List trending manga | Unauthenticated GET is allowed in the resource table. |
| Public | `/categories` | Categories | Categories | List categories | Unauthenticated GET is allowed in the resource table. |
| Public | `/categories/{id}` | Categories | Categories | Read categories | Unauthenticated GET is allowed in the resource table. |
| Public | `/category-favorites` | Categories | Category Favorites | List category favorites | Unauthenticated GET is allowed in the resource table. |
| Public | `/category-favorites/{id}` | Categories | Category Favorites | Read category favorites | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-relationships` | Media Relations | Media Relationships | List media relationships | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-relationships/{id}` | Media Relations | Media Relationships | Read media relationships | Unauthenticated GET is allowed in the resource table. |
| Public | `/mappings` | Media Relations | Mappings | List mappings | Unauthenticated GET is allowed in the resource table. |
| Public | `/mappings/{id}` | Media Relations | Mappings | Read mappings | Unauthenticated GET is allowed in the resource table. |
| Public | `/franchises` | Media Relations | Franchises | List franchises | Unauthenticated GET is allowed in the resource table. |
| Public | `/franchises/{id}` | Media Relations | Franchises | Read franchises | Unauthenticated GET is allowed in the resource table. |
| Public | `/installments` | Media Relations | Installments | List installments | Unauthenticated GET is allowed in the resource table. |
| Public | `/installments/{id}` | Media Relations | Installments | Read installments | Unauthenticated GET is allowed in the resource table. |
| Public? (no local auth table) | `/media-follows` | Media Follows | Media Follows | List media follows | Blueprint omits a local auth table for this resource. |
| Public? (no local auth table) | `/media-follows/{id}` | Media Follows | Media Follows | Read media follows | Blueprint omits a local auth table for this resource. |
| Public | `/media-attributes` | Media Follows | Media Attributes | List media attributes | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-attributes/{id}` | Media Follows | Media Attributes | Read media attributes | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-attribute-votes` | Media Follows | Media Attribute Votes | List media attribute votes | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-attribute-votes/{id}` | Media Follows | Media Attribute Votes | Read media attribute votes | Unauthenticated GET is allowed in the resource table. |
| Public | `/streamers` | Streamers | Streamers | List streamers | Unauthenticated GET is allowed in the resource table. |
| Public | `/streamers/{id}` | Streamers | Streamers | Read streamers | Unauthenticated GET is allowed in the resource table. |
| Public | `/streaming-links` | Streamers | Streaming Links | List streaming links | Unauthenticated GET is allowed in the resource table. |
| Public | `/streaming-links/{id}` | Streamers | Streaming Links | Read streaming links | Unauthenticated GET is allowed in the resource table. |
| Auth required | `/blocks` | Users | Blocks | List blocks | User-specific safety/moderation surface. |
| Auth required | `/blocks/{id}` | Users | Blocks | Read blocks | User-specific safety/moderation surface. |
| Public | `/favorites` | Users | Favorites | List favorites | Unauthenticated GET is allowed in the resource table. |
| Public | `/favorites/{id}` | Users | Favorites | Read favorites | Unauthenticated GET is allowed in the resource table. |
| Public | `/follows` | Users | Follows | List follows | Unauthenticated GET is allowed in the resource table. |
| Public | `/follows/{id}` | Users | Follows | Read follows | Unauthenticated GET is allowed in the resource table. |
| Auth required | `/linked-accounts` | Users | Linked Accounts | List linked accounts | Directly user-account-scoped. |
| Auth required | `/linked-accounts/{id}` | Users | Linked Accounts | Read linked accounts | Directly user-account-scoped. |
| Public | `/profile-link-sites` | Users | Profile Link Sites | List profile link sites | Unauthenticated GET is allowed in the resource table. |
| Public | `/profile-link-sites/{id}` | Users | Profile Link Sites | Read profile link sites | Unauthenticated GET is allowed in the resource table. |
| Public | `/profile-links` | Users | Profile Links | List profile links | Unauthenticated GET is allowed in the resource table. |
| Public | `/profile-links/{id}` | Users | Profile Links | Read profile links | Unauthenticated GET is allowed in the resource table. |
| Public | `/roles` | Users | Roles | List roles | Unauthenticated GET is allowed in the resource table. |
| Public | `/roles/{id}` | Users | Roles | Read roles | Unauthenticated GET is allowed in the resource table. |
| Public | `/stats` | Users | Stats | List stats | Unauthenticated GET is allowed in the resource table. |
| Public | `/stats/{id}` | Users | Stats | Read stats | Unauthenticated GET is allowed in the resource table. |
| Public | `/user-roles` | Users | User Roles | List user roles | Unauthenticated GET is allowed in the resource table. |
| Public | `/user-roles/{id}` | Users | User Roles | Read user roles | Unauthenticated GET is allowed in the resource table. |
| Public | `/users` | Users | Users | List users | Unauthenticated GET is allowed in the resource table. |
| Public | `/users/{id}` | Users | Users | Read users | Unauthenticated GET is allowed in the resource table. |
| Public, auth-enhanced | `/library-entries` | User Libraries | Library Entries | List library entries | Private entries stay hidden unless the caller is the owning user. Adult content is auth- and settings-sensitive. |
| Public, auth-enhanced | `/library-entries/{id}` | User Libraries | Library Entries | Read library entries | Private entries stay hidden unless the caller is the owning user. Adult content is auth- and settings-sensitive. |
| Public | `/library-entry-logs` | User Libraries | Library Entry Logs | List library entry logs | Unauthenticated GET is allowed in the resource table. |
| Public | `/library-entry-logs/{id}` | User Libraries | Library Entry Logs | Read library entry logs | Unauthenticated GET is allowed in the resource table. |
| Public | `/library-events` | User Libraries | Library Events | List library events | Unauthenticated GET is allowed in the resource table. |
| Public | `/library-events/{id}` | User Libraries | Library Events | Read library events | Unauthenticated GET is allowed in the resource table. |
| Auth required | `/list-imports` | User Libraries | List Imports | List list imports | Tracks import jobs for a user account. |
| Auth required | `/list-imports/{id}` | User Libraries | List Imports | Read list imports | Tracks import jobs for a user account. |
| Public | `/media-reaction-votes` | Reactions | Media Reaction Votes | List media reaction votes | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-reaction-votes/{id}` | Reactions | Media Reaction Votes | Read media reaction votes | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-reactions` | Reactions | Media Reactions | List media reactions | Unauthenticated GET is allowed in the resource table. |
| Public | `/media-reactions/{id}` | Reactions | Media Reactions | Read media reactions | Unauthenticated GET is allowed in the resource table. |
| Public | `/review-likes` | Reactions | Review Likes | List review likes | Unauthenticated GET is allowed in the resource table. |
| Public | `/review-likes/{id}` | Reactions | Review Likes | Read review likes | Unauthenticated GET is allowed in the resource table. |
| Public | `/reviews` | Reactions | Reviews | List reviews | Unauthenticated GET is allowed in the resource table. |
| Public | `/reviews/{id}` | Reactions | Reviews | Read reviews | Unauthenticated GET is allowed in the resource table. |
| Public | `/posts` | Posts | Posts | List posts | Unauthenticated GET is allowed in the resource table. |
| Public | `/posts/{id}` | Posts | Posts | Read posts | Unauthenticated GET is allowed in the resource table. |
| Public | `/post-likes` | Posts | Post Likes | List post likes | Unauthenticated GET is allowed in the resource table. |
| Public | `/post-likes/{id}` | Posts | Post Likes | Read post likes | Unauthenticated GET is allowed in the resource table. |
| Public | `/post-follows` | Posts | Post Follows | List post follows | Unauthenticated GET is allowed in the resource table. |
| Public | `/post-follows/{id}` | Posts | Post Follows | Read post follows | Unauthenticated GET is allowed in the resource table. |
| Public | `/comments` | Comments | Comments | List comments | Unauthenticated GET is allowed in the resource table. |
| Public | `/comments/{id}` | Comments | Comments | Read comments | Unauthenticated GET is allowed in the resource table. |
| Public | `/comment-likes` | Comments | Comment Likes | List comment likes | Unauthenticated GET is allowed in the resource table. |
| Public | `/comment-likes/{id}` | Comments | Comment Likes | Read comment likes | Unauthenticated GET is allowed in the resource table. |
| Public | `/anime-characters` | Characters | Anime Characters | List anime characters | Unauthenticated GET is allowed in the resource table. |
| Public | `/anime-characters/{id}` | Characters | Anime Characters | Read anime characters | Unauthenticated GET is allowed in the resource table. |
| Public | `/manga-characters` | Characters | Manga Characters | List manga characters | Unauthenticated GET is allowed in the resource table. |
| Public | `/manga-characters/{id}` | Characters | Manga Characters | Read manga characters | Unauthenticated GET is allowed in the resource table. |
| Public | `/characters` | Characters | Characters | List characters | Unauthenticated GET is allowed in the resource table. |
| Public | `/characters/{id}` | Characters | Characters | Read characters | Unauthenticated GET is allowed in the resource table. |
| Public | `/anime-productions` | Producers & Staff | Anime Productions | List anime productions | Unauthenticated GET is allowed in the resource table. |
| Public | `/anime-productions/{id}` | Producers & Staff | Anime Productions | Read anime productions | Unauthenticated GET is allowed in the resource table. |
| Public | `/anime-staff` | Producers & Staff | Anime Staff | List anime staff | Unauthenticated GET is allowed in the resource table. |
| Public | `/anime-staff/{id}` | Producers & Staff | Anime Staff | Read anime staff | Unauthenticated GET is allowed in the resource table. |
| Public | `/manga-staff` | Producers & Staff | Manga Staff | List manga staff | Unauthenticated GET is allowed in the resource table. |
| Public | `/manga-staff/{id}` | Producers & Staff | Manga Staff | Read manga staff | Unauthenticated GET is allowed in the resource table. |
| Public | `/producers` | Producers & Staff | Producers | List producers | Unauthenticated GET is allowed in the resource table. |
| Public | `/producers/{id}` | Producers & Staff | Producers | Read producers | Unauthenticated GET is allowed in the resource table. |
| Public | `/people` | Producers & Staff | People | List people | Unauthenticated GET is allowed in the resource table. |
| Public | `/people/{id}` | Producers & Staff | People | Read people | Unauthenticated GET is allowed in the resource table. |
| Public | `/castings` | Producers & Staff | Castings | List castings | Unauthenticated GET is allowed in the resource table. |
| Public | `/castings/{id}` | Producers & Staff | Castings | Read castings | Unauthenticated GET is allowed in the resource table. |
| Public | `/groups` | Groups | Groups | List groups | Unauthenticated GET is allowed in the resource table. |
| Public | `/groups/{id}` | Groups | Groups | Read groups | Unauthenticated GET is allowed in the resource table. |
| Auth required | `/group-action-logs` | Groups | Group Action Logs | List group action logs | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-action-logs/{id}` | Groups | Group Action Logs | Read group action logs | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-bans` | Groups | Group Bans | List group bans | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-bans/{id}` | Groups | Group Bans | Read group bans | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Public | `/group-categories` | Groups | Group Categories | List group categories | Unauthenticated GET is allowed in the resource table. |
| Public | `/group-categories/{id}` | Groups | Group Categories | Read group categories | Unauthenticated GET is allowed in the resource table. |
| Auth required | `/group-invites` | Groups | Group Invites | List group invites | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-invites/{id}` | Groups | Group Invites | Read group invites | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-member-notes` | Groups | Group Member Notes | List group member notes | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-member-notes/{id}` | Groups | Group Member Notes | Read group member notes | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Public | `/group-members` | Groups | Group Members | List group members | Unauthenticated GET is allowed in the resource table. |
| Public | `/group-members/{id}` | Groups | Group Members | Read group members | Unauthenticated GET is allowed in the resource table. |
| Public | `/group-neighbors` | Groups | Group Neighbors | List group neighbors | Unauthenticated GET is allowed in the resource table. |
| Public | `/group-neighbors/{id}` | Groups | Group Neighbors | Read group neighbors | Unauthenticated GET is allowed in the resource table. |
| Public | `/group-permissions` | Groups | Group Permissions | List group permissions | Unauthenticated GET is allowed in the resource table. |
| Public | `/group-permissions/{id}` | Groups | Group Permissions | Read group permissions | Unauthenticated GET is allowed in the resource table. |
| Auth required | `/group-reports` | Groups | Group Reports | List group reports | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-reports/{id}` | Groups | Group Reports | Read group reports | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-ticket-messages` | Groups | Group Ticket Messages | List group ticket messages | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-ticket-messages/{id}` | Groups | Group Ticket Messages | Read group ticket messages | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-tickets` | Groups | Group Tickets | List group tickets | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/group-tickets/{id}` | Groups | Group Tickets | Read group tickets | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/leader-chat-messages` | Groups | Leader Chat Messages | List leader chat messages | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Auth required | `/leader-chat-messages/{id}` | Groups | Leader Chat Messages | Read leader chat messages | Unauthenticated GET is denied, but authenticated user GET is allowed. |
| Closed to non-admin | `/reports` | Reports | Reports | List reports | Moderation/admin surface only. |
| Closed to non-admin | `/reports/{id}` | Reports | Reports | Read reports | Moderation/admin surface only. |
| Public | `/site-announcements` | Site Announcements | Site Announcements | List site announcements | Unauthenticated GET is allowed in the resource table. |
| Public | `/site-announcements/{id}` | Site Announcements | Site Announcements | Read site announcements | Unauthenticated GET is allowed in the resource table. |

## Gaps and cautions

- This index is derived from the checked-in `kitsu.apib`, not from live probing against `kitsu.io`.
- `GET /media-follows` and `GET /media-follows/{id}` do not include a local `Authorisation` table in the blueprint, so their public-read status should be verified before using them in product code.
- The blueprint makes it clear that auth can change visibility even on public GET routes, especially for NSFW/R18 content and user-private library data.
- If Nexio adds authenticated Kitsu functionality later, start with `library-entries` before more account-specific or moderation-specific routes.
