Yes — the last two API-driven provider contracts should be:

```text
MDBList = ratings + list/catalog resolver
TheIntroDB = playback skip-segment resolver
```

Your current audit already shows **MDBList** is runtime-covered for ratings, raw-list URLs, and key validation, while `mdblist.user` is planned-not-active; **TheIntroDB** is runtime-covered for `GET /media` with `CacheFirst`. 

## MDBList — blueprint-validated contract

| Area                        | Contract                                                                                                                  |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Role                        | Secondary resolver: ratings, list/catalog rails, discovery rows                                                           |
| Primary metadata authority? | No                                                                                                                        |
| Base URL                    | `https://api.mdblist.com`                                                                                                 |
| Auth                        | API key as `apikey` query param for current app; OAuth exists but should be out of scope for now                          |
| Header policy               | `mdblist-api-key-v1`                                                                                                      |
| Credential location         | Query/path, never raw cache key                                                                                           |
| Runtime status              | Active runtime-covered for ratings, raw-list fetches, and key validation                                                  |
| Cache posture               | Ratings = `CacheFirst`; raw list/catalog URLs = currently observe-only, but should become `CacheFirst` once canonicalized |
| 429                         | Capture `Retry-After`; provider + credential scoped cooldown                                                              |

### MDBList matrix

| Shape                        | Blueprint endpoint                                                      | Purpose                            | Runtime status                                          | Cache policy                                             | Key inputs                                                       |
| ---------------------------- | ----------------------------------------------------------------------- | ---------------------------------- | ------------------------------------------------------- | -------------------------------------------------------- | ---------------------------------------------------------------- |
| `mdblist.key_validation`     | `GET /user?apikey=...`                                                  | Validate key / user info           | Covered                                                 | Disabled                                                 | `credentialHash`                                                 |
| `mdblist.user`               | `GET /user?apikey=...`                                                  | User profile / account check       | Planned                                                 | Disabled or short `CacheFirst`                           | `credentialHash`                                                 |
| `mdblist.rating.batch`       | `POST /rating/{media_type}/{return_rating}?apikey=...`                  | Batch title ratings                | Covered                                                 | `CacheFirst`                                             | media type, IMDb IDs/query, rating provider set, credential hash |
| `mdblist.episode_ratings`    | same rating family / season helper                                      | Episode-season ratings             | Covered through current adapter                         | `CacheFirst`                                             | series ID namespace, season, provider set, credential hash       |
| `mdblist.raw_url.list`       | `GET {remoteUrl}` / list endpoints                                      | MDBList discovery/custom list rows | Covered                                                 | Move to `CacheFirst` once URL canonicalization is stable | canonical URL, pagination, filters, credential hash              |
| `mdblist.list.items`         | `/lists/{listid}/items?...` or `/lists/{username}/{listname}/items?...` | Explicit list item page            | Should be first-class instead of only raw URL long-term | `CacheFirst`                                             | list id/name, limit, offset, filters, sort                       |
| `mdblist.catalog.movie/show` | `/catalog/movie`, `/catalog/show`                                       | Catalog/discovery rails            | Planned                                                 | `CacheFirst`                                             | filters, sort, cursor, credential hash                           |

MDBList’s blueprint documents API-key authentication via the `apikey` query parameter, plus list endpoints, catalog endpoints, and `POST /rating/{media_type}/{return_rating}`. 

## MDBList corrections

The main improvement is to stop treating all list/catalog rails as opaque `raw_url.list` forever. Keep the raw-url adapter for compatibility, but add typed shapes for:

```text
mdblist.list.items
mdblist.list.changes
mdblist.catalog.movie
mdblist.catalog.show
```

That will make caching and invalidation much easier to audit.

---

## TheIntroDB — blueprint-validated contract

| Area                        | Contract                                                                              |
| --------------------------- | ------------------------------------------------------------------------------------- |
| Role                        | Secondary playback resolver for intro/recap/credits/preview skip intervals            |
| Primary metadata authority? | No                                                                                    |
| Base URL                    | `https://api.theintrodb.org/v2`                                                       |
| Runtime status              | Active runtime-covered for `theintrodb.media`                                         |
| Auth                        | Optional bearer API key for `/media`; required bearer API key for `/submit`           |
| Header policy               | `introdb-json-optional-bearer-v1`                                                     |
| Cache posture               | `CacheFirst` for `/media`; mutation/no-cache for `/submit`                            |
| Work class                  | `PLAYBACK_RESOLUTION` for `/media`; `MUTATION_OUTBOX` or `USER_VISIBLE` for `/submit` |
| 429                         | Capture rate-limit and usage-limit headers                                            |

### TheIntroDB matrix

| Shape                            | Blueprint endpoint       | Purpose                                            | Runtime status                                 | Cache policy                       | Key inputs                                                                         |
| -------------------------------- | ------------------------ | -------------------------------------------------- | ---------------------------------------------- | ---------------------------------- | ---------------------------------------------------------------------------------- |
| `theintrodb.media`               | `GET /media`             | Fetch intro/recap/credits/preview timestamps       | Covered                                        | `CacheFirst`                       | `tmdb_id` preferred, optional `imdb_id`, type, season, episode, credential profile |
| `theintrodb.submit`              | `POST /submit`           | Submit segment timestamps                          | Planned                                        | Mutation / no metadata cache       | tmdb_id, type, segment, season/episode, start/end                                  |
| `theintrodb.authenticated_media` | `GET /media` with bearer | Include requester’s pending + accepted submissions | Covered by same endpoint if optional auth used | Cache must vary by credential hash | same as media + credentialHash                                                     |

The blueprint says `/media` supports movie lookup with `tmdb_id`, TV lookup with `tmdb_id + season + episode`, optional `imdb_id`, and optional `Authorization: Bearer <api_key>`; TMDB ID is preferred because IMDb lookup may add latency and mapping uncertainty. 

TheIntroDB also has strict limits: `/media` is 30 requests per 10 seconds, unauthenticated `/media` is 100/day, authenticated `/media` is 500/day, and 429 responses expose `X-RateLimit-*` and `X-UsageLimit-*` headers. 

## TheIntroDB corrections

Your current header policy is good, but the cache key should explicitly vary by auth mode:

```text
theintrodb:media:public:tmdb:{id}:s:{season}:e:{episode}
theintrodb:media:auth:{credentialHash}:tmdb:{id}:s:{season}:e:{episode}
```

Reason: authenticated `/media` can include the user’s pending submissions and weighted results, so it is not equivalent to public `/media`.

## Final recommendation

Keep both as secondary modules:

```text
RatingRouter
  └── MDBList

SkipSegmentRouter
  └── TheIntroDB
```

Neither should be part of primary metadata ownership. Both should be fully IntegrationRuntime-governed, with typed shapes replacing generic raw URL use over time.
