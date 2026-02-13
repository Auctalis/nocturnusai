import type { OperationMode } from '@/lib/types'
import {
  Search,
  CirclePlus,
  GitBranch,
  Minus,
  LayoutTemplate,
  Eye,
  Play,
  Loader2,
  SlidersHorizontal,
  Code2,
} from 'lucide-react'
import Kbd from './Kbd'

interface ActionToolbarProps {
  mode: OperationMode
  onModeChange: (mode: OperationMode) => void
  onRun: () => void
  isRunning: boolean
  useVisual: boolean
  onToggleVisual: () => void
}

const MODES: { id: OperationMode; label: string; icon: typeof Search }[] = [
  { id: 'infer', label: 'Query', icon: Search },
  { id: 'assert_fact', label: 'Fact', icon: CirclePlus },
  { id: 'assert_rule', label: 'Rule', icon: GitBranch },
  { id: 'retract', label: 'Retract', icon: Minus },
  { id: 'assert_template', label: 'Template', icon: LayoutTemplate },
  { id: 'inspect', label: 'Inspect', icon: Eye },
]

export default function ActionToolbar({
  mode,
  onModeChange,
  onRun,
  isRunning,
  useVisual,
  onToggleVisual,
}: ActionToolbarProps) {
  return (
    <div className="toolbar-row">
      <div className="toolbar-left">
        <div className="tabs">
          {MODES.map((m) => {
            const Icon = m.icon
            return (
              <button
                key={m.id}
                className={`tab ${mode === m.id ? 'active' : ''}`}
                onClick={() => onModeChange(m.id)}
              >
                <Icon size={14} style={{ marginRight: 4 }} />
                {m.label}
              </button>
            )
          })}
        </div>

        <button className="btn btn-primary" onClick={onRun} disabled={isRunning}>
          {isRunning ? (
            <Loader2 size={14} className="spin" />
          ) : (
            <Play size={14} />
          )}
          {isRunning ? 'Running...' : 'Run'}
          {!isRunning && (
            <span style={{ marginLeft: 'var(--space-sm)', opacity: 0.7 }}>
              <Kbd keys={['\u2318', '\u21B5']} />
            </span>
          )}
        </button>
      </div>

      <div className="toolbar-right">
        <div className="segmented-toggle">
          <button
            className={`segmented-toggle-btn ${useVisual ? 'active' : ''}`}
            onClick={useVisual ? undefined : onToggleVisual}
          >
            <SlidersHorizontal size={12} style={{ marginRight: 4 }} />
            Visual
          </button>
          <button
            className={`segmented-toggle-btn ${!useVisual ? 'active' : ''}`}
            onClick={useVisual ? onToggleVisual : undefined}
          >
            <Code2 size={12} style={{ marginRight: 4 }} />
            Code
          </button>
        </div>
      </div>
    </div>
  )
}
