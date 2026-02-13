import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import type { QueryHistoryEntry, OperationMode } from '@/lib/types'

interface AppState {
  apiKey: string
  setApiKey: (key: string) => void
  logout: () => void
  commandPaletteOpen: boolean
  setCommandPaletteOpen: (open: boolean) => void
  queryHistory: QueryHistoryEntry[]
  addToHistory: (mode: OperationMode, input: string) => void
  sidebarCollapsed: boolean
  toggleSidebar: () => void
}

const AppContext = createContext<AppState | null>(null)

export function AppProvider({ children }: { children: ReactNode }) {
  const [apiKey, setApiKeyState] = useState(() => localStorage.getItem('api_key') ?? '')
  const [commandPaletteOpen, setCommandPaletteOpen] = useState(false)
  const [queryHistory, setQueryHistory] = useState<QueryHistoryEntry[]>([])
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)

  const setApiKey = useCallback((key: string) => {
    localStorage.setItem('api_key', key)
    setApiKeyState(key)
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem('api_key')
    setApiKeyState('')
  }, [])

  const addToHistory = useCallback((mode: OperationMode, input: string) => {
    setQueryHistory((prev) => {
      const entry: QueryHistoryEntry = {
        id: crypto.randomUUID(),
        mode,
        input,
        timestamp: Date.now(),
      }
      return [entry, ...prev].slice(0, 20)
    })
  }, [])

  const toggleSidebar = useCallback(() => {
    setSidebarCollapsed((prev) => !prev)
  }, [])

  return (
    <AppContext.Provider
      value={{
        apiKey,
        setApiKey,
        logout,
        commandPaletteOpen,
        setCommandPaletteOpen,
        queryHistory,
        addToHistory,
        sidebarCollapsed,
        toggleSidebar,
      }}
    >
      {children}
    </AppContext.Provider>
  )
}

export function useApp(): AppState {
  const ctx = useContext(AppContext)
  if (!ctx) throw new Error('useApp must be used within AppProvider')
  return ctx
}
