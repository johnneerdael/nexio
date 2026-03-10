import { createError } from 'h3'
import { bearerToken, okJson, supabaseUser } from '~/server/utils/supabase'

type DeviceCodeResponse = {
  device_code: string
  user_code: string
  verification_url: string
  expires_in: number
  interval?: number
}

export default defineEventHandler(async (event) => {
  bearerToken(event)
  await supabaseUser(event)

  const config = useRuntimeConfig()
  const clientId = String(config.realDebridClientId || '').trim()
  if (!clientId) {
    throw createError({ statusCode: 503, statusMessage: 'REAL_DEBRID_CLIENT_ID is not configured.' })
  }

  const response = await fetch(`https://api.real-debrid.com/oauth/v2/device/code?client_id=${encodeURIComponent(clientId)}&new_credentials=yes`)
  if (!response.ok) {
    throw createError({ statusCode: response.status, statusMessage: await response.text() })
  }

  const payload = await response.json() as DeviceCodeResponse
  const startedAt = Date.now()
  return okJson({
    deviceCode: payload.device_code,
    userCode: payload.user_code,
    verificationUrl: payload.verification_url,
    expiresIn: payload.expires_in,
    interval: payload.interval ?? null,
    startedAt: new Date(startedAt).toISOString(),
    expiresAt: startedAt + (payload.expires_in * 1000)
  })
})
