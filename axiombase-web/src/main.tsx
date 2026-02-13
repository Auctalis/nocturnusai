import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Toaster } from 'sonner'
import { AppProvider } from '@/context/AppContext'
import App from './App'
import './index.css'
import './components.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProvider>
      <App />
      <Toaster position="bottom-right" richColors />
    </AppProvider>
  </StrictMode>,
)
