import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import { useDatabases, useDatabaseActions } from '@/lib/hooks'
import * as api from '@/lib/api'
import type { BackupInfo } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import { ConfirmModal } from '@/components/Modal'
import { Archive, Plus, RotateCcw, Clock } from 'lucide-react'

const noopRefreshTenants = async () => {}
const noopSetTenant = () => {}

const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
}

const formatRelativeTime = (timestamp: number): string => {
  const date = new Date(timestamp)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMins / 60)
  const diffDays = Math.floor(diffHours / 24)

  if (diffMins < 1) return 'just now'
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  if (diffDays < 7) return `${diffDays}d ago`
  return date.toLocaleString()
}

export default function BackupManager() {
  const { apiKey } = useApp()
  const navigate = useNavigate()
  const { databases, refresh: refreshDbs } = useDatabases()
  const [backups, setBackups] = useState<BackupInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [restoreTarget, setRestoreTarget] = useState<string | null>(null)

  // PITR state
  const [pitrOpen, setPitrOpen] = useState(false)
  const [pitrDb, setPitrDb] = useState('')
  const [pitrBackup, setPitrBackup] = useState('')
  const [pitrTimestamp, setPitrTimestamp] = useState('')

  const { sidebarActions, modalState } = useDatabaseActions(
    undefined,
    refreshDbs,
    noopRefreshTenants,
    noopSetTenant,
  )

  const fetchBackups = async () => {
    try {
      const result = await api.listBackups(apiKey)
      setBackups(result)
    } catch {
      toast.error('Failed to load backups')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void fetchBackups()
  }, [apiKey])

  const handleCreateBackup = async () => {
    try {
      await api.createBackup(apiKey)
      toast.success('Backup created')
      await fetchBackups()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to create backup')
    }
  }

  const handleRestore = async () => {
    if (!restoreTarget) return
    try {
      await api.restoreBackup(apiKey, restoreTarget)
      toast.success('Restore completed')
      setRestoreTarget(null)
      await fetchBackups()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to restore')
    }
  }

  const handlePitrRestore = async () => {
    if (!pitrDb || !pitrBackup || !pitrTimestamp) return
    try {
      await api.restorePointInTime(apiKey, pitrDb, new Date(pitrTimestamp).getTime(), pitrBackup)
      toast.success('Point-in-time restore completed')
      setPitrOpen(false)
      await fetchBackups()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to restore')
    }
  }

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            onSelectDb={(name) => navigate(`/db/${name}/context`)}
            {...sidebarActions}
            tenants={[]}
            currentTenant=""
            onSelectTenant={() => {}}
            isMultiTenant={false}
          />
        }
        toolbar={
          <div className="toolbar-row">
            <div className="toolbar-left">
              <Archive size={18} />
              <h1 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, margin: 0 }}>
                Backup Manager
              </h1>
            </div>
            <div className="toolbar-right">
              <button className="btn btn-secondary btn-sm" onClick={() => setPitrOpen(true)}>
                <Clock size={14} />
                Point-in-Time Restore
              </button>
              <button className="btn btn-primary btn-sm" onClick={handleCreateBackup}>
                <Plus size={14} />
                Create Backup
              </button>
            </div>
          </div>
        }
      >
        <div className="card">
          {loading ? (
            <div className="card-body" style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
              Loading backups...
            </div>
          ) : backups.length === 0 ? (
            <div className="card-body" style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
              No backups found. Create one to get started.
            </div>
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)' }}>
                  {['Name', 'Size', 'Date', 'Database', 'Actions'].map((h) => (
                    <th
                      key={h}
                      style={{
                        padding: 'var(--space-sm) var(--space-md)',
                        textAlign: 'left',
                        fontWeight: 600,
                        fontSize: 'var(--text-xs)',
                        color: 'var(--text-secondary)',
                        textTransform: 'uppercase',
                        letterSpacing: '0.1em',
                      }}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {backups.map((backup) => (
                  <tr key={backup.name} style={{ borderBottom: '1px solid var(--border-color)' }}>
                    <td style={{ padding: 'var(--space-sm) var(--space-md)', fontFamily: 'var(--font-mono)', fontSize: 'var(--text-sm)' }}>
                      {backup.name}
                    </td>
                    <td style={{ padding: 'var(--space-sm) var(--space-md)', fontSize: 'var(--text-sm)' }}>
                      {formatBytes(backup.sizeBytes)}
                    </td>
                    <td style={{ padding: 'var(--space-sm) var(--space-md)', fontSize: 'var(--text-sm)' }}>
                      {formatRelativeTime(backup.createdAt)}
                    </td>
                    <td style={{ padding: 'var(--space-sm) var(--space-md)', fontSize: 'var(--text-sm)' }}>
                      {backup.database || '—'}
                    </td>
                    <td style={{ padding: 'var(--space-sm) var(--space-md)' }}>
                      <button
                        className="btn btn-sm btn-danger"
                        onClick={() => setRestoreTarget(backup.name)}
                      >
                        <RotateCcw size={12} />
                        Restore
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </Layout>

      {/* Restore Confirmation */}
      <ConfirmModal
        open={restoreTarget !== null}
        onClose={() => setRestoreTarget(null)}
        onConfirm={handleRestore}
        title={`Restore from "${restoreTarget}"?`}
        description="This will replace all current data with the backup. This action cannot be undone."
        confirmLabel="Restore"
        danger
      />

      {/* PITR Dialog */}
      {pitrOpen && (
        <div className="modal-overlay" onClick={() => setPitrOpen(false)}>
          <div className="modal-panel" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, marginBottom: 'var(--space-md)' }}>
              Point-in-Time Restore
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)' }}>
              <div>
                <label className="input-label">Database</label>
                <select className="input" value={pitrDb} onChange={(e) => setPitrDb(e.target.value)}>
                  <option value="">Select database...</option>
                  {databases.map((db) => (
                    <option key={db.name} value={db.name}>{db.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="input-label">Backup</label>
                <select className="input" value={pitrBackup} onChange={(e) => setPitrBackup(e.target.value)}>
                  <option value="">Select backup...</option>
                  {backups.map((b) => (
                    <option key={b.name} value={b.name}>
                      {b.name} ({new Date(b.createdAt).toLocaleString()})
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="input-label">Restore to Time</label>
                <input
                  type="datetime-local"
                  className="input"
                  value={pitrTimestamp}
                  onChange={(e) => setPitrTimestamp(e.target.value)}
                />
              </div>
              <p style={{ color: 'var(--red-60)', fontSize: 'var(--text-xs)' }}>
                This will restore the database to the specified point in time. This action cannot be undone.
              </p>
              <div style={{ display: 'flex', gap: 'var(--space-sm)', justifyContent: 'flex-end' }}>
                <button className="btn btn-secondary" onClick={() => setPitrOpen(false)}>Cancel</button>
                <button
                  className="btn btn-danger"
                  onClick={handlePitrRestore}
                  disabled={!pitrDb || !pitrBackup || !pitrTimestamp}
                >
                  Restore
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      <DatabaseModals {...modalState} />
    </>
  )
}
