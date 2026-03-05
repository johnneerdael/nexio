import { createError } from 'h3'
import { bearerToken, okJson, supabaseUser } from '~/server/utils/supabase'

type DeviceCodeResponse = {
  device_code: string
  user_code: string
  verification_url: string
  verification_url_complete?: string
  expires_in: number
  interval: number
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  await supabaseUser(event)

  const config = useRuntimeConfig()
  const clientId = String(config.traktClientId || '').trim()

  if (!clientId) {
    throw createError({ statusCode: 503, statusMessage: 'TRAKT_CLIENT_ID is not configured.' })
  }

  const response = await fetch('https://api.trakt.tv/oauth/device/code', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'trakt-api-key': clientId,
      'trakt-api-version': '2'
    },
    body: JSON.stringify({ client_id: clientId })
  })

  if (!response.ok) {
    throw createError({ statusCode: response.status, statusMessage: await response.text() })
  }

  const payload = await response.json() as DeviceCodeResponse
  return okJson({
    deviceCode: payload.device_code,
    userCode: payload.user_code,
    verificationUrl: payload.verification_url,
    verificationUrlComplete: payload.verification_url_complete,
    expiresIn: payload.expires_in,
    interval: payload.interval,
    startedAt: new Date().toISOString()
  })
})
