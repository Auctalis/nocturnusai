import type { DerivationStep } from '@/lib/types'

interface DerivationTreeProps {
  steps: DerivationStep[]
}

export default function DerivationTree({ steps }: DerivationTreeProps) {
  if (steps.length === 0) return null

  return (
    <div className="derivation-tree">
      {steps.map((step, index) => (
        <div
          key={index}
          className={`derivation-step ${step.type === 'fact' ? 'derivation-step-fact' : 'derivation-step-rule'}`}
        >
          <div
            className="derivation-step-type"
            style={{ color: step.type === 'fact' ? 'var(--teal-60)' : 'var(--purple-60)' }}
          >
            {step.type}
          </div>
          <div style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--text-sm)' }}>
            {step.content}
          </div>
          {step.description && (
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginTop: '2px' }}>
              {step.description}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
