import type { ExtractedRuleDto, ExtractedAtomDto } from '@/lib/types'

interface ExtractedRulesListProps {
  rules: ExtractedRuleDto[]
}

export function formatAtom(atom: ExtractedAtomDto): string {
  const prefix = atom.negated ? 'NOT ' : ''
  return `${prefix}${atom.predicate}(${atom.args.join(', ')})`
}

export function formatRule(rule: ExtractedRuleDto): string {
  const head = formatAtom(rule.head)
  const body = rule.body.map(formatAtom).join(' AND ')
  return `${head} :- ${body}`
}

export default function ExtractedRulesList({ rules }: ExtractedRulesListProps) {
  if (rules.length === 0) {
    return (
      <div className="empty-state">
        <div className="empty-state-text">No rules extracted.</div>
      </div>
    )
  }

  return (
    <div className="flex-col gap-2">
      {rules.map((rule, i) => (
        <div key={i} className="logic-item">
          <span className="font-mono text-sm">{formatRule(rule)}</span>
          {rule.templateType && (
            <span className="badge badge-rule">{rule.templateType}</span>
          )}
        </div>
      ))}
    </div>
  )
}
