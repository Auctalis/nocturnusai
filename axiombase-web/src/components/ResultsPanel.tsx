import type { AtomResponse, OperationMode, InspectItem } from '@/lib/types'
import { Terminal, AlertCircle } from 'lucide-react'
import LogicVisualizer from './LogicVisualizer'
import Skeleton from './Skeleton'

interface ResultsPanelProps {
  result: string | AtomResponse[] | InspectItem[] | null
  error: string | null
  isLoading: boolean
  mode: OperationMode
}

export default function ResultsPanel({ result, error, isLoading, mode }: ResultsPanelProps) {
  if (!result && !error && !isLoading) {
    return (
      <div className="results-panel">
        <div className="results-header">
          <Terminal size={12} style={{ marginRight: 6, verticalAlign: 'middle' }} />
          Results
        </div>
        <div className="empty-state">
          <div className="empty-state-text">Run a command to see results here.</div>
        </div>
      </div>
    )
  }

  return (
    <div className="results-panel">
      <div className="results-header">
        <Terminal size={12} style={{ marginRight: 6, verticalAlign: 'middle' }} />
        Results
      </div>
      <div className="results-body">
        {isLoading && (
          <div className="flex-col gap-2">
            <Skeleton width="100%" height={16} />
            <Skeleton width="80%" height={16} />
            <Skeleton width="60%" height={16} />
          </div>
        )}

        {error && (
          <div className="results-error">
            <AlertCircle size={14} style={{ marginRight: 6, verticalAlign: 'middle' }} />
            {error}
          </div>
        )}

        {result && !isLoading && <LogicVisualizer data={result} mode={mode} />}
      </div>
    </div>
  )
}
