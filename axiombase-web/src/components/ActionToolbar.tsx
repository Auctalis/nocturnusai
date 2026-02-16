import type { OperationMode } from '@/lib/types'
import {
  Search,
  MessageSquarePlus,
  GraduationCap,
  Eraser,
  Eye,
  Play,
  Loader2,
  SlidersHorizontal,
  Code2,
  Terminal,
} from 'lucide-react'
import Kbd from './Kbd'

interface ActionToolbarProps {
  mode: OperationMode
  onModeChange: (mode: OperationMode) => void
  onRun: () => void
  isRunning: boolean
  useVisual: boolean
  onToggleVisual: () => void
  scope?: string
  onScopeChange?: (scope: string) => void
}

const MODES: { id: OperationMode; label: string; icon: typeof Search; description: string }[] = [
  { id: 'ask', label: 'Ask', icon: Search, description: 'Ask a question — find answers via reasoning' },
  { id: 'tell', label: 'Tell', icon: MessageSquarePlus, description: 'Tell a fact — store knowledge' },
  { id: 'teach', label: 'Teach', icon: GraduationCap, description: 'Teach a rule — define reasoning logic' },
  { id: 'forget', label: 'Forget', icon: Eraser, description: 'Forget — remove knowledge and derived facts' },
  { id: 'inspect', label: 'Inspect', icon: Eye, description: 'Inspect — browse all stored knowledge' },
  { id: 'execute', label: 'DSL', icon: Terminal, description: 'Execute raw Logiql DSL commands' },
]

export default function ActionToolbar({
  mode,
  onModeChange,
  onRun,
  isRunning,
  useVisual,
  onToggleVisual,
  scope,
  onScopeChange,
}: ActionToolbarProps) {
  const showVisualToggle = !['execute', 'synthesize'].includes(mode)

  return (
    <div className="toolbar-row">
      <div className="toolbar-left">
        <div className="tabs" style={{ flexWrap: 'wrap' }}>
          {MODES.map((m) => {
            const Icon = m.icon
            return (
              <button
                key={m.id}
                className={`tab ${mode === m.id ? 'active' : ''}`}
                onClick={() => onModeChange(m.id)}
                title={m.description}
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
        {onScopeChange && (
          <input
            type="text"
            className="input"
            placeholder="scope"
            value={scope ?? ''}
            onChange={(e) => onScopeChange(e.target.value)}
            style={{ width: 120, fontSize: 'var(--text-xs)', padding: '4px 8px' }}
          />
        )}

        {showVisualToggle && (
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
        )}
      </div>
    </div>
  )
}
