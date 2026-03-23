// @ts-nocheck
import type { Handler, ParsedResult, ParseMeta } from './types.js';
import { ValueSet } from './types.js';
import {
  cleanTitle,
  beforeTitleRegex,
  whitespacesRegex,
  underscoresRegex,
  getMatchIndices,
  trailingEpisodePattern
} from './utils.js';

/**
 * Fields that use value sets
 */
const VALUE_SET_FIELDS = new Set([
  'audio',
  'channels',
  'editions',
  'hdr',
  'languages',
  'releaseTypes'
]);

function hasValueSet(field: string): boolean {
  return VALUE_SET_FIELDS.has(field);
}

/**
 * Main parse function matching Go parse() function
 */
export function parse(title: string, handlers: Handler[]): ParsedResult {
  const result = new Map<string, ParseMeta>();

  title = title.replace(whitespacesRegex, ' ');
  title = title.replace(underscoresRegex, ' ');

  let endOfTitle = title.length;

  for (const handler of handlers) {
    const field = handler.field;
    let skipFromTitle = handler.skipFromTitle ?? false;

    let m = result.get(field);
    const mFound = m !== undefined;

    if (handler.pattern) {
      if (mFound && !handler.keepMatching) {
        continue;
      }

      const idxs = getMatchIndices(handler.pattern, title);
      if (idxs.length === 0) {
        continue;
      }

      if (handler.validateMatch && !handler.validateMatch(title, idxs)) {
        continue;
      }

      let shouldSkip = false;
      if (handler.skipIfFirst) {
        let hasOther = false;
        let hasBefore = false;
        for (const [f, fm] of result) {
          if (f !== field) {
            hasOther = true;
            if (idxs[0] >= fm.mIndex) {
              hasBefore = true;
              break;
            }
          }
        }
        shouldSkip = hasOther && !hasBefore;
      }

      if (shouldSkip) {
        continue;
      }

      if (handler.skipIfBefore && handler.skipIfBefore.length > 0) {
        for (const skipField of handler.skipIfBefore) {
          const fm = result.get(skipField);
          if (fm && idxs[0] < fm.mIndex) {
            shouldSkip = true;
            break;
          }
        }
        if (shouldSkip) {
          continue;
        }
      }

      const rawMatchedPart = title.substring(idxs[0], idxs[1]);
      let matchedPart = rawMatchedPart;

      if (idxs.length > 2) {
        // Default to capture group 1 if valueGroup is not specified
        if (handler.valueGroup === undefined || handler.valueGroup === 0) {
          matchedPart = title.substring(idxs[2], idxs[3]);
        } else if (idxs.length > handler.valueGroup * 2) {
          matchedPart = title.substring(
            idxs[handler.valueGroup * 2],
            idxs[handler.valueGroup * 2 + 1]
          );
        }
      }

      const beforeTitleMatch = beforeTitleRegex.exec(title);
      if (beforeTitleMatch && beforeTitleMatch[0].includes(rawMatchedPart)) {
        skipFromTitle = true;
      }

      if (!mFound) {
        m = {
          mIndex: 0,
          mValue: '',
          value: hasValueSet(field) ? new ValueSet<any>() : null,
          remove: false,
          processed: false
        };
        result.set(field, m);
      }

      if (m) {
        m.mIndex = idxs[0];
        m.mValue = rawMatchedPart;
        if (!hasValueSet(field)) {
          m.value = matchedPart;
        }

        if (handler.matchGroup) {
          m.mIndex = idxs[handler.matchGroup * 2];
          m.mValue = title.substring(
            idxs[handler.matchGroup * 2],
            idxs[handler.matchGroup * 2 + 1]
          );
        }
      }
    }

    if (handler.process) {
      if (mFound && m) {
        m = handler.process(title, m, result);
      } else {
        const emptyMeta: ParseMeta = {
          mIndex: 0,
          mValue: '',
          value: null,
          remove: false,
          processed: false
        };
        m = handler.process(title, emptyMeta, result);
        if (m.value !== null) {
          result.set(field, m);
        }
      }
    }

    if (!m) {
      continue;
    }

    if (m.value !== null && handler.transform) {
      handler.transform(title, m, result);
    }

    if (m.value === null) {
      result.delete(field);
      continue;
    }

    if (
      !result.has(field) ||
      (m.processed && !handler.keepMatching && !hasValueSet(field))
    ) {
      continue;
    }

    if (handler.remove || m.remove) {
      m.remove = true;
      title =
        title.substring(0, m.mIndex) +
        title.substring(m.mIndex + m.mValue.length);
    }

    if (!skipFromTitle && m.mIndex !== 0 && m.mIndex < endOfTitle) {
      endOfTitle = m.mIndex;
    }

    if (
      m.remove &&
      (skipFromTitle || m.mIndex === 0) &&
      m.mIndex < endOfTitle
    ) {
      // adjust title index in case part of it should be removed and skipped
      endOfTitle -= m.mValue.length;
    }

    m.remove = false;
    m.processed = true;
  }

  // Build final result object
  const finalResult: Partial<ParsedResult> = {};

  for (const [field, fieldMeta] of result) {
    const v = fieldMeta.value;

    switch (field) {
      case 'audio':
        if (v instanceof ValueSet) {
          finalResult.audio = v.values as string[];
        }
        break;
      case 'bitDepth':
        finalResult.bitDepth = v as string;
        break;
      case 'channels':
        if (v instanceof ValueSet) {
          finalResult.channels = v.values as string[];
        }
        break;
      case 'codec':
        finalResult.codec = v as string;
        break;
      case 'commentary':
        finalResult.commentary = v as boolean;
        break;
      case 'complete':
        finalResult.complete = v as boolean;
        break;
      case 'container':
        finalResult.container = v as string;
        break;
      case 'convert':
        finalResult.convert = v as boolean;
        break;
      case 'date':
        finalResult.date = v as string;
        break;
      case 'documentary':
        finalResult.documentary = v as boolean;
        break;
      case 'ppv':
        finalResult.ppv = v as boolean;
        break;
      case 'dubbed':
        finalResult.dubbed = v as boolean;
        break;
      case 'editions':
        if (v instanceof ValueSet) {
          finalResult.editions = v.values as string[];
        }
        break;
      case 'episodeCode':
        finalResult.episodeCode = v as string;
        break;
      case 'episodes':
        finalResult.episodes = v as number[];
        break;
      case 'extended':
        finalResult.extended = v as boolean;
        break;
      case 'extension':
        finalResult.extension = v as string;
        break;
      case 'group':
        finalResult.group = v as string;
        break;
      case 'hardcoded':
        finalResult.hardcoded = v as boolean;
        break;
      case 'hdr':
        if (v instanceof ValueSet) {
          finalResult.hdr = v.values as string[];
        }
        break;
      case 'languages':
        if (v instanceof ValueSet) {
          const languages = v.values as string[];
          // If Latin American Spanish (es-419) is present, remove generic Spanish (es)
          if (languages.includes('es-419') && languages.includes('es')) {
            finalResult.languages = languages.filter((lang) => lang !== 'es');
          } else {
            finalResult.languages = languages;
          }
        }
        break;
      case 'network':
        finalResult.network = v as string;
        break;
      case 'proper':
        finalResult.proper = v as boolean;
        break;
      case 'region':
        finalResult.region = v as string;
        break;
      case 'repack':
        finalResult.repack = v as boolean;
        break;
      case 'resolution':
        finalResult.resolution = v as string;
        break;
      case 'retail':
        finalResult.retail = v as boolean;
        break;
      case 'seasons':
        finalResult.seasons = v as number[];
        break;
      case 'size':
        finalResult.size = v as string;
        break;
      case 'site':
        finalResult.site = v as string;
        break;
      case 'quality':
        finalResult.quality = v as string;
        break;
      case 'releaseTypes':
        if (v instanceof ValueSet) {
          finalResult.releaseTypes = v.values as string[];
        }
        break;
      case 'subbed':
        finalResult.subbed = v as boolean;
        break;
      case 'threeD':
        finalResult.threeD = v as string;
        break;
      case 'uncensored':
        finalResult.uncensored = v as boolean;
        break;
      case 'unrated':
        finalResult.unrated = v as boolean;
        break;
      case 'regraded':
        finalResult.regraded = v as boolean;
        break;
      case 'upscaled':
        finalResult.upscaled = v as boolean;
        break;
      case 'volumes':
        finalResult.volumes = v as number[];
        break;
      case 'year':
        finalResult.year = v as string;
        break;
      default:
        // Handle custom fields not in the predefined list
        (finalResult as any)[field] = v;
        break;
    }
  }

  const titleEnd = Math.max(Math.min(endOfTitle, title.length), 0);
  let rawTitle = title.substring(0, titleEnd);

  if (finalResult.episodes && finalResult.episodes.length > 0) {
    rawTitle = rawTitle.replace(trailingEpisodePattern, '');
  }

  finalResult.title = cleanTitle(rawTitle);

  return finalResult as ParsedResult;
}
