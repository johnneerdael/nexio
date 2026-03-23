import { resolveFormatterIconToken } from '../../../utils/formatter-icon-tokens.mjs'

const tokenPattern = /\[\[icon:([a-z0-9_]+)\]\]/gi

export function parseFormatterRichText(text, _variant = 'detail') {
  if (!text) return []

  const segments = []
  let cursor = 0

  for (const match of text.matchAll(tokenPattern)) {
    const fullMatch = match[0]
    const rawId = match[1] ?? ''
    const start = match.index ?? 0

    if (start > cursor) {
      appendTextSegment(segments, text.slice(cursor, start))
    }

    const token = resolveFormatterIconToken(rawId)
    if (token) {
      segments.push({
        type: 'icon',
        id: token.id,
        src: token.src,
        fallbackLabel: token.fallbackLabel,
        scaleClass: token.scaleClass,
        token,
      })
    } else {
      appendTextSegment(segments, fullMatch)
    }

    cursor = start + fullMatch.length
  }

  if (cursor < text.length) {
    appendTextSegment(segments, text.slice(cursor))
  }

  return segments
}

export function formatterRichTextToPlainText(text) {
  if (!text) return ''

  let result = ''
  let cursor = 0

  for (const match of text.matchAll(tokenPattern)) {
    const fullMatch = match[0]
    const rawId = match[1] ?? ''
    const start = match.index ?? 0

    if (start > cursor) {
      result += text.slice(cursor, start)
    }

    const token = resolveFormatterIconToken(rawId)
    if (!token) {
      result += fullMatch
    } else if (!nextSegmentStartsWithLabel(text.slice(start + fullMatch.length), token.fallbackLabel)) {
      result += token.fallbackLabel
    }

    cursor = start + fullMatch.length
  }

  if (cursor < text.length) {
    result += text.slice(cursor)
  }

  return result
}

function appendTextSegment(segments, text) {
  if (!text) return
  const last = segments.at(-1)
  if (last?.type === 'text') {
    last.text += text
  } else {
    segments.push({ type: 'text', text })
  }
}

function nextSegmentStartsWithLabel(text, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`^\\s*${escaped}(?:\\b|\\s|$)`, 'i').test(text)
}
