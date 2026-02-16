import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import { useDatabases } from '@/lib/hooks'
import * as api from '@/lib/api'
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
} from 'lucide-react'

export default function Dashboard() {
  const { apiKey } = useApp()
  const { databases, loading, error, refresh } = useDatabases()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [showOnboarding, setShowOnboarding] = useState<string | null>(null)

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
        <div className="dashboard-header">
          <div className="dashboard-header-row">
            <div className="sidebar-logo-icon">
              <Hexagon size={16} />
            </div>
            <h1 className="dashboard-title">AxiomBase</h1>
          </div>
          <p className="dashboard-subtitle">Select a database to manage</p>
        </div>

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
                <div className="db-card-meta">Logic knowledge base</div>
                <div className="db-card-link">
                  <span>Open</span>
                  <ArrowRight size={14} />
                </div>
              </div>
            ))}

            {databases.length === 0 && (
              <div className="empty-state" style={{ gridColumn: '1 / -1' }}>
                <Inbox size={40} style={{ opacity: 0.3, marginBottom: 'var(--space-md)' }} />
                <div className="empty-state-text">No databases found. Create one to get started!</div>
              </div>
            )}
          </div>
        )}

        {/* Onboarding: show when a database is selected for quick start */}
        {showOnboarding && (
          <div style={{ marginTop: 'var(--space-xl)' }}>
            <Onboarding
              database={showOnboarding}
              onComplete={() => navigate(`/db/${showOnboarding}`)}
            />
          </div>
        )}

        {/* Quick Start CTA: show when databases exist but no onboarding is active */}
        {!loading && !error && databases.length > 0 && !showOnboarding && (
          <div style={{ marginTop: 'var(--space-xl)', textAlign: 'center' }}>
            <p style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-sm)' }}>
              New to AxiomBase? Try the guided walkthrough.
            </p>
            <button
              className="btn btn-secondary"
              onClick={() => setShowOnboarding(databases[0]?.name ?? '')}
            >
              Quick Start Tutorial
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
