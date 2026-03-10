import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { secretRefs } from '~/server/utils/account-secrets'

type ValidateBody = {
  apiKey?: string
}

type PremiumizeAccountResponse = {
  status?: string
  customer_id?: number | null
  premium_until?: number | null
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
        p_secret_type: 'premiumize_api_key',
        p_secret_ref: secretRefs.premiumize,
        p_source: 'web-premiumize'
      })
    }, undefined, true)
    apiKey = String(payload.apiKey || '').trim()
  }

  if (!apiKey) {
    throw createError({ statusCode: 400, statusMessage: 'Premiumize API key is required.' })
  }

  const response = await fetch(`https://www.premiumize.me/api/account/info?apikey=${encodeURIComponent(apiKey)}`)
  const payload = await response.json().catch(() => ({} as PremiumizeAccountResponse)) as PremiumizeAccountResponse

  if (!response.ok || String(payload.status || '').toLowerCase() !== 'success') {
    throw createError({ statusCode: 400, statusMessage: `Premiumize validation failed (${response.status}).` })
  }

  return okJson({
    valid: true,
    customerId: payload.customer_id ?? null,
    premiumUntil: payload.premium_until ?? null
  })
})
