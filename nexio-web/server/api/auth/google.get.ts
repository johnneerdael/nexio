import { createError, getQuery, getRequestURL, sendRedirect } from 'h3'
import { getSupabaseConfig } from '~/server/utils/supabase'

function safeNextPath(value: unknown): string {
  if (typeof value !== 'string') {
    return '/account'
  }

  const trimmed = value.trim()
  if (!trimmed.startsWith('/')) {
    return '/account'
  }

  return trimmed
}

export default defineEventHandler(async (event) => {
  const config = getSupabaseConfig()
  if (!config.url) {
    throw createError({ statusCode: 503, statusMessage: 'Supabase runtime config is missing.' })
  }

  const requestUrl = getRequestURL(event)
  const query = getQuery(event)
  const callbackUrl = new URL('/auth/callback', requestUrl.origin)
  callbackUrl.searchParams.set('next', safeNextPath(query.next))

  const authorizeUrl = new URL('/auth/v1/authorize', config.url)
  authorizeUrl.searchParams.set('provider', 'google')
  authorizeUrl.searchParams.set('redirect_to', callbackUrl.toString())

  return sendRedirect(event, authorizeUrl.toString(), 302)
})
