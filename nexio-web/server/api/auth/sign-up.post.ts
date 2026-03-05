import { createError } from 'h3'
import { okJson, readJsonBody, supabaseFetch } from '~/server/utils/supabase'
import type { PortalSession } from '~/types/portal'

type AuthResponse = {
  access_token?: string
  refresh_token?: string
  expires_in?: number
  user?: {
    id: string
    email: string
  }
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<{ email?: string; password?: string }>(event)
  const email = body.email?.trim() ?? ''
  const password = body.password ?? ''

  if (!email || !password) {
    throw createError({ statusCode: 400, statusMessage: 'Email and password are required.' })
  }

  const payload = await supabaseFetch<AuthResponse>('/auth/v1/signup', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  })

  if (!payload.access_token || !payload.refresh_token || !payload.user) {
    throw createError({
      statusCode: 409,
      statusMessage: 'Sign-up succeeded but Supabase did not return a session. Check email confirmation settings.'
    })
  }

  const session: PortalSession = {
    accessToken: payload.access_token,
    refreshToken: payload.refresh_token,
    expiresAt: Date.now() + Number(payload.expires_in || 3600) * 1000,
    user: {
      id: payload.user.id,
      email: payload.user.email
    }
  }

  return okJson(session)
})
