import { bearerToken, okJson, supabaseFetch } from '~/server/utils/supabase'
import { mapSecretRows } from '~/server/utils/account-secrets'
import type { SecretType } from '~/types/portal'

type SecretRow = {
  id: string
  secret_type: SecretType
  secret_ref: string
  masked_preview?: string | null
  status?: string | null
  version?: number | null
  updated_at?: string | null
}

export default defineEventHandler(async (event) => {
  const token = bearerToken(event)
  const rows = await supabaseFetch<SecretRow[]>(
    '/rest/v1/account_secrets?select=id,secret_type,secret_ref,masked_preview,status,version,updated_at',
    {},
    token
  )

  return okJson({
    secrets: mapSecretRows(rows)
  })
})
