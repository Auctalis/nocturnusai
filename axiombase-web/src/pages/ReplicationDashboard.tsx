import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import { useDatabases, useDatabaseActions, usePolling } from '@/lib/hooks'
import * as api from '@/lib/api'
import type { ReplicationDashboard as ReplicationDashboardType } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import { ConfirmModal } from '@/components/Modal'
import { GitFork, ArrowUp, ArrowDown } from 'lucide-react'

const noopRefreshTenants = async () => {}
const noopSetTenant = () => {}

export default function ReplicationDashboard() {
  const { apiKey } = useApp()
  const navigate = useNavigate()
  const { databases, refresh: refreshDbs } = useDatabases()

  const { sidebarActions, modalState } = useDatabaseActions(
    undefined,
    refreshDbs,
    noopRefreshTenants,
    noopSetTenant,
  )

  const dashFetcher = useCallback(() => api.getReplicationDashboard(apiKey), [apiKey])
  const { data: dashboard } = usePolling<ReplicationDashboardType>(dashFetcher, 5000)

  const [confirmAction, setConfirmAction] = useState<'promote' | 'stepdown' | null>(null)

  const handleConfirmAction = async () => {
    try {
      if (confirmAction === 'promote') {
        await api.promoteToLeader(apiKey)
        toast.success('Promoted to leader')
      } else if (confirmAction === 'stepdown') {
        await api.stepdownToFollower(apiKey)
        toast.success('Stepped down to follower')
      }
      setConfirmAction(null)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Action failed')
    }
  }

  const circuitBreakerColor = (state: string) => {
    if (state === 'CLOSED') return 'var(--green-50)'
    if (state === 'OPEN') return 'var(--red-60)'
    if (state === 'HALF_OPEN') return 'var(--yellow-50, #f1c21b)'
    return 'var(--gray-40)'
  }

  const circuitBreakerLabel = (state: string) => {
    if (state === 'CLOSED') return 'Healthy'
    if (state === 'OPEN') return 'Broken'
    if (state === 'HALF_OPEN') return 'Probing'
    return state
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
              <GitFork size={18} />
              <h1 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, margin: 0 }}>
                Replication Dashboard
              </h1>
            </div>
          </div>
        }
      >
        {!dashboard ? (
          <div style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 'var(--space-xl)' }}>
            Loading replication status...
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-lg)' }}>
            {/* Topology */}
            <div className="card">
              <div className="card-header">Topology</div>
              <div className="card-body" style={{ display: 'flex', gap: 'var(--space-lg)', flexWrap: 'wrap' }}>
                <div>
                  <div className="text-xs text-muted">Mode</div>
                  <div className="font-semibold">{dashboard.topology.mode}</div>
                </div>
                <div>
                  <div className="text-xs text-muted">Node ID</div>
                  <div className="font-mono text-sm">{dashboard.topology.nodeId}</div>
                </div>
                {dashboard.topology.epoch !== undefined && (
                  <div>
                    <div className="text-xs text-muted">Epoch</div>
                    <div className="font-mono text-sm">{dashboard.topology.epoch}</div>
                  </div>
                )}
              </div>
            </div>

            {/* Replication Lag */}
            {dashboard.replication && Object.keys(dashboard.replication.databases).length > 0 && (
              <div className="card">
                <div className="card-header">Replication Lag</div>
                <div className="card-body">
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid var(--border-color)' }}>
                        <th style={{ padding: 'var(--space-sm)', textAlign: 'left', fontSize: 'var(--text-xs)', color: 'var(--text-secondary)' }}>
                          Database
                        </th>
                        <th style={{ padding: 'var(--space-sm)', textAlign: 'left', fontSize: 'var(--text-xs)', color: 'var(--text-secondary)' }}>
                          Lag
                        </th>
                        <th style={{ padding: 'var(--space-sm)', textAlign: 'left', fontSize: 'var(--text-xs)', color: 'var(--text-secondary)' }}>
                          Status
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(dashboard.replication.databases).map(([db, info]) => (
                        <tr key={db} style={{ borderBottom: '1px solid var(--border-color)' }}>
                          <td style={{ padding: 'var(--space-sm)', fontSize: 'var(--text-sm)' }}>{db}</td>
                          <td style={{ padding: 'var(--space-sm)', fontSize: 'var(--text-sm)', fontFamily: 'var(--font-mono)' }}>
                            {info.lagMs}ms
                          </td>
                          <td style={{ padding: 'var(--space-sm)', fontSize: 'var(--text-sm)' }}>
                            {info.status}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {/* Circuit Breaker */}
            {dashboard.replication && (
              <div className="card">
                <div className="card-header">Circuit Breaker</div>
                <div className="card-body" style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-md)' }}>
                  <div
                    style={{
                      width: 12,
                      height: 12,
                      borderRadius: '50%',
                      backgroundColor: circuitBreakerColor(dashboard.replication.circuitBreaker),
                    }}
                  />
                  <span className="font-semibold">{circuitBreakerLabel(dashboard.replication.circuitBreaker)}</span>
                </div>
              </div>
            )}

            {/* Failover Actions */}
            <div className="card">
              <div className="card-header">Failover Actions</div>
              <div className="card-body" style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                {dashboard.topology.mode === 'FOLLOWER' && (
                  <button
                    className="btn btn-primary"
                    onClick={() => setConfirmAction('promote')}
                  >
                    <ArrowUp size={14} />
                    Promote to Leader
                  </button>
                )}
                {dashboard.topology.mode === 'LEADER' && (
                  <button
                    className="btn btn-secondary"
                    onClick={() => setConfirmAction('stepdown')}
                  >
                    <ArrowDown size={14} />
                    Step Down to Follower
                  </button>
                )}
              </div>
            </div>
          </div>
        )}
      </Layout>

      <ConfirmModal
        open={confirmAction !== null}
        onClose={() => setConfirmAction(null)}
        onConfirm={handleConfirmAction}
        title={confirmAction === 'promote' ? 'Promote to Leader?' : 'Step Down to Follower?'}
        description={
          confirmAction === 'promote'
            ? 'This will make this node the primary write node.'
            : 'This node will become read-only.'
        }
        confirmLabel="Confirm"
        danger={confirmAction === 'stepdown'}
      />
      <DatabaseModals {...modalState} />
    </>
  )
}
