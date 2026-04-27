# Metadata Execution Audit

> Smoke/debug artifact only. Use `metadata-execution-report.md` for production sign-off.

**Fixture:** `netflix_movie_nfx.json`
**Scenario:** `netflix-movie-detail-core`
**Verdict:** `PASS`
**Schema version:** `1`
**Git SHA:** `4d2d956ec`
**Git worktree:** `DIRTY` (1 changed, 3 untracked)

## Summary
| Metric | Value |
|---|---:|
| Items | 1 |
| Routed items | 1 |
| Network calls | 1 |
| Cache hits | 0 |
| Cache misses | 1 |
| Forbidden overwrites | 0 |
| Policy violations | 0 |

## tt16431404 / movie

### First paint
- Source: `ADDON_META_PREVIEW`
- Router executed: `false`
- Network executed: `false`

### Routing
| Field | Value |
|---|---|
| Parent ID | `tt16431404` |
| Provider | `TMDB` |
| Media kind | `MOVIE` |
| Reason | `ITEM_TYPE_MOVIE` |
| Pre-resolution identity required | `false` |
| Execution identity resolved | `true` |

### Provider plan
| Step | Provider | API shape | Work class | Cache policy |
|---|---|---|---|---|
| `primary_core-0` | `TMDB` | `tmdb.movie.core` | `USER_VISIBLE` | `provider-metadata` |

### Cache decisions
| Provider | API shape | Decision | TTL | Stale |
|---|---|---|---:|---:|
| `TMDB` | `tmdb.movie.core` | `MISS_THEN_NETWORK` | `604800000` | `2592000000` |

### Final fields
| Field | Source provider | Role | Value | Ownership rule | Rejected candidates |
|---|---|---|---|---|---|
| `canonical_id` | `TMDB` | `PRIMARY` | `tt16431404` | `canonical_id owned by PRIMARY` | `` |
| `title` | `TMDB` | `PRIMARY` | `Runtime TMDB title` | `title owned by PRIMARY` | `` |
| `poster` | `TMDB` | `PRIMARY` | `https://example.test/tmdb-poster.jpg` | `poster owned by PRIMARY` | `` |

