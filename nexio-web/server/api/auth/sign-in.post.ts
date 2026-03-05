import { createError } from 'h3'
import { okJson, readJsonBody, supabaseFetch } from '~/server/utils/supabase'
import type { PortalSession } from '~/types/portal'

type AuthResponse = {
  access_token: string
  refresh_token: string
  expires_in: number
  user: {
    id: string
    email: string
  }
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<{ email?: string; password?: string }>(event)
  if (!body.email || !body.password) {
    throw createError({ statusCode: 400, statusMessage: 'Email and password are required.' })
  }

  const payload = await supabaseFetch<AuthResponse>('/auth/v1/token?grant_type=password', {
    method: 'POST',
    body: JSON.stringify({ email: body.email, password: body.password })
  })

  const session: PortalSession = {
    accessToken: payload.access_token,
    refreshToken: payload.refresh_token,
    expiresAt: Date.now() + payload.expires_in * 1000,
    user: {
      id: payload.user.id,
      email: payload.user.email
    }
  }

  return okJson(session)
})
