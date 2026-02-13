import { Command } from 'cmdk'
import { useNavigate } from 'react-router-dom'
import { useApp } from '@/context/AppContext'
import { useKeyboardShortcut, useDatabases } from '@/lib/hooks'
import { LayoutDashboard, Database, LogOut } from 'lucide-react'
import Kbd from './Kbd'

export default function CommandPalette() {
  const { commandPaletteOpen, setCommandPaletteOpen, logout } = useApp()
  const { databases } = useDatabases()
  const navigate = useNavigate()

  useKeyboardShortcut('k', () => setCommandPaletteOpen(!commandPaletteOpen), { meta: true })

  if (!commandPaletteOpen) return null

  const runAndClose = (fn: () => void) => {
    fn()
    setCommandPaletteOpen(false)
  }

  return (
    <Command.Dialog
      open={commandPaletteOpen}
      onOpenChange={setCommandPaletteOpen}
      label="Command Palette"
    >
      <Command.Input placeholder="Type a command or search..." />
      <Command.List>
        <Command.Empty>No results found.</Command.Empty>

        <Command.Group heading="Navigation">
          <Command.Item onSelect={() => runAndClose(() => navigate('/'))}>
            <LayoutDashboard size={14} />
            <span>Dashboard</span>
          </Command.Item>
          {databases.map((db) => (
            <Command.Item
              key={db.name}
              onSelect={() => runAndClose(() => navigate(`/db/${db.name}`))}
            >
              <Database size={14} />
              <span>Open {db.name}</span>
            </Command.Item>
          ))}
        </Command.Group>

        <Command.Group heading="Account">
          <Command.Item onSelect={() => runAndClose(logout)}>
            <LogOut size={14} />
            <span>Sign out</span>
          </Command.Item>
        </Command.Group>
      </Command.List>
      <div style={{ padding: '8px 16px', borderTop: '1px solid var(--border-color)', display: 'flex', justifyContent: 'flex-end' }}>
        <Kbd keys={['Esc']} />
      </div>
    </Command.Dialog>
  )
}
