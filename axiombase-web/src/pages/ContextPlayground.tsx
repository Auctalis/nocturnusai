import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import { useDatabases, useTenants, useDatabaseActions } from '@/lib/hooks'
import AskPanel from '@/components/AskPanel'
import TellPanel from '@/components/TellPanel'
import RecallPanel from '@/components/RecallPanel'

type TabType = 'ask' | 'tell' | 'recall'

export default function ContextPlayground() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState<TabType>('ask')
  const [scope, setScope] = useState('')

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

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            currentDb={dbName}
            onSelectDb={(name) => navigate(`/db/${name}/context`)}
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
                Context Playground
              </h1>
              {dbName && (
                <span className="badge badge-info">{dbName}</span>
              )}
              {currentTenant && (
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
          </div>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 'var(--space-md)' }}>
          <div className="tabs">
            <button
              className={`tab ${activeTab === 'ask' ? 'active' : ''}`}
              onClick={() => setActiveTab('ask')}
            >
              Ask
            </button>
            <button
              className={`tab ${activeTab === 'tell' ? 'active' : ''}`}
              onClick={() => setActiveTab('tell')}
            >
              Tell
            </button>
            <button
              className={`tab ${activeTab === 'recall' ? 'active' : ''}`}
              onClick={() => setActiveTab('recall')}
            >
              Recall
            </button>
          </div>

          <div style={{ flex: 1, overflow: 'hidden' }}>
            {activeTab === 'ask' && (
              <AskPanel
                db={dbName ?? ''}
                tenant={isMultiTenant ? currentTenant : undefined}
                scope={scope || undefined}
              />
            )}
            {activeTab === 'tell' && (
              <TellPanel
                db={dbName ?? ''}
                tenant={isMultiTenant ? currentTenant : undefined}
                scope={scope || undefined}
              />
            )}
            {activeTab === 'recall' && (
              <RecallPanel
                db={dbName ?? ''}
                tenant={isMultiTenant ? currentTenant : undefined}
                scope={scope || undefined}
              />
            )}
          </div>
        </div>
      </Layout>
      <DatabaseModals {...modalState} />
    </>
  )
}
