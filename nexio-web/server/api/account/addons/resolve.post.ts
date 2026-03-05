import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { buildResolvedManifestUrl, type AddonTransportPayload } from '~/server/utils/account-secrets'

type ResolveAddonBody = {
  addonId?: string
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  const user = await supabaseUser(event)
  const body = await readJsonBody<ResolveAddonBody>(event)
  const addonId = body.addonId?.trim()
  if (!addonId) {
    throw createError({ statusCode: 400, statusMessage: 'addonId is required.' })
  }

  const payload = await supabaseFetch<AddonTransportPayload>(
    '/rest/v1/rpc/service_get_account_addon_transport',
    {
      method: 'POST',
      body: JSON.stringify({
        p_user_id: user.id,
        p_addon_id: addonId,
        p_source: 'web'
      })
    },
    undefined,
    true
  )

  return okJson({
    addonId,
    resolvedManifestUrl: buildResolvedManifestUrl({
      manifestUrl: payload.manifest_url,
      baseUrl: payload.base_url,
      publicQueryParams: payload.public_query_params ?? {},
      secretPayload: payload.secret_payload ?? null
    })
  })
})
