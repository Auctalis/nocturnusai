/**
 * Lightweight parser for predicate(arg, arg) syntax.
 * Turns plain text like "likes(alice, bob)" into structured requests.
 */

export interface ParsedAtom {
  predicate: string
  args: string[]
  negated: boolean
}

export interface ParsedRule {
  head: ParsedAtom
  body: ParsedAtom[]
}

/** Is this text a rule (contains ":-")? */
export function isRule(text: string): boolean {
  return text.includes(':-')
}

/** Parse "predicate(arg1, arg2)" or "NOT predicate(arg1)" */
export function parseAtom(text: string): ParsedAtom {
  text = text.trim()

  let negated = false
  if (/^not\s+/i.test(text)) {
    negated = true
    text = text.replace(/^not\s+/i, '')
  }

  const parenOpen = text.indexOf('(')
  if (parenOpen === -1) {
    // No parens → treat as predicate with no args
    return { predicate: text, args: [], negated }
  }

  const predicate = text.slice(0, parenOpen).trim()
  const inner = text.slice(parenOpen + 1, text.lastIndexOf(')'))
  const args = splitOutsideParens(inner, ',').map((a) => a.trim()).filter(Boolean)

  return { predicate, args, negated }
}

/** Parse "head :- body1, body2" into structured rule */
export function parseRule(text: string): ParsedRule {
  const sepIdx = text.indexOf(':-')
  if (sepIdx === -1) {
    throw new Error('Not a rule — missing ":-"')
  }

  const head = parseAtom(text.slice(0, sepIdx))
  const bodyStr = text.slice(sepIdx + 2)
  const bodyParts = splitOutsideParens(bodyStr, ',')
  const body = bodyParts.map((part) => parseAtom(part))

  return { head, body }
}

/** Split string by delimiter, respecting parentheses depth */
function splitOutsideParens(text: string, delim: string): string[] {
  const result: string[] = []
  let current = ''
  let depth = 0

  for (const ch of text) {
    if (ch === '(') depth++
    else if (ch === ')') depth--
    else if (ch === delim && depth === 0) {
      result.push(current)
      current = ''
      continue
    }
    current += ch
  }

  if (current.trim()) result.push(current)
  return result
}
