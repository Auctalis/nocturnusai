import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useApp } from '@/context/AppContext'
import Login from '@/pages/Login'
import Dashboard from '@/pages/Dashboard'
import QueryConsole from '@/pages/QueryConsole'
import ContextPlayground from '@/pages/ContextPlayground'
import KnowledgeGraphPage from '@/pages/KnowledgeGraphPage'
import ApiReference from '@/pages/ApiReference'
import CommandPalette from '@/components/CommandPalette'

function PrivateRoute({ children }: { children: React.ReactNode }) {
  const { apiKey } = useApp()
  if (!apiKey) return <Navigate to="/login" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <BrowserRouter>
      <CommandPalette />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<PrivateRoute><Dashboard /></PrivateRoute>} />
        <Route path="/db/:dbName" element={<PrivateRoute><QueryConsole /></PrivateRoute>} />
        <Route path="/db/:dbName/graph" element={<PrivateRoute><KnowledgeGraphPage /></PrivateRoute>} />
        <Route path="/db/:dbName/context" element={<PrivateRoute><ContextPlayground /></PrivateRoute>} />
        <Route path="/db/:dbName/api" element={<PrivateRoute><ApiReference /></PrivateRoute>} />
      </Routes>
    </BrowserRouter>
  )
}
