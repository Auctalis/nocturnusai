import { useState } from 'react'
import type { AtomResponse, OperationMode, InspectItem } from '@/lib/types'
import {
  CircleDot,
  GitBranch,
  Hash,
  CheckCircle2,
  Inbox,
  XCircle,
  List,
  Share2,
  Tag,
} from 'lucide-react'
import KnowledgeGraph from './KnowledgeGraph'

interface LogicVisualizerProps {
  data: string | AtomResponse[] | InspectItem[]
  mode: OperationMode
}

function isInspectItems(data: unknown): data is InspectItem[] {
  return (
    Array.isArray(data) &&
    data.length > 0 &&
    typeof data[0] === 'object' &&
    data[0] !== null &&
    'Type' in data[0] &&
    'Content' in data[0]
  )
}

function isAtomResponses(data: unknown): data is AtomResponse[] {
  return (
    Array.isArray(data) &&
    data.length > 0 &&
    typeof data[0] === 'object' &&
    data[0] !== null &&
    'predicate' in data[0] &&
    'args' in data[0]
  )
}

function formatAtom(atom: AtomResponse): string {
  const neg = atom.negated ? 'NOT ' : ''
  const scopeStr = atom.scope ? ` @${atom.scope}` : ''
  return `${neg}${atom.predicate}(${atom.args.join(', ')})${scopeStr}`
}

function hasMetadata(meta: Record<string, unknown> | undefined): boolean {
  return !!meta && Object.keys(meta).length > 0
}

function MetadataBadges({ metadata }: { metadata: Record<string, unknown> }) {
  const [expanded, setExpanded] = useState(false)
  const entries = Object.entries(metadata)

  if (entries.length === 0) return null

  if (!expanded) {
    return (
      <button
        className="metadata-toggle"
        onClick={(e) => { e.stopPropagation(); setExpanded(true) }}
        title="Show metadata"
      >
        <Tag size={10} style={{ marginRight: 3 }} />
        {entries.length}
      </button>
    )
  }

  return (
    <div className="metadata-panel" onClick={(e) => e.stopPropagation()}>
      <button
        className="metadata-toggle metadata-toggle-active"
        onClick={() => setExpanded(false)}
        title="Hide metadata"
      >
        <Tag size={10} style={{ marginRight: 3 }} />
        {entries.length}
      </button>
      <div className="metadata-entries">
        {entries.map(([key, value]) => (
          <span key={key} className="metadata-entry">
            <span className="metadata-key">{key}</span>
            <span className="metadata-value">
              {typeof value === 'object' ? JSON.stringify(value) : String(value)}
            </span>
          </span>
        ))}
      </div>
    </div>
  )
}

export default function LogicVisualizer({ data, mode }: LogicVisualizerProps) {
  const [viewMode, setViewMode] = useState<'graph' | 'list'>('graph')

  // Inspect results (array of {Type, Content})
  if (mode === 'inspect' && isInspectItems(data)) {
    const facts = data.filter((d) => d.Type === 'Fact')
    const rules = data.filter((d) => d.Type === 'Rule')

    if (data.length === 0) {
      return (
        <div className="empty-state">
          <Inbox size={32} style={{ opacity: 0.3, marginBottom: 'var(--space-sm)' }} />
          <div className="empty-state-text">Knowledge base is empty.</div>
        </div>
      )
    }

    return (
      <div className="flex-col gap-2">
        {/* View mode toggle */}
        <div className="inspect-view-header">
          <div className="segmented-toggle">
            <button
              className={`segmented-toggle-btn ${viewMode === 'graph' ? 'active' : ''}`}
              onClick={() => setViewMode('graph')}
            >
              <Share2 size={11} style={{ marginRight: 4 }} />
              Graph
            </button>
            <button
              className={`segmented-toggle-btn ${viewMode === 'list' ? 'active' : ''}`}
              onClick={() => setViewMode('list')}
            >
              <List size={11} style={{ marginRight: 4 }} />
              List
            </button>
          </div>
        </div>

        {viewMode === 'graph' ? (
          <KnowledgeGraph items={data} />
        ) : (
          <div className="flex-col gap-4">
            {facts.length > 0 && (
              <div>
                <div className="inspect-section-title">
                  <CircleDot size={12} style={{ marginRight: 4, verticalAlign: 'middle' }} />
                  Facts ({facts.length})
                </div>
                <div className="inspect-grid">
                  {facts.map((f, i) => {
                    const atom = typeof f.Content === 'object' ? f.Content as AtomResponse : null
                    const display = atom ? formatAtom(atom) : String(f.Content)
                    const meta = atom?.metadata
                    return (
                      <div key={i} className="logic-item">
                        <span className="badge badge-fact">
                          <CircleDot size={10} style={{ marginRight: 3 }} />
                          Fact
                        </span>
                        <span className="font-mono text-sm">{display}</span>
                        {meta && hasMetadata(meta) && <MetadataBadges metadata={meta} />}
                      </div>
                    )
                  })}
                </div>
              </div>
            )}

            {rules.length > 0 && (
              <div>
                <div className="inspect-section-title">
                  <GitBranch size={12} style={{ marginRight: 4, verticalAlign: 'middle' }} />
                  Rules ({rules.length})
                </div>
                <div className="inspect-grid">
                  {rules.map((r, i) => (
                    <div key={i} className="logic-item">
                      <span className="badge badge-rule">
                        <GitBranch size={10} style={{ marginRight: 3 }} />
                        Rule
                      </span>
                      <span className="font-mono text-sm">{String(r.Content)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    )
  }

  // Infer results (AtomResponse[])
  if (mode === 'infer' && Array.isArray(data) && !isInspectItems(data)) {
    if (data.length === 0) {
      return (
        <div className="empty-state">
          <XCircle size={32} style={{ opacity: 0.3, marginBottom: 'var(--space-sm)' }} />
          <div className="empty-state-text">No solutions found. (False)</div>
        </div>
      )
    }

    if (isAtomResponses(data)) {
      return (
        <div className="inspect-grid">
          {data.map((atom, i) => (
            <div key={i} className="logic-item">
              <span className="badge badge-info">
                <Hash size={10} style={{ marginRight: 2 }} />
                {i + 1}
              </span>
              <span className="font-mono text-sm">{formatAtom(atom)}</span>
              {hasMetadata(atom.metadata) && <MetadataBadges metadata={atom.metadata} />}
            </div>
          ))}
        </div>
      )
    }

    // Fallback for string[] (shouldn't happen with new API, but safe)
    return (
      <div className="inspect-grid">
        {(data as string[]).map((solution, i) => (
          <div key={i} className="logic-item">
            <span className="badge badge-info">
              <Hash size={10} style={{ marginRight: 2 }} />
              {i + 1}
            </span>
            <span className="font-mono text-sm">{solution}</span>
          </div>
        ))}
      </div>
    )
  }

  // Plain text result (assert, retract, template)
  if (typeof data === 'string') {
    return (
      <div className="logic-item">
        <CheckCircle2 size={14} style={{ color: 'var(--success)', flexShrink: 0 }} />
        <span className="text-sm">{data}</span>
      </div>
    )
  }

  // Fallback: JSON
  return <pre className="logic-json-block">{JSON.stringify(data, null, 2)}</pre>
}
