import { createError } from 'h3'
import { normalizeAddonManifestUrl, normalizeAddonUrl, recommendParserPresetForAddonUrl } from '~/server/utils/account-secrets'
import type { AddonRecord } from '~/types/portal'

export type MigrationSource = 'stremio' | 'nuvio'

export type ImportedAddonCandidate = {
  id: string
  url: string
  manifestUrl: string
  name: string
  description?: string
  logo?: string
  parserPreset: AddonRecord['parserPreset']
  enabled: boolean
  publicQueryParams: Record<string, string>
}

function parserPresetFromUrl(url: string): AddonRecord['parserPreset'] {
  const recommended = recommendParserPresetForAddonUrl(url)
  if (recommended) return recommended

  const value = url.toLowerCase()
  if (value.includes('torrentio')) return 'TORRENTIO'
  if (value.includes('stremthru')) return 'STREMTHRU'
  if (value.includes('webstreamr')) return 'WEBSTREAMR'
  return 'GENERIC'
}

export function toImportedAddonCandidate(input: {
  url?: string | null
  manifestUrl?: string | null
  name?: string | null
  description?: string | null
  logo?: string | null
  parserPreset?: AddonRecord['parserPreset'] | null
  enabled?: boolean | null
  publicQueryParams?: Record<string, string> | null
}, index: number): ImportedAddonCandidate | null {
  const base = normalizeAddonUrl(String(input.url || ''))
  const manifest = normalizeAddonManifestUrl(base, input.manifestUrl)
  if (!base || !manifest) {
    return null
  }

  return {
    id: `import-${index}-${base}`,
    url: base,
    manifestUrl: manifest,
    name: String(input.name || '').trim() || new URL(base).hostname.replace(/^www\./, ''),
    description: input.description ? String(input.description).trim() : undefined,
    logo: input.logo ? String(input.logo).trim() : undefined,
    parserPreset: parserPresetFromUrl(manifest) ?? input.parserPreset ?? 'GENERIC',
    enabled: input.enabled ?? true,
    publicQueryParams: { ...(input.publicQueryParams ?? {}) }
  }
}

export function normalizeImportedAddonCandidates(candidates: ImportedAddonCandidate[]): ImportedAddonCandidate[] {
  const dedupe = new Map<string, ImportedAddonCandidate>()
  for (const candidate of candidates) {
    const key = `${normalizeAddonUrl(candidate.url)}::${candidate.manifestUrl}`
    if (!dedupe.has(key)) {
      dedupe.set(key, candidate)
    }
  }

  return [...dedupe.values()]
}

export function toAddonRecords(candidates: ImportedAddonCandidate[], currentCount: number): AddonRecord[] {
  return candidates.map((candidate, index) => ({
    id: crypto.randomUUID(),
    url: normalizeAddonUrl(candidate.url),
    manifestUrl: normalizeAddonManifestUrl(candidate.url, candidate.manifestUrl),
    parserPreset: candidate.parserPreset,
    name: candidate.name,
    description: candidate.description,
    logo: candidate.logo,
    enabled: candidate.enabled,
    installKind: 'manifest',
    publicQueryParams: candidate.publicQueryParams,
    secretRef: null,
    sortOrder: currentCount + index
  }))
}

export function requireCredentials(email?: string, password?: string) {
  const normalizedEmail = String(email || '').trim()
  const normalizedPassword = String(password || '')
  if (!normalizedEmail || !normalizedPassword) {
    throw createError({ statusCode: 400, statusMessage: 'Email and password are required.' })
  }

  return {
    email: normalizedEmail,
    password: normalizedPassword
  }
}
