import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { secretRefs } from '~/server/utils/account-secrets'

type DeviceTokenBody = {
  deviceCode?: string
}

type DeviceTokenResponse = {
  access_token: string
  refresh_token: string
  token_type: string
  created_at: number
  expires_in: number
}

type UserSettingsResponse = {
  user?: {
    username?: string
    ids?: {
      slug?: string
    }
  }
}

function traktHeaders(clientId: string, accessToken?: string): HeadersInit {
  return {
    'Content-Type': 'application/json',
    'trakt-api-key': clientId,
    'trakt-api-version': '2',
    ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {})
  }
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  const user = await supabaseUser(event)
  const body = await readJsonBody<DeviceTokenBody>(event)
  const deviceCode = body.deviceCode?.trim()
  if (!deviceCode) {
    throw createError({ statusCode: 400, statusMessage: 'Trakt device code is required.' })
  }

  const config = useRuntimeConfig()
  const clientId = String(config.traktClientId || '').trim()
  const clientSecret = String(config.traktClientSecret || '').trim()
  if (!clientId || !clientSecret) {
    throw createError({ statusCode: 503, statusMessage: 'TRAKT credentials are not configured.' })
  }

  const tokenResponse = await fetch('https://api.trakt.tv/oauth/device/token', {
    method: 'POST',
    headers: traktHeaders(clientId),
    body: JSON.stringify({
      code: deviceCode,
      client_id: clientId,
      client_secret: clientSecret
    })
  })

  if (!tokenResponse.ok) {
    return okJson({
      status: tokenResponse.status,
      pending: tokenResponse.status === 400 || tokenResponse.status === 429,
      approved: false
    })
  }

  const tokenPayload = await tokenResponse.json() as DeviceTokenResponse
  const userSettingsResponse = await fetch('https://api.trakt.tv/users/settings', {
    headers: traktHeaders(clientId, tokenPayload.access_token)
  })

  if (!userSettingsResponse.ok) {
    throw createError({ statusCode: userSettingsResponse.status, statusMessage: await userSettingsResponse.text() })
  }

  const userSettings = await userSettingsResponse.json() as UserSettingsResponse

  await supabaseFetch('/rest/v1/rpc/service_set_account_secret', {
    method: 'POST',
    body: JSON.stringify({
      p_user_id: user.id,
      p_secret_type: 'trakt_access_token',
      p_secret_ref: secretRefs.trakt,
      p_secret_payload: {
        accessToken: tokenPayload.access_token,
        tokenType: tokenPayload.token_type,
        createdAt: tokenPayload.created_at,
        expiresIn: tokenPayload.expires_in
      },
      p_masked_preview: `Connected ••••${tokenPayload.access_token.slice(-4)}`,
      p_status: 'configured',
      p_source: 'web-trakt'
    })
  }, undefined, true)

  await supabaseFetch('/rest/v1/rpc/service_set_account_secret', {
    method: 'POST',
    body: JSON.stringify({
      p_user_id: user.id,
      p_secret_type: 'trakt_refresh_token',
      p_secret_ref: secretRefs.trakt,
      p_secret_payload: {
        refreshToken: tokenPayload.refresh_token
      },
      p_masked_preview: `Stored ••••${tokenPayload.refresh_token.slice(-4)}`,
      p_status: 'configured',
      p_source: 'web-trakt'
    })
  }, undefined, true)

  return okJson({
    status: 200,
    pending: false,
    approved: true,
    traktAuth: {
      connected: true,
      username: userSettings.user?.username ?? '',
      userSlug: userSettings.user?.ids?.slug ?? '',
      connectedAt: new Date().toISOString(),
      pending: false
    }
  })
})
