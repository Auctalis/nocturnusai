import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useApp } from '@/context/AppContext'
import { Hexagon, KeyRound, ArrowRight } from 'lucide-react'
import NetworkBackground from '@/components/NetworkBackground'

export default function Login() {
  const [key, setKey] = useState('')
  const { setApiKey } = useApp()
  const navigate = useNavigate()

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (key.trim()) {
      setApiKey(key.trim())
      navigate('/')
    }
  }

  return (
    <div className="login-page">
      <NetworkBackground />
      <div className="card login-card">
        <div className="login-logo">
          <div className="login-logo-icon">
            <Hexagon size={20} />
          </div>
          <span className="login-title">AxiomBase</span>
        </div>
        <form onSubmit={handleSubmit}>
          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label className="input-label">
              <KeyRound size={12} style={{ marginRight: 4, verticalAlign: 'middle' }} />
              API Key
            </label>
            <input
              className="input"
              type="password"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="Enter your API Key"
              autoFocus
              required
            />
          </div>
          <button type="submit" className="btn btn-primary w-full">
            Sign in
            <ArrowRight size={14} style={{ marginLeft: 4 }} />
          </button>
        </form>
      </div>
    </div>
  )
}
