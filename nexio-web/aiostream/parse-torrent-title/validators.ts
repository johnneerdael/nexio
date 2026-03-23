// @ts-nocheck
import type { HandlerMatchValidator } from './types.js';

/**
 * Validators - matching Go validate_* functions
 */

export function validateOr(
  ...validators: HandlerMatchValidator[]
): HandlerMatchValidator {
  return (input: string, idxs: number[]): boolean => {
    return validators.some((v) => v(input, idxs));
  };
}

export function validateAnd(
  ...validators: HandlerMatchValidator[]
): HandlerMatchValidator {
  return (input: string, idxs: number[]): boolean => {
    return validators.every((v) => v(input, idxs));
  };
}

export function validateLookbehind(
  pattern: string,
  flags: string,
  polarity: boolean
): HandlerMatchValidator {
  const flagStr = flags.toLowerCase().replace(/[^gimsuy]/g, '');
  const re = new RegExp(pattern + '$', flagStr);

  return (input: string, match: number[]): boolean => {
    const rv = input.substring(0, match[0]);

    if (polarity) {
      return re.test(rv);
    }
    return !re.test(rv);
  };
}

export function validateLookahead(
  pattern: string,
  flags: string,
  polarity: boolean
): HandlerMatchValidator {
  const flagStr = flags.toLowerCase().replace(/[^gimsuy]/g, '');
  const re = new RegExp('^' + pattern, flagStr);

  return (input: string, match: number[]): boolean => {
    const rv = input.substring(match[1]);

    if (polarity) {
      return re.test(rv);
    }
    return !re.test(rv);
  };
}

export function validateNotAtStart(): HandlerMatchValidator {
  return (input: string, match: number[]): boolean => {
    return match[0] !== 0;
  };
}

export function validateNotAtEnd(): HandlerMatchValidator {
  return (input: string, match: number[]): boolean => {
    return match[1] !== input.length;
  };
}

export function validateNotMatch(re: RegExp): HandlerMatchValidator {
  return (input: string, match: number[]): boolean => {
    const rv = input.substring(match[0], match[1]);
    return !re.test(rv);
  };
}

export function validateMatch(re: RegExp): HandlerMatchValidator {
  return (input: string, match: number[]): boolean => {
    const rv = input.substring(match[0], match[1]);
    return re.test(rv);
  };
}

export function validateMatchedGroupsAreSame(
  ...indices: number[]
): HandlerMatchValidator {
  return (input: string, match: number[]): boolean => {
    const first = input.substring(
      match[indices[0] * 2],
      match[indices[0] * 2 + 1]
    );
    for (let i = 1; i < indices.length; i++) {
      const index = indices[i];
      const other = input.substring(match[index * 2], match[index * 2 + 1]);
      if (other !== first) {
        return false;
      }
    }
    return true;
  };
}
