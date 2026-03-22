import test from 'node:test'
import assert from 'node:assert/strict'
import { parseFormatterRichText } from '../formatter/rich-text.js'

test('inline icon tokens keep title badges prominent', () => {
  assert.equal(
    parseFormatterRichText('[[icon:4k]]', 'title')[0].scaleClass,
    'title-prominent',
  )
})
