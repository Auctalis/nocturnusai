import { useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import { useDatabases } from '@/lib/hooks'
import * as api from '@/lib/api'
import { BASE_URL } from '@/lib/api'
import { PromptModal } from '@/components/Modal'
import Skeleton from '@/components/Skeleton'
import NetworkBackground from '@/components/NetworkBackground'
import Onboarding from '@/components/Onboarding'
import {
  Hexagon,
  Plus,
  Database,
  ArrowRight,
  Users,
  Inbox,
  Copy,
  Plug,
  BookOpen,
  Zap,
} from 'lucide-react'

function CopyButton({ text, label }: { text: string; label?: string }) {
  const handleCopy = useCallback(() => {
    void navigator.clipboard.writeText(text)
    toast.success('Copied')
  }, [text])

  return (
    <button
      className="btn btn-ghost btn-icon"
      onClick={handleCopy}
      title={label ?? 'Copy'}
      style={{ opacity: 0.6 }}
    >
      <Copy size={14} />
    </button>
  )
}

const MCP_CONFIG = JSON.stringify({
  mcpServers: {
    axiombase: {
      url: `${BASE_URL}/mcp/sse`,
    },
  },
}, null, 2)

const PYTHON_QUICKSTART = `from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient("${BASE_URL}")

# Tell it something
client.tell("parent", ["alice", "bob"])

# Teach it a rule
client.teach(
    head={"predicate": "grandparent", "args": ["?x", "?z"]},
    body=[
        {"predicate": "parent", "args": ["?x", "?y"]},
        {"predicate": "parent", "args": ["?y", "?z"]},
    ]
)

# Ask it a question
results = client.ask("grandparent", ["?who", "?of"])`

const TS_QUICKSTART = `import { AxiomBaseClient } from '@axiombase/sdk';

const ab = new AxiomBaseClient({ baseUrl: '${BASE_URL}' });

// Tell it something
await ab.tell('parent', ['alice', 'bob']);

// Teach it a rule
await ab.teach(
  { predicate: 'grandparent', args: ['?x', '?z'] },
  [
    { predicate: 'parent', args: ['?x', '?y'] },
    { predicate: 'parent', args: ['?y', '?z'] },
  ]
);

// Ask it a question
const results = await ab.ask('grandparent', ['?who', '?of']);`

export default function Dashboard() {
  const { apiKey } = useApp()
  const { databases, loading, error, refresh } = useDatabases()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [showOnboarding, setShowOnboarding] = useState<string | null>(null)
  const [connectTab, setConnectTab] = useState<'mcp' | 'python' | 'typescript' | 'rest'>('mcp')

  const handleCreate = async (name: string) => {
    try {
      await api.createDatabase(apiKey, name, true)
      toast.success(`Database "${name}" created`)
      await refresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to create database')
    }
  }

  return (
    <div className="dashboard-page">
      <NetworkBackground />
      <div style={{ position: 'relative', zIndex: 1 }}>
        {/* Header */}
        <div className="dashboard-header">
          <div className="dashboard-header-row">
            <div className="sidebar-logo-icon">
              <Hexagon size={16} />
            </div>
            <h1 className="dashboard-title">AxiomBase</h1>
          </div>
          <p className="dashboard-subtitle">The knowledge and reasoning backend for AI agents</p>
          <p style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)', marginTop: 'var(--space-xs)' }}>
            Tell it facts. Teach it rules. Ask it questions. Get provable answers.
          </p>
        </div>

        {/* Connect Your Agent — the FIRST thing developers see */}
        <div style={{ maxWidth: 800, margin: '0 auto var(--space-xl)' }}>
          <div className="card">
            <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
              <Plug size={16} style={{ color: 'var(--accent)' }} />
              <span>Connect Your Agent</span>
            </div>
            <div className="card-body">
              <div className="tabs" style={{ marginBottom: 'var(--space-md)' }}>
                {(['mcp', 'python', 'typescript', 'rest'] as const).map((tab) => (
                  <button
                    key={tab}
                    className={`tab ${connectTab === tab ? 'active' : ''}`}
                    onClick={() => setConnectTab(tab)}
                  >
                    {tab === 'mcp' ? 'MCP' : tab === 'python' ? 'Python' : tab === 'typescript' ? 'TypeScript' : 'REST API'}
                  </button>
                ))}
              </div>

              {connectTab === 'mcp' && (
                <div>
                  <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', margin: '0 0 var(--space-sm)' }}>
                    Connect any MCP-compatible agent (Claude, GPT, Gemini) with two lines of config:
                  </p>
                  <div style={{ position: 'relative' }}>
                    <pre className="logic-json-block" style={{ fontSize: 12, paddingRight: 40 }}>{MCP_CONFIG}</pre>
                    <div style={{ position: 'absolute', top: 8, right: 8 }}>
                      <CopyButton text={MCP_CONFIG} />
                    </div>
                  </div>
                </div>
              )}

              {connectTab === 'python' && (
                <div>
                  <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', margin: '0 0 var(--space-sm)' }}>
                    <code style={{ background: 'var(--bg-code)', padding: '2px 6px', borderRadius: 4 }}>pip install axiombase</code>
                  </p>
                  <div style={{ position: 'relative' }}>
                    <pre className="logic-json-block" style={{ fontSize: 12, paddingRight: 40 }}>{PYTHON_QUICKSTART}</pre>
                    <div style={{ position: 'absolute', top: 8, right: 8 }}>
                      <CopyButton text={PYTHON_QUICKSTART} />
                    </div>
                  </div>
                </div>
              )}

              {connectTab === 'typescript' && (
                <div>
                  <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', margin: '0 0 var(--space-sm)' }}>
                    <code style={{ background: 'var(--bg-code)', padding: '2px 6px', borderRadius: 4 }}>npm install @axiombase/sdk</code>
                  </p>
                  <div style={{ position: 'relative' }}>
                    <pre className="logic-json-block" style={{ fontSize: 12, paddingRight: 40 }}>{TS_QUICKSTART}</pre>
                    <div style={{ position: 'absolute', top: 8, right: 8 }}>
                      <CopyButton text={TS_QUICKSTART} />
                    </div>
                  </div>
                </div>
              )}

              {connectTab === 'rest' && (
                <div>
                  <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', margin: '0 0 var(--space-sm)' }}>
                    Four verbs. That's the entire API.
                  </p>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-sm)' }}>
                    {[
                      { method: 'POST', path: '/tell', desc: 'Store knowledge' },
                      { method: 'POST', path: '/ask', desc: 'Query with reasoning' },
                      { method: 'POST', path: '/teach', desc: 'Define rules' },
                      { method: 'POST', path: '/forget', desc: 'Remove knowledge' },
                    ].map((ep) => (
                      <div key={ep.path} style={{
                        padding: 'var(--space-sm)',
                        background: 'var(--bg-code)',
                        borderRadius: 'var(--radius-sm)',
                        fontSize: 'var(--text-xs)',
                      }}>
                        <span className="badge badge-info" style={{ marginRight: 'var(--space-xs)' }}>{ep.method}</span>
                        <code>{ep.path}</code>
                        <div style={{ color: 'var(--text-muted)', marginTop: 2 }}>{ep.desc}</div>
                      </div>
                    ))}
                  </div>
                  <div style={{ marginTop: 'var(--space-sm)', display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
                    {[
                      { method: 'POST', path: '/memory/context', desc: 'Get relevant knowledge' },
                      { method: 'POST', path: '/memory/recall', desc: 'Time-travel queries' },
                      { method: 'POST', path: '/memory/compress', desc: 'Compress patterns' },
                      { method: 'POST', path: '/memory/cleanup', desc: 'Expire stale facts' },
                      { method: 'GET', path: '/memory/stream', desc: 'Real-time events (SSE)' },
                    ].map((ep) => (
                      <div key={ep.path} style={{
                        padding: '4px 8px',
                        background: 'var(--bg-code)',
                        borderRadius: 'var(--radius-sm)',
                        fontSize: 10,
                      }}>
                        <span style={{ opacity: 0.5 }}>{ep.method}</span>{' '}
                        <code>{ep.path}</code>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div style={{ marginTop: 'var(--space-md)', display: 'flex', gap: 'var(--space-sm)', flexWrap: 'wrap' }}>
                <a
                  href={`${BASE_URL}/.well-known/agent.json`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn btn-ghost btn-sm"
                  style={{ textDecoration: 'none' }}
                >
                  <Zap size={12} />
                  A2A Agent Card
                </a>
                <a
                  href={`${BASE_URL}/llm.txt`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn btn-ghost btn-sm"
                  style={{ textDecoration: 'none' }}
                >
                  <BookOpen size={12} />
                  llm.txt
                </a>
                <a
                  href={`${BASE_URL}/userguide`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn btn-ghost btn-sm"
                  style={{ textDecoration: 'none' }}
                >
                  <BookOpen size={12} />
                  Developer Guide
                </a>
              </div>
            </div>
          </div>
        </div>

        {/* Databases */}
        {loading && (
          <div className="dashboard-grid">
            {[1, 2, 3].map((i) => (
              <div key={i} className="card" style={{ padding: 'var(--space-lg)' }}>
                <Skeleton width={40} height={40} />
                <Skeleton width="60%" height={18} className="mt-2" />
                <Skeleton width="40%" height={14} className="mt-1" />
              </div>
            ))}
          </div>
        )}

        {error && (
          <div className="results-error">Error: {error}</div>
        )}

        {!loading && !error && (
          <div className="dashboard-grid">
            <div className="db-card db-card-create" onClick={() => setCreateOpen(true)}>
              <Plus size={32} style={{ color: 'var(--accent)', marginBottom: 'var(--space-sm)' }} />
              <div className="db-card-title" style={{ color: 'var(--accent)' }}>Create Database</div>
              <div className="db-card-meta">Start a new knowledge base</div>
            </div>

            {databases.map((db) => (
              <div key={db.name} className="db-card" onClick={() => navigate(`/db/${db.name}`)}>
                <div className="flex-between" style={{ marginBottom: 'var(--space-md)' }}>
                  <div className="db-card-icon">
                    <Database size={20} />
                  </div>
                  {db.isMultiTenant && (
                    <span className="badge badge-info">
                      <Users size={10} style={{ marginRight: 3 }} />
                      Multi-Tenant
                    </span>
                  )}
                </div>
                <div className="db-card-title">{db.name}</div>
                <div className="db-card-meta">Knowledge base</div>
                <div className="db-card-link">
                  <span>Open</span>
                  <ArrowRight size={14} />
                </div>
              </div>
            ))}

            {databases.length === 0 && (
              <div className="empty-state" style={{ gridColumn: '1 / -1' }}>
                <Inbox size={40} style={{ opacity: 0.3, marginBottom: 'var(--space-md)' }} />
                <div className="empty-state-text">No databases yet. Create one to get started.</div>
              </div>
            )}
          </div>
        )}

        {/* Onboarding */}
        {showOnboarding && (
          <div style={{ marginTop: 'var(--space-xl)' }}>
            <Onboarding
              database={showOnboarding}
              onComplete={() => navigate(`/db/${showOnboarding}`)}
            />
          </div>
        )}

        {!loading && !error && databases.length > 0 && !showOnboarding && (
          <div style={{ marginTop: 'var(--space-xl)', textAlign: 'center' }}>
            <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
              New to AxiomBase? Try the interactive walkthrough.
            </p>
            <button
              className="btn btn-secondary"
              onClick={() => setShowOnboarding(databases[0]?.name ?? '')}
            >
              Quick Start: Tell, Teach, Ask
              <ArrowRight size={14} />
            </button>
          </div>
        )}
      </div>

      <PromptModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSubmit={handleCreate}
        title="Create Database"
        description="Enter a name for the new knowledge base."
        placeholder="my-knowledge-base"
      />
    </div>
  )
}
