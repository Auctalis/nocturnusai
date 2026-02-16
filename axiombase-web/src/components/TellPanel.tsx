import { useState } from 'react'
import { Send } from 'lucide-react'
import Spinner from './Spinner'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import * as api from '@/lib/api'
import type { TellResponse } from '@/lib/types'
import ExtractedRulesList from '@/components/ExtractedRulesList'

interface TellPanelProps {
  db: string
  tenant?: string
  scope?: string
}

export default function TellPanel({ db, tenant, scope }: TellPanelProps) {
  const { apiKey } = useApp()
  const [text, setText] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [lastResponse, setLastResponse] = useState<TellResponse | null>(null)

  const handleTell = async () => {
    if (!text.trim()) {
      toast.error('Please enter some text')
      return
    }

    setIsLoading(true)
    try {
      const response = await api.contextTell(apiKey, db, text, tenant, scope)
      setLastResponse(response)
      setText('')
      const parts: string[] = []
      if (response.count > 0) parts.push(`${response.count} fact${response.count !== 1 ? 's' : ''}`)
      if (response.rulesCount > 0) parts.push(`${response.rulesCount} rule${response.rulesCount !== 1 ? 's' : ''}`)
      toast.success(parts.length > 0 ? `Understood: ${parts.join(', ')}` : 'No facts or rules extracted')
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to tell facts')
    } finally {
      setIsLoading(false)
    }
  }

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      void handleTell()
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)', height: '100%' }}>
      <div className="card">
        <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
          <textarea
            className="input"
            placeholder="Tell facts in natural language (e.g., 'Alice is the parent of Bob. Bob is the parent of Charlie.')"
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={handleKeyPress}
            disabled={isLoading}
            rows={6}
            style={{
              resize: 'vertical',
              minHeight: '120px',
              fontFamily: 'inherit',
              lineHeight: 1.5
            }}
          />
          <button
            className="btn btn-primary"
            onClick={handleTell}
            disabled={isLoading || !text.trim()}
            style={{ alignSelf: 'flex-end' }}
          >
            {isLoading ? <Spinner size={16} /> : <Send size={16} />}
            {isLoading ? 'Telling...' : 'Tell'}
          </button>
        </div>
      </div>

      {lastResponse && (
        <div className="card">
          <div className="card-header">
            Last Response
          </div>
          <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
            <div>
              <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                Facts Understood: {lastResponse.count}
              </div>
            </div>

            {lastResponse.understood.length > 0 && (
              <div>
                <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                  Extracted Facts
                </div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-xs)' }}>
                  {lastResponse.understood.map((fact, index) => (
                    <span key={index} className="badge badge-fact">
                      {fact}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {lastResponse.rules.length > 0 && (
              <div>
                <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                  Extracted Rules: {lastResponse.rulesCount}
                </div>
                <ExtractedRulesList rules={lastResponse.rules} />
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
