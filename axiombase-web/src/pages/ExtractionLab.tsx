import { useState, useCallback, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useApp } from '@/context/AppContext'
import { useDatabases, useTenants, useDatabaseActions } from '@/lib/hooks'
import * as api from '@/lib/api'
import type { ExtractionResponse, BatchExtractionResponse } from '@/lib/types'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'
import ExtractedFactsList from '@/components/ExtractedFactsList'
import ExtractedRulesList from '@/components/ExtractedRulesList'
import TemplateBuilder from '@/components/TemplateBuilder'
import Spinner from '@/components/Spinner'
import ProgressSteps from '@/components/ProgressSteps'
import type { Step } from '@/components/ProgressSteps'
import { Sparkles, Plus, X } from 'lucide-react'

export default function ExtractionLab() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()
  const { apiKey } = useApp()

  const [inputText, setInputText] = useState('')
  const [assertMode, setAssertMode] = useState(false)
  const [contextField, setContextField] = useState('')
  const [batchMode, setBatchMode] = useState(false)
  const [batchTexts, setBatchTexts] = useState(['', '', ''])
  const [scope, setScope] = useState('')
  const [loading, setLoading] = useState(false)
  const [progressStep, setProgressStep] = useState(0)
  const progressTimers = useRef<ReturnType<typeof setTimeout>[]>([])
  const [result, setResult] = useState<ExtractionResponse | null>(null)
  const [batchResults, setBatchResults] = useState<BatchExtractionResponse | null>(null)

  const { databases, refresh: refreshDbs } = useDatabases()
  const currentDb = databases.find((d) => d.name === dbName)
  const isMultiTenant = currentDb?.isMultiTenant ?? false

  const { tenants, currentTenant, setCurrentTenant, refresh: refreshTenants } = useTenants(
    dbName,
    isMultiTenant,
  )

  const { sidebarActions, modalState } = useDatabaseActions(
    dbName,
    refreshDbs,
    refreshTenants,
    setCurrentTenant,
    { onDeletedCurrentDb: () => navigate('/') },
  )

  const startProgress = useCallback(() => {
    setProgressStep(0)
    progressTimers.current.forEach(clearTimeout)
    progressTimers.current = [
      setTimeout(() => setProgressStep(1), 600),
      setTimeout(() => setProgressStep(2), 1800),
      setTimeout(() => setProgressStep(3), 3200),
    ]
  }, [])

  const stopProgress = useCallback((success: boolean) => {
    progressTimers.current.forEach(clearTimeout)
    progressTimers.current = []
    setProgressStep(success ? 4 : 0)
  }, [])

  useEffect(() => {
    return () => progressTimers.current.forEach(clearTimeout)
  }, [])

  const PROGRESS_LABELS = ['Analyzing...', 'Extracting facts...', 'Inferring rules...', 'Matching templates...', 'Complete']

  const progressSteps: Step[] = PROGRESS_LABELS.map((label, i) => ({
    label,
    status: i < progressStep ? 'done' : i === progressStep && loading ? 'active' : 'pending',
  }))

  const handleExtractSingle = useCallback(async () => {
    if (!dbName || !inputText.trim()) {
      toast.error('Please enter text to extract')
      return
    }

    setLoading(true)
    startProgress()
    setResult(null)
    setBatchResults(null)
    try {
      const resp = await api.knowledgeExtract(
        apiKey,
        dbName,
        inputText,
        {
          assert: assertMode,
          rules: true,
          context: contextField || undefined,
        },
        isMultiTenant ? currentTenant : undefined,
        scope || undefined,
      )
      setResult(resp)
      stopProgress(true)
      if (assertMode) {
        toast.success(`Extracted and asserted ${resp.facts.length} facts${resp.rules.length > 0 ? ` and ${resp.rules.length} rules` : ''}`)
      } else {
        toast.success(`Extracted ${resp.facts.length} facts${resp.rules.length > 0 ? ` and ${resp.rules.length} rules` : ''}`)
      }
    } catch (e) {
      stopProgress(false)
      toast.error(e instanceof Error ? e.message : 'Failed to extract')
    } finally {
      setLoading(false)
    }
  }, [apiKey, dbName, inputText, assertMode, contextField, isMultiTenant, currentTenant, scope, startProgress, stopProgress])

  const handleExtractBatch = useCallback(async () => {
    if (!dbName) {
      toast.error('No database selected')
      return
    }
    const validTexts = batchTexts.filter((t) => t.trim())
    if (validTexts.length === 0) {
      toast.error('Please enter at least one text to extract')
      return
    }

    setLoading(true)
    startProgress()
    setResult(null)
    setBatchResults(null)
    try {
      const resp = await api.extractBatch(
        apiKey,
        dbName,
        validTexts,
        {
          assert: assertMode,
          rules: true,
          context: contextField || undefined,
        },
        isMultiTenant ? currentTenant : undefined,
      )
      setBatchResults(resp)
      stopProgress(true)
      const totalFacts = resp.results.reduce((sum, r) => sum + r.facts.length, 0)
      const totalRules = resp.results.reduce((sum, r) => sum + r.rules.length, 0)
      if (assertMode) {
        toast.success(`Extracted and asserted ${totalFacts} facts${totalRules > 0 ? ` and ${totalRules} rules` : ''} from ${validTexts.length} texts`)
      } else {
        toast.success(`Extracted ${totalFacts} facts${totalRules > 0 ? ` and ${totalRules} rules` : ''} from ${validTexts.length} texts`)
      }
    } catch (e) {
      stopProgress(false)
      toast.error(e instanceof Error ? e.message : 'Failed to extract batch')
    } finally {
      setLoading(false)
    }
  }, [apiKey, dbName, batchTexts, assertMode, contextField, isMultiTenant, currentTenant, startProgress, stopProgress])

  const handleExtract = () => {
    if (batchMode) {
      void handleExtractBatch()
    } else {
      void handleExtractSingle()
    }
  }

  const handleAssertAll = useCallback(async () => {
    if (!result) return
    setLoading(true)
    try {
      await api.knowledgeExtract(
        apiKey,
        dbName!,
        inputText,
        {
          assert: true,
          rules: true,
          context: contextField || undefined,
        },
        isMultiTenant ? currentTenant : undefined,
        scope || undefined,
      )
      toast.success(`Asserted ${result.facts.length} facts${result.rules.length > 0 ? ` and ${result.rules.length} rules` : ''}`)
      setAssertMode(true)
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to assert')
    } finally {
      setLoading(false)
    }
  }, [apiKey, dbName, inputText, contextField, result, isMultiTenant, currentTenant, scope])

  const addBatchTextarea = () => {
    setBatchTexts([...batchTexts, ''])
  }

  const removeBatchTextarea = (index: number) => {
    setBatchTexts(batchTexts.filter((_, i) => i !== index))
  }

  const updateBatchText = (index: number, value: string) => {
    const updated = [...batchTexts]
    updated[index] = value
    setBatchTexts(updated)
  }

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            currentDb={dbName}
            onSelectDb={(name) => navigate(`/db/${name}/extract`)}
            {...sidebarActions}
            tenants={tenants}
            currentTenant={currentTenant}
            onSelectTenant={setCurrentTenant}
            isMultiTenant={isMultiTenant}
          />
        }
        toolbar={
          <div className="flex items-center gap-4">
            <h1 className="text-lg font-semibold">Extraction Lab</h1>
            <div className="flex items-center gap-2">
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={assertMode}
                  onChange={(e) => setAssertMode(e.target.checked)}
                />
                <span className="text-sm">Assert Mode</span>
              </label>
              <label className="flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={batchMode}
                  onChange={(e) => setBatchMode(e.target.checked)}
                />
                <span className="text-sm">Batch Mode</span>
              </label>
              <input
                type="text"
                className="input"
                placeholder="scope"
                value={scope}
                onChange={(e) => setScope(e.target.value)}
                style={{ width: 120, fontSize: 'var(--text-xs)', padding: '4px 8px' }}
              />
            </div>
          </div>
        }
      >
        <div className="console-content" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-md)' }}>
          {/* Left Panel: Input */}
          <div className="flex-col gap-3" style={{ minHeight: 0 }}>
            <div className="flex-col gap-2">
              <label className="input-label">Context (optional)</label>
              <input
                type="text"
                className="input"
                placeholder="e.g., 'Greek mythology'"
                value={contextField}
                onChange={(e) => setContextField(e.target.value)}
              />
            </div>

            {!batchMode ? (
              <div className="flex-col gap-2" style={{ flex: 1, minHeight: 0 }}>
                <label className="input-label">Input Text</label>
                <textarea
                  className="input"
                  placeholder="Enter text to extract facts and rules from..."
                  value={inputText}
                  onChange={(e) => setInputText(e.target.value)}
                  style={{ flex: 1, minHeight: 200, resize: 'vertical' }}
                />
              </div>
            ) : (
              <div className="flex-col gap-2" style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
                <div className="flex items-center justify-between">
                  <label className="input-label">Batch Texts</label>
                  <button className="btn btn-sm btn-ghost" onClick={addBatchTextarea}>
                    <Plus size={14} />
                    Add
                  </button>
                </div>
                {batchTexts.map((text, i) => (
                  <div key={i} className="flex gap-2">
                    <textarea
                      className="input"
                      placeholder={`Text ${i + 1}...`}
                      value={text}
                      onChange={(e) => updateBatchText(i, e.target.value)}
                      style={{ flex: 1, minHeight: 80, resize: 'vertical' }}
                    />
                    {batchTexts.length > 1 && (
                      <button className="btn btn-icon btn-ghost" onClick={() => removeBatchTextarea(i)}>
                        <X size={14} />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}

            <button
              className="btn btn-primary"
              onClick={handleExtract}
              disabled={loading || (!batchMode && !inputText.trim()) || (batchMode && batchTexts.every((t) => !t.trim()))}
            >
              {loading ? <Spinner size={14} /> : <Sparkles size={14} />}
              {loading ? 'Extracting...' : 'Extract'}
            </button>

            {loading && (
              <ProgressSteps steps={progressSteps} />
            )}

            <details style={{ marginTop: 'var(--space-sm)' }}>
              <summary style={{ cursor: 'pointer', fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>
                Template Builder
              </summary>
              <div style={{ marginTop: 'var(--space-sm)' }}>
                <TemplateBuilder db={dbName!} tenant={isMultiTenant ? currentTenant : undefined} />
              </div>
            </details>
          </div>

          {/* Right Panel: Results */}
          <div className="flex-col gap-3" style={{ minHeight: 0, overflowY: 'auto' }}>
            {result && !batchMode && (
              <>
                <div className="card">
                  <div className="card-header">
                    <span>Extracted Facts ({result.facts.length})</span>
                    {!assertMode && result.facts.length > 0 && (
                      <button className="btn btn-sm btn-primary" onClick={() => void handleAssertAll()}>
                        Assert All
                      </button>
                    )}
                  </div>
                  <div className="card-body">
                    <ExtractedFactsList facts={result.facts} />
                  </div>
                </div>

                <div className="card">
                  <div className="card-header">Extracted Rules ({result.rules.length})</div>
                  <div className="card-body">
                    <ExtractedRulesList rules={result.rules} />
                  </div>
                </div>

                {result.rules.some((r) => r.templateType) && (
                  <div className="card">
                    <div className="card-header">
                      Template Matches ({result.rules.filter((r) => r.templateType).length})
                    </div>
                    <div className="card-body">
                      <div className="flex-col gap-2">
                        {Object.entries(
                          result.rules
                            .filter((r) => r.templateType)
                            .reduce<Record<string, typeof result.rules>>((acc, r) => {
                              const key = r.templateType!
                              ;(acc[key] ??= []).push(r)
                              return acc
                            }, {}),
                        ).map(([type, rules]) => (
                          <div key={type}>
                            <div className="text-xs font-semibold" style={{ color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                              {type}
                            </div>
                            <ExtractedRulesList rules={rules} />
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                )}

                <div className="flex gap-4 text-xs text-muted">
                  <span>Provider: {result.provider}</span>
                  <span>Model: {result.model}</span>
                  {assertMode && <span className="badge badge-success">Asserted</span>}
                </div>
              </>
            )}

            {batchResults && batchMode && (
              <>
                {batchResults.results.map((res, i) => (
                  <div key={i} className="card">
                    <div className="card-header">Text {i + 1}</div>
                    <div className="card-body flex-col gap-3">
                      {res.error ? (
                        <div className="text-danger text-sm">{res.error}</div>
                      ) : (
                        <>
                          <div>
                            <div className="text-sm font-semibold mb-2">Facts ({res.facts.length})</div>
                            <ExtractedFactsList facts={res.facts} />
                          </div>
                          {res.rules.length > 0 && (
                            <div>
                              <div className="text-sm font-semibold mb-2">Rules ({res.rules.length})</div>
                              <ExtractedRulesList rules={res.rules} />
                            </div>
                          )}
                        </>
                      )}
                    </div>
                  </div>
                ))}

                <div className="flex gap-4 text-xs text-muted">
                  <span>Provider: {batchResults.provider}</span>
                  <span>Model: {batchResults.model}</span>
                  {assertMode && <span className="badge badge-success">Asserted</span>}
                </div>
              </>
            )}

            {!result && !batchResults && !loading && (
              <div className="empty-state">
                <div className="empty-state-text">Extract facts and rules to see results here.</div>
              </div>
            )}
          </div>
        </div>
      </Layout>

      <DatabaseModals {...modalState} />
    </>
  )
}
