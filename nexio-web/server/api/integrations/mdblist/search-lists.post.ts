import { createError } from 'h3'
import { bearerToken, okJson, readJsonBody, supabaseFetch, supabaseUser } from '~/server/utils/supabase'
import { secretRefs } from '~/server/utils/account-secrets'
import type { MDBListListOption } from '~/types/portal'

type SearchBody = {
  query: string
  apiKey?: string
}

function firstNonBlank(...values: Array<string | undefined | null>): string {
  return values.find((value) => value && value.trim())?.trim() ?? ''
}

function positiveInt(...values: number[]): number {
  return values.find((value) => Number.isFinite(value) && value >= 0) ?? 0
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<SearchBody>(event)
  const query = body.query?.trim() || ''
  if (!query) {
    return okJson({ lists: [] })
  }

  let apiKey = body.apiKey?.trim() ?? ''
  if (!apiKey) {
    bearerToken(event)
    const user = await supabaseUser(event)
    const payload = await supabaseFetch<Record<string, string>>('/rest/v1/rpc/service_resolve_account_secret', {
      method: 'POST',
      body: JSON.stringify({
        p_user_id: user.id,
        p_secret_type: 'mdblist_api_key',
        p_secret_ref: secretRefs.mdblist,
        p_source: 'web-mdblist'
      })
    }, undefined, true)
    apiKey = String(payload.apiKey || '').trim()
  }

  if (!apiKey) {
    throw createError({ statusCode: 400, statusMessage: 'MDBList API key is required.' })
  }

  const response = await fetch(`https://api.mdblist.com/lists/search?query=${encodeURIComponent(query)}&apikey=${encodeURIComponent(apiKey)}`)
  
  if (!response.ok) {
    throw createError({ statusCode: response.status, statusMessage: await response.text() })
  }

  let raw = []
  try {
    const text = await response.text()
    raw = JSON.parse(text)
    if (!Array.isArray(raw)) raw = []
  } catch {
    raw = []
  }

  const lists: MDBListListOption[] = raw.flatMap((entry) => {
    const listId = firstNonBlank(String(entry.id), entry.slug)
    const owner = firstNonBlank(entry.user_name, String(entry.user_id), 'mdblist')
    if (!listId) return []

    return [{
      key: `top:${owner}/${listId}`,
      owner,
      listId,
      title: firstNonBlank(entry.name, `${owner}/${listId}`),
      itemCount: positiveInt(entry.items),
      isPersonal: false
    }]
  })

  return okJson({ lists })
})
