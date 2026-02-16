import { useState } from 'react'
import { Search } from 'lucide-react'
import Spinner from './Spinner'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import * as api from '@/lib/api'
import type { RecallResponse } from '@/lib/types'
import PaginationControls from './PaginationControls'

interface RecallPanelProps {
  db: string
  tenant?: string
  scope?: string
}

export default function RecallPanel({ db, tenant, scope }: RecallPanelProps) {
  const { apiKey } = useApp()
  const [mode, setMode] = useState<'topic' | 'predicate'>('topic')
  const [query, setQuery] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [result, setResult] = useState<RecallResponse | null>(null)
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(25)

  const handleRecall = async (newPage = 0) => {
    if (!query.trim()) {
      toast.error('Please enter a search term')
      return
    }

    setIsLoading(true)
    setPage(newPage)

    try {
      const params = mode === 'topic' ? { topic: query } : { predicate: query }
      const response = await api.contextRecall(
        apiKey,
        db,
        params,
        tenant,
        { limit: pageSize, offset: newPage * pageSize },
        scope,
      )
      setResult(response)
      toast.success(`Found ${response.count} fact${response.count !== 1 ? 's' : ''}`)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to recall facts')
    } finally {
      setIsLoading(false)
    }
  }

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      void handleRecall(0)
    }
  }

  const totalPages = result ? Math.max(1, Math.ceil(result.count / pageSize)) : 1

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)', height: '100%' }}>
      <div className="card">
        <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
          <div className="segmented-toggle">
            <button
              className={`segmented-toggle-btn ${mode === 'topic' ? 'active' : ''}`}
              onClick={() => setMode('topic')}
            >
              Topic (fuzzy)
            </button>
            <button
              className={`segmented-toggle-btn ${mode === 'predicate' ? 'active' : ''}`}
              onClick={() => setMode('predicate')}
            >
              Predicate (exact)
            </button>
          </div>

          <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
            <input
              type="text"
              className="input"
              placeholder={mode === 'topic' ? 'Search by topic (e.g., "family")' : 'Search by predicate (e.g., "Parent")'}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={handleKeyPress}
              disabled={isLoading}
              style={{ flex: 1 }}
            />
            <button
              className="btn btn-primary"
              onClick={() => handleRecall(0)}
              disabled={isLoading || !query.trim()}
            >
              {isLoading ? <Spinner size={16} /> : <Search size={16} />}
              {isLoading ? 'Recalling...' : 'Recall'}
            </button>
          </div>
        </div>
      </div>

      {result && (
        <div className="card" style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <div className="card-header">
            Results ({result.count})
          </div>
          <div className="card-body" style={{ flex: 1, overflow: 'auto' }}>
            {result.facts.length === 0 ? (
              <div style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 'var(--space-lg)' }}>
                No facts found
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                {result.facts.map((fact, index) => (
                  <div
                    key={index}
                    style={{
                      padding: 'var(--space-sm)',
                      background: 'var(--gray-10)',
                      borderRadius: 'var(--radius-sm)',
                      fontSize: 'var(--text-sm)',
                      fontFamily: 'var(--font-mono)'
                    }}
                  >
                    {fact}
                  </div>
                ))}
              </div>
            )}
          </div>
          {result.count > 0 && (
            <PaginationControls
              page={page}
              totalPages={totalPages}
              total={result.count}
              pageSize={pageSize}
              onPageChange={(newPage) => handleRecall(newPage)}
              onPageSizeChange={(newSize) => {
                setPageSize(newSize)
                void handleRecall(0)
              }}
            />
          )}
        </div>
      )}
    </div>
  )
}
