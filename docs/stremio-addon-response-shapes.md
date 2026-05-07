# Stremio Add-on Response Shapes

Tested on 2026-04-29 from the live add-on URLs supplied in the request.

## TMDB add-on

Manifest:

```text
https://tmdb.stremio.ru/N4IgNghgdg5grhGBTEAuESoFoCqBlEAGhAGcAXAJyQgFsBLWNAbQF1iBjCMiMAexhLNQdACZoQZGiIBGAOjK8ADkQkBPRSnQ1eANzopiUWppAAFJXEgUVJABa8A7gEkoACV41NlOEgC+hYTF0SRl5JRUydRMSJAp9QUNjcXNFSwhrYjtHF3dPNG8-AJBRcRC5VWoMtQ1xbT0DECM89ABNSpt7ZzcPLwoff0DSqXL24kia9Bi4pATGpNbR0k6cnvy+wsHg4dlIWARkCKja3X0VJpMAGWh4RAasrtze-qKSrdDdm4Oxo8nY+LP5iArntbh1st1mgUBsUghJtpRMCIGDBDhMQHVTolmiAACpUKBIxiZZYQp4bGFDUIIgnI1HRP4zAHYvGI2nE8GPNb9NggTjkADCvDgUDIaAArOyHABBZAAJS4yJcAHFMFRBMF1ipMBBpGAkDKkPKyLT0AAzHgxMHSuUK2AAdToZFsTmGRpNIHNYEtviAA/manifest.json
```

Manifest highlights:

- `id`: `tmdb-addon`
- `version`: `3.1.7`
- `resources`: `catalog`, `meta`
- `types`: `movie`, `series`
- `idPrefixes`: `tmdb:`
- active catalog entries: `tmdb.top`, `tmdb.year`, `tmdb.language`, `tmdb.trending`, `tmdb.search` for both `movie` and `series`

### Search: Project Hail Mary

Tested endpoints:

```text
/catalog/movie/tmdb.search/search=tt12042730.json
/catalog/movie/tmdb.search/search=Project%20Hail%20Mary.json
/catalog/movie/tmdb.search/search=tt12042730%20Project%20Hail%20Mary.json
```

Searching by IMDb ID only, or by `tt12042730 Project Hail Mary`, returned an empty result:

```json
{
  "query": "tt12042730",
  "metas": []
}
```

Searching by title returned one item:

```json
{
  "query": "Project Hail Mary",
  "metas": [
    {
      "id": "tmdb:687163",
      "name": "Project Hail Mary",
      "genre": [
        "Science Fiction",
        "Adventure"
      ],
      "poster": "https://image.tmdb.org/t/p/w500/yihdXomYb5kTeSivtFndMy5iDmf.jpg",
      "background": "https://image.tmdb.org/t/p/original/8Tfys3mDZVp4tNoH2ktm06a0Tau.jpg",
      "posterShape": "regular",
      "imdbRating": "8.2",
      "year": "2026",
      "type": "movie",
      "description": "Science teacher Ryland Grace wakes up on a spaceship light years from home with no recollection of who he is or how he got there. As his memory returns, he begins to uncover his mission: solve the riddle of the mysterious substance causing the sun to die out. He must call on his scientific knowledge and unorthodox ideas to save everything on Earth from extinction."
    }
  ]
}
```

Search item shape:

```json
{
  "query": "string",
  "metas": [
    {
      "id": "tmdb:<number>",
      "name": "string",
      "genre": ["string"],
      "poster": "string",
      "background": "string",
      "posterShape": "string",
      "imdbRating": "string",
      "year": "string",
      "type": "movie",
      "description": "string"
    }
  ]
}
```

### Catalog Rails

All tested non-search TMDB rails returned this root shape:

```json
{
  "metas": []
}
```

The default catalog pages returned 20 items each:

| Type | Catalog ID | Endpoint | Count | Extra support |
| --- | --- | --- | ---: | --- |
| movie | `tmdb.top` | `/catalog/movie/tmdb.top.json` | 20 | `genre`, `skip` |
| series | `tmdb.top` | `/catalog/series/tmdb.top.json` | 20 | `genre`, `skip` |
| movie | `tmdb.year` | `/catalog/movie/tmdb.year.json` | 20 | `genre`, `skip` |
| series | `tmdb.year` | `/catalog/series/tmdb.year.json` | 20 | `genre`, `skip` |
| movie | `tmdb.language` | `/catalog/movie/tmdb.language.json` | 20 | `genre`, `skip` |
| series | `tmdb.language` | `/catalog/series/tmdb.language.json` | 20 | `genre`, `skip` |
| movie | `tmdb.trending` | `/catalog/movie/tmdb.trending.json` | 20 | `genre`, `skip` |
| series | `tmdb.trending` | `/catalog/series/tmdb.trending.json` | 20 | `genre`, `skip` |

Extra path examples that returned the same item shape:

```text
/catalog/movie/tmdb.top/genre=Action.json
/catalog/movie/tmdb.top/skip=20.json
/catalog/movie/tmdb.year/genre=2025.json
/catalog/movie/tmdb.language/genre=English.json
/catalog/movie/tmdb.trending/genre=Week.json
```

Movie catalog row item shape:

```json
{
  "imdb_id": "tt<number>",
  "country": "string",
  "description": "string",
  "director": ["string"],
  "genre": ["string"],
  "imdbRating": "string",
  "name": "string",
  "released": "ISO-8601 string",
  "slug": "movie/<slug>",
  "type": "movie",
  "writer": ["string"],
  "year": "string",
  "trailers": [
    {
      "source": "string",
      "type": "string"
    }
  ],
  "background": "string",
  "poster": "string",
  "runtime": "string",
  "id": "tmdb:<number>",
  "genres": ["string"],
  "ageRating": null,
  "releaseInfo": "string",
  "trailerStreams": [
    {
      "title": "string",
      "ytId": "string"
    }
  ],
  "links": [
    {
      "name": "string",
      "category": "string",
      "url": "string"
    }
  ],
  "behaviorHints": {
    "defaultVideoId": "tt<number>",
    "hasScheduledVideos": false
  },
  "logo": "string",
  "app_extras": {
    "cast": [
      {
        "name": "string",
        "character": "string",
        "photo": "string or null"
      }
    ]
  }
}
```

Series catalog row item shape:

```json
{
  "country": "string",
  "description": "string",
  "genre": ["string"],
  "imdbRating": "string",
  "imdb_id": "tt<number>",
  "name": "string",
  "poster": "string",
  "released": "ISO-8601 string",
  "runtime": "string",
  "status": "string",
  "type": "series",
  "writer": ["string"],
  "year": "string",
  "background": "string",
  "slug": "series/<slug>",
  "id": "tmdb:<number>",
  "genres": ["string"],
  "ageRating": null,
  "releaseInfo": "string",
  "videos": [
    {
      "id": "tt<number>:<season>:<episode>",
      "name": "string",
      "season": 0,
      "number": 0,
      "episode": 0,
      "thumbnail": "string or null",
      "overview": "string",
      "description": "string",
      "rating": "string",
      "runtime": "string",
      "firstAired": "ISO-8601 string or null",
      "released": "ISO-8601 string or null"
    }
  ],
  "links": [
    {
      "name": "string",
      "category": "string",
      "url": "string"
    }
  ],
  "trailers": [
    {
      "source": "string",
      "type": "string"
    }
  ],
  "trailerStreams": [
    {
      "title": "string",
      "ytId": "string"
    }
  ],
  "behaviorHints": {
    "defaultVideoId": null,
    "hasScheduledVideos": true
  },
  "logo": "string",
  "app_extras": {
    "cast": [
      {
        "name": "string",
        "character": "string",
        "photo": "string or null"
      }
    ]
  }
}
```

## Top Streaming add-on

Manifest:

```text
https://top-streaming.stream/8e054798-89ab-42fa-a791-9881b268af5f/manifest.json
```

Manifest highlights:

- `id`: `uuid.8e054798.topstreaming.flixpatrol`
- `version`: `4.2.1`
- `resources`: `catalog`, `meta`
- `types`: `movie`, `series`
- `idPrefixes`: `tt`, `tmdb:`
- one catalog is declared:
  - `type`: `series`
  - `id`: `disney-overall-united-states`
  - `name`: `🏰 Disney+ - Top 10 United States - Movies`

### Catalog Rail

Tested endpoints:

```text
/catalog/series/disney-overall-united-states.json
/catalog/movie/disney-overall-united-states.json
```

Both routes returned HTTP 200 and identical content. The response contains 10 row items: 6 with `type: "movie"` and 4 with `type: "series"`. The route type and manifest catalog type should not be treated as the item type.

The Top Streaming payload has a stable core item shape plus optional ranking/source fields. In the tested page, these fields were not present on every item: `accessTracking`, `dataSource`, `flixpatrolRank`, `originalTitle`, `translatedFrom`, `popularRank`, `originalGlobalRank`, `popularPoints`, and `trend`.

Root shape:

```json
{
  "metas": []
}
```

Movie row item shape:

```json
{
  "id": "tt<number>",
  "type": "movie",
  "name": "string",
  "description": "string",
  "imdbRating": "string",
  "poster": "string",
  "logo": "string",
  "background": "string",
  "releaseInfo": "string",
  "runtime": "string",
  "links": [
    {
      "name": "string",
      "category": "string",
      "url": "string"
    }
  ],
  "behaviorHints": {
    "defaultVideoId": "tt<number>",
    "hasScheduledVideos": false
  },
  "accessTracking": "optional object",
  "flixpatrolRank": "optional number",
  "originalTitle": "optional string",
  "translatedFrom": "optional string",
  "dataSource": "optional string",
  "popularRank": "optional number",
  "originalGlobalRank": "optional number",
  "popularPoints": "optional number",
  "trend": "optional object"
}
```

Optional ranking/source fields, when present:

```json
{
  "accessTracking": {
    "lastAccessed": 0,
    "accessCount": 0,
    "firstAccessed": 0
  },
  "flixpatrolRank": 0,
  "originalTitle": "string",
  "translatedFrom": "string",
  "dataSource": "string",
  "popularRank": 0,
  "originalGlobalRank": 0,
  "popularPoints": 0,
  "trend": {
    "type": "string",
    "value": 0,
    "display": "string",
    "bgColor": "string"
  }
}
```

Series row item shape:

```json
{
  "id": "tt<number>",
  "type": "series",
  "name": "string",
  "description": "string",
  "imdbRating": "string",
  "poster": "string",
  "logo": "string",
  "background": "string",
  "releaseInfo": "string",
  "runtime": "string",
  "links": [
    {
      "name": "string",
      "category": "string",
      "url": "string"
    }
  ],
  "behaviorHints": {
    "defaultVideoId": null,
    "hasScheduledVideos": true
  },
  "videos": [
    {
      "id": "tt<number>:<season>:<episode>",
      "name": "string",
      "season": 0,
      "episode": 0,
      "number": 0,
      "thumbnail": "string or null",
      "overview": "string",
      "description": "string",
      "rating": "string or null",
      "firstAired": "ISO-8601 string",
      "released": "ISO-8601 string"
    }
  ],
  "accessTracking": "optional object",
  "flixpatrolRank": "optional number",
  "originalTitle": "optional string",
  "translatedFrom": "optional string",
  "dataSource": "optional string",
  "popularRank": "optional number",
  "originalGlobalRank": "optional number",
  "popularPoints": "optional number",
  "trend": "optional object"
}
```

Optional ranking/source fields, when present:

```json
{
  "accessTracking": {
    "lastAccessed": 0,
    "accessCount": 0,
    "firstAccessed": 0
  },
  "flixpatrolRank": 0,
  "originalTitle": "string",
  "translatedFrom": "string",
  "dataSource": "string",
  "popularRank": 0,
  "originalGlobalRank": 0,
  "popularPoints": 0,
  "trend": {
    "type": "string",
    "value": 0,
    "display": "string",
    "bgColor": "string"
  }
}
```
