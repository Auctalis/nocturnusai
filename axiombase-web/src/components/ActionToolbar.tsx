import type { OperationMode } from '@/lib/types'
import { useTransaction } from '@/context/TransactionContext'
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
  Terminal,
  Sparkles,
  GitCommitHorizontal,
} from 'lucide-react'
import Kbd from './Kbd'

interface ActionToolbarProps {
  mode: OperationMode
  onModeChange: (mode: OperationMode) => void
  onRun: () => void
  isRunning: boolean
  useVisual: boolean
  onToggleVisual: () => void
  database?: string
  tenantId?: string
  scope?: string
  onScopeChange?: (scope: string) => void
}

const MODES: { id: OperationMode; label: string; icon: typeof Search }[] = [
  { id: 'infer', label: 'Query', icon: Search },
  { id: 'assert_fact', label: 'Fact', icon: CirclePlus },
  { id: 'assert_rule', label: 'Rule', icon: GitBranch },
  { id: 'retract', label: 'Retract', icon: Minus },
  { id: 'assert_template', label: 'Template', icon: LayoutTemplate },
  { id: 'inspect', label: 'Inspect', icon: Eye },
  { id: 'execute', label: 'Execute', icon: Terminal },
  { id: 'synthesize', label: 'Synthesize', icon: Sparkles },
]

export default function ActionToolbar({
  mode,
  onModeChange,
  onRun,
  isRunning,
  useVisual,
  onToggleVisual,
  database,
  tenantId,
  scope,
  onScopeChange,
}: ActionToolbarProps) {
  const { txId, begin } = useTransaction()

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

        {/* Transaction button */}
        {database && !txId && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => void begin(database, tenantId)}
            title="Begin Transaction"
          >
            <GitCommitHorizontal size={12} />
            Begin TX
          </button>
        )}
        {txId && (
          <span className="badge badge-info" style={{ fontSize: 'var(--text-xs)' }}>
            TX: {txId.slice(0, 8)}...
          </span>
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
