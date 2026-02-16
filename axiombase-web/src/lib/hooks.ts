import { useState, useEffect, useCallback, useRef } from 'react'
import { useApp } from '@/context/AppContext'
import { toast } from 'sonner'
import * as api from './api'
import { parseAtom, parseRule, isRule } from './parse'
import type {
  AtomResponse,
  Database,
  OperationMode,
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
    async (mode: OperationMode, inputText: string) => {
      setResult(null)
      setError(null)
      setIsLoading(true)

      const text = inputText.trim()

      try {
        // ── Inspect: browse all knowledge (text is optional filter) ──
        if (mode === 'inspect') {
          const filter = text.toLowerCase()
          const [facts, rules] = await Promise.all([
            api.listFacts(apiKey, database, tenantId),
            api.listRules(apiKey, database, tenantId),
          ])

          let combined: InspectItem[] = [
            ...facts.map((f) => ({ Type: 'Fact' as const, Content: f as AtomResponse })),
            ...rules.map((r) => ({ Type: 'Rule' as const, Content: r })),
          ]
          if (filter) {
            combined = combined.filter((item) =>
              JSON.stringify(item.Content).toLowerCase().includes(filter),
            )
          }
          setResult(combined)
          addToHistory(mode, text)
          return
        }

        // ── Context: fetch salience-ranked context window ──
        if (mode === 'context') {
          const maxFacts = text ? parseInt(text, 10) || 50 : 50
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
          setResult(lines.join('\n'))
          addToHistory(mode, text)
          return
        }

        // ── Memory: compress or cleanup ──
        if (mode === 'memory') {
          const lower = text.toLowerCase()
          if (lower.startsWith('cleanup')) {
            const parts = lower.split(/\s+/)
            const threshold = parts[1] ? parseFloat(parts[1]) : 0.05
            const r = await api.cleanup(apiKey, database, threshold, tenantId)
            setResult(`Cleanup complete\n  Expired: ${r.expiredCount}\n  Evicted: ${r.evictedCount}\n  Total removed: ${r.removedAtoms.length}`)
          } else {
            const r = await api.compress(apiKey, database, tenantId)
            setResult(`Compression complete\n  Facts consolidated: ${r.factsConsolidated}\n  New summary facts: ${r.newFacts.length}`)
          }
          addToHistory(mode, text)
          return
        }

        // ── DSL: pass raw text to /execute ──
        if (mode === 'execute') {
          if (!text) throw new Error('Enter a Logiql command')
          const response = await api.execute(apiKey, database, text, tenantId)
          setResult(response)
          addToHistory(mode, text)
          return
        }

        // ── Agent modes: parse predicate(args) syntax ──
        if (!text) throw new Error('Enter a predicate, e.g. likes(alice, bob)')

        let response: string | AtomResponse[]

        switch (mode) {
          case 'ask': {
            const atom = parseAtom(text)
            response = await api.ask(apiKey, database, atom, tenantId)
            break
          }
          case 'tell': {
            const atom = parseAtom(text)
            response = await api.tell(apiKey, database, { ...atom, truthVal: !atom.negated }, tenantId)
            break
          }
          case 'teach': {
            if (!isRule(text)) throw new Error('Use rule syntax: head(?x) :- body(?x)')
            const rule = parseRule(text)
            response = await api.teach(apiKey, database, rule, tenantId)
            break
          }
          case 'forget': {
            const atom = parseAtom(text)
            response = await api.forget(apiKey, database, atom, tenantId)
            break
          }
          // Legacy modes
          case 'infer': {
            const atom = parseAtom(text)
            response = await api.infer(apiKey, database, atom, tenantId)
            break
          }
          case 'assert_fact': {
            const atom = parseAtom(text)
            response = await api.assertFact(apiKey, database, { ...atom, truthVal: !atom.negated }, tenantId)
            break
          }
          case 'assert_rule': {
            const rule = parseRule(text)
            response = await api.assertRule(apiKey, database, rule, tenantId)
            break
          }
          case 'retract': {
            const atom = parseAtom(text)
            response = await api.retractFact(apiKey, database, atom, tenantId)
            break
          }
          default:
            throw new Error(`Unknown mode: ${mode as string}`)
        }

        setResult(response)
        addToHistory(mode, text)
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
