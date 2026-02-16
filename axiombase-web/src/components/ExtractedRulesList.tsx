interface ExtractedRule {
  head: { predicate: string; args: string[]; negated?: boolean }
  body: { predicate: string; args: string[]; negated?: boolean }[]
  templateType?: string
}

interface ExtractedRulesListProps {
  rules: ExtractedRule[] | string[]
}

function formatAtomObj(atom: { predicate: string; args: string[]; negated?: boolean }): string {
  const prefix = atom.negated ? 'NOT ' : ''
  return `${prefix}${atom.predicate}(${atom.args.join(', ')})`
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
      {rules.map((rule, i) => {
        const display = typeof rule === 'string'
          ? rule
          : `${formatAtomObj(rule.head)} :- ${rule.body.map(formatAtomObj).join(' AND ')}`
        const templateType = typeof rule === 'string' ? undefined : rule.templateType

        return (
          <div key={i} className="logic-item">
            <span className="font-mono text-sm">{display}</span>
            {templateType && (
              <span className="badge badge-rule">{templateType}</span>
            )}
          </div>
        )
      })}
    </div>
  )
}
