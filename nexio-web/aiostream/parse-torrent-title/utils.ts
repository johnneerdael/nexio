// @ts-nocheck
/**
 * Utility regex patterns and helper functions
 * Matching ptt.go patterns
 */

// Unicode property escapes aren't fully supported in JS regex
// We'll use XRegExp or create character ranges for non-English chars
export const NON_ENGLISH_CHARS =
  '\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\u0400-\\u04FF';

// Main regex patterns from ptt.go
export const russianCastRegex = new RegExp(
  `(\\([^)]*[${NON_ENGLISH_CHARS}][^)]*\\))$|(?:\\/.*?)(\\(.*\\))$`,
  'u'
);
export const altTitlesRegex = new RegExp(
  `[^/|(]*[${NON_ENGLISH_CHARS}][^/|]*[/|]|[/|][^/|(]*[${NON_ENGLISH_CHARS}][^/|]*`,
  'gu'
);
export const notOnlyNonEnglishRegex = new RegExp(
  `(?:[a-zA-Z][^${NON_ENGLISH_CHARS}]+)([${NON_ENGLISH_CHARS}].*[${NON_ENGLISH_CHARS}])|([${NON_ENGLISH_CHARS}].*[${NON_ENGLISH_CHARS}])(?:[^${NON_ENGLISH_CHARS}]+[a-zA-Z])`,
  'u'
);
export const notAllowedSymbolsAtStartAndEndRegex = new RegExp(
  `^[^\\w${NON_ENGLISH_CHARS}#[【★]+|[ \\-:/\\\\[|{(#$&^]+$`,
  'gu'
);
export const remainingNotAllowedSymbolsAtStartAndEndRegex = new RegExp(
  `^[^\\w${NON_ENGLISH_CHARS}#]+|[\\[\\]({} ]+$`,
  'gu'
);

export const movieIndicatorRegex = /[[(]movie[)\]]/gi;
export const releaseGroupMarkingAtStartRegex = /^[[【★].*[\]】★][ .]?(.+)/;
export const releaseGroupMarkingAtEndRegex = /(.+)[ .]?[[【★].*[\]】★]$/;

export const beforeTitleRegex = /^\[([^[\]]+)]/;
export const nonDigitRegex = /\D/;
export const nonDigitsRegex = /\D+/g;
export const nonAlphasRegex = /\W+/g;
export const underscoresRegex = /_+/g;
export const whitespacesRegex = /\s+/g;

export const redundantSymbolsAtEnd = /[ \-:./\\]+$/;
export const trailingEpisodePattern = /[ .]*-[ .]*\d{1,4}[ .]*$/;

export const curlyBrackets = ['{', '}'];
export const squareBrackets = ['[', ']'];
export const parentheses = ['(', ')'];
export const brackets = [curlyBrackets, squareBrackets, parentheses];

/**
 * Clean title matching Go clean_title function
 */
export function cleanTitle(rawTitle: string): string {
  let title = rawTitle.trim();

  title = title.replace(/_/g, ' ');
  title = title.replace(movieIndicatorRegex, ''); // clear movie indication flag
  title = title.replace(notAllowedSymbolsAtStartAndEndRegex, '');

  // clear russian cast information
  const russianMatches = title.match(russianCastRegex);
  if (russianMatches) {
    for (let i = 1; i < russianMatches.length; i++) {
      if (russianMatches[i]) {
        title = title.replace(russianMatches[i], '');
      }
    }
  }

  title = title.replace(releaseGroupMarkingAtStartRegex, '$1'); // remove release group markings sections from the start
  title = title.replace(releaseGroupMarkingAtEndRegex, '$1'); // remove unneeded markings section at the end if present
  title = title.replace(altTitlesRegex, ''); // remove alt language titles

  // remove non english chars if they are not the only ones left
  const notOnlyNonEnglishMatch = title.match(notOnlyNonEnglishRegex);
  if (notOnlyNonEnglishMatch) {
    for (let i = 1; i < notOnlyNonEnglishMatch.length; i++) {
      if (notOnlyNonEnglishMatch[i]) {
        title = title.replace(notOnlyNonEnglishMatch[i], '');
        break;
      }
    }
  }

  title = title.replace(remainingNotAllowedSymbolsAtStartAndEndRegex, '');

  if (!title.includes(' ') && title.includes('.')) {
    title = title.replace(/\./g, ' ');
  }

  for (const [open, close] of brackets) {
    const openCount = (title.match(new RegExp('\\' + open, 'g')) || []).length;
    const closeCount = (title.match(new RegExp('\\' + close, 'g')) || [])
      .length;
    if (openCount !== closeCount) {
      title = title
        .replace(new RegExp('\\' + open, 'g'), '')
        .replace(new RegExp('\\' + close, 'g'), '');
    }
  }

  title = title.replace(redundantSymbolsAtEnd, '');
  title = title.replace(whitespacesRegex, ' ');

  return title.trim();
}

/**
 * Helper to get all regex match indices (like Go FindStringSubmatchIndex)
 */
export function getMatchIndices(regex: RegExp, str: string): number[] {
  const match = regex.exec(str);
  if (!match) return [];

  const indices: number[] = [];
  indices.push(match.index, match.index + match[0].length);

  for (let i = 1; i < match.length; i++) {
    if (match[i] !== undefined) {
      const captureIndex = str.indexOf(match[i], match.index);
      indices.push(captureIndex, captureIndex + match[i].length);
    } else {
      indices.push(-1, -1);
    }
  }

  return indices;
}
