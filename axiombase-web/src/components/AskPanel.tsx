import { useState } from 'react'
import { Send } from 'lucide-react'
import Spinner from './Spinner'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import * as api from '@/lib/api'
import type { AskResponse } from '@/lib/types'
import ConfidenceIndicator from './ConfidenceIndicator'
import DerivationTree from './DerivationTree'

interface AskPanelProps {
  db: string
  tenant?: string
  scope?: string
}

interface ConversationEntry {
  question: string
  response: AskResponse
}

export default function AskPanel({ db, tenant, scope }: AskPanelProps) {
  const { apiKey } = useApp()
  const [question, setQuestion] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [conversation, setConversation] = useState<ConversationEntry[]>([])

  const handleAsk = async () => {
    if (!question.trim()) {
      toast.error('Please enter a question')
      return
    }

    setIsLoading(true)
    try {
      const response = await api.contextAsk(apiKey, db, question, tenant, scope)
      setConversation(prev => [...prev, { question, response }])
      setQuestion('')
      toast.success('Question answered')
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to ask question')
    } finally {
      setIsLoading(false)
    }
  }

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      void handleAsk()
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)', height: '100%' }}>
      <div className="card">
        <div className="card-body" style={{ display: 'flex', gap: 'var(--space-sm)' }}>
          <input
            type="text"
            className="input"
            placeholder="Ask a question in natural language..."
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={handleKeyPress}
            disabled={isLoading}
            style={{ flex: 1 }}
          />
          <button
            className="btn btn-primary"
            onClick={handleAsk}
            disabled={isLoading || !question.trim()}
          >
            {isLoading ? <Spinner size={16} /> : <Send size={16} />}
            {isLoading ? 'Asking...' : 'Ask'}
          </button>
        </div>
      </div>

      {conversation.length === 0 ? (
        <div className="card">
          <div className="card-body" style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
            Ask a question to start a conversation
          </div>
        </div>
      ) : (
        <div style={{ flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
          {conversation.map((entry, index) => (
            <div key={index} className="card">
              <div className="card-header">
                <span style={{ fontWeight: 600 }}>Q:</span> {entry.question}
              </div>
              <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
                <div>
                  <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                    Answer
                  </div>
                  <div style={{ fontSize: 'var(--text-sm)' }}>
                    {entry.response.answer}
                  </div>
                </div>

                <div>
                  <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                    Confidence
                  </div>
                  <ConfidenceIndicator confidence={entry.response.confidence} />
                </div>

                {entry.response.derivation.length > 0 && (
                  <div>
                    <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                      Derivation
                    </div>
                    <DerivationTree steps={entry.response.derivation} />
                  </div>
                )}

                {entry.response.queriesExecuted.length > 0 && (
                  <div>
                    <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                      Queries Executed ({entry.response.queriesExecuted.length})
                    </div>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-xs)' }}>
                      {entry.response.queriesExecuted.map((query, i) => (
                        <code
                          key={i}
                          className="badge badge-info"
                          style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)' }}
                        >
                          {query}
                        </code>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
