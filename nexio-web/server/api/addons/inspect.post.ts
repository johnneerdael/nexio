import { createError } from 'h3'
import { bearerToken, readJsonBody, okJson, supabaseUser } from '~/server/utils/supabase'
import { resolveAddonTransport, resolvedAddonManifestUrl } from '~/server/utils/account-addon-transport'
import type { AddonManifestInspection, AddonRecord } from '~/types/portal'

type InspectBody = {
  addons?: AddonRecord[]
}

type ManifestCatalog = {
  type?: string
  id?: string
  name?: string
  extra?: Array<string | { name?: string; isRequired?: boolean }>
}

type ManifestResource = string | { name?: string }

type ManifestPayload = {
  id?: string
  name?: string
  version?: string
  description?: string | null
  logo?: string | null
  catalogs?: ManifestCatalog[]
  resources?: ManifestResource[]
  types?: string[]
}

function canonicalAddonUrl(url: string): string {
  const trimmed = url.trim().replace(/\/manifest\.json$/i, '')
  return trimmed.replace(/\/$/, '')
}

function normalizeCatalogType(value: string | undefined): string {
  const type = String(value || '').trim().toLowerCase()
  if (type === 'tv' || type === 'show') {
    return 'series'
  }
  return type || 'unknown'
}

function isSearchOnlyCatalog(extra: ManifestCatalog['extra']): boolean {
  return (extra ?? []).some((entry) => {
    if (typeof entry === 'string') {
      return entry.trim().toLowerCase() === 'search'
    }
    return entry?.name?.trim().toLowerCase() === 'search' && Boolean(entry.isRequired)
  })
}

function catalogKey(addonId: string, type: string, catalogId: string): string {
  return `${addonId}_${type}_${catalogId}`
}

function disableKey(addonUrl: string, type: string, catalogId: string, catalogName: string): string {
  return `${addonUrl}_${type}_${catalogId}_${catalogName}`
}

async function inspectAddon(addon: AddonRecord, userId?: string | null): Promise<AddonManifestInspection> {
  const addonUrl = canonicalAddonUrl(addon.url)
  const publicManifestUrl = `${addonUrl}/manifest.json`
  let resolvedManifestUrl = publicManifestUrl

  try {
    if (userId && addon.secretRef) {
      const transport = await resolveAddonTransport(userId, addon, 'web-inspect')
      resolvedManifestUrl = resolvedAddonManifestUrl(transport)
    }

    const response = await fetch(resolvedManifestUrl)
    if (!response.ok) {
      throw new Error(`Manifest returned ${response.status}`)
    }

    const manifest = (await response.json()) as ManifestPayload
    const addonId = String(manifest.id || addon.id || addonUrl)
    const addonName = String(manifest.name || addon.name || addonUrl)
    const catalogs = (manifest.catalogs ?? []).map((catalog) => {
      const type = normalizeCatalogType(catalog.type)
      const catalogId = String(catalog.id || '')
      const catalogName = String(catalog.name || catalogId || 'Catalog')
      return {
        key: catalogKey(addonId, type, catalogId),
        disableKey: disableKey(addonUrl, type, catalogId, catalogName),
        addonId,
        addonName,
        addonUrl,
        catalogId,
        catalogName,
        type,
        source: 'addon' as const,
        isSearchOnly: isSearchOnlyCatalog(catalog.extra)
      }
    }).filter((catalog) => catalog.catalogId)

    return {
      addonUrl,
      manifestUrl: publicManifestUrl,
      addonId,
      addonName,
      description: manifest.description ?? addon.description,
      logo: manifest.logo ?? addon.logo,
      version: manifest.version,
      types: (manifest.types ?? []).map((entry) => String(entry).trim()).filter(Boolean),
      resources: (manifest.resources ?? []).map((resource) => typeof resource === 'string' ? resource : String(resource?.name || '')).filter(Boolean),
      catalogs
    }
  } catch (error) {
    return {
      addonUrl,
      manifestUrl: publicManifestUrl,
      addonId: addon.id,
      addonName: addon.name,
      description: addon.description,
      logo: addon.logo,
      types: [],
      resources: [],
      catalogs: [],
      error: error instanceof Error ? error.message : 'Manifest inspection failed.'
    }
  }
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<InspectBody>(event)
  const addons = body.addons ?? []
  const userId = await (async () => {
    try {
      bearerToken(event)
      const user = await supabaseUser(event)
      return user.id
    } catch {
      return null
    }
  })()

  if (!Array.isArray(addons)) {
    throw createError({ statusCode: 400, statusMessage: 'Addons payload must be an array.' })
  }

  const inspections = await Promise.all(addons.map((addon) => inspectAddon(addon, userId)))
  return okJson({ inspections })
})
