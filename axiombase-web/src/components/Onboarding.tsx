import { useState, useCallback } from 'react'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import * as api from '@/lib/api'
import { Check, ChevronRight, Play, Loader2, Sparkles } from 'lucide-react'

interface OnboardingProps {
  database: string
  onComplete: () => void
}

interface Step {
  title: string
  description: string
  action: string
  code: string
}

const STEPS: Step[] = [
  {
    title: 'Assert a fact',
    description: 'Store a basic fact: "Tom is the parent of Bob"',
    action: 'Assert',
    code: JSON.stringify({ predicate: 'parent', args: ['tom', 'bob'] }, null, 2),
  },
  {
    title: 'Assert another fact',
    description: 'Store: "Bob is the parent of Charlie"',
    action: 'Assert',
    code: JSON.stringify({ predicate: 'parent', args: ['bob', 'charlie'] }, null, 2),
  },
  {
    title: 'Assert a rule',
    description: 'Define: "If X is parent of Y and Y is parent of Z, then X is grandparent of Z"',
    action: 'Assert Rule',
    code: JSON.stringify({
      head: { predicate: 'grandparent', args: ['?x', '?z'] },
      body: [
        { predicate: 'parent', args: ['?x', '?y'] },
        { predicate: 'parent', args: ['?y', '?z'] },
      ],
    }, null, 2),
  },
  {
    title: 'Run inference',
    description: 'Ask: "Who is a grandparent of whom?"',
    action: 'Query',
    code: JSON.stringify({ predicate: 'grandparent', args: ['?who', '?of'] }, null, 2),
  },
]

export default function Onboarding({ database, onComplete }: OnboardingProps) {
  const { apiKey } = useApp()
  const [currentStep, setCurrentStep] = useState(0)
  const [loading, setLoading] = useState(false)
  const [results, setResults] = useState<(string | null)[]>(STEPS.map(() => null))
  const [completed, setCompleted] = useState<boolean[]>(STEPS.map(() => false))

  const runStep = useCallback(async () => {
    const step = STEPS[currentStep]
    if (!step) return

    setLoading(true)
    try {
      const body = JSON.parse(step.code)
      let result: string

      if (currentStep < 2) {
        // Assert facts
        result = await api.assertFact(apiKey, database, body)
      } else if (currentStep === 2) {
        // Assert rule
        result = await api.assertRule(apiKey, database, body)
      } else {
        // Query
        const atoms = await api.infer(apiKey, database, body)
        result = atoms.length > 0
          ? atoms.map((a) => `${a.predicate}(${a.args.join(', ')})`).join('\n')
          : 'No results'
      }

      const newResults = [...results]
      newResults[currentStep] = result
      setResults(newResults)

      const newCompleted = [...completed]
      newCompleted[currentStep] = true
      setCompleted(newCompleted)

      toast.success(currentStep === 3 ? 'Inference complete!' : 'Done!')

      if (currentStep < STEPS.length - 1) {
        setTimeout(() => setCurrentStep(currentStep + 1), 600)
      }
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed')
    } finally {
      setLoading(false)
    }
  }, [apiKey, database, currentStep, results, completed])

  const allDone = completed.every(Boolean)

  return (
    <div className="card" style={{ maxWidth: 600, margin: '0 auto' }}>
      <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
        <Sparkles size={16} style={{ color: 'var(--accent)' }} />
        <span>Quick Start: Your First Inference</span>
      </div>
      <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
        {STEPS.map((step, i) => {
          const isActive = i === currentStep
          const isDone = completed[i]
          const isFuture = i > currentStep && !isDone

          return (
            <div
              key={i}
              style={{
                padding: 'var(--space-md)',
                borderRadius: 'var(--radius-md)',
                border: `1px solid ${isActive ? 'var(--blue-60)' : isDone ? 'var(--green-40)' : 'var(--border-color)'}`,
                background: isActive ? 'var(--blue-10)' : isDone ? 'var(--green-10)' : 'var(--bg-surface)',
                opacity: isFuture ? 0.5 : 1,
                transition: 'all 0.3s ease',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)' }}>
                <div style={{
                  width: 24,
                  height: 24,
                  borderRadius: '50%',
                  background: isDone ? 'var(--green-50)' : isActive ? 'var(--blue-60)' : 'var(--gray-20)',
                  color: isDone || isActive ? 'white' : 'var(--text-muted)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 12,
                  fontWeight: 700,
                }}>
                  {isDone ? <Check size={14} /> : i + 1}
                </div>
                <span style={{ fontWeight: 600, fontSize: 'var(--text-sm)' }}>{step.title}</span>
                {isDone && <span className="badge badge-success" style={{ marginLeft: 'auto' }}>Done</span>}
              </div>
              <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', margin: 0 }}>
                {step.description}
              </p>

              {(isActive || isDone) && (
                <pre className="logic-json-block" style={{ margin: 'var(--space-sm) 0 0', fontSize: 11 }}>
                  {step.code}
                </pre>
              )}

              {results[i] && (
                <div style={{
                  marginTop: 'var(--space-sm)',
                  padding: 'var(--space-sm)',
                  background: 'var(--bg-code)',
                  borderRadius: 'var(--radius-sm)',
                  fontSize: 'var(--text-xs)',
                  fontFamily: 'var(--font-mono)',
                  color: 'var(--green-50)',
                }}>
                  {results[i]}
                </div>
              )}

              {isActive && !isDone && (
                <button
                  className="btn btn-primary btn-sm"
                  onClick={runStep}
                  disabled={loading}
                  style={{ marginTop: 'var(--space-sm)' }}
                >
                  {loading ? <Loader2 size={14} className="spin" /> : <Play size={14} />}
                  {loading ? 'Running...' : step.action}
                  <ChevronRight size={14} />
                </button>
              )}
            </div>
          )
        })}

        {allDone && (
          <div style={{ textAlign: 'center', padding: 'var(--space-md)' }}>
            <p style={{ fontWeight: 600, color: 'var(--green-50)', marginBottom: 'var(--space-sm)' }}>
              You just ran your first logical inference!
            </p>
            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', marginBottom: 'var(--space-md)' }}>
              Tom is the grandparent of Charlie, derived from two parent facts and one rule.
            </p>
            <button className="btn btn-primary" onClick={onComplete}>
              Open Query Console
              <ChevronRight size={14} />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
