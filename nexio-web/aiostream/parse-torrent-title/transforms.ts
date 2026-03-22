// @ts-nocheck
import type { HandlerTransformer, ParseMeta } from './types.js';
import { ValueSet } from './types.js';
import { nonDigitsRegex } from './utils.js';

/**
 * Transformers - matching Go to_* functions
 */

export function toValue(value: string): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    m.value = value;
  };
}

export function toLowercase(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    m.value = (m.value as string).toLowerCase();
  };
}

export function toUppercase(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    m.value = (m.value as string).toUpperCase();
  };
}

export function toTrimmed(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    m.value = (m.value as string).trim();
  };
}

export function toCleanDate(): HandlerTransformer {
  const re = /(\d+)(?:st|nd|rd|th)/g;
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value === 'string') {
      m.value = m.value.replace(re, '$1');
    }
  };
}

export function toCleanMonth(): HandlerTransformer {
  const re =
    /(?:feb(?:ruary)?|jan(?:uary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)/gi;
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value === 'string') {
      m.value = m.value.replace(re, (str: string) => str.substring(0, 3));
    }
  };
}

export function toDate(format: string): HandlerTransformer {
  const separatorRe = /[.\-/\\]/g;
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value === 'string') {
      const normalized = m.value.replace(separatorRe, ' ');
      const parsed = parseDate(normalized, format);
      m.value = parsed || '';
    }
  };
}

/**
 * Simple date parser matching Go's time.Parse behavior
 */
function parseDate(dateStr: string, format: string): string | null {
  try {
    // Handle compact formats like 20060102 (YYYYMMDD)
    if (format === '20060102' && dateStr.length === 8) {
      const year = parseInt(dateStr.substring(0, 4));
      const month = parseInt(dateStr.substring(4, 6));
      const day = parseInt(dateStr.substring(6, 8));
      if (year && month && day) {
        const date = new Date(Date.UTC(year, month - 1, day));
        if (!isNaN(date.getTime())) {
          return date.toISOString().split('T')[0];
        }
      }
      return null;
    }

    // Map Go format to JS parsing
    // This is a simplified version - you may need to enhance this
    const parts = dateStr.trim().split(/\s+/);
    const formatParts = format.split(/\s+/);

    let year = 0,
      month = 0,
      day = 0;

    for (let i = 0; i < formatParts.length && i < parts.length; i++) {
      const fmt = formatParts[i];
      const val = parts[i];

      if (fmt === '2006' || fmt === 'YYYY') {
        year = parseInt(val);
      } else if (fmt === '06' || fmt === 'YY') {
        year = 2000 + parseInt(val);
        if (year > 2069) year -= 100;
      } else if (fmt === '01' || fmt === 'MM') {
        month = parseInt(val);
      } else if (fmt === '02' || fmt === 'DD' || fmt === '_2') {
        day = parseInt(val);
      } else if (fmt === 'Jan' || fmt === 'MMM') {
        const months = [
          'jan',
          'feb',
          'mar',
          'apr',
          'may',
          'jun',
          'jul',
          'aug',
          'sep',
          'oct',
          'nov',
          'dec'
        ];
        month = months.indexOf(val.toLowerCase().substring(0, 3)) + 1;
      } else if (fmt === 'YYYYMMDD') {
        year = parseInt(val.substring(0, 4));
        month = parseInt(val.substring(4, 6));
        day = parseInt(val.substring(6, 8));
      }
    }

    if (year && month && day) {
      // Use Date.UTC to avoid timezone conversion issues
      const date = new Date(Date.UTC(year, month - 1, day));
      if (!isNaN(date.getTime())) {
        return date.toISOString().split('T')[0];
      }
    }
    return null;
  } catch {
    return null;
  }
}

export function toYear(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value !== 'string') {
      m.value = '';
      return;
    }

    const parts = m.value.split(nonDigitsRegex).filter((p) => p);
    if (parts.length === 1) {
      m.value = parts[0];
      return;
    }

    const start = parts[0];
    const end = parts[1];
    let endYear = parseInt(end);

    if (isNaN(endYear)) {
      m.value = start;
      return;
    }

    const startYear = parseInt(start);
    if (isNaN(startYear)) {
      m.value = '';
      return;
    }

    if (endYear < 100) {
      endYear = endYear + startYear - (startYear % 100);
    }

    if (endYear <= startYear) {
      m.value = '';
      return;
    }

    m.value = `${startYear}-${endYear}`;
  };
}

export function toIntRange(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value !== 'string') {
      m.value = null;
      return;
    }

    const parts = m.value
      .replace(nonDigitsRegex, ' ')
      .trim()
      .split(' ')
      .filter((p) => p);
    const nums = parts.map((p) => parseInt(p)).filter((n) => !isNaN(n));

    if (nums.length === 2 && nums[0] < nums[1]) {
      const seq: number[] = [];
      for (let i = nums[0]; i <= nums[1]; i++) {
        seq.push(i);
      }
      m.value = seq;
      return;
    }

    // Check if in sequence and ascending order
    for (let i = 0; i < nums.length - 1; i++) {
      if (nums[i] + 1 !== nums[i + 1]) {
        m.value = null;
        return;
      }
    }

    m.value = nums;
  };
}

export function toWithSuffix(suffix: string): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value === 'string') {
      m.value = m.value + suffix;
    } else {
      m.value = '';
    }
  };
}

export function toBoolean(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    m.value = true;
  };
}

export function toValueSet(v: any): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (m.value instanceof ValueSet) {
      m.value.append(v);
    }
  };
}

export function toValueSetWithTransform(
  toV: (v: string) => any
): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (m.value instanceof ValueSet) {
      m.value.append(toV(m.mValue));
    }
  };
}

export function toValueSetMultiWithTransform(
  toV: (v: string) => any[]
): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (m.value instanceof ValueSet) {
      const values = toV(m.mValue);
      for (const val of values) {
        m.value.append(val);
      }
    }
  };
}

export function toIntArray(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value === 'string') {
      const num = parseInt(m.value);
      m.value = !isNaN(num) ? [num] : [];
    } else {
      m.value = [];
    }
  };
}



export function toIntRangeTill(): HandlerTransformer {
  return (title: string, m: ParseMeta): void => {
    if (typeof m.value !== 'string') {
      m.value = null;
      return;
    }

    const parts = m.value
      .replace(nonDigitsRegex, ' ')
      .trim()
      .split(' ')
      .filter((p) => p);
    
    if (parts.length === 0) {
      m.value = null;
      return;
    }

    const num = parseInt(parts[0]);
    if (!isNaN(num)) {
      const nums: number[] = [];
      for (let i = 1; i <= num; i++) {
        nums.push(i);
      }
      m.value = nums;
      return;
    }

    m.value = null;
  };
}
