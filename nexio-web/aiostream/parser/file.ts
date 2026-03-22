import { Parser, handlers } from '../parse-torrent-title/index.js'
import { FULL_LANGUAGE_MAPPING } from '~/formatter/languages'
import * as constants from '~/formatter/constants'
import { PARSE_REGEX } from './regex'
import type { ParsedFile } from './types'

function matchPattern(filename: string, patterns: Record<string, RegExp>): string | undefined {
  return Object.entries(patterns).find(([, pattern]) => pattern.test(filename))?.[0]
}

function matchMultiplePatterns(filename: string, patterns: Record<string, RegExp>): string[] {
  return Object.entries(patterns)
    .filter(([, pattern]) => pattern.test(filename))
    .map(([tag]) => tag)
}

function normaliseResolution(resolution: string | undefined): string | undefined {
  if (!resolution) return undefined
  const lower = resolution.toLowerCase()
  if (lower === '4k') return '2160p'
  if ((constants.RESOLUTIONS as readonly string[]).includes(lower)) return lower
  const match = lower.match(/^(\d+)p$/)
  if (!match) return undefined
  const num = parseInt(match[1] || '0', 10)
  const numericResolutions = (constants.RESOLUTIONS as readonly string[])
    .filter((value) => value !== 'Unknown')
    .map((value) => [value, parseInt(value, 10)] as const)
  return numericResolutions.reduce((prev, curr) =>
    Math.abs(curr[1] - num) < Math.abs(prev[1] - num) ? curr : prev,
  )[0]
}

export function mapLanguageCode(code: string): string {
  switch (code.toLowerCase()) {
    case 'zh-tw':
    case 'zh-hans':
      return 'zh'
    case 'es-419':
      return 'es-MX'
    default:
      return code
  }
}

export function convertLangCodeToName(code: string): string | undefined {
  const parts = code.split('-')
  const languagePart = parts[0] ?? ''
  const regionPart = parts[1] ?? ''
  const possibleLangs = FULL_LANGUAGE_MAPPING.filter((language) => {
    if (parts.length === 2) {
      return (
        language.iso_639_1?.toLowerCase() === languagePart.toLowerCase() &&
        language.iso_3166_1?.toLowerCase() === regionPart.toLowerCase()
      )
    }
    return language.iso_639_1?.toLowerCase() === languagePart.toLowerCase()
  })
  const chosenLang = possibleLangs.find((lang) => (lang as any).flag_priority) || possibleLangs[0]
  if (!chosenLang) return undefined
  const candidate = (((chosenLang as any).internal_english_name || chosenLang.english_name) as string)
    .split(/;|\(/)[0]
    ?.trim()
  return candidate && (constants.LANGUAGES as readonly string[]).includes(candidate) ? candidate : undefined
}

export class FileParser {
  private static parser = new Parser().addHandlers(
    handlers.filter((handler) => handler.field !== 'country'),
  )

  static parse(filename: string): ParsedFile {
    const parsed = this.parser.parse(filename)
    const normalisedTitle = (parsed.title || '')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/[^\p{L}\p{N}+]/gu, '')
      .toLowerCase()
    if (['vinland', 'furiosaamadmax', 'horizonanamerican'].includes(normalisedTitle) && parsed.complete) {
      parsed.title += ' Saga'
    }

    if (parsed.title && parsed.title.length > 4) {
      const escapedTitle = parsed.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const titleRegex = new RegExp(escapedTitle.replace(/ /g, '[._ ]'), 'i')
      filename = filename.replace(titleRegex, '').trim()
      filename = filename.replace(/\s+/g, '.').replace(/^\.+|\.+$/g, '')
    }

    const resolution = normaliseResolution(parsed.resolution) || matchPattern(filename, PARSE_REGEX.resolutions)
    const quality = matchPattern(filename, PARSE_REGEX.qualities)
    const encode = matchPattern(filename, PARSE_REGEX.encodes)
    const audioChannels = matchMultiplePatterns(filename, PARSE_REGEX.audioChannels)
    const visualTags = matchMultiplePatterns(filename, PARSE_REGEX.visualTags)
    const audioTags = matchMultiplePatterns(filename, PARSE_REGEX.audioTags)

    const mapParsedLanguageToKnown = (lang: string): string | undefined => {
      switch (lang.toLowerCase()) {
        case 'multi audio':
          return 'Multi'
        case 'dual audio':
          return 'Dual Audio'
        case 'multi subs':
          return undefined
        default:
          return convertLangCodeToName(mapLanguageCode(lang))
      }
    }

    let filenameForLangParsing = filename
    if (parsed.group?.toLowerCase() === 'ind') {
      filenameForLangParsing = filenameForLangParsing.replace(/ind/i, '')
    }
    const languages = [
      ...new Set([
        ...matchMultiplePatterns(filenameForLangParsing, PARSE_REGEX.languages),
        ...((parsed.languages || []).map(mapParsedLanguageToKnown).filter(Boolean) as string[]),
      ]),
    ]

    return {
      filename,
      size: 0,
      resolution,
      quality,
      languages,
      subtitles: [],
      encode,
      audioChannels,
      audioTags,
      visualTags,
      releaseGroup: filename.match(PARSE_REGEX.releaseGroup)?.[1] ?? parsed.group,
      title: parsed.title,
      year: parsed.year ? parsed.year.toString() : undefined,
      subbed: parsed.subbed ?? false,
      dubbed: parsed.dubbed ?? false,
      editions: parsed.editions,
      regraded: parsed.regraded ?? false,
      repack: parsed.repack ?? false,
      uncensored: parsed.uncensored ?? false,
      unrated: parsed.unrated ?? false,
      upscaled: parsed.upscaled ?? false,
      network: parsed.network,
      container: parsed.container,
      extension: parsed.extension,
      seasons: parsed.seasons,
      episodes: parsed.episodes,
      date: parsed.date,
      seasonPack: !!(parsed.seasons?.length && !parsed.episodes?.length),
      folderSeasons: undefined,
      folderEpisodes: undefined,
    }
  }
}
