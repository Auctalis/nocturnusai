import { useEffect } from 'react'
import type { Database, UserRole } from '@/lib/types'
import { useApp } from '@/context/AppContext'
import { useNavigate, useLocation } from 'react-router-dom'
import {
  Database as DatabaseIcon,
  Plus,
  Trash2,
  Eraser,
  Clock,
  Command,
  LogOut,
  Search,
  MessageCircle,
  Terminal,
  Share2,
  Home,
  FileText,
} from 'lucide-react'
import Kbd from './Kbd'

interface SidebarProps {
  databases: Database[]
  currentDb?: string
  onSelectDb: (name: string) => void
  onCreateDb: () => void
  onDeleteDb: (name: string) => void
  onNukeDb: (name: string) => void
  tenants: string[]
  currentTenant: string
  onSelectTenant: (id: string) => void
  onCreateTenant: () => void
  onNukeTenant: (id: string) => void
  isMultiTenant: boolean
  role?: UserRole
}

interface NavItem {
  label: string
  icon: typeof Search
  path: string
  roles: UserRole[]
}

export default function Sidebar({
  databases,
  currentDb: currentDbProp,
  onSelectDb,
  onCreateDb,
  onDeleteDb,
  onNukeDb,
  tenants,
  currentTenant,
  onSelectTenant,
  onCreateTenant,
  onNukeTenant,
  isMultiTenant,
  role = 'admin',
}: SidebarProps) {
  const { setCommandPaletteOpen, queryHistory, logout, sidebarCollapsed, selectedDb, setSelectedDb } = useApp()
  const navigate = useNavigate()
  const location = useLocation()

  const currentDb = currentDbProp ?? (selectedDb || undefined)

  useEffect(() => {
    if (currentDbProp) setSelectedDb(currentDbProp)
  }, [currentDbProp, setSelectedDb])

  const mainNav: NavItem[] = [
    { label: 'Query Console', icon: Terminal, path: `/db/${currentDb}`, roles: ['admin', 'developer', 'agent'] },
    { label: 'Knowledge Graph', icon: Share2, path: `/db/${currentDb}/graph`, roles: ['admin', 'developer', 'agent'] },
    { label: 'Context Playground', icon: MessageCircle, path: `/db/${currentDb}/context`, roles: ['admin', 'developer', 'agent'] },
    { label: 'API Reference', icon: FileText, path: `/db/${currentDb}/api`, roles: ['admin', 'developer', 'agent'] },
  ]

  const isActive = (path: string) => {
    return location.pathname === path
  }

  const renderNavItems = (items: NavItem[]) =>
    items
      .filter((item) => item.roles.includes(role))
      .map((item) => {
        const Icon = item.icon
        return (
          <div
            key={item.path}
            className={`sidebar-item ${isActive(item.path) ? 'active' : ''}`}
            onClick={() => navigate(item.path)}
            title={sidebarCollapsed ? item.label : undefined}
          >
            <Icon size={14} className="sidebar-item-icon" />
            {!sidebarCollapsed && <span className="truncate" style={{ flex: 1 }}>{item.label}</span>}
          </div>
        )
      })

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
      {/* Database Selector */}
      <div className="sidebar-section">
        <div className="sidebar-label">
          {!sidebarCollapsed && <span>Database</span>}
          <button className="btn btn-ghost btn-sm" onClick={onCreateDb} title="New Database">
            <Plus size={14} />
          </button>
        </div>
        {!sidebarCollapsed ? (
          <div className="flex-col gap-1">
            <select
              className="input"
              value={currentDb ?? ''}
              onChange={(e) => { setSelectedDb(e.target.value); onSelectDb(e.target.value) }}
              style={{
                background: 'var(--gray-80)',
                color: 'var(--text-inverse)',
                border: '1px solid var(--gray-70)',
                borderRadius: 'var(--radius-sm)',
                padding: '6px 8px',
                fontSize: 'var(--text-sm)',
              }}
            >
              <option value="">Select database...</option>
              {databases.map((db) => (
                <option key={db.name} value={db.name}>{db.name}</option>
              ))}
            </select>
            {currentDb && currentDb !== 'default' && (
              <div className="flex gap-1" style={{ justifyContent: 'flex-end' }}>
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => onNukeDb(currentDb)}
                  title="Clear all data"
                  style={{ color: 'var(--gray-40)' }}
                >
                  <Eraser size={12} />
                </button>
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => onDeleteDb(currentDb)}
                  title="Delete database"
                  style={{ color: 'var(--gray-40)' }}
                >
                  <Trash2 size={12} />
                </button>
              </div>
            )}
          </div>
        ) : (
          <div className="flex-col gap-1">
            {databases.slice(0, 3).map((db) => (
              <div
                key={db.name}
                className={`sidebar-item ${currentDb === db.name ? 'active' : ''}`}
                onClick={() => { setSelectedDb(db.name); onSelectDb(db.name) }}
                title={db.name}
              >
                <DatabaseIcon size={14} />
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Tenant Selector */}
      {currentDb && isMultiTenant && !sidebarCollapsed && (
        <div className="sidebar-section" style={{ paddingTop: 0 }}>
          <div className="sidebar-label">
            <span>Tenant</span>
            <button className="btn btn-ghost btn-sm" onClick={onCreateTenant} title="New Tenant">
              <Plus size={14} />
            </button>
          </div>
          <select
            className="input"
            value={currentTenant}
            onChange={(e) => onSelectTenant(e.target.value)}
            style={{
              background: 'var(--gray-80)',
              color: 'var(--text-inverse)',
              border: '1px solid var(--gray-70)',
              borderRadius: 'var(--radius-sm)',
              padding: '4px 8px',
              fontSize: 'var(--text-xs)',
            }}
          >
            {tenants.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
          {currentTenant && (
            <div className="flex gap-1" style={{ justifyContent: 'flex-end', marginTop: 2 }}>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => onNukeTenant(currentTenant)}
                title="Clear tenant data"
                style={{ color: 'var(--gray-40)' }}
              >
                <Eraser size={10} />
                <span style={{ fontSize: 10, marginLeft: 2 }}>Clear</span>
              </button>
            </div>
          )}
        </div>
      )}

      {/* Dashboard Link */}
      <div className="sidebar-section" style={{ paddingTop: 0 }}>
        <div className="flex-col gap-1">
          <div
            className={`sidebar-item ${location.pathname === '/' ? 'active' : ''}`}
            onClick={() => navigate('/')}
            title={sidebarCollapsed ? 'Dashboard' : undefined}
          >
            <Home size={14} className="sidebar-item-icon" />
            {!sidebarCollapsed && <span className="truncate" style={{ flex: 1 }}>Dashboard</span>}
          </div>
        </div>
      </div>

      {/* Navigation */}
      {currentDb && (
        <div className="sidebar-section" style={{ paddingTop: 0 }}>
          <div className="flex-col gap-1">
            {renderNavItems(mainNav)}
          </div>
        </div>
      )}

      {/* Query History */}
      {queryHistory.length > 0 && !sidebarCollapsed && (
        <div className="sidebar-section">
          <div className="sidebar-label">
            <span>History</span>
          </div>
          <div className="flex-col gap-1">
            {queryHistory.slice(0, 5).map((entry) => (
              <div key={entry.id} className="sidebar-item">
                <Clock size={12} className="sidebar-item-icon" style={{ flexShrink: 0 }} />
                <span className="badge badge-info" style={{ marginRight: 'var(--space-xs)' }}>
                  {entry.mode.replace('assert_', '').replace('_', ' ')}
                </span>
                <span className="truncate text-xs font-mono" style={{ flex: 1 }}>
                  {entry.input.slice(0, 30)}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Footer */}
      <div className="sidebar-footer">
        <div
          className="sidebar-hint"
          onClick={() => setCommandPaletteOpen(true)}
        >
          {sidebarCollapsed ? (
            <Search size={16} />
          ) : (
            <>
              <Command size={14} />
              <span>Command palette</span>
              <Kbd keys={['\u2318', 'K']} />
            </>
          )}
        </div>
        {!sidebarCollapsed && (
          <div style={{ marginTop: 'var(--space-sm)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span className="text-muted text-xs">v2.0.0</span>
            <button className="btn btn-ghost btn-sm" onClick={() => { logout(); navigate('/login') }}>
              <LogOut size={12} />
              <span style={{ marginLeft: 4 }}>Sign out</span>
            </button>
          </div>
        )}
        {sidebarCollapsed && (
          <button
            className="sidebar-hint"
            onClick={() => { logout(); navigate('/login') }}
            title="Sign out"
            style={{ marginTop: 'var(--space-sm)', border: 'none', width: '100%' }}
          >
            <LogOut size={16} />
          </button>
        )}
      </div>
    </div>
  )
}
