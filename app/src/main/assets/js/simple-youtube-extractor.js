const RuntimeURL =
  (typeof globalThis !== 'undefined' && typeof globalThis.URL !== 'undefined')
    ? globalThis.URL
    : (typeof require === 'function' ? require('url').URL : null);

const RuntimeFetch =
  (typeof globalThis !== 'undefined' && typeof globalThis.fetch === 'function')
    ? globalThis.fetch.bind(globalThis)
    : (typeof require === 'function' ? require('node-fetch') : null);

const DEFAULT_HEADERS = {
  'accept-language': 'en-US,en;q=0.9',
  'user-agent':
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_2_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36',
};

const CLIENTS = [
  {
    key: 'ios',
    id: '5',
    version: '20.10.1',
    userAgent: 'com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)',
    context: {
      clientName: 'IOS',
      clientVersion: '20.10.1',
      deviceModel: 'iPhone16,2',
      osName: 'iPhone',
      osVersion: '17.4.0.21E219',
      platform: 'MOBILE',
      hl: 'en',
      gl: 'US',
    },
    priority: 0,
  },
  {
    key: 'android_vr',
    id: '28',
    version: '1.56.21',
    userAgent:
      'com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip',
    context: {
      clientName: 'ANDROID_VR',
      clientVersion: '1.56.21',
      deviceMake: 'Oculus',
      deviceModel: 'Quest 3',
      osName: 'Android',
      osVersion: '12',
      platform: 'MOBILE',
      androidSdkVersion: 32,
      hl: 'en',
      gl: 'US',
    },
    priority: 1,
  },
  {
    key: 'android',
    id: '3',
    version: '20.10.35',
    userAgent: 'com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip',
    context: {
      clientName: 'ANDROID',
      clientVersion: '20.10.35',
      osName: 'Android',
      osVersion: '14',
      platform: 'MOBILE',
      androidSdkVersion: 34,
      hl: 'en',
      gl: 'US',
    },
    priority: 2,
  },
];

function extractVideoId(input) {
  if (!input || typeof input !== 'string') return null;
  if (/^[a-zA-Z0-9_-]{11}$/.test(input)) return input;
  if (!RuntimeURL) return null;

  try {
    const parsed = new RuntimeURL(input);
    if (parsed.hostname === 'youtu.be') {
      const id = parsed.pathname.split('/').filter(Boolean)[0];
      return /^[a-zA-Z0-9_-]{11}$/.test(id || '') ? id : null;
    }
    const fromQuery = parsed.searchParams.get('v');
    if (/^[a-zA-Z0-9_-]{11}$/.test(fromQuery || '')) return fromQuery;
    const embedMatch = parsed.pathname.match(/\/(?:embed|shorts|live)\/([a-zA-Z0-9_-]{11})/);
    if (embedMatch) return embedMatch[1];
  } catch {
    return null;
  }

  return null;
}

function getWatchConfig(html) {
  const apiKey = html.match(/"INNERTUBE_API_KEY":"([^"]+)"/)?.[1] || null;
  const visitorData = html.match(/"VISITOR_DATA":"([^"]+)"/)?.[1] || null;
  const clientVersion = html.match(/"INNERTUBE_CLIENT_VERSION":"([^"]+)"/)?.[1] || null;
  return { apiKey, visitorData, clientVersion };
}

function hasNParam(urlStr) {
  if (!RuntimeURL) return false;
  try {
    return new RuntimeURL(urlStr).searchParams.has('n');
  } catch {
    return false;
  }
}

function parseQualityLabel(label) {
  if (!label) return 0;
  const m = String(label).match(/(\d{2,4})p/);
  return m ? Number(m[1]) : 0;
}

function getCodecFlags(mimeType) {
  const mt = String(mimeType || '');
  return {
    video: mt.includes('video/'),
    audio: mt.includes('audio/'),
  };
}

function videoScore(fmt) {
  const height = Number(fmt.height || parseQualityLabel(fmt.qualityLabel) || 0);
  const fps = Number(fmt.fps || 0);
  const bitrate = Number(fmt.bitrate || fmt.averageBitrate || 0);
  return height * 1e9 + fps * 1e6 + bitrate;
}

function audioScore(fmt) {
  const bitrate = Number(fmt.bitrate || fmt.averageBitrate || 0);
  const asr = Number(fmt.audioSampleRate || 0);
  return bitrate * 1e6 + asr;
}

function sortCandidates(items) {
  return [...items].sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    const aHasN = a.hasN ? 1 : 0;
    const bHasN = b.hasN ? 1 : 0;
    if (aHasN !== bHasN) return aHasN - bHasN;
    return (a.priority ?? 99) - (b.priority ?? 99);
  });
}

function pickBestForClient(items, clientKey) {
  const sameClient = items.filter((x) => x.client === clientKey);
  if (sameClient.length > 0) return sortCandidates(sameClient)[0] || null;
  return sortCandidates(items)[0] || null;
}

function absolutizeUrl(baseUrl, maybeRelative) {
  if (!RuntimeURL) return maybeRelative;
  try {
    return new RuntimeURL(maybeRelative, baseUrl).toString();
  } catch {
    return maybeRelative;
  }
}

function parseHlsAttributeList(line) {
  const idx = line.indexOf(':');
  if (idx === -1) return {};
  const raw = line.slice(idx + 1);
  const out = {};
  let key = '';
  let val = '';
  let inKey = true;
  let inQuote = false;

  for (let i = 0; i < raw.length; i += 1) {
    const ch = raw[i];
    if (inKey) {
      if (ch === '=') {
        inKey = false;
      } else {
        key += ch;
      }
      continue;
    }

    if (ch === '"') {
      inQuote = !inQuote;
      continue;
    }
    if (ch === ',' && !inQuote) {
      out[key.trim()] = val.trim();
      key = '';
      val = '';
      inKey = true;
      continue;
    }
    val += ch;
  }
  if (key.trim()) out[key.trim()] = val.trim();
  return out;
}

async function parseHlsManifest(manifestUrl, fetchImpl) {
  const resp = await fetchImpl(manifestUrl, { headers: DEFAULT_HEADERS });
  if (!resp.ok) throw new Error(`Failed to fetch HLS manifest (${resp.status})`);
  const text = await resp.text();
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);

  const variants = [];
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (!line.startsWith('#EXT-X-STREAM-INF:')) continue;
    const attrs = parseHlsAttributeList(line);
    const nextLine = lines[i + 1];
    if (!nextLine || nextLine.startsWith('#')) continue;

    const [w, h] = String(attrs.RESOLUTION || '')
      .split('x')
      .map((n) => Number(n || 0));
    const bandwidth = Number(attrs.BANDWIDTH || 0);
    variants.push({
      width: Number.isFinite(w) ? w : 0,
      height: Number.isFinite(h) ? h : 0,
      bandwidth: Number.isFinite(bandwidth) ? bandwidth : 0,
      audioGroup: attrs.AUDIO || null,
      codecs: attrs.CODECS || '',
      url: absolutizeUrl(manifestUrl, nextLine),
    });
  }

  variants.sort((a, b) => {
    if (b.height !== a.height) return b.height - a.height;
    if (b.bandwidth !== a.bandwidth) return b.bandwidth - a.bandwidth;
    return b.width - a.width;
  });

  return {
    variants,
    bestVariant: variants[0] || null,
  };
}

async function fetchPlayerResponse({ apiKey, videoId, client, visitorData, cookieHeader, fetchImpl }) {
  const endpoint = `https://www.youtube.com/youtubei/v1/player?key=${encodeURIComponent(apiKey)}`;
  const headers = {
    ...DEFAULT_HEADERS,
    'content-type': 'application/json',
    origin: 'https://www.youtube.com',
    'x-youtube-client-name': client.id,
    'x-youtube-client-version': client.version,
    'user-agent': client.userAgent,
  };
  if (visitorData) headers['x-goog-visitor-id'] = visitorData;
  if (cookieHeader) headers.cookie = cookieHeader;

  const payload = {
    videoId,
    contentCheckOk: true,
    racyCheckOk: true,
    context: { client: client.context },
    playbackContext: {
      contentPlaybackContext: {
        html5Preference: 'HTML5_PREF_WANTS',
      },
    },
  };

  const resp = await fetchImpl(endpoint, {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(`player API ${client.key} failed (${resp.status}): ${text.slice(0, 200)}`);
  }
  return resp.json();
}

async function extractBestUrlsNoResolver(youtubeUrl, options = {}) {
  const fetchImpl = options.fetchImpl || RuntimeFetch;
  if (typeof fetchImpl !== 'function') {
    throw new Error('No fetch implementation available in current runtime');
  }
  const videoId = extractVideoId(youtubeUrl);
  if (!videoId) throw new Error('Invalid YouTube URL or video id');

  const watchResp = await fetchImpl(`https://www.youtube.com/watch?v=${videoId}&hl=en`, {
    headers: DEFAULT_HEADERS,
  });
  if (!watchResp.ok) throw new Error(`Failed to fetch watch page (${watchResp.status})`);
  const watchHtml = await watchResp.text();
  const cfg = getWatchConfig(watchHtml);
  if (!cfg.apiKey) throw new Error('Unable to extract INNERTUBE_API_KEY from watch page');

  const clients = CLIENTS.map((c, idx) => {
    if (idx === 0 && cfg.clientVersion) {
      return {
        ...c,
        version: c.key === 'ios' ? c.version : c.version,
      };
    }
    return c;
  });

  const titleCandidates = [];
  const progressive = [];
  const adaptiveVideo = [];
  const adaptiveAudio = [];
  const manifests = [];

  for (const client of clients) {
    try {
      const pr = await fetchPlayerResponse({
        apiKey: cfg.apiKey,
        videoId,
        client,
        visitorData: cfg.visitorData,
        cookieHeader: options.cookieHeader || '',
        fetchImpl,
      });
      if (pr?.videoDetails?.title) titleCandidates.push(pr.videoDetails.title);
      const sd = pr?.streamingData;
      if (!sd) continue;

      if (sd.hlsManifestUrl) {
        manifests.push({
          client: client.key,
          priority: client.priority,
          url: sd.hlsManifestUrl,
        });
      }

      for (const fmt of sd.formats || []) {
        if (!fmt?.url) continue;
        const codecs = getCodecFlags(fmt.mimeType);
        if (!codecs.video && fmt.mimeType) continue;
        progressive.push({
          client: client.key,
          priority: client.priority,
          itag: String(fmt.itag || ''),
          height: Number(fmt.height || parseQualityLabel(fmt.qualityLabel) || 0),
          fps: Number(fmt.fps || 0),
          ext: String(fmt.mimeType || '').includes('webm') ? 'webm' : 'mp4',
          score: videoScore(fmt),
          hasN: hasNParam(fmt.url),
          url: fmt.url,
        });
      }

      for (const fmt of sd.adaptiveFormats || []) {
        if (!fmt?.url) continue;
        const codecs = getCodecFlags(fmt.mimeType);
        if (codecs.video) {
          adaptiveVideo.push({
            client: client.key,
            priority: client.priority,
            itag: String(fmt.itag || ''),
            height: Number(fmt.height || parseQualityLabel(fmt.qualityLabel) || 0),
            fps: Number(fmt.fps || 0),
            ext: String(fmt.mimeType || '').includes('webm') ? 'webm' : 'mp4',
            score: videoScore(fmt),
            hasN: hasNParam(fmt.url),
            url: fmt.url,
          });
        } else if (codecs.audio || String(fmt.mimeType || '').startsWith('audio/')) {
          adaptiveAudio.push({
            client: client.key,
            priority: client.priority,
            itag: String(fmt.itag || ''),
            ext: String(fmt.mimeType || '').includes('webm') ? 'webm' : 'm4a',
            score: audioScore(fmt),
            hasN: hasNParam(fmt.url),
            url: fmt.url,
          });
        }
      }
    } catch (err) {
      if (options.debug) {
        // eslint-disable-next-line no-console
        console.error(`[simple-extractor] client ${client.key} failed: ${err.message}`);
      }
    }
  }

  if (
    manifests.length === 0 &&
    progressive.length === 0 &&
    adaptiveVideo.length === 0 &&
    adaptiveAudio.length === 0
  ) {
    throw new Error('No playable URLs returned by API clients');
  }

  let bestManifest = null;
  for (const m of manifests) {
    try {
      const parsed = await parseHlsManifest(m.url, fetchImpl);
      const v = parsed.bestVariant;
      if (!v) continue;
      const candidate = {
        client: m.client,
        priority: m.priority,
        manifestUrl: m.url,
        selectedVariantUrl: v.url,
        height: Number(v.height || 0),
        bandwidth: Number(v.bandwidth || 0),
      };
      if (
        !bestManifest ||
        candidate.height > bestManifest.height ||
        (candidate.height === bestManifest.height && candidate.bandwidth > bestManifest.bandwidth)
      ) {
        bestManifest = candidate;
      }
    } catch (err) {
      if (options.debug) {
        // eslint-disable-next-line no-console
        console.error(`[simple-extractor] manifest parse failed: ${err.message}`);
      }
    }
  }

  const bestProgressive = sortCandidates(progressive)[0] || null;
  const separateClient = options.separateClient || 'android_vr';
  const bestVideo = pickBestForClient(adaptiveVideo, separateClient);
  const bestAudio = pickBestForClient(adaptiveAudio, separateClient);

  const bestCombinedIsManifest =
    bestManifest && (!bestProgressive || bestManifest.height > (bestProgressive.height || 0));

  return {
    ok: true,
    mode: 'no_resolver',
    videoId,
    title: titleCandidates[0] || null,
    combined: bestCombinedIsManifest
      ? {
          type: 'hls_manifest',
          client: bestManifest.client,
          url: bestManifest.manifestUrl,
          selectedVariantUrl: bestManifest.selectedVariantUrl,
          quality: {
            height: bestManifest.height,
            bandwidth: bestManifest.bandwidth,
          },
        }
      : bestProgressive
        ? {
            type: 'progressive',
            client: bestProgressive.client,
            url: bestProgressive.url,
            quality: {
              itag: bestProgressive.itag,
              height: bestProgressive.height,
              fps: bestProgressive.fps,
              ext: bestProgressive.ext,
              hasN: bestProgressive.hasN,
            },
          }
        : null,
    separate: {
      video: bestVideo
        ? {
            client: bestVideo.client,
            url: bestVideo.url,
            itag: bestVideo.itag,
            height: bestVideo.height,
            fps: bestVideo.fps,
            ext: bestVideo.ext,
            hasN: bestVideo.hasN,
          }
        : null,
      audio: bestAudio
        ? {
            client: bestAudio.client,
            url: bestAudio.url,
            itag: bestAudio.itag,
            ext: bestAudio.ext,
            hasN: bestAudio.hasN,
          }
        : null,
    },
    preferredSeparateClient: separateClient,
    note:
      'No JS signature/n resolver is used. Some URLs may still fail if YouTube requires n transform for that specific format/client.',
  };
}

module.exports = {
  extractVideoId,
  extractBestUrlsNoResolver,
};
