import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useDatabases, useTenants, useQuery, useKeyboardShortcut, useDatabaseActions } from '@/lib/hooks'
import type { OperationMode } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import ActionToolbar from '@/components/ActionToolbar'
import ResultsPanel from '@/components/ResultsPanel'
import DatabaseModals from '@/components/DatabaseModals'

const PLACEHOLDERS: Record<string, string> = {
  ask:     'mortal(?who)',
  tell:    'likes(alice, bob)',
  teach:   'mortal(?x) :- human(?x)',
  forget:  'likes(alice, bob)',
  inspect: 'filter by predicate name…',
  context: '50',
  memory:  'compress   or   cleanup 0.05',
  execute: 'ASSERT human(socrates).',
}

const HINTS: Record<string, string> = {
  ask:     'Query the knowledge base.  Variables start with ?',
  tell:    'Store a fact.  e.g. parent(tom, bob)',
  teach:   'Define a rule.  head(?x) :- condition1(?x), condition2(?x)',
  forget:  'Remove a fact and any derived conclusions.',
  inspect: 'Leave empty to see everything, or type a predicate name to filter.',
  context: 'Number of top-salience facts to retrieve (default 50).',
  memory:  'Type "compress" to consolidate, or "cleanup 0.05" to evict low-salience facts.',
  execute: 'Raw Logiql DSL.  e.g. QUERY mortal(?x).',
}

export default function QueryConsole() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const [mode, setMode] = useState<OperationMode>('ask')
  const [inputText, setInputText] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

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

  // For inspect and context, allow running with empty input
  const canRunEmpty = ['inspect', 'context', 'memory'].includes(mode)

  const handleRun = useCallback(() => {
    if (!dbName) return
    if (!inputText.trim() && !canRunEmpty) return
    void execute(mode, inputText)
  }, [dbName, inputText, mode, execute, canRunEmpty])

  useKeyboardShortcut('Enter', handleRun, { meta: true })

  // Clear results and refocus input on mode change
  useEffect(() => {
    setInputText('')
    clear()
    textareaRef.current?.focus()
  }, [mode, clear])

  // Handle Cmd+Enter inside the textarea
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      handleRun()
    }
  }

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
          />
        }
      >
        <div className="console-content">
          <div className="console-input-area">
            <textarea
              ref={textareaRef}
              className="console-textarea"
              placeholder={PLACEHOLDERS[mode] ?? ''}
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              onKeyDown={handleKeyDown}
              rows={mode === 'execute' ? 5 : 2}
              spellCheck={false}
              autoFocus
            />
            <div className="console-hint">{HINTS[mode]}</div>
          </div>

          <ResultsPanel
            result={result}
            error={error}
            isLoading={isLoading}
            mode={mode}
          />
        </div>
      </Layout>
      <DatabaseModals {...modalState} />
    </>
  )
}
