import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseUser } from '~/server/utils/supabase'
import { resolveAddonTransport, resolvedAddonManifestUrl } from '~/server/utils/account-addon-transport'
import { normalizeAddonUrl } from '~/server/utils/account-secrets'

type ResolveAddonBody = {
  addonId?: string
  addonUrl?: string
  secretRef?: string | null
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  const user = await supabaseUser(event)
  const body = await readJsonBody<ResolveAddonBody>(event)
  const addonId = body.addonId?.trim()
  const addonUrl = body.addonUrl?.trim()
  if (!addonId && !addonUrl) {
    throw createError({ statusCode: 400, statusMessage: 'addonId or addonUrl is required.' })
  }

  const normalizedUrl = normalizeAddonUrl(addonUrl || '')
  const payload = await resolveAddonTransport(
    user.id,
    {
      id: addonId || '',
      url: normalizedUrl,
      manifestUrl: normalizedUrl ? `${normalizedUrl}/manifest.json` : '',
      publicQueryParams: {},
      secretRef: body.secretRef?.trim() || null
    },
    'web'
  )

  return okJson({
    addonId: addonId || payload.addon_id || null,
    resolvedManifestUrl: resolvedAddonManifestUrl(payload)
  })
})
