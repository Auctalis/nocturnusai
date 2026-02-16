import Spinner from './Spinner'
import { Check } from 'lucide-react'

export interface Step {
  label: string
  status: 'pending' | 'active' | 'done'
}

interface ProgressStepsProps {
  steps: Step[]
}

export default function ProgressSteps({ steps }: ProgressStepsProps) {
  return (
    <div className="progress-steps">
      {steps.map((step, i) => (
        <div key={i} className="progress-step-wrapper">
          {i > 0 && (
            <div className={`progress-step-line ${step.status === 'done' || step.status === 'active' ? 'progress-step-line-active' : ''}`} />
          )}
          <div className={`progress-step progress-step-${step.status}`}>
            <div className="progress-step-indicator">
              {step.status === 'done' ? (
                <Check size={10} />
              ) : step.status === 'active' ? (
                <Spinner size={12} />
              ) : (
                <span className="progress-step-dot" />
              )}
            </div>
            <span className="progress-step-label">{step.label}</span>
          </div>
        </div>
      ))}
    </div>
  )
}
