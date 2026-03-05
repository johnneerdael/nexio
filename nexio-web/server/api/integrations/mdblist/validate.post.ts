import { createError } from 'h3'
import { okJson, readJsonBody } from '~/server/utils/supabase'
import type { MDBListListOption } from '~/types/portal'

type ValidateBody = {
  apiKey?: string
}

function firstNonBlank(...values: Array<string | undefined | null>): string {
  return values.find((value) => value && value.trim())?.trim() ?? ''
}

function positiveInt(...values: number[]): number {
  return values.find((value) => Number.isFinite(value) && value >= 0) ?? 0
}

function parseJsonArray(raw: string): any[] {
  if (!raw.trim()) {
    return []
  }

  const parsed = JSON.parse(raw)
  if (Array.isArray(parsed)) {
    return parsed
  }

  const containers = [parsed.lists, parsed.results, parsed.items, parsed.data?.items, parsed.data?.results, parsed.data?.lists]
  return containers.find(Array.isArray) ?? []
}

function parseListOptions(entries: any[], isPersonal: boolean): MDBListListOption[] {
  const prefix = isPersonal ? 'personal' : 'top'
  return entries.flatMap((entry) => {
    const list = entry?.list ?? entry ?? {}
    const ownerObj = entry?.user ?? list.user ?? {}
    const owner = firstNonBlank(ownerObj.slug, ownerObj.username, list.owner, 'mdblist')
    const listId = firstNonBlank(list.slug, list.id, list.uuid, list.list_id)
    if (!listId) {
      return []
    }

    return [{
      key: `${prefix}:${owner}/${listId}`,
      owner,
      listId,
      title: firstNonBlank(list.name, list.title, `${owner}/${listId}`),
      itemCount: positiveInt(list.item_count, list.items, list.count),
      isPersonal
    }]
  })
}

async function requestListOptions(apiKey: string, relativeUrl: string): Promise<any[]> {
  try {
    const response = await fetch(`https://api.mdblist.com/${relativeUrl}?apikey=${encodeURIComponent(apiKey)}`)
    if (!response.ok) {
      return []
    }
    return parseJsonArray(await response.text())
  } catch {
    return []
  }
}

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<ValidateBody>(event)
  const apiKey = body.apiKey?.trim()
  if (!apiKey) {
    throw createError({ statusCode: 400, statusMessage: 'MDBList API key is required.' })
  }

  const profileResponse = await fetch(`https://api.mdblist.com/user?apikey=${encodeURIComponent(apiKey)}`)
  if (!profileResponse.ok) {
    throw createError({ statusCode: 400, statusMessage: `MDBList validation failed (${profileResponse.status}).` })
  }

  const personalLists = [
    ...parseListOptions(await requestListOptions(apiKey, 'lists/user'), true),
    ...parseListOptions(await requestListOptions(apiKey, 'my/lists'), true),
    ...parseListOptions(await requestListOptions(apiKey, 'lists/me'), true)
  ].filter((entry, index, list) => list.findIndex((candidate) => candidate.key === entry.key) === index)

  const topLists = [
    ...parseListOptions(await requestListOptions(apiKey, 'lists/top'), false),
    ...parseListOptions(await requestListOptions(apiKey, 'top/lists'), false)
  ].filter((entry, index, list) => list.findIndex((candidate) => candidate.key === entry.key) === index)

  return okJson({
    valid: true,
    personalLists,
    topLists
  })
})
