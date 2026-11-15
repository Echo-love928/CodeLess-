export type CodeLine = { number: number; text: string }
export type DiffRow = { left?: CodeLine; right?: CodeLine; changed: boolean }
export type LineComparison = {
  rows: DiffRow[]
  additions: number
  removals: number
  grouped: boolean
}

/** Align lines using LCS, bounding work for very large changed blocks. Never evaluates source code. */
export function compareLines(before: string, after: string): LineComparison {
  const split = (text: string) => (text === '' ? [] : text.replace(/\r\n?/g, '\n').split('\n'))
  const left = split(before)
  const right = split(after)
  const rows: DiffRow[] = []
  let additions = 0
  let removals = 0
  let prefix = 0
  while (prefix < left.length && prefix < right.length && left[prefix] === right[prefix]) {
    rows.push({
      left: { number: prefix + 1, text: left[prefix]! },
      right: { number: prefix + 1, text: right[prefix]! },
      changed: false,
    })
    prefix++
  }
  let suffix = 0
  while (
    suffix < left.length - prefix &&
    suffix < right.length - prefix &&
    left[left.length - suffix - 1] === right[right.length - suffix - 1]
  )
    suffix++
  const a = left.slice(prefix, left.length - suffix)
  const b = right.slice(prefix, right.length - suffix)
  const grouped = (a.length + 1) * (b.length + 1) > 2_000_000
  const removed: CodeLine[] = []
  const added: CodeLine[] = []
  const flush = () => {
    removals += removed.length
    additions += added.length
    for (let i = 0; i < Math.max(removed.length, added.length); i++)
      rows.push({ left: removed[i], right: added[i], changed: true })
    removed.length = 0
    added.length = 0
  }
  if (grouped) {
    a.forEach((text, index) => removed.push({ number: prefix + index + 1, text }))
    b.forEach((text, index) => added.push({ number: prefix + index + 1, text }))
    flush()
  } else {
    const width = b.length + 1
    const lcs = new Uint32Array((a.length + 1) * width)
    for (let i = a.length - 1; i >= 0; i--) {
      for (let j = b.length - 1; j >= 0; j--) {
        lcs[i * width + j] =
          a[i] === b[j]
            ? lcs[(i + 1) * width + j + 1]! + 1
            : Math.max(lcs[(i + 1) * width + j]!, lcs[i * width + j + 1]!)
      }
    }
    let i = 0
    let j = 0
    while (i < a.length || j < b.length) {
      if (i < a.length && j < b.length && a[i] === b[j]) {
        flush()
        rows.push({
          left: { number: prefix + i + 1, text: a[i]! },
          right: { number: prefix + j + 1, text: b[j]! },
          changed: false,
        })
        i++
        j++
      } else if (
        i < a.length &&
        (j >= b.length || lcs[(i + 1) * width + j]! >= lcs[i * width + j + 1]!)
      ) {
        removed.push({ number: prefix + i + 1, text: a[i++]! })
      } else {
        added.push({ number: prefix + j + 1, text: b[j++]! })
      }
    }
    flush()
  }
  for (let i = 0; i < suffix; i++)
    rows.push({
      left: { number: left.length - suffix + i + 1, text: left[left.length - suffix + i]! },
      right: { number: right.length - suffix + i + 1, text: right[right.length - suffix + i]! },
      changed: false,
    })
  return { rows, additions, removals, grouped }
}
