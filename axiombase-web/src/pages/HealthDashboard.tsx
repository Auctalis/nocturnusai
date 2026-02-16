import { useCallback } from 'react'
import { useApp } from '@/context/AppContext'
import { useDatabases, useDatabaseActions, usePolling } from '@/lib/hooks'
import { useNavigate } from 'react-router-dom'
import * as api from '@/lib/api'
import type { HealthStatus, TopologyResponse } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import { StatusBadge } from '@/components/StatusBadge'
import { HealthCheckCard } from '@/components/HealthCheckCard'
import { Heart, Server, Activity } from 'lucide-react'
import { useState, useEffect } from 'react'
import { toast } from 'sonner'

const noopRefreshTenants = async () => {}
const noopSetTenant = () => {}

export default function HealthDashboard() {
  const { apiKey } = useApp()
  const navigate = useNavigate()
  const { databases, refresh: refreshDbs } = useDatabases()
  const [metrics, setMetrics] = useState('')

  const { sidebarActions, modalState } = useDatabaseActions(
    undefined,
    refreshDbs,
    noopRefreshTenants,
    noopSetTenant,
  )

  const healthFetcher = useCallback(() => api.getHealth(apiKey), [apiKey])
  const { data: health } = usePolling<HealthStatus>(healthFetcher, 10000)

  const topoFetcher = useCallback(() => api.getTopology(apiKey), [apiKey])
  const { data: topology } = usePolling<TopologyResponse>(topoFetcher, 10000)

  useEffect(() => {
    const fetchMetrics = async () => {
      try {
        const result = await api.getMetrics(apiKey)
        setMetrics(result)
      } catch {
        toast.error('Failed to fetch metrics')
      }
    }
    void fetchMetrics()
    const timer = setInterval(() => void fetchMetrics(), 30000)
    return () => clearInterval(timer)
  }, [apiKey])

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
              <Heart size={18} />
              <h1 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, margin: 0 }}>
                Health Dashboard
              </h1>
            </div>
          </div>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-lg)' }}>
          {/* Overall Status */}
          {health && (
            <div className="card">
              <div className="card-header">
                <span><Activity size={14} style={{ marginRight: 6, verticalAlign: 'middle' }} />Overall Status</span>
              </div>
              <div className="card-body">
                <StatusBadge status={health.status} large />
              </div>
            </div>
          )}

          {/* Health Checks */}
          {health?.checks && (
            <div>
              <h2 style={{ fontSize: 'var(--text-md)', fontWeight: 600, marginBottom: 'var(--space-md)' }}>
                Health Checks
              </h2>
              <div className="dashboard-grid">
                {Object.entries(health.checks).map(([name, check]) => (
                  <HealthCheckCard key={name} name={name} check={check} />
                ))}
              </div>
            </div>
          )}

          {/* Topology */}
          {topology && (
            <div className="card">
              <div className="card-header">
                <span><Server size={14} style={{ marginRight: 6, verticalAlign: 'middle' }} />Topology</span>
              </div>
              <div className="card-body" style={{ display: 'flex', gap: 'var(--space-lg)', flexWrap: 'wrap' }}>
                <div>
                  <div className="text-xs text-muted">Mode</div>
                  <div className="font-semibold">{topology.mode}</div>
                </div>
                <div>
                  <div className="text-xs text-muted">Node ID</div>
                  <div className="font-mono text-sm">{topology.nodeId}</div>
                </div>
                <div>
                  <div className="text-xs text-muted">Epoch</div>
                  <div className="font-mono text-sm">{topology.epoch}</div>
                </div>
                <div>
                  <div className="text-xs text-muted">Capabilities</div>
                  <div className="flex gap-1">
                    {topology.capabilities.map((c) => (
                      <span key={c} className="badge badge-info">{c}</span>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Metrics */}
          {metrics && (
            <div className="card">
              <div className="card-header">Metrics</div>
              <div className="card-body">
                <pre style={{
                  fontSize: 'var(--text-xs)',
                  fontFamily: 'var(--font-mono)',
                  overflow: 'auto',
                  maxHeight: '400px',
                  margin: 0,
                }}>
                  {metrics}
                </pre>
              </div>
            </div>
          )}
        </div>
      </Layout>
      <DatabaseModals {...modalState} />
    </>
  )
}
