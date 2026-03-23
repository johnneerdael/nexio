import { createError } from 'h3'
import { bearerToken, okJson, supabaseUser } from '~/server/utils/supabase'

type DeviceCodeResponse = {
  device_code: string
  user_code: string
  verification_url: string
  direct_verification_url?: string
  expires_in: number
  interval?: number
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  await supabaseUser(event)

  const config = useRuntimeConfig()
  const clientId = String(config.realDebridClientId || '').trim()
  const clientSecret = String(config.realDebridClientSecret || '').trim()
  if (!clientId) {
    throw createError({ statusCode: 503, statusMessage: 'REAL_DEBRID_CLIENT_ID is not configured.' })
  }

  const query = new URLSearchParams({ client_id: clientId })
  if (!clientSecret) {
    query.set('new_credentials', 'yes')
  }

  const response = await fetch(`https://api.real-debrid.com/oauth/v2/device/code?${query.toString()}`)
  if (!response.ok) {
    throw createError({ statusCode: response.status, statusMessage: await response.text() })
  }

  const payload = await response.json() as DeviceCodeResponse
  const startedAt = Date.now()
  return okJson({
    deviceCode: payload.device_code,
    userCode: payload.user_code,
    verificationUrl: payload.direct_verification_url || payload.verification_url,
    expiresIn: payload.expires_in,
    interval: payload.interval ?? null,
    startedAt: new Date(startedAt).toISOString(),
    expiresAt: startedAt + (payload.expires_in * 1000)
  })
})
