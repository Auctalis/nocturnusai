import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import { useDatabases, useTenants, useQuery, useKeyboardShortcut } from '@/lib/hooks'
import * as api from '@/lib/api'
import type { OperationMode } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import ActionToolbar from '@/components/ActionToolbar'
import CodeEditor from '@/components/CodeEditor'
import VisualBuilder from '@/components/VisualBuilder'
import ResultsPanel from '@/components/ResultsPanel'
import { PromptModal, ConfirmModal } from '@/components/Modal'

const TEMPLATES: Record<OperationMode, string> = {
  infer: '{\n  "predicate": "GrandParent",\n  "args": ["?x", "?y"]\n}',
  assert_fact: '{\n  "predicate": "Parent",\n  "args": ["Zeus", "Ares"],\n  "truthVal": true\n}',
  retract: '{\n  "predicate": "Parent",\n  "args": ["Zeus", "Ares"]\n}',
  assert_rule:
    '{\n  "head": { "predicate": "GrandParent", "args": ["?x", "?z"] },\n  "body": [\n    { "predicate": "Parent", "args": ["?x", "?y"] },\n    { "predicate": "Parent", "args": ["?y", "?z"] }\n  ]\n}',
  assert_template:
    '{\n  "type": "SYLLOGISM",\n  "predicates": { "P": "Man", "Q": "Mortal" },\n  "args": ["?x"]\n}',
  inspect: '',
}

export default function QueryConsole() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const { apiKey } = useApp()

  const [mode, setMode] = useState<OperationMode>('infer')
  const [inputData, setInputData] = useState(TEMPLATES.infer)
  const [useVisual, setUseVisual] = useState(true)

  const { databases, refresh: refreshDbs } = useDatabases()
  const currentDb = databases.find((d) => d.name === dbName)
  const isMultiTenant = currentDb?.isMultiTenant ?? false

  const { tenants, currentTenant, setCurrentTenant, refresh: refreshTenants } = useTenants(
    dbName,
    isMultiTenant,
  )

  const { result, error, isLoading, execute, clear } = useQuery(
    dbName ?? '',
    isMultiTenant ? currentTenant : undefined,
  )

  // Modals
  const [createDbOpen, setCreateDbOpen] = useState(false)
  const [createTenantOpen, setCreateTenantOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null)
  const [nukeTarget, setNukeTarget] = useState<string | null>(null)
  const [nukeTenantTarget, setNukeTenantTarget] = useState<string | null>(null)

  // Reset on DB/Tenant change
  useEffect(() => {
    clear()
    setMode('infer')
    setInputData(TEMPLATES.infer)
  }, [dbName, currentTenant, clear])

  const switchMode = useCallback(
    (newMode: OperationMode) => {
      setMode(newMode)
      if (!useVisual) {
        setInputData(TEMPLATES[newMode])
      }
      clear()
    },
    [useVisual, clear],
  )

  const handleRun = useCallback(() => {
    void execute(mode, inputData)
  }, [execute, mode, inputData])

  useKeyboardShortcut('Enter', handleRun, { meta: true })

  // DB actions
  const handleCreateDb = async (name: string) => {
    try {
      await api.createDatabase(apiKey, name, true)
      toast.success(`Database "${name}" created`)
      await refreshDbs()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to create')
    }
  }

  const handleDeleteDb = async () => {
    if (!deleteTarget) return
    try {
      await api.deleteDatabase(apiKey, deleteTarget)
      toast.success(`Database "${deleteTarget}" deleted`)
      await refreshDbs()
      if (dbName === deleteTarget) navigate('/')
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to delete')
    }
  }

  const handleNukeDb = async () => {
    if (!nukeTarget) return
    try {
      await api.nukeDatabase(apiKey, nukeTarget)
      toast.success(`Database "${nukeTarget}" cleared`)
      clear()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to nuke')
    }
  }

  const handleCreateTenant = async (tenantId: string) => {
    if (!dbName) return
    try {
      await api.createTenant(apiKey, dbName, tenantId)
      toast.success(`Tenant "${tenantId}" created`)
      await refreshTenants()
      setCurrentTenant(tenantId)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to create tenant')
    }
  }

  const handleNukeTenant = async () => {
    if (!dbName || !nukeTenantTarget) return
    try {
      await api.nukeTenant(apiKey, dbName, nukeTenantTarget)
      toast.success(`Tenant "${nukeTenantTarget}" cleared`)
      clear()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to nuke tenant')
    }
  }

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            currentDb={dbName}
            onSelectDb={(name) => navigate(`/db/${name}`)}
            onCreateDb={() => setCreateDbOpen(true)}
            onDeleteDb={(name) => setDeleteTarget(name)}
            onNukeDb={(name) => setNukeTarget(name)}
            tenants={tenants}
            currentTenant={currentTenant}
            onSelectTenant={setCurrentTenant}
            onCreateTenant={() => setCreateTenantOpen(true)}
            onNukeTenant={(id) => setNukeTenantTarget(id)}
            isMultiTenant={isMultiTenant}
          />
        }
        toolbar={
          <ActionToolbar
            mode={mode}
            onModeChange={switchMode}
            onRun={handleRun}
            isRunning={isLoading}
            useVisual={useVisual}
            onToggleVisual={() => setUseVisual(!useVisual)}
          />
        }
      >
        <div className="console-content">
          <div className="console-editor-area">
            {useVisual ? (
              <VisualBuilder mode={mode} onJsonChange={setInputData} />
            ) : (
              <CodeEditor value={inputData} onChange={setInputData} onRun={handleRun} />
            )}
          </div>
          <ResultsPanel result={result} error={error} isLoading={isLoading} mode={mode} />
        </div>
      </Layout>

      <PromptModal
        open={createDbOpen}
        onClose={() => setCreateDbOpen(false)}
        onSubmit={handleCreateDb}
        title="Create Database"
        placeholder="database-name"
      />
      <PromptModal
        open={createTenantOpen}
        onClose={() => setCreateTenantOpen(false)}
        onSubmit={handleCreateTenant}
        title="Create Tenant"
        placeholder="tenant-id"
      />
      <ConfirmModal
        open={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDeleteDb}
        title={`Delete "${deleteTarget}"?`}
        description="This cannot be undone. All data will be permanently removed."
        confirmLabel="Delete"
        danger
      />
      <ConfirmModal
        open={nukeTarget !== null}
        onClose={() => setNukeTarget(null)}
        onConfirm={handleNukeDb}
        title={`Clear all data in "${nukeTarget}"?`}
        description="This will remove all facts and rules. The database will remain."
        confirmLabel="Clear Data"
        danger
      />
      <ConfirmModal
        open={nukeTenantTarget !== null}
        onClose={() => setNukeTenantTarget(null)}
        onConfirm={handleNukeTenant}
        title={`Clear tenant "${nukeTenantTarget}"?`}
        description="This will remove all facts and rules for this tenant."
        confirmLabel="Clear Data"
        danger
      />
    </>
  )
}
