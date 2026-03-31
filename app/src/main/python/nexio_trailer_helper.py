import json
from urllib.parse import parse_qs, urlparse

import yt_dlp


def resolve_youtube_playback(
    youtube_url,
    cookie_header,
    js_runtime_name,
    js_runtime_path,
    user_agent,
    accept_language,
    origin,
    referer,
):
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "remote_components": ["ejs:github"],
    }

    headers = {}
    if user_agent:
        headers["User-Agent"] = user_agent
    if accept_language:
        headers["Accept-Language"] = accept_language
    if origin:
        headers["Origin"] = origin
    if referer:
        headers["Referer"] = referer
    if cookie_header:
        headers["Cookie"] = cookie_header
    if headers:
        ydl_opts["http_headers"] = headers

    if js_runtime_name and js_runtime_path:
        ydl_opts["js_runtimes"] = {js_runtime_name: {"path": js_runtime_path}}

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(youtube_url, download=False)

    payload = _playback_payload_from_info(info)
    return json.dumps(payload)


def _playback_payload_from_info(info):
    requested_formats = info.get("requested_formats") or []
    if requested_formats:
        video_url = None
        audio_url = None
        for fmt in requested_formats:
            fmt_url = fmt.get("url")
            if not fmt_url:
                continue
            vcodec = fmt.get("vcodec")
            acodec = fmt.get("acodec")
            if vcodec and vcodec != "none" and not video_url:
                video_url = fmt_url
                if acodec and acodec != "none":
                    audio_url = None
                    break
            elif acodec and acodec != "none" and vcodec == "none" and not audio_url:
                audio_url = fmt_url

        video_url = video_url or requested_formats[0].get("url")
        if not video_url:
            raise ValueError("yt-dlp returned requested formats without a playable video URL")
        return {
            "videoUrl": video_url,
            "audioUrl": audio_url,
            "expiresAtEpochMs": _derive_expiry_ms(video_url) or _derive_expiry_ms(audio_url),
        }

    video_url = info.get("url")
    if not video_url:
        raise ValueError("yt-dlp returned no direct playback URL")

    return {
        "videoUrl": video_url,
        "audioUrl": None,
        "expiresAtEpochMs": _derive_expiry_ms(video_url),
    }


def _derive_expiry_ms(url):
    if not url:
        return None
    query = parse_qs(urlparse(url).query)
    raw_expire = (query.get("expire") or [None])[0]
    if raw_expire is None:
        return None
    try:
        return int(raw_expire) * 1000
    except (TypeError, ValueError):
        return None
