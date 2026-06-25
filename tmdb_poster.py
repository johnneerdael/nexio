#!/usr/bin/env python3
"""Download a TMDB poster (Dutch preferred, English fallback) for an IMDB ID.

Usage:
    python3 tmdb_poster.py tt0043274 [--out DIR] [--lang nl] [--fallback en]

Reads TMDB_API_KEY / TMDB_API_URL from local.properties (same dir as this script).
"""
import argparse
import os
import sys
import urllib.parse
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def load_properties(path):
    props = {}
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            props[key.strip()] = value.strip()
    return props


def http_get_json(url):
    req = urllib.request.Request(url, headers={
        "Accept": "application/json",
        "User-Agent": "nexio-tmdb-poster/1.0",
    })
    with urllib.request.urlopen(req, timeout=30) as resp:
        import json
        return json.load(resp)


def http_download(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "nexio-tmdb-poster/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp, open(dest, "wb") as out:
        out.write(resp.read())


def find_by_imdb(base, api_key, imdb_id):
    """Resolve an IMDB id to a TMDB object via /find. Returns (media_type, tmdb_id)."""
    q = urllib.parse.urlencode({"api_key": api_key, "external_source": "imdb_id"})
    url = f"{base}find/{imdb_id}?{q}"
    data = http_get_json(url)
    for key, media_type in (
        ("movie_results", "movie"),
        ("tv_results", "tv"),
        ("tv_episode_results", "tv"),
    ):
        results = data.get(key) or []
        if results:
            return media_type, results[0]["id"]
    return None, None


def get_image_base_url(base, api_key):
    cfg = http_get_json(f"{base}configuration?api_key={api_key}")
    images = cfg.get("images", {})
    secure = images.get("secure_base_url") or "https://image.tmdb.org/t/p/"
    return secure


def fetch_posters(base, api_key, media_type, tmdb_id):
    """Return the posters list. We pass NO include_image_language so TMDB returns
    all languages, then we pick the language ourselves."""
    url = f"{base}{media_type}/{tmdb_id}/images?api_key={api_key}"
    data = http_get_json(url)
    return data.get("posters") or []


def pick_poster(posters, lang, fallback):
    """Pick the highest-voted poster for lang, else fallback, else any."""
    def best(candidates):
        if not candidates:
            return None
        return max(candidates, key=lambda p: (p.get("vote_average", 0), p.get("vote_count", 0)))

    by_lang = [p for p in posters if p.get("iso_639_1") == lang]
    chosen = best(by_lang)
    if chosen:
        return chosen, lang
    by_fallback = [p for p in posters if p.get("iso_639_1") == fallback]
    chosen = best(by_fallback)
    if chosen:
        return chosen, fallback
    # last resort: language-neutral (null) or anything available
    neutral = [p for p in posters if p.get("iso_639_1") in (None, "")]
    chosen = best(neutral) or best(posters)
    if chosen:
        return chosen, chosen.get("iso_639_1") or "und"
    return None, None


def main():
    parser = argparse.ArgumentParser(description="Download TMDB poster for an IMDB ID.")
    parser.add_argument("imdb_id", help="IMDB ID, e.g. tt0043274")
    parser.add_argument("--out", default=SCRIPT_DIR, help="Output directory (default: script dir)")
    parser.add_argument("--lang", default="nl", help="Preferred poster language (default: nl)")
    parser.add_argument("--fallback", default="en", help="Fallback poster language (default: en)")
    parser.add_argument("--size", default="original", help="TMDB image size (default: original)")
    args = parser.parse_args()

    props = load_properties(os.path.join(SCRIPT_DIR, "local.properties"))
    api_key = props.get("TMDB_API_KEY")
    base = props.get("TMDB_API_URL", "https://api.themoviedb.org/3/")
    if not base.endswith("/"):
        base += "/"
    if not api_key:
        sys.exit("TMDB_API_KEY not found in local.properties")

    print(f"Resolving {args.imdb_id} via TMDB /find ...")
    media_type, tmdb_id = find_by_imdb(base, api_key, args.imdb_id)
    if not tmdb_id:
        sys.exit(f"No TMDB match found for {args.imdb_id}")
    print(f"  -> {media_type} #{tmdb_id}")

    posters = fetch_posters(base, api_key, media_type, tmdb_id)
    print(f"Found {len(posters)} poster(s). "
          f"Languages: {sorted({p.get('iso_639_1') or 'null' for p in posters})}")

    poster, used_lang = pick_poster(posters, args.lang, args.fallback)
    if not poster:
        sys.exit("No posters available at all for this title.")

    if used_lang == args.lang:
        print(f"Selected {args.lang.upper()} poster.")
    elif used_lang == args.fallback:
        print(f"No {args.lang.upper()} poster; falling back to {args.fallback.upper()}.")
    else:
        print(f"No {args.lang.upper()}/{args.fallback.upper()} poster; using '{used_lang}'.")

    image_base = get_image_base_url(base, api_key)
    file_path = poster["file_path"]
    image_url = f"{image_base}{args.size}{file_path}"

    os.makedirs(args.out, exist_ok=True)
    ext = os.path.splitext(file_path)[1] or ".jpg"
    dest = os.path.join(args.out, f"{args.imdb_id}_{used_lang}{ext}")
    print(f"Downloading {image_url}")
    http_download(image_url, dest)
    size_kb = os.path.getsize(dest) / 1024
    print(f"Saved -> {dest} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()
