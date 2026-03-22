import type { ParsedFile } from './types'

export function arrayMerge<T>(arr1: T[] | undefined, arr2: T[] | undefined): T[] {
  return Array.from(new Set([...(arr1 ?? []), ...(arr2 ?? [])]))
}

function richerParsedFile(
  fileParsed: ParsedFile | undefined,
  folderParsed: ParsedFile | undefined,
): ParsedFile | undefined {
  if (!fileParsed) return folderParsed
  if (!folderParsed) return fileParsed

  const importantFields: (keyof ParsedFile)[] = [
    'title',
    'year',
    'seasons',
    'episodes',
    'resolution',
    'quality',
    'encode',
    'releaseGroup',
    'editions',
    'regraded',
    'repack',
    'uncensored',
    'unrated',
    'upscaled',
    'network',
    'container',
    'extension',
    'visualTags',
    'audioTags',
    'audioChannels',
    'languages',
    'subtitles',
    'seasonPack',
  ]

  const richness = (parsed: ParsedFile) =>
    importantFields.reduce((count, field) => count + (parsed[field] ? 1 : 0), 0)

  return richness(fileParsed) >= richness(folderParsed) ? fileParsed : folderParsed
}

export function mergeParsedFiles(
  fileParsed: ParsedFile | undefined,
  folderParsed: ParsedFile | undefined,
  overrides?: Partial<ParsedFile>,
): ParsedFile | undefined {
  if (!fileParsed && !folderParsed) return undefined

  function arrayFallback<T>(...arrs: Array<T[] | undefined>): T[] | undefined {
    for (const arr of arrs) {
      if (arr && arr.length > 0) return arr
    }
    return undefined
  }

  const episodes = arrayFallback(fileParsed?.episodes, folderParsed?.episodes)
  const seasons = arrayFallback(fileParsed?.seasons, folderParsed?.seasons)
  const richest = richerParsedFile(fileParsed, folderParsed)

  return {
    filename: fileParsed?.filename ?? folderParsed?.filename ?? '',
    size: fileParsed?.size ?? folderParsed?.size ?? 0,
    title: richest?.title || folderParsed?.title || fileParsed?.title,
    year: fileParsed?.year || folderParsed?.year,
    folderSeasons: seasons !== folderParsed?.seasons ? folderParsed?.seasons : undefined,
    folderEpisodes: episodes !== folderParsed?.episodes ? folderParsed?.episodes : undefined,
    seasons,
    episodes,
    date: fileParsed?.date || folderParsed?.date,
    resolution: fileParsed?.resolution || folderParsed?.resolution,
    quality: fileParsed?.quality || folderParsed?.quality,
    encode: fileParsed?.encode || folderParsed?.encode,
    releaseGroup: fileParsed?.releaseGroup || folderParsed?.releaseGroup,
    editions: arrayMerge(folderParsed?.editions, fileParsed?.editions),
    regraded: fileParsed?.regraded || folderParsed?.regraded,
    repack: fileParsed?.repack || folderParsed?.repack,
    uncensored: fileParsed?.uncensored || folderParsed?.uncensored,
    unrated: fileParsed?.unrated || folderParsed?.unrated,
    upscaled: fileParsed?.upscaled || folderParsed?.upscaled,
    network: fileParsed?.network || folderParsed?.network,
    container: fileParsed?.container || folderParsed?.container,
    extension: fileParsed?.extension || folderParsed?.extension,
    visualTags: arrayMerge(folderParsed?.visualTags, fileParsed?.visualTags),
    audioTags: arrayMerge(folderParsed?.audioTags, fileParsed?.audioTags),
    audioChannels: arrayMerge(folderParsed?.audioChannels, fileParsed?.audioChannels),
    languages: arrayMerge(folderParsed?.languages, fileParsed?.languages),
    subtitles: arrayMerge(folderParsed?.subtitles, fileParsed?.subtitles),
    subbed: fileParsed?.subbed || folderParsed?.subbed || false,
    dubbed: fileParsed?.dubbed || folderParsed?.dubbed || false,
    seasonPack: folderParsed?.seasonPack || fileParsed?.seasonPack || false,
    ...overrides,
  }
}
