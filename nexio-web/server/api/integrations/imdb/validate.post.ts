import { okJson, readJsonBody, supabaseFetch, supabaseUser, bearerToken } from '~/server/utils/supabase'
import { validateImdbConfig } from '~/server/utils/imdb-validation'

type ValidateBody = {
  baseUrl?: string
  apiKey?: string
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<ValidateBody>(event)

  if (body.apiKey?.trim()) {
    return okJson(await validateImdbConfig({
      baseUrl: body.baseUrl,
      apiKey: body.apiKey
    }))
  }

  bearerToken(event)
  const user = await supabaseUser(event)
  const result = await validateImdbConfig({
    baseUrl: body.baseUrl,
    userId: user.id,
    source: 'web-imdb',
    resolveSecret: async ({ userId, secretType, secretRef, source }) => {
      const payload = await supabaseFetch<Record<string, string>>('/rest/v1/rpc/service_resolve_account_secret', {
        method: 'POST',
        body: JSON.stringify({
          p_user_id: userId,
          p_secret_type: secretType,
          p_secret_ref: secretRef,
          p_source: source
        })
      }, undefined, true)
      return payload
    }
  })

  return okJson(result)
})
