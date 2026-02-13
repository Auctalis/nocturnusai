import { useState, useEffect, useCallback, useRef } from 'react'
import { useApp } from '@/context/AppContext'
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
