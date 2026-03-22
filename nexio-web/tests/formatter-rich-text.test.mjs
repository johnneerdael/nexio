import test from 'node:test'
import assert from 'node:assert/strict'

test('inline icon tokens keep title badges prominent', async () => {
  try {
    const mod = await import('../formatter/rich-text.js')
    assert.equal(typeof mod.parseFormatterRichText, 'function')
    assert.equal(
      mod.parseFormatterRichText('[[icon:4k]]', 'title')[0].scaleClass,
      'title-prominent',
    )
  } catch (error) {
    assert.fail(`missing rich-text parser contract: ${error.message}`)
  }
})
