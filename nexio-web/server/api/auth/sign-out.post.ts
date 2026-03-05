import { bearerToken, okJson, supabaseFetch } from '~/server/utils/supabase'

export default defineEventHandler(async (event) => {
  const token = bearerToken(event)
  await supabaseFetch('/auth/v1/logout', { method: 'POST' }, token)
  return okJson({ ok: true })
})
