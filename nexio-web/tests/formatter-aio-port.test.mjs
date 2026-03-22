import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const webTemplates = readFileSync(
  new URL('../utils/formatter-templates.ts', import.meta.url),
  'utf8',
)

const androidTemplates = readFileSync(
  new URL('../../app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt', import.meta.url),
  'utf8',
)

const previewBuilder = readFileSync(
  new URL('../utils/formatter-preview.ts', import.meta.url),
  'utf8',
)

test('web universal template uses the updated compact 4K/2K/HD labels', () => {
  assert.match(webTemplates, /⭐⭐⭐⭐⭐ 4K/)
  assert.match(webTemplates, /⭐⭐⭐☆☆ HD/)
  assert.match(webTemplates, /🗣️ \{stream\.uLanguageEmojis::join/)
  assert.doesNotMatch(webTemplates, /💎 Cached/)
})

test('android universal template uses the updated compact 4K/2K/HD labels', () => {
  assert.match(androidTemplates, /⭐⭐⭐⭐⭐ 4K/)
  assert.match(androidTemplates, /⭐⭐⭐☆☆ HD/)
  assert.match(androidTemplates, /🗣️ \{stream\.uLanguageEmojis::join/)
  assert.doesNotMatch(androidTemplates, /💎 Cached/)
})

test('web formatter preview no longer relies on the local parseParsedFile shim', () => {
  assert.doesNotMatch(previewBuilder, /function parseParsedFile\(/)
  assert.match(previewBuilder, /aiostream\/parser\/streams/)
})
