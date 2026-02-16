import { useState, useEffect, useCallback, useRef } from 'react'
import { useApp } from '@/context/AppContext'
import { toast } from 'sonner'
import * as api from './api'
import type {
  AtomResponse,
  Database,
  OperationMode,
  FactRequest,
  RuleRequest,
  TemplateRequest,
  InspectItem,
} from './types'

// ── useDatabases ──────────────────────────────────────────────

export function useDatabases() {
  const { apiKey } = useApp()
  const [databases, setDatabases] = useState<Database[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetch = useCallback(async () => {
    if (!apiKey) return
    setLoading(true)
    setError(null)
    try {
      const list = await api.listDatabases(apiKey)
      setDatabases(list)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to fetch databases')
    } finally {
      setLoading(false)
    }
  }, [apiKey])

  useEffect(() => {
    void fetch()
  }, [fetch])

  return { databases, loading, error, refresh: fetch }
}

// ── useTenants ────────────────────────────────────────────────

export function useTenants(dbName: string | undefined, isMultiTenant: boolean) {
  const { apiKey } = useApp()
  const [tenants, setTenants] = useState<string[]>([])
  const [currentTenant, setCurrentTenant] = useState('')

  const fetch = useCallback(async () => {
    if (!apiKey || !dbName || !isMultiTenant) {
      setTenants([])
      return
    }
    try {
      const list = await api.listTenants(apiKey, dbName)
      setTenants(list)
      if (list.length > 0 && !currentTenant) {
        setCurrentTenant(list[0]!)
      }
    } catch (e) {
      console.error('Failed to fetch tenants', e)
    }
  }, [apiKey, dbName, isMultiTenant, currentTenant])

  useEffect(() => {
    void fetch()
  }, [fetch])

  return { tenants, currentTenant, setCurrentTenant, refresh: fetch }
}

// ── useQuery ──────────────────────────────────────────────────

type QueryResult = string | AtomResponse[] | InspectItem[]

export function useQuery(database: string, tenantId?: string) {
  const { apiKey, addToHistory } = useApp()
  const [result, setResult] = useState<QueryResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)

  const execute = useCallback(
    async (mode: OperationMode, inputData: string) => {
      setResult(null)
      setError(null)
      setIsLoading(true)

      try {
        if (mode === 'inspect') {
          let filterPred = ''
          let filterType: 'ALL' | 'FACT' | 'RULE' = 'ALL'
          let filterScope: string | undefined
          try {
            const fJson = JSON.parse(inputData) as Record<string, unknown>
            if (typeof fJson.filter === 'string') filterPred = fJson.filter.toLowerCase()
            if (fJson.type === 'FACT' || fJson.type === 'RULE') filterType = fJson.type
            if (typeof fJson.scope === 'string' && fJson.scope) filterScope = fJson.scope
          } catch {
            // filters are optional
          }

          const [facts, rules] = await Promise.all([
            api.listFacts(apiKey, database, tenantId, filterScope),
            api.listRules(apiKey, database, tenantId, filterScope),
          ])

          let combined: InspectItem[] = []
          if (filterType === 'ALL' || filterType === 'FACT') {
            combined.push(...facts.map((f) => ({ Type: 'Fact' as const, Content: f as AtomResponse })))
          }
          if (filterType === 'ALL' || filterType === 'RULE') {
            combined.push(...rules.map((r) => ({ Type: 'Rule' as const, Content: r })))
          }
          if (filterPred) {
            combined = combined.filter((item) =>
              JSON.stringify(item.Content).toLowerCase().includes(filterPred),
            )
          }
          setResult(combined)
          addToHistory(mode, inputData)
          return
        }

        // Parse JSON input
        const cleanedInput = inputData.replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '')
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const body: any = cleanedInput.trim() ? JSON.parse(cleanedInput) : {}

        let response: string | AtomResponse[]

        switch (mode) {
          // Simplified API (primary)
          case 'ask':
            response = await api.ask(apiKey, database, body as FactRequest, tenantId)
            break
          case 'tell':
            response = await api.tell(apiKey, database, body as FactRequest, tenantId)
            break
          case 'teach':
            response = await api.teach(apiKey, database, body as RuleRequest, tenantId)
            break
          case 'forget':
            response = await api.forget(apiKey, database, body as FactRequest, tenantId)
            break
          // Legacy modes (backward compat)
          case 'infer':
            response = await api.infer(apiKey, database, body as FactRequest, tenantId)
            break
          case 'assert_fact':
            response = await api.assertFact(apiKey, database, body as FactRequest, tenantId)
            break
          case 'assert_rule':
            response = await api.assertRule(apiKey, database, body as RuleRequest, tenantId)
            break
          case 'assert_template':
            response = await api.assertTemplate(apiKey, database, body as TemplateRequest, tenantId)
            break
          case 'retract':
            response = await api.retractFact(apiKey, database, body as FactRequest, tenantId)
            break
          case 'execute':
            response = await api.execute(apiKey, database, inputData, tenantId)
            break
          case 'context': {
            const ctxBody = cleanedInput.trim() ? JSON.parse(cleanedInput) as Record<string, unknown> : {}
            const maxFacts = typeof ctxBody.maxFacts === 'number' ? ctxBody.maxFacts : 50
            const ctx = await api.getContextWindow(apiKey, database, tenantId, maxFacts)
            const lines = [`Context Window — ${ctx.facts.length} of ${ctx.totalAvailable} facts (ranked by salience)\n`]
            for (const scored of ctx.facts) {
              const a = scored.atom
              const neg = a.negated ? 'NOT ' : ''
              lines.push(`  [${scored.salience.toFixed(3)}]  ${neg}${a.predicate}(${a.args.join(', ')})`)
            }
            if (Object.keys(ctx.predicateDistribution).length > 0) {
              lines.push(`\nPredicate distribution:`)
              for (const [pred, count] of Object.entries(ctx.predicateDistribution)) {
                lines.push(`  ${pred}: ${count}`)
              }
            }
            response = lines.join('\n')
            break
          }
          case 'memory': {
            const memBody = cleanedInput.trim() ? JSON.parse(cleanedInput) as Record<string, unknown> : {}
            const op = (memBody.operation as string) || 'compress'
            if (op === 'cleanup') {
              const threshold = typeof memBody.threshold === 'number' ? memBody.threshold : 0.05
              const result = await api.cleanup(apiKey, database, threshold, tenantId)
              response = `Cleanup complete\n  Expired: ${result.expiredCount}\n  Evicted: ${result.evictedCount}\n  Total removed: ${result.removedAtoms.length}`
            } else {
              const result = await api.compress(apiKey, database, tenantId)
              response = `Compression complete\n  Facts consolidated: ${result.factsConsolidated}\n  New summary facts: ${result.newFacts.length}`
            }
            break
          }
          default:
            throw new Error(`Unknown mode: ${mode as string}`)
        }

        setResult(response)
        addToHistory(mode, inputData)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Unknown error')
      } finally {
        setIsLoading(false)
      }
    },
    [apiKey, database, tenantId, addToHistory],
  )

  const clear = useCallback(() => {
    setResult(null)
    setError(null)
  }, [])

  return { result, error, isLoading, execute, clear }
}

// ── useDatabaseActions ────────────────────────────────────────

interface DatabaseActionCallbacks {
  onDeletedCurrentDb?: () => void
  onNukedDb?: () => void
  onNukedTenant?: () => void
}

export function useDatabaseActions(
  currentDb: string | undefined,
  refreshDbs: () => Promise<void>,
  refreshTenants: () => Promise<void>,
  setCurrentTenant: (id: string) => void,
  callbacks: DatabaseActionCallbacks = {},
) {
  const { apiKey } = useApp()
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null)
  const [nukeTarget, setNukeTarget] = useState<string | null>(null)
  const [nukeTenantTarget, setNukeTenantTarget] = useState<string | null>(null)
  const [createTenantOpen, setCreateTenantOpen] = useState(false)

  const handleCreateDb = useCallback(() => {
    // Handled by parent component's PromptModal
  }, [])

  const handleDeleteDb = useCallback(
    async (name: string) => {
      try {
        await api.deleteDatabase(apiKey, name)
        toast.success(`Database "${name}" deleted`)
        await refreshDbs()
        if (name === currentDb) callbacks.onDeletedCurrentDb?.()
      } catch (e) {
        toast.error(e instanceof Error ? e.message : 'Failed to delete')
      }
    },
    [apiKey, currentDb, refreshDbs, callbacks],
  )

  const handleNukeDb = useCallback(
    async (name: string) => {
      try {
        await api.nukeDatabase(apiKey, name)
        toast.success(`Database "${name}" cleared`)
        callbacks.onNukedDb?.()
      } catch (e) {
        toast.error(e instanceof Error ? e.message : 'Failed to clear')
      }
    },
    [apiKey, callbacks],
  )

  const handleCreateTenant = useCallback(
    async (tenantId: string) => {
      if (!currentDb) return
      try {
        await api.createTenant(apiKey, currentDb, tenantId)
        toast.success(`Tenant "${tenantId}" created`)
        await refreshTenants()
        setCurrentTenant(tenantId)
      } catch (e) {
        toast.error(e instanceof Error ? e.message : 'Failed to create tenant')
      }
    },
    [apiKey, currentDb, refreshTenants, setCurrentTenant],
  )

  const handleNukeTenant = useCallback(
    async (tenantId: string) => {
      if (!currentDb) return
      try {
        await api.nukeTenant(apiKey, currentDb, tenantId)
        toast.success(`Tenant "${tenantId}" cleared`)
        callbacks.onNukedTenant?.()
      } catch (e) {
        toast.error(e instanceof Error ? e.message : 'Failed to clear tenant')
      }
    },
    [apiKey, currentDb, callbacks],
  )

  return {
    sidebarActions: {
      onCreateDb: handleCreateDb,
      onDeleteDb: handleDeleteDb,
      onNukeDb: handleNukeDb,
      onCreateTenant: () => setCreateTenantOpen(true),
      onNukeTenant: handleNukeTenant,
    },
    modalState: {
      deleteTarget,
      setDeleteTarget,
      nukeTarget,
      setNukeTarget,
      nukeTenantTarget,
      setNukeTenantTarget,
      createTenantOpen,
      setCreateTenantOpen,
      onCreateTenant: handleCreateTenant,
    },
  }
}

// ── useKeyboardShortcut ───────────────────────────────────────

export function useKeyboardShortcut(
  key: string,
  callback: () => void,
  options: { meta?: boolean; shift?: boolean; ctrl?: boolean } = {},
) {
  const callbackRef = useRef(callback)

  useEffect(() => {
    callbackRef.current = callback
  }, [callback])

  useEffect(() => {
    function handler(e: KeyboardEvent) {
      if (options.meta && !e.metaKey && !e.ctrlKey) return
      if (options.shift && !e.shiftKey) return
      if (options.ctrl && !e.ctrlKey) return
      if (e.key.toLowerCase() === key.toLowerCase()) {
        e.preventDefault()
        callbackRef.current()
      }
    }

    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [key, options.meta, options.shift, options.ctrl])
}
