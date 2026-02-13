import type { ReactNode } from 'react'
import { PanelLeftClose, PanelLeftOpen, Hexagon } from 'lucide-react'
import { useApp } from '@/context/AppContext'
import NetworkBackground from './NetworkBackground'

interface LayoutProps {
  sidebar: ReactNode
  toolbar?: ReactNode
  children: ReactNode
}

export default function Layout({ sidebar, toolbar, children }: LayoutProps) {
  const { sidebarCollapsed, toggleSidebar } = useApp()

  return (
    <div className={`app-layout ${sidebarCollapsed ? 'sidebar-collapsed' : ''}`}>
      <NetworkBackground />
      <aside className={`app-sidebar ${sidebarCollapsed ? 'collapsed' : ''}`}>
        <div className="sidebar-logo">
          <div className="sidebar-logo-icon">
            <Hexagon size={16} />
          </div>
          {!sidebarCollapsed && <span>AxiomBase</span>}
        </div>
        {sidebar}
      </aside>

      <main className="app-main">
        {toolbar && (
          <header className="app-header">
            <button
              className="btn btn-ghost btn-icon sidebar-toggle"
              onClick={toggleSidebar}
              title={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            >
              {sidebarCollapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
            </button>
            {toolbar}
          </header>
        )}
        <div className="app-content">{children}</div>
      </main>
    </div>
  )
}
