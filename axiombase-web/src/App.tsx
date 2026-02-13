import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useApp } from '@/context/AppContext'
import Login from '@/pages/Login'
import Dashboard from '@/pages/Dashboard'
import QueryConsole from '@/pages/QueryConsole'
import CommandPalette from '@/components/CommandPalette'
import type { ReactNode } from 'react'

function PrivateRoute({ children }: { children: ReactNode }) {
  const { apiKey } = useApp()
  return apiKey ? <>{children}</> : <Navigate to="/login" />
}

export default function App() {
  return (
    <BrowserRouter>
      <CommandPalette />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route
          path="/"
          element={
            <PrivateRoute>
              <Dashboard />
            </PrivateRoute>
          }
        />
        <Route
          path="/db/:dbName"
          element={
            <PrivateRoute>
              <QueryConsole />
            </PrivateRoute>
          }
        />
      </Routes>
    </BrowserRouter>
  )
}
