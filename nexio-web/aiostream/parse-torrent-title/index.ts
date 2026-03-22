// @ts-nocheck
/**
 * Parse Torrent Title - TypeScript Port
 *
 * A 1:1 port of the Go torrent title parser
 */

import type { ParsedResult, Handler } from './types.js';
import { parse } from './parser.js';
import { handlers as defaultHandlers } from './handlers.js';

/**
 * Parser class for customisable torrent title parsing
 */
export class Parser {
  private handlers: Handler[] = [];

  /**
   * Add a custom handler to the parser
   * @param handler - The handler to add
   * @returns The parser instance for chaining
   */
  addHandler(handler: Handler): Parser {
    if (!handler) {
      throw new Error('Handler cannot be undefined or null');
    }
    this.handlers.push(handler);
    return this;
  }

  /**
   * Add multiple custom handlers to the parser
   * @param handlers - Array of handlers to add
   * @returns The parser instance for chaining
   */
  addHandlers(handlers: Handler[]): Parser {
    if (!handlers || handlers.length === 0) {
      throw new Error('Handlers array cannot be undefined, null, or empty');
    }
    if (handlers.some((h) => h === null || h === undefined)) {
      throw new Error(
        'Handlers array cannot contain null or undefined handlers'
      );
    }
    this.handlers.push(...handlers);
    return this;
  }

  /**
   * Add default handlers for specific fields
   * @param fields - Optional array of field names to include. If empty, all default handlers are added.
   * @returns The parser instance for chaining
   */
  addDefaultHandlers(...fields: string[]): Parser {
    if (fields.length === 0) {
      this.handlers.push(...defaultHandlers);
    } else {
      // Add only handlers for specified fields
      const selectedFieldMap = new Set(fields);
      const selectedHandlers = defaultHandlers.filter((h) =>
        selectedFieldMap.has(h.field)
      );
      this.handlers.push(...selectedHandlers);
    }
    return this;
  }

  /**
   * Parse a torrent title using the configured handlers
   * @param title - The torrent title to parse
   * @returns Parsed result with extracted metadata and any custom fields
   * @template T - Optional type for custom fields added by custom handlers
   *
   * @example
   * // Without custom fields
   * const result = parser.parse('Movie.2024.1080p');
   *
   * @example
   * // With typed custom fields
   * const result = parser.parse<{ customId: number[] }>('Movie.2024.custom-123.1080p');
   * console.log(result.customId); // Type-safe access
   */
  parse<T extends Record<string, any> = Record<string, never>>(
    title: string
  ): ParsedResult & T {
    return parse(title, this.handlers) as ParsedResult & T;
  }
}

/**
 * Parse a torrent title and extract metadata using all default handlers
 * @param title - The torrent title to parse
 * @returns Parsed result with extracted metadata
 */
export function parseTorrentTitle(title: string): ParsedResult {
  return parse(title, defaultHandlers);
}

/**
 * Create a partial parser with only specific fields
 * @param fieldNames - Array of field names to parse
 * @returns A parser function that only extracts the specified fields
 */
export function getPartialParser(
  fieldNames: string[]
): (title: string) => ParsedResult {
  const selectedFieldMap = new Set(fieldNames);

  const selectedHandlers = defaultHandlers.filter((h) =>
    selectedFieldMap.has(h.field)
  );

  return (title: string) => parse(title, selectedHandlers);
}

import * as transforms from './transforms.js';
import * as processors from './processors.js';
import * as validators from './validators.js';

export type { ParsedResult, Handler } from './types.js';
export { handlers } from './handlers.js';
export { transforms, processors, validators };
