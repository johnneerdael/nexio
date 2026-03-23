import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { secretRefs } from '~/server/utils/account-secrets'

type ValidateBody = {
  apiKey?: string
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<ValidateBody>(event)
  let apiKey = body.apiKey?.trim() ?? ''

  if (!apiKey) {
    bearerToken(event)
    const user = await supabaseUser(event)
    const payload = await supabaseFetch<Record<string, string>>('/rest/v1/rpc/service_resolve_account_secret', {
      method: 'POST',
      body: JSON.stringify({
        p_user_id: user.id,
        p_secret_type: 'omdb_api_key',
        p_secret_ref: secretRefs.omdb,
        p_source: 'web-omdb'
      })
    }, undefined, true)
    apiKey = String(payload.apiKey || '').trim()
  }

  if (!apiKey) {
    throw createError({ statusCode: 400, statusMessage: 'OMDB API key is required.' })
  }

  let response: Response
  try {
    response = await fetch(`https://www.omdbapi.com/?apikey=${encodeURIComponent(apiKey)}&i=tt0111161`)
  } catch {
    throw createError({ statusCode: 502, statusMessage: 'OMDB validation request failed.' })
  }

  if (!response.ok) {
    throw createError({ statusCode: 400, statusMessage: `OMDB validation failed (${response.status}).` })
  }

  const payload = await response.json() as Record<string, unknown>
  if (String(payload.Response || '').toLowerCase() !== 'true') {
    const message = String(payload.Error || 'OMDB rejected the API key.')
    throw createError({ statusCode: 400, statusMessage: message })
  }

  return okJson({ valid: true })
})
