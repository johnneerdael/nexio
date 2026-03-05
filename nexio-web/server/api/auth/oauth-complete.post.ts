import { createError } from 'h3'
import { okJson, readJsonBody, supabaseFetch } from '~/server/utils/supabase'
import type { PortalSession } from '~/types/portal'

type AuthUser = {
  id: string
  email: string
}

type Body = {
  accessToken?: string
  refreshToken?: string
  expiresIn?: number
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<Body>(event)
  const accessToken = body.accessToken?.trim() ?? ''
  const refreshToken = body.refreshToken?.trim() ?? ''

  if (!accessToken || !refreshToken) {
    throw createError({ statusCode: 400, statusMessage: 'OAuth tokens are required.' })
  }

  const user = await supabaseFetch<AuthUser>('/auth/v1/user', {}, accessToken)
  const session: PortalSession = {
    accessToken,
    refreshToken,
    expiresAt: Date.now() + Number(body.expiresIn || 3600) * 1000,
    user: {
      id: user.id,
      email: user.email
    }
  }

  return okJson(session)
})
