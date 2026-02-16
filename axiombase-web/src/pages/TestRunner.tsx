import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import { useDatabases, useDatabaseActions } from '@/lib/hooks'
import * as api from '@/lib/api'
import type { TestCaseRequest, TestSuiteResult } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import { TestCaseEditor } from '@/components/TestCaseEditor'
import { TestResults } from '@/components/TestResults'
import { TestTube2 } from 'lucide-react'

const noopRefreshTenants = async () => {}
const noopSetTenant = () => {}

export default function TestRunner() {
  const { apiKey } = useApp()
  const navigate = useNavigate()
  const { databases, refresh: refreshDbs } = useDatabases()

  const { sidebarActions, modalState } = useDatabaseActions(
    undefined,
    refreshDbs,
    noopRefreshTenants,
    noopSetTenant,
  )
  const [results, setResults] = useState<TestSuiteResult | null>(null)
  const [loading, setLoading] = useState(false)

  const handleRunTests = async (cases: TestCaseRequest[]) => {
    setLoading(true)
    try {
      const result = await api.runTests(apiKey, cases)
      setResults(result)

      const passedCount = result.results.filter((r) => r.passed).length
      const failedCount = result.results.length - passedCount

      if (failedCount === 0) {
        toast.success(`All ${passedCount} tests passed`)
      } else {
        toast.error(`${failedCount} test(s) failed`)
      }
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to run tests')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            onSelectDb={(name) => navigate(`/db/${name}/context`)}
            {...sidebarActions}
            tenants={[]}
            currentTenant=""
            onSelectTenant={() => {}}
            isMultiTenant={false}
          />
        }
        toolbar={
          <div className="toolbar-row">
            <div className="toolbar-left">
              <TestTube2 size={18} />
              <h1 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, margin: 0 }}>
                Test Runner
              </h1>
            </div>
          </div>
        }
      >
        <div style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gap: 'var(--space-md)',
          height: 'calc(100vh - var(--header-height) - var(--space-lg) * 2)',
        }}>
          <TestCaseEditor onSubmit={handleRunTests} loading={loading} />
          <TestResults results={results} />
        </div>
      </Layout>
      <DatabaseModals {...modalState} />
    </>
  )
}
