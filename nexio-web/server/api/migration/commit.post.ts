import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch } from '~/server/utils/supabase'
import { normalizeAddonManifestUrl, normalizeAddonUrl, recommendParserPresetForAddonUrl } from '~/server/utils/account-secrets'
import type { AddonRecord } from '~/types/portal'

type CommitBody = {
  importedAddons?: Array<{
    url?: string
    manifestUrl?: string
    name?: string
    description?: string
    logo?: string
    parserPreset?: AddonRecord['parserPreset']
    enabled?: boolean
    publicQueryParams?: Record<string, string>
  }>
}

type SnapshotRpcPayload = {
  addons?: Array<{
    id?: string
    url?: string
    manifest_url?: string | null
    parser_preset?: AddonRecord['parserPreset'] | null
    name?: string | null
    description?: string | null
    enabled?: boolean | null
    sort_order?: number | null
    public_query_params?: Record<string, string> | null
    install_kind?: 'manifest' | 'configured' | null
    secret_ref?: string | null
  }>
}

type RpcMutationResult = Array<{
  sync_revision?: number
  updated_at?: string | null
}>

function sanitizeImportedAddon(
  addon: NonNullable<CommitBody['importedAddons']>[number],
  fallbackIndex: number
) {
  const normalizedUrl = normalizeAddonUrl(String(addon.url || ''))
  const manifestUrl = normalizeAddonManifestUrl(normalizedUrl, addon.manifestUrl)
  if (!normalizedUrl || !manifestUrl) {
    return null
  }

  return {
    url: normalizedUrl,
    manifestUrl,
    parserPreset: recommendParserPresetForAddonUrl(manifestUrl) ?? addon.parserPreset ?? 'GENERIC',
    name: String(addon.name || '').trim() || new URL(normalizedUrl).hostname.replace(/^www\./, ''),
    description: addon.description ? String(addon.description).trim() : null,
    enabled: addon.enabled ?? true,
    publicQueryParams: addon.publicQueryParams ?? {},
    installKind: 'manifest' as const,
    secretRef: null,
    sortOrder: fallbackIndex
  }
}

export default defineEventHandler(async (event) => {
  const token = bearerToken(event)
  const body = await readJsonBody<CommitBody>(event)

  const imported = (body.importedAddons ?? [])
    .map((addon, index) => sanitizeImportedAddon(addon, index))
    .filter((addon): addon is NonNullable<typeof addon> => Boolean(addon))

  if (imported.length === 0) {
    throw createError({ statusCode: 400, statusMessage: 'No valid imported addons were provided.' })
  }

  const snapshot = await supabaseFetch<SnapshotRpcPayload>(
    '/rest/v1/rpc/sync_pull_account_snapshot',
    {
      method: 'POST',
      body: JSON.stringify({})
    },
    token
  )

  const existing = (snapshot.addons ?? []).map((addon, index) => ({
    url: normalizeAddonUrl(String(addon.url || '')),
    manifestUrl: normalizeAddonManifestUrl(String(addon.url || ''), addon.manifest_url),
    parserPreset: addon.parser_preset ?? 'GENERIC',
    name: addon.name ?? addon.url ?? 'Addon',
    description: addon.description ?? null,
    enabled: addon.enabled ?? true,
    publicQueryParams: addon.public_query_params ?? {},
    installKind: addon.install_kind ?? 'manifest',
    secretRef: addon.secret_ref ?? null,
    sortOrder: addon.sort_order ?? index
  })).filter((addon) => addon.url)

  const dedupe = new Set(existing.map((addon) => addon.url))
  const additions = imported.filter((addon) => !dedupe.has(addon.url))
  const merged = [...existing, ...additions].map((addon, index) => ({ ...addon, sortOrder: index }))

  const persisted = await supabaseFetch<RpcMutationResult>(
    '/rest/v1/rpc/sync_push_account_addons',
    {
      method: 'POST',
      body: JSON.stringify({
        p_addons: merged.map((addon) => ({
          url: addon.url,
          manifest_url: addon.manifestUrl,
          parser_preset: addon.parserPreset,
          name: addon.name,
          description: addon.description,
          enabled: addon.enabled,
          public_query_params: addon.publicQueryParams,
          install_kind: addon.installKind,
          secret_ref: addon.secretRef,
          sort_order: addon.sortOrder
        })),
        p_source: 'web-migration'
      })
    },
    token
  )

  return okJson({
    importedCount: imported.length,
    addedCount: additions.length,
    totalCount: merged.length,
    syncRevision: persisted[0]?.sync_revision ?? Date.now(),
    lastSyncedAt: persisted[0]?.updated_at ?? new Date().toISOString()
  })
})
