import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Database } from 'lucide-react'
import { useDatabases, useTenants, useDatabaseActions } from '@/lib/hooks'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import FactsTable from '@/components/FactsTable'
import RulesList from '@/components/RulesList'
import SchemaViewer from '@/components/SchemaViewer'
import QueryBuilder from '@/components/QueryBuilder'
import TemplateBuilder from '@/components/TemplateBuilder'

type Tab = 'facts' | 'rules' | 'templates' | 'schema' | 'query'

export default function KnowledgeManager() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const [activeTab, setActiveTab] = useState<Tab>('facts')
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

  const toolbar = (
    <div className="toolbar-row">
      <div className="toolbar-left">
        <Database size={18} />
        <span style={{ fontWeight: 600 }}>{dbName}</span>
        {isMultiTenant && tenants.length > 0 && (
          <>
            <span style={{ color: 'var(--text-muted)' }}>/</span>
            <select
              className="input"
              value={currentTenant}
              onChange={(e) => setCurrentTenant(e.target.value)}
              style={{ width: 'auto', minWidth: '120px' }}
            >
              {tenants.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </>
        )}
        <span style={{ color: 'var(--text-muted)' }}>#</span>
        <input
          type="text"
          className="input"
          placeholder="scope"
          value={scope}
          onChange={(e) => setScope(e.target.value)}
          style={{ width: 120, minWidth: 80, fontSize: 'var(--text-xs)', padding: '4px 8px' }}
        />
      </div>
    </div>
  )

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            currentDb={dbName}
            onSelectDb={(name) => navigate(`/db/${name}/knowledge`)}
            {...sidebarActions}
            tenants={tenants}
            currentTenant={currentTenant}
            onSelectTenant={setCurrentTenant}
            isMultiTenant={isMultiTenant}
          />
        }
        toolbar={toolbar}
      >
        <div>
          <div className="tabs">
            <button
              className={`tab ${activeTab === 'facts' ? 'active' : ''}`}
              onClick={() => setActiveTab('facts')}
            >
              Facts
            </button>
            <button
              className={`tab ${activeTab === 'rules' ? 'active' : ''}`}
              onClick={() => setActiveTab('rules')}
            >
              Rules
            </button>
            <button
              className={`tab ${activeTab === 'templates' ? 'active' : ''}`}
              onClick={() => setActiveTab('templates')}
            >
              Templates
            </button>
            <button
              className={`tab ${activeTab === 'schema' ? 'active' : ''}`}
              onClick={() => setActiveTab('schema')}
            >
              Schema
            </button>
            <button
              className={`tab ${activeTab === 'query' ? 'active' : ''}`}
              onClick={() => setActiveTab('query')}
            >
              Query
            </button>
          </div>

          <div style={{ marginTop: 'var(--space-lg)' }}>
            {activeTab === 'facts' && (
              <FactsTable db={dbName} tenant={isMultiTenant ? currentTenant : undefined} scope={scope || undefined} />
            )}
            {activeTab === 'rules' && (
              <RulesList db={dbName} tenant={isMultiTenant ? currentTenant : undefined} scope={scope || undefined} />
            )}
            {activeTab === 'templates' && (
              <TemplateBuilder db={dbName} tenant={isMultiTenant ? currentTenant : undefined} />
            )}
            {activeTab === 'schema' && (
              <SchemaViewer db={dbName} tenant={isMultiTenant ? currentTenant : undefined} scope={scope || undefined} />
            )}
            {activeTab === 'query' && (
              <QueryBuilder db={dbName} tenant={isMultiTenant ? currentTenant : undefined} scope={scope || undefined} />
            )}
          </div>
        </div>
      </Layout>
      <DatabaseModals {...modalState} />
    </>
  )
}
