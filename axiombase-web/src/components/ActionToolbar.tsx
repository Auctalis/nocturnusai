import type { OperationMode } from '@/lib/types'
import {
  Search,
  MessageSquarePlus,
  GraduationCap,
  Eraser,
  Eye,
  Play,
  Loader2,
  Terminal,
  Brain,
  Layers,
} from 'lucide-react'
import Kbd from './Kbd'

interface ActionToolbarProps {
  mode: OperationMode
  onModeChange: (mode: OperationMode) => void
  onRun: () => void
  isRunning: boolean
}

interface ModeItem {
  id: OperationMode
  label: string
  icon: typeof Search
  tip: string
}

interface Plane {
  label: string
  modes: ModeItem[]
}

const PLANES: Plane[] = [
  {
    label: 'AGENT',
    modes: [
      { id: 'ask', label: 'Ask', icon: Search, tip: 'Query with reasoning' },
      { id: 'tell', label: 'Tell', icon: MessageSquarePlus, tip: 'Store a fact' },
      { id: 'teach', label: 'Teach', icon: GraduationCap, tip: 'Define a rule' },
      { id: 'forget', label: 'Forget', icon: Eraser, tip: 'Remove knowledge' },
    ],
  },
  {
    label: 'EXPLORE',
    modes: [
      { id: 'inspect', label: 'Inspect', icon: Eye, tip: 'Browse all knowledge' },
      { id: 'context', label: 'Context', icon: Brain, tip: 'Agent context window' },
    ],
  },
  {
    label: 'OPS',
    modes: [
      { id: 'memory', label: 'Memory', icon: Layers, tip: 'Compress & cleanup' },
      { id: 'execute', label: 'DSL', icon: Terminal, tip: 'Raw Logiql commands' },
    ],
  },
]

export default function ActionToolbar({
  mode,
  onModeChange,
  onRun,
  isRunning,
}: ActionToolbarProps) {
  return (
    <div className="toolbar-row">
      <div className="toolbar-left" style={{ gap: 0, flex: 1, minWidth: 0 }}>
        <div className="toolbar-planes">
          {PLANES.map((plane, planeIdx) => (
            <div key={plane.label} className="toolbar-plane">
              <span className="toolbar-plane-label">{plane.label}</span>
              <div className="toolbar-plane-tabs">
                {plane.modes.map((m) => {
                  const Icon = m.icon
                  const isActive = mode === m.id
                  return (
                    <button
                      key={m.id}
                      className={`toolbar-plane-btn ${isActive ? 'active' : ''}`}
                      onClick={() => onModeChange(m.id)}
                      title={m.tip}
                    >
                      <Icon size={13} />
                      <span>{m.label}</span>
                    </button>
                  )
                })}
              </div>
              {planeIdx < PLANES.length - 1 && <div className="toolbar-plane-divider" />}
            </div>
          ))}
        </div>

        <button
          className="btn btn-primary"
          onClick={onRun}
          disabled={isRunning}
          style={{ marginLeft: 'var(--space-sm)', flexShrink: 0 }}
        >
          {isRunning ? <Loader2 size={14} className="spin" /> : <Play size={14} />}
          {isRunning ? 'Running...' : 'Run'}
          {!isRunning && (
            <span style={{ marginLeft: 'var(--space-sm)', opacity: 0.7 }}>
              <Kbd keys={['\u2318', '\u21B5']} />
            </span>
          )}
        </button>
      </div>
    </div>
  )
}
