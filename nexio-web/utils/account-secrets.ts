import type { AddonRecord, SecretType } from '~/types/portal'

export const secretRefs = {
  tmdb: 'integration:tmdb',
  omdb: 'integration:omdb',
  mdblist: 'integration:mdblist',
  premiumize: 'integration:premiumize',
  gemini: 'integration:gemini',
  rpdb: 'integration:rpdb',
  topPosters: 'integration:topposters',
  trakt: 'integration:trakt',
  realDebrid: 'integration:realdebrid'
} as const

const sensitiveQueryKeys = new Set([
  'access_token',
  'api_key',
  'apikey',
  'auth',
  'authorization',
  'debrid_api_key',
  'key',
  'password',
  'premiumize',
  'rd',
  'rd_key',
  'refresh_token',
  'token',
  'user',
  'username'
])

const parserPresetDomainMap: Array<{ domain: string; parserPreset: AddonRecord['parserPreset'] }> = [
  { domain: 'webstreamr.hayd.uk', parserPreset: 'WEBSTREAMR' },
  { domain: 'torrentio.strem.fun', parserPreset: 'TORRENTIO' },
  { domain: 'stremthrufortheweebs.midnightignite.me', parserPreset: 'STREMTHRU' },
  { domain: 'stremthru.elfhosted.com', parserPreset: 'STREMTHRU' },
  { domain: 'stremthru.fortheweak.cloud', parserPreset: 'STREMTHRU' },
  { domain: 'stremthru.13377001.xyz', parserPreset: 'STREMTHRU' },
  { domain: 'stremthru.stremio.ru', parserPreset: 'STREMTHRU' },
  { domain: 'stremthru.atbphosting.com', parserPreset: 'STREMTHRU' }
]

export type AddonSecretPayload = {
  kind: 'query_params' | 'path_segment' | 'composite'
  params?: Record<string, string>
  pathSegment?: string
}

function looksSensitivePathSegment(segment: string) {
  const value = segment.trim()
  if (value.length < 24) {
    return false
  }
  return /^[A-Za-z0-9._~+=-]+$/.test(value)
}

export function normalizeAddonUrl(url: string): string {
  return url.trim().replace(/\/manifest\.json$/i, '').replace(/\/$/, '')
}

export function normalizeAddonManifestUrl(baseUrl: string, manifestUrl?: string | null): string {
  const normalizedBaseUrl = normalizeAddonUrl(baseUrl)
  const candidate = String(manifestUrl || '').trim()
  if (candidate) {
    try {
      const parsed = new URL(candidate)
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
        return parsed.toString().replace(/\/$/, '')
      }
    } catch {
      // Fall back to the canonical manifest path derived from the base URL.
    }
  }

  return normalizedBaseUrl ? `${normalizedBaseUrl}/manifest.json` : ''
}

export function addonSecretRef(url: string): string {
  const normalized = normalizeAddonUrl(url)
  return `addon:${normalized.toLowerCase().replace(/^https?:\/\//, '').replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}`
}

export function recommendParserPresetForAddonUrl(url: string): AddonRecord['parserPreset'] | null {
  try {
    const host = new URL(normalizeAddonManifestUrl(url)).hostname.toLowerCase()
    const matched = parserPresetDomainMap.find((entry) => host === entry.domain)
    return matched?.parserPreset ?? null
  } catch {
    return null
  }
}

export function parseAddonInstallUrl(rawUrl: string): {
  addon: AddonRecord
  secretType: SecretType | null
  secretRef: string | null
  secretPayload: AddonSecretPayload | null
} {
  const candidate = rawUrl.trim()
  if (!candidate) {
    throw new Error('Addon URL is required.')
  }

  const parsed = new URL(candidate)
  const pathSegments = parsed.pathname.split('/').filter(Boolean)
  const hasManifestPath = pathSegments[pathSegments.length - 1]?.toLowerCase() === 'manifest.json'
  const pathSecretSegment = hasManifestPath ? pathSegments[pathSegments.length - 2] ?? null : null
  const hasPathSecret = Boolean(pathSecretSegment && looksSensitivePathSegment(pathSecretSegment))
  const publicPathSegments = hasPathSecret
    ? [...pathSegments.slice(0, -2), 'manifest.json']
    : pathSegments
  const publicPathname = publicPathSegments.length > 0 ? `/${publicPathSegments.join('/')}` : '/manifest.json'
  const normalized = normalizeAddonUrl(`${parsed.origin}${publicPathname}`)
  const publicQueryParams: Record<string, string> = {}
  const secretParams: Record<string, string> = {}

  parsed.searchParams.forEach((value, key) => {
    if (sensitiveQueryKeys.has(key.trim().toLowerCase())) {
      secretParams[key] = value
      return
    }
    publicQueryParams[key] = value
  })

  const hasQuerySecrets = Object.keys(secretParams).length > 0
  const secretRef = hasQuerySecrets || hasPathSecret ? addonSecretRef(normalized) : null
  const secretPayload: AddonSecretPayload | null = secretRef
    ? {
      kind: hasQuerySecrets && hasPathSecret ? 'composite' : hasPathSecret ? 'path_segment' : 'query_params',
      ...(hasQuerySecrets ? { params: secretParams } : {}),
      ...(hasPathSecret ? { pathSegment: pathSecretSegment as string } : {})
    }
    : null

  return {
    addon: {
      id: crypto.randomUUID(),
      url: normalized,
      manifestUrl: `${normalized}/manifest.json`,
      parserPreset: recommendParserPresetForAddonUrl(`${normalized}/manifest.json`) ?? 'GENERIC',
      name: parsed.hostname.replace(/^www\./, ''),
      enabled: true,
      installKind: secretRef ? 'configured' : 'manifest',
      publicQueryParams,
      secretRef,
      sortOrder: 0
    },
    secretType: secretRef ? 'addon_credential' : null,
    secretRef,
    secretPayload
  }
}
