import { createError } from 'h3'
import { okJson, readJsonBody } from '~/server/utils/supabase'

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
  return okJson({
    status: 200,
    pending: false,
    approved: true,
    traktAuth: {
      connected: true,
      username: userSettings.user?.username ?? '',
      userSlug: userSettings.user?.ids?.slug ?? '',
      connectedAt: new Date().toISOString(),
      pending: false,
      accessToken: tokenPayload.access_token,
      refreshToken: tokenPayload.refresh_token,
      tokenType: tokenPayload.token_type,
      createdAt: tokenPayload.created_at,
      expiresIn: tokenPayload.expires_in
    }
  })
})
