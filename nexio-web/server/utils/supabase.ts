import type { H3Event } from 'h3'
import { createError, getHeader, readBody } from 'h3'

const jsonHeaders = {
  'Content-Type': 'application/json'
}

export type SupabaseConfig = {
  url: string
  anonKey: string
  serviceRoleKey: string
}

export async function readJsonBody<T>(event: H3Event): Promise<T> {
  return await readBody<T>(event)
}

export function getSupabaseConfig(): SupabaseConfig {
  const config = useRuntimeConfig()
  return {
    url: String(config.supabaseUrl || '').replace(/\/$/, ''),
    anonKey: String(config.supabaseAnonKey || ''),
    serviceRoleKey: String(config.supabaseServiceRoleKey || '')
  }
}

export function hasSupabaseConfig(): boolean {
  const config = getSupabaseConfig()
  return Boolean(config.url && config.anonKey)
}

export function bearerToken(event: H3Event): string {
  const header = getHeader(event, 'authorization') || ''
  if (!header.toLowerCase().startsWith('bearer ')) {
    throw createError({ statusCode: 401, statusMessage: 'Missing bearer token.' })
  }

  return header.slice(7)
}

export async function supabaseFetch<T>(
  path: string,
  options: RequestInit = {},
  authToken?: string,
  useServiceRole = false
): Promise<T> {
  const config = getSupabaseConfig()
  if (!config.url || !config.anonKey) {
    throw createError({ statusCode: 503, statusMessage: 'Supabase runtime config is missing.' })
  }

  const headers = new Headers(options.headers)
  headers.set('apikey', useServiceRole && config.serviceRoleKey ? config.serviceRoleKey : config.anonKey)
  headers.set('Content-Type', 'application/json')

  if (authToken) {
    headers.set('Authorization', `Bearer ${authToken}`)
  } else if (useServiceRole && config.serviceRoleKey) {
    // Supabase secret keys must match the apikey header exactly.
    headers.set('Authorization', config.serviceRoleKey)
  }

  const response = await fetch(`${config.url}${path}`, {
    ...options,
    headers
  })

  if (!response.ok) {
    const message = await response.text()
    throw createError({
      statusCode: response.status,
      statusMessage: message || response.statusText
    })
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

export async function supabaseUser(event: H3Event): Promise<{ id: string; email: string }> {
  const token = bearerToken(event)
  const payload = await supabaseFetch<{ id: string; email: string }>('/auth/v1/user', {}, token)
  return payload
}

export function okJson<T>(payload: T) {
  return new Response(JSON.stringify(payload), {
    headers: jsonHeaders
  })
}
