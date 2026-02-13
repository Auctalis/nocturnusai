import type { Database } from '@/lib/types'
import { useApp } from '@/context/AppContext'
import { useNavigate } from 'react-router-dom'
import {
  Database as DatabaseIcon,
  Plus,
  Trash2,
  Eraser,
  Users,
  Clock,
  Command,
  LogOut,
  Search,
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
}

export default function Sidebar({
  databases,
  currentDb,
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
}: SidebarProps) {
  const { setCommandPaletteOpen, queryHistory, logout, sidebarCollapsed } = useApp()
  const navigate = useNavigate()

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
      {/* Databases */}
      <div className="sidebar-section">
        <div className="sidebar-label">
          {!sidebarCollapsed && <span>Databases</span>}
          <button className="btn btn-ghost btn-sm" onClick={onCreateDb} title="New Database">
            <Plus size={14} />
          </button>
        </div>
        <div className="flex-col gap-1">
          {databases.map((db) => (
            <div
              key={db.name}
              className={`sidebar-item ${currentDb === db.name ? 'active' : ''}`}
              onClick={() => onSelectDb(db.name)}
              title={sidebarCollapsed ? db.name : undefined}
            >
              <DatabaseIcon size={14} className="sidebar-item-icon" />
              {!sidebarCollapsed && <span className="truncate" style={{ flex: 1 }}>{db.name}</span>}
              {!sidebarCollapsed && db.name !== 'default' && (
                <div className="sidebar-item-actions">
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={(e) => {
                      e.stopPropagation()
                      onNukeDb(db.name)
                    }}
                    title="Clear all data"
                  >
                    <Eraser size={12} />
                  </button>
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={(e) => {
                      e.stopPropagation()
                      onDeleteDb(db.name)
                    }}
                    title="Delete database"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Tenants */}
      {currentDb && isMultiTenant && !sidebarCollapsed && (
        <div className="sidebar-section">
          <div className="sidebar-label">
            <span>Tenants</span>
            <button className="btn btn-ghost btn-sm" onClick={onCreateTenant} title="New Tenant">
              <Plus size={14} />
            </button>
          </div>
          <div className="flex-col gap-1">
            {tenants.length > 0 ? (
              tenants.map((t) => (
                <div
                  key={t}
                  className={`sidebar-item ${currentTenant === t ? 'active' : ''}`}
                  onClick={() => onSelectTenant(t)}
                >
                  <Users size={14} className="sidebar-item-icon" />
                  <span className="truncate text-sm" style={{ flex: 1 }}>{t}</span>
                  <div className="sidebar-item-actions">
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={(e) => {
                        e.stopPropagation()
                        onNukeTenant(t)
                      }}
                      title="Clear tenant data"
                    >
                      <Eraser size={12} />
                    </button>
                  </div>
                </div>
              ))
            ) : (
              <div className="text-muted text-sm" style={{ padding: 'var(--space-sm)', fontStyle: 'italic' }}>
                No tenants yet.
              </div>
            )}
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
            {queryHistory.slice(0, 8).map((entry) => (
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
            <span className="text-muted text-xs">v1.0.0</span>
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
