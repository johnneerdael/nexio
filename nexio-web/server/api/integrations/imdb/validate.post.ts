import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { secretRefs } from '~/server/utils/account-secrets'
import { normalizeImdbBaseUrl } from '~/server/utils/imdb-validation'

type ValidateBody = {
  baseUrl?: string
  apiKey?: string
}

type ImdbValidationResponse = {
  status?: string
  message?: string
  error?: string
  data?: Record<string, unknown>
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<ValidateBody>(event)
  const normalizedBaseUrl = normalizeImdbBaseUrl(String(body.baseUrl || ''))

  if (!normalizedBaseUrl) {
    throw createError({ statusCode: 400, statusMessage: 'IMDb base URL is required.' })
  }

  let parsedBaseUrl: URL
  try {
    parsedBaseUrl = new URL(normalizedBaseUrl)
  } catch {
    throw createError({ statusCode: 400, statusMessage: 'IMDb base URL is invalid.' })
  }

  if (parsedBaseUrl.protocol !== 'http:' && parsedBaseUrl.protocol !== 'https:') {
    throw createError({ statusCode: 400, statusMessage: 'IMDb base URL must use http or https.' })
  }

  let apiKey = body.apiKey?.trim() ?? ''
  if (!apiKey) {
    bearerToken(event)
    const user = await supabaseUser(event)
    const payload = await supabaseFetch<Record<string, string>>('/rest/v1/rpc/service_resolve_account_secret', {
      method: 'POST',
      body: JSON.stringify({
        p_user_id: user.id,
        p_secret_type: 'imdb_api_key',
        p_secret_ref: secretRefs.imdb,
        p_source: 'web-imdb'
      })
    }, undefined, true)
    apiKey = String(payload.apiKey || '').trim()
  }

  if (!apiKey) {
    throw createError({ statusCode: 400, statusMessage: 'IMDb API key is required.' })
  }

  let response: Response
  try {
    response = await fetch(`${parsedBaseUrl.toString().replace(/\/$/, '')}/v1/meta/stats`, {
      method: 'GET',
      headers: {
        'X-API-Key': apiKey
      }
    })
  } catch {
    throw createError({ statusCode: 502, statusMessage: 'IMDb validation request failed.' })
  }

  if (!response.ok) {
    let payload: ImdbValidationResponse | null = null
    try {
      payload = await response.json() as ImdbValidationResponse
    } catch {
      payload = null
    }

    const message = String(
      payload?.message ||
      payload?.error ||
      payload?.status ||
      'IMDb validation failed.'
    ).trim()

    if (response.status === 401 || response.status === 403) {
      throw createError({ statusCode: response.status, statusMessage: message || 'IMDb provider rejected the API key.' })
    }

    throw createError({ statusCode: 502, statusMessage: message || 'IMDb validation request failed.' })
  }

  return okJson({
    valid: true,
    baseUrl: parsedBaseUrl.toString().replace(/\/$/, '')
  })
})
