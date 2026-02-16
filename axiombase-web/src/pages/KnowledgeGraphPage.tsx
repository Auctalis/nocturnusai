import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { RefreshCw } from 'lucide-react'
import { useDatabases, useTenants, useDatabaseActions } from '@/lib/hooks'
import { useApp } from '@/context/AppContext'
import * as api from '@/lib/api'
import type { AtomResponse, InspectItem } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import KnowledgeGraph from '@/components/KnowledgeGraph'

export default function KnowledgeGraphPage() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const { apiKey } = useApp()

  const [scope, setScope] = useState('')
  const [items, setItems] = useState<InspectItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const { databases, refresh: refreshDbs } = useDatabases()
  const currentDb = databases.find((d) => d.name === dbName)
  const isMultiTenant = currentDb?.isMultiTenant ?? false

  const { tenants, currentTenant, setCurrentTenant, refresh: refreshTenants } = useTenants(
    dbName,
    isMultiTenant,
  )

  const { sidebarActions, modalState } = useDatabaseActions(
    dbName,
    refreshDbs,
    refreshTenants,
    setCurrentTenant,
    { onDeletedCurrentDb: () => navigate('/') },
  )

  const fetchData = useCallback(async () => {
    if (!dbName || !apiKey) return
    setLoading(true)
    setError(null)
    try {
      const tenantId = isMultiTenant ? currentTenant : undefined
      const filterScope = scope || undefined

      const [facts, rules] = await Promise.all([
        api.listFacts(apiKey, dbName, tenantId, filterScope),
        api.listRules(apiKey, dbName, tenantId, filterScope),
      ])

      const combined: InspectItem[] = [
        ...facts.map((f: AtomResponse) => ({ Type: 'Fact' as const, Content: f })),
        ...rules.map((r: string) => ({ Type: 'Rule' as const, Content: r })),
      ]
      setItems(combined)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to fetch knowledge graph data')
    } finally {
      setLoading(false)
    }
  }, [apiKey, dbName, isMultiTenant, currentTenant, scope])

  useEffect(() => {
    void fetchData()
  }, [fetchData])

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            currentDb={dbName}
            onSelectDb={(name) => navigate(`/db/${name}/graph`)}
            {...sidebarActions}
            tenants={tenants}
            currentTenant={currentTenant}
            onSelectTenant={setCurrentTenant}
            isMultiTenant={isMultiTenant}
          />
        }
        toolbar={
          <div className="toolbar-row">
            <div className="toolbar-left">
              <h1 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, margin: 0 }}>
                Knowledge Graph
              </h1>
              {dbName && (
                <span className="badge badge-info">{dbName}</span>
              )}
              {isMultiTenant && currentTenant && (
                <span className="badge badge-success">{currentTenant}</span>
              )}
              <input
                type="text"
                className="input"
                placeholder="scope"
                value={scope}
                onChange={(e) => setScope(e.target.value)}
                style={{ width: 120, fontSize: 'var(--text-xs)', padding: '4px 8px' }}
              />
            </div>
            <div className="toolbar-right">
              <button
                className="btn btn-ghost btn-icon"
                onClick={() => void fetchData()}
                disabled={loading}
                title="Refresh graph"
              >
                <RefreshCw size={16} className={loading ? 'spin' : ''} />
              </button>
            </div>
          </div>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 'var(--space-md)' }}>
          {error && (
            <div className="card" style={{ borderColor: 'var(--red-60)' }}>
              <div className="card-body" style={{ color: 'var(--red-40)' }}>
                {error}
              </div>
            </div>
          )}

          {loading && items.length === 0 && (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 1 }}>
              <div className="text-muted" style={{ fontSize: 'var(--text-sm)' }}>
                Loading knowledge graph...
              </div>
            </div>
          )}

          {!loading && items.length === 0 && !error && (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 1 }}>
              <div className="text-muted" style={{ fontSize: 'var(--text-sm)', textAlign: 'center' }}>
                <p style={{ margin: 0 }}>No facts or rules found.</p>
                <p style={{ margin: '4px 0 0', fontSize: 'var(--text-xs)' }}>
                  Assert some facts in the Query Console to see them visualized here.
                </p>
              </div>
            </div>
          )}

          {items.length > 0 && (
            <div className="card" style={{ flex: 1, minHeight: 0 }}>
              <div className="card-header">
                <span>Graph Visualization</span>
                <span className="text-muted text-xs">
                  {items.filter((i) => i.Type === 'Fact').length} facts,{' '}
                  {items.filter((i) => i.Type === 'Rule').length} rules
                </span>
              </div>
              <div className="card-body" style={{ padding: 0, height: '100%', overflow: 'hidden' }}>
                <KnowledgeGraph items={items} />
              </div>
            </div>
          )}
        </div>
      </Layout>

      <DatabaseModals {...modalState} />
    </>
  )
}
