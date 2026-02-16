import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useDatabases, useTenants, useQuery, useKeyboardShortcut, useDatabaseActions } from '@/lib/hooks'
import type { OperationMode } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import ActionToolbar from '@/components/ActionToolbar'
import VisualBuilder from '@/components/VisualBuilder'
import CodeEditor from '@/components/CodeEditor'
import ResultsPanel from '@/components/ResultsPanel'
import DatabaseModals from '@/components/DatabaseModals'
import CopyAsCode from '@/components/CopyAsCode'

export default function QueryConsole() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const [mode, setMode] = useState<OperationMode>('ask')
  const [scope, setScope] = useState('')
  const [useVisual, setUseVisual] = useState(true)
  const [inputData, setInputData] = useState('')

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

  const { result, error, isLoading, execute, clear } = useQuery(
    dbName ?? '',
    isMultiTenant ? currentTenant : undefined,
  )

  const handleRun = useCallback(() => {
    if (!dbName || !inputData.trim()) return
    void execute(mode, inputData)
  }, [dbName, inputData, mode, execute])

  useKeyboardShortcut('Enter', handleRun, { meta: true })

  useEffect(() => {
    clear()
  }, [mode, clear])

  if (!dbName) {
    navigate('/')
    return null
  }

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
            onModeChange={setMode}
            onRun={handleRun}
            isRunning={isLoading}
            useVisual={useVisual}
            onToggleVisual={() => setUseVisual(!useVisual)}
            scope={scope}
            onScopeChange={setScope}
          />
        }
      >
        <div className="console-content">
          <div className="console-editor-area">
            {useVisual && !['execute', 'synthesize'].includes(mode) ? (
              <VisualBuilder
                mode={mode}
                onJsonChange={setInputData}
              />
            ) : (
              <CodeEditor
                value={inputData}
                onChange={setInputData}
                onRun={handleRun}
              />
            )}
          </div>
          <ResultsPanel
            result={result}
            error={error}
            isLoading={isLoading}
            mode={mode}
          />
          {result && (
            <CopyAsCode
              mode={mode}
              inputData={inputData}
              database={dbName}
              tenantId={isMultiTenant ? currentTenant : undefined}
            />
          )}
        </div>
      </Layout>
      <DatabaseModals {...modalState} />
    </>
  )
}
