// @ts-nocheck
/**
 * Processors
 */

import type { HandlerProcessor, HandlerMatchValidator, ParseMeta } from './types.js';
import { getMatchIndices } from './utils.js';

export function removeFromValue(re: RegExp): HandlerProcessor {
  return (title: string, m: ParseMeta): ParseMeta => {
    if (typeof m.value === 'string' && m.value !== '') {
      m.value = m.value.replace(re, '');
    }
    return m;
  };
}

export function regexMatchUntilValid(
  re: RegExp,
  validator: HandlerMatchValidator
): HandlerProcessor {
  return (title: string, m: ParseMeta): ParseMeta => {
    let offset = 0;

    while (offset < title.length) {
      const substring = title.substring(offset);
      const idxs = getMatchIndices(re, substring);

      if (idxs.length === 0) {
        return m;
      }

      // Adjust indices to account for offset
      for (let i = 0; i < idxs.length; i++) {
        if (idxs[i] >= 0) {
          idxs[i] += offset;
        }
      }

      if (validator(title, idxs)) {
        m.mIndex = idxs[0];
        m.mValue = title.substring(idxs[0], idxs[1]);

        if (idxs.length >= 4 && idxs[2] >= 0 && idxs[3] >= 0) {
          m.value = title.substring(idxs[2], idxs[3]);
        } else {
          m.value = m.mValue;
        }

        return m;
      }

      offset = idxs[1];
      if (offset === idxs[0]) {
        offset++;
      }
    }

    return m;
  };
}
