import test from 'node:test'
import assert from 'node:assert/strict'
import { compareLines } from '../src/utils/lineDiff.ts'

test('aligns inserted and removed lines with their original line numbers', () => {
  const result = compareLines('a\nb\nc', 'a\nc\nd')
  assert.equal(result.additions, 1)
  assert.equal(result.removals, 1)
  assert.deepEqual(
    result.rows.map((row) => [row.left?.number, row.right?.number]),
    [
      [1, 1],
      [2, undefined],
      [3, 2],
      [undefined, 3],
    ],
  )
})

test('identical files have no changes and normalize CRLF', () => {
  const result = compareLines('first\r\nsecond', 'first\nsecond')
  assert.equal(result.additions + result.removals, 0)
})

test('handles added files, removed files and empty text', () => {
  assert.equal(compareLines('', 'new').additions, 1)
  assert.equal(compareLines('old', '').removals, 1)
  assert.equal(compareLines('', '').rows.length, 0)
})

test('repeated lines and replacements preserve each side exactly', () => {
  const before = 'a\nx\na\nb\n'
  const after = 'a\na\ny\nb\n'
  const result = compareLines(before, after)
  assert.equal(result.rows.flatMap((row) => (row.left ? [row.left.text] : [])).join('\n'), before)
  assert.equal(result.rows.flatMap((row) => (row.right ? [row.right.text] : [])).join('\n'), after)
})

test('large changes remain bounded and preserve code', () => {
  const before = Array.from({ length: 2500 }, (_, i) => `old${i}`).join('\n')
  const after = Array.from({ length: 2500 }, (_, i) => `new${i}`).join('\n')
  const result = compareLines(before, after)
  assert.equal(result.removals, 2500)
  assert.equal(result.additions, 2500)
  assert.equal(result.rows.length, 2500)
})
