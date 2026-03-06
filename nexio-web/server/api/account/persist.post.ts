import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseUser, supabaseFetch } from '~/server/utils/supabase'
import { normalizeAddonManifestUrl, normalizeAddonUrl } from '~/server/utils/account-secrets'
import type { AddonRecord, PortalSettings } from '~/types/portal'

type PersistBody = {
  settings?: PortalSettings
  addons?: AddonRecord[]
}

type RpcMutationResult = Array<{
  sync_revision?: number
  updated_at?: string | null
}>

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<PersistBody>(event)
  const token = bearerToken(event)

  if (!body.settings) {
    throw createError({ statusCode: 400, statusMessage: 'Settings payload is required.' })
  }

  await supabaseUser(event)

  const settingsResult = await supabaseFetch<RpcMutationResult>('/rest/v1/rpc/sync_push_account_settings', {
    method: 'POST',
    body: JSON.stringify({
      p_settings_payload: body.settings,
      p_source: 'web'
    })
  }, token)

  const addonsResult = await supabaseFetch<RpcMutationResult>('/rest/v1/rpc/sync_push_account_addons', {
    method: 'POST',
    body: JSON.stringify({
      p_addons: (body.addons ?? []).map((addon, index) => ({
        url: normalizeAddonUrl(addon.url),
        manifest_url: normalizeAddonManifestUrl(addon.url, addon.manifestUrl),
        name: addon.name,
        description: addon.description ?? null,
        enabled: addon.enabled,
        public_query_params: addon.publicQueryParams ?? {},
        install_kind: addon.installKind ?? 'manifest',
        secret_ref: addon.secretRef ?? null,
        sort_order: index
      })),
      p_source: 'web'
    })
  }, token)

  const syncRevision = Math.max(
    settingsResult[0]?.sync_revision ?? 0,
    addonsResult[0]?.sync_revision ?? 0,
    Date.now()
  )
  const updatedAt =
    addonsResult[0]?.updated_at ??
    settingsResult[0]?.updated_at ??
    new Date().toISOString()

  return okJson({
    syncRevision,
    lastSyncedAt: updatedAt
  })
})
