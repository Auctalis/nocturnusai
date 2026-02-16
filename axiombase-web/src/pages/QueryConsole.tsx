import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useDatabases, useTenants, useQuery, useKeyboardShortcut, useDatabaseActions } from '@/lib/hooks'
import type { OperationMode } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import ActionToolbar from '@/components/ActionToolbar'
import CodeEditor from '@/components/CodeEditor'
import VisualBuilder from '@/components/VisualBuilder'
import ResultsPanel from '@/components/ResultsPanel'
import TransactionBanner from '@/components/TransactionBanner'

const TEMPLATES: Record<OperationMode, string> = {
  infer: '{\n  "predicate": "GrandParent",\n  "args": ["?x", "?y"]\n}',
  assert_fact: '{\n  "predicate": "Parent",\n  "args": ["Zeus", "Ares"],\n  "truthVal": true\n}',
  retract: '{\n  "predicate": "Parent",\n  "args": ["Zeus", "Ares"]\n}',
  assert_rule:
    '{\n  "head": { "predicate": "GrandParent", "args": ["?x", "?z"] },\n  "body": [\n    { "predicate": "Parent", "args": ["?x", "?y"] },\n    { "predicate": "Parent", "args": ["?y", "?z"] }\n  ]\n}',
  assert_template:
    '{\n  "type": "SYLLOGISM",\n  "predicates": { "P": "Man", "Q": "Mortal" },\n  "args": ["?x"]\n}',
  inspect: '',
  execute: '// Logiql DSL command\nassert Parent(Zeus, Ares).',
  synthesize: '',
}

export default function QueryConsole() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const [mode, setMode] = useState<OperationMode>('infer')
  const [scope, setScope] = useState('')
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

  const { sidebarActions, modalState } = useDatabaseActions(
    dbName,
    refreshDbs,
    refreshTenants,
    setCurrentTenant,
    {
      onDeletedCurrentDb: () => navigate('/'),
      onNukedDb: clear,
      onNukedTenant: clear,
    },
  )

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

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            currentDb={dbName}
            onSelectDb={(name) => navigate(`/db/${name}`)}
            {...sidebarActions}
            tenants={tenants}
            currentTenant={currentTenant}
            onSelectTenant={setCurrentTenant}
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
            database={dbName}
            tenantId={isMultiTenant ? currentTenant : undefined}
            scope={scope}
            onScopeChange={setScope}
          />
        }
      >
        <TransactionBanner database={dbName ?? ''} tenantId={isMultiTenant ? currentTenant : undefined} />
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

      <DatabaseModals {...modalState} />
    </>
  )
}
