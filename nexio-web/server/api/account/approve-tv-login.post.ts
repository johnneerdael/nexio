import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch } from '~/server/utils/supabase'

type ApproveBody = {
  code?: string
  nonce?: string
}

type ApproveRpcResult = {
  message?: string
}

export default defineEventHandler(async (event) => {
  const token = bearerToken(event)
  const body = await readJsonBody<ApproveBody>(event)
  if (!body.code || !body.nonce) {
    throw createError({ statusCode: 400, statusMessage: 'Both code and nonce are required.' })
  }

  const payload = await supabaseFetch<ApproveRpcResult[] | ApproveRpcResult>('/rest/v1/rpc/approve_tv_login_session', {
    method: 'POST',
    body: JSON.stringify({
      p_code: body.code.trim().toUpperCase(),
      p_device_nonce: body.nonce.trim()
    })
  }, token)

  const row = Array.isArray(payload) ? payload[0] : payload
  return okJson({
    message: row?.message ?? 'TV login approved.'
  })
})
