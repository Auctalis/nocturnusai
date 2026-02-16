import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useDatabases, useTenants, useDatabaseActions } from '@/lib/hooks'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import AskPanel from '@/components/AskPanel'
import TellPanel from '@/components/TellPanel'
import RecallPanel from '@/components/RecallPanel'
import { MessageCircle } from 'lucide-react'

type Tab = 'ask' | 'tell' | 'recall'

export default function ContextPlayground() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState<Tab>('ask')
  const [scope, setScope] = useState('')

  const { databases, refresh: refreshDbs } = useDatabases()
  const currentDb = databases.find((d) => d.name === dbName)
  const isMultiTenant = currentDb?.isMultiTenant ?? false

  const { tenants, currentTenant, setCurrentTenant, refresh: refreshTenants } = useTenants(dbName, isMultiTenant)

  const { sidebarActions, modalState } = useDatabaseActions(
    dbName,
    refreshDbs,
    refreshTenants,
    setCurrentTenant,
    { onDeletedCurrentDb: () => navigate('/') },
  )

  if (!dbName) {
    navigate('/')
    return null
  }

  const tabs: { id: Tab; label: string; description: string }[] = [
    { id: 'ask', label: 'Ask', description: 'Query knowledge with natural language' },
    { id: 'tell', label: 'Tell', description: 'Assert facts in natural language' },
    { id: 'recall', label: 'Recall', description: 'Search and retrieve stored facts' },
  ]

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
              <MessageCircle size={18} />
              <span style={{ fontWeight: 600 }}>Context Playground</span>
              <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                {dbName}
              </span>
            </div>
            <div className="toolbar-right">
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
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)', height: '100%' }}>
          <div className="tabs">
            {tabs.map((tab) => (
              <button
                key={tab.id}
                className={`tab ${activeTab === tab.id ? 'active' : ''}`}
                onClick={() => setActiveTab(tab.id)}
                title={tab.description}
              >
                {tab.label}
              </button>
            ))}
          </div>

          <div style={{ flex: 1, minHeight: 0 }}>
            {activeTab === 'ask' && (
              <AskPanel
                db={dbName}
                tenant={isMultiTenant ? currentTenant : undefined}
                scope={scope || undefined}
              />
            )}
            {activeTab === 'tell' && (
              <TellPanel
                db={dbName}
                tenant={isMultiTenant ? currentTenant : undefined}
                scope={scope || undefined}
              />
            )}
            {activeTab === 'recall' && (
              <RecallPanel
                db={dbName}
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
