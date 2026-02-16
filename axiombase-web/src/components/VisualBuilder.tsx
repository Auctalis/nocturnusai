import { useState, useEffect } from 'react'
import type { OperationMode, AtomState, InspectFilters, TemplateType } from '@/lib/types'

// ── AtomInput ─────────────────────────────────────────────────

interface AtomInputProps {
  predicate: string
  args: string[]
  negated: boolean
  onChange: (atom: AtomState) => void
  onDelete?: () => void
  placeholder?: string
  canNegate?: boolean
  isHead?: boolean
}

function AtomInput({
  predicate,
  args,
  negated,
  onChange,
  onDelete,
  placeholder = 'Predicate',
  canNegate = true,
  isHead = false,
}: AtomInputProps) {
  const setArg = (idx: number, val: string) => {
    const newArgs = [...args]
    newArgs[idx] = val
    onChange({ predicate, args: newArgs, negated })
  }

  return (
    <div className="vb-atom-row">
      {canNegate && (
        <button
          className={`vb-negation-btn ${negated ? 'negated' : ''}`}
          onClick={() => onChange({ predicate, args, negated: !negated })}
          title="Toggle Negation"
        >
          {negated ? 'NOT' : 'POS'}
        </button>
      )}

      <input
        className="input"
        value={predicate}
        onChange={(e) => onChange({ predicate: e.target.value, args, negated })}
        placeholder={placeholder}
        style={{
          width: 140,
          fontWeight: 600,
          color: isHead ? 'var(--accent)' : 'var(--text-primary)',
          textAlign: 'right',
        }}
      />

      <span className="vb-atom-paren">(</span>

      <div className="flex gap-1 items-center" style={{ flexWrap: 'wrap' }}>
        {args.map((arg, idx) => (
          <div key={idx} className="flex items-center">
            <input
              className="input"
              value={arg}
              onChange={(e) => setArg(idx, e.target.value)}
              placeholder="?"
              style={{ width: 80, textAlign: 'center', fontSize: 13 }}
            />
            {args.length > 1 && (
              <button
                className="btn btn-ghost btn-sm"
                onClick={() =>
                  onChange({
                    predicate,
                    args: args.filter((_, i) => i !== idx),
                    negated,
                  })
                }
              >
                &times;
              </button>
            )}
            {idx < args.length - 1 && <span className="vb-atom-comma">,</span>}
          </div>
        ))}
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => onChange({ predicate, args: [...args, ''], negated })}
          title="Add Argument"
        >
          +
        </button>
      </div>

      <span className="vb-atom-paren">)</span>

      {onDelete && (
        <button className="btn btn-danger btn-sm" onClick={onDelete} style={{ marginLeft: 'auto' }}>
          &times;
        </button>
      )}
    </div>
  )
}

// ── Descriptions ──────────────────────────────────────────────

const DESCRIPTIONS: Record<OperationMode, string> = {
  ask: 'Ask a question — find answers via reasoning.',
  tell: 'Tell a fact — store knowledge.',
  teach: 'Teach a rule — define reasoning logic.',
  forget: 'Forget — remove knowledge and derived facts.',
  context: 'View the agent context window — facts ranked by salience.',
  memory: 'Manage memory lifecycle — compress patterns or cleanup expired knowledge.',
  infer: 'Query the database for patterns.',
  assert_fact: 'Add a known Fact.',
  assert_rule: 'Add a logical Rule (Head \u2190 Body).',
  retract: 'Remove a Fact.',
  inspect: 'Browse the Knowledge Base.',
  assert_template: 'Use a Logic Template.',
  execute: 'Execute a DSL command.',
  synthesize: 'Synthesize knowledge.',
}

// ── Main Component ────────────────────────────────────────────

interface VisualBuilderProps {
  mode: OperationMode
  onJsonChange: (json: string) => void
}

export default function VisualBuilder({ mode, onJsonChange }: VisualBuilderProps) {
  // Fact/Query state
  const [factState, setFactState] = useState<AtomState>({ predicate: '', args: [''], negated: false })

  // Rule state
  const [ruleHead, setRuleHead] = useState<AtomState>({ predicate: '', args: [''], negated: false })
  const [ruleBody, setRuleBody] = useState<AtomState[]>([
    { predicate: '', args: [''], negated: false },
  ])

  // Inspect state
  const [inspectFilters, setInspectFilters] = useState<InspectFilters>({
    type: 'ALL',
    filter: '',
    scope: '',
  })

  // Template state
  const [templateType, setTemplateType] = useState<TemplateType>('SYLLOGISM')
  const [templatePredicates, setTemplatePreds] = useState<Record<string, string>>({ P: '', Q: '' })
  const [templateArgs, setTemplateArgs] = useState([''])

  // Metadata (assert_fact mode)
  const [metadataJson, setMetadataJson] = useState('')
  const [metadataError, setMetadataError] = useState('')

  // Context state
  const [contextMaxFacts, setContextMaxFacts] = useState(50)

  // Memory state
  const [memoryOp, setMemoryOp] = useState<'compress' | 'cleanup'>('compress')
  const [cleanupThreshold, setCleanupThreshold] = useState(0.05)

  // Scope
  const [scope, setScope] = useState('')

  // Reset on mode change
  useEffect(() => {
    setFactState({ predicate: '', args: [''], negated: false })
    setRuleHead({ predicate: '', args: [''], negated: false })
    setRuleBody([{ predicate: '', args: [''], negated: false }])
    setInspectFilters({ type: 'ALL', filter: '', scope: '' })
    setScope('')
    setMetadataJson('')
    setMetadataError('')
    setTemplateType('SYLLOGISM')
    setTemplatePreds({ P: '', Q: '' })
    setTemplateArgs([''])
    setContextMaxFacts(50)
    setMemoryOp('compress')
    setCleanupThreshold(0.05)
  }, [mode])

  // JSON construction
  useEffect(() => {
    let json: Record<string, unknown> = {}

    if (mode === 'assert_rule' || mode === 'teach') {
      json = {
        head: {
          predicate: ruleHead.predicate,
          args: ruleHead.args.filter((a) => a.trim() !== ''),
          negated: ruleHead.negated,
        },
        body: ruleBody.map((b) => ({
          predicate: b.predicate,
          args: b.args.filter((a) => a.trim() !== ''),
          negated: b.negated,
        })),
      }
    } else if (mode === 'inspect') {
      json = {
        type: inspectFilters.type,
        filter: inspectFilters.filter,
        scope: inspectFilters.scope.trim() || undefined,
      }
    } else if (mode === 'context') {
      json = { maxFacts: contextMaxFacts }
    } else if (mode === 'memory') {
      json = { operation: memoryOp }
      if (memoryOp === 'cleanup') {
        json.threshold = cleanupThreshold
      }
    } else if (mode === 'assert_template') {
      json = {
        type: templateType,
        predicates: templatePredicates,
        args: templateArgs.filter((a) => a.trim() !== ''),
      }
    } else {
      json = {
        predicate: factState.predicate,
        args: factState.args.filter((a) => a.trim() !== ''),
        negated: factState.negated,
      }
      if (mode === 'assert_fact' || mode === 'tell') {
        json.truthVal = !factState.negated
        if (metadataJson.trim()) {
          try {
            json.metadata = JSON.parse(metadataJson)
            setMetadataError('')
          } catch {
            setMetadataError('Invalid JSON')
          }
        }
      }
    }

    if (mode !== 'inspect' && scope.trim()) {
      json.scope = scope.trim()
    }

    onJsonChange(JSON.stringify(json, null, 2))
  }, [
    mode,
    factState,
    ruleHead,
    ruleBody,
    inspectFilters,
    scope,
    metadataJson,
    templateType,
    templatePredicates,
    templateArgs,
    contextMaxFacts,
    memoryOp,
    cleanupThreshold,
    onJsonChange,
  ])

  // ── Context Mode ──
  if (mode === 'context') {
    return (
      <div className="flex-col gap-4" style={{ padding: 'var(--space-md)' }}>
        <div className="vb-section">
          <div className="vb-section-title">Agent Context Window</div>
          <div className="vb-section-desc">
            View the most relevant knowledge for the current reasoning step, ranked by salience (recency + frequency + priority).
          </div>
        </div>
        <div>
          <label className="input-label">Max Facts</label>
          <input
            className="input"
            type="number"
            min={1}
            max={500}
            value={contextMaxFacts}
            onChange={(e) => setContextMaxFacts(Number(e.target.value) || 50)}
            style={{ width: 120 }}
          />
          <p className="text-muted text-xs" style={{ marginTop: 4 }}>
            Number of top-salience facts to retrieve.
          </p>
        </div>
        <p className="text-muted text-xs text-center">Click &apos;Run&apos; to fetch the context window.</p>
      </div>
    )
  }

  // ── Memory Mode ──
  if (mode === 'memory') {
    return (
      <div className="flex-col gap-4" style={{ padding: 'var(--space-md)' }}>
        <div className="vb-section">
          <div className="vb-section-title">Memory Management</div>
          <div className="vb-section-desc">
            Manage the knowledge lifecycle — consolidate patterns or expire stale facts.
          </div>
        </div>
        <div>
          <label className="input-label">Operation</label>
          <div className="vb-type-toggle" style={{ marginBottom: 'var(--space-md)' }}>
            <button
              className={`vb-type-toggle-btn ${memoryOp === 'compress' ? 'active' : ''}`}
              onClick={() => setMemoryOp('compress')}
            >
              Compress
            </button>
            <button
              className={`vb-type-toggle-btn ${memoryOp === 'cleanup' ? 'active' : ''}`}
              onClick={() => setMemoryOp('cleanup')}
            >
              Cleanup
            </button>
          </div>
        </div>

        {memoryOp === 'compress' && (
          <div className="card card-body">
            <div className="vb-section-title" style={{ fontSize: 'var(--text-sm)' }}>Compress</div>
            <p className="text-secondary text-xs" style={{ margin: '4px 0 0' }}>
              Consolidate repeated episodic patterns into summary facts. Reduces memory size while preserving semantics.
            </p>
          </div>
        )}

        {memoryOp === 'cleanup' && (
          <div className="card card-body">
            <div className="vb-section-title" style={{ fontSize: 'var(--text-sm)' }}>Cleanup</div>
            <p className="text-secondary text-xs" style={{ margin: '4px 0 8px' }}>
              Expire facts past their TTL and evict low-salience facts below the threshold.
            </p>
            <label className="input-label">Salience Threshold</label>
            <input
              className="input"
              type="number"
              step={0.01}
              min={0}
              max={1}
              value={cleanupThreshold}
              onChange={(e) => setCleanupThreshold(Number(e.target.value) || 0.05)}
              style={{ width: 120 }}
            />
            <p className="text-muted text-xs" style={{ marginTop: 4 }}>
              Facts with salience below this value will be evicted (0.0 to 1.0).
            </p>
          </div>
        )}

        <p className="text-muted text-xs text-center">Click &apos;Run&apos; to execute.</p>
      </div>
    )
  }

  // ── Inspect Mode ──
  if (mode === 'inspect') {
    return (
      <div className="flex-col gap-4" style={{ padding: 'var(--space-md)' }}>
        <div className="vb-section">
          <div className="vb-section-title">Knowledge Base Inspector</div>
        </div>
        <div className="vb-inspect-controls">
          <div className="flex-between">
            <span className="text-secondary text-sm">Filter Content</span>
            <div className="vb-type-toggle">
              {(['ALL', 'FACT', 'RULE'] as const).map((t) => (
                <button
                  key={t}
                  className={`vb-type-toggle-btn ${inspectFilters.type === t ? 'active' : ''}`}
                  onClick={() => setInspectFilters({ ...inspectFilters, type: t })}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>
          <input
            className="input"
            placeholder="Search predicates..."
            value={inspectFilters.filter}
            onChange={(e) => setInspectFilters({ ...inspectFilters, filter: e.target.value })}
          />
          <input
            className="input"
            placeholder="Filter by scope ID..."
            value={inspectFilters.scope}
            onChange={(e) => setInspectFilters({ ...inspectFilters, scope: e.target.value })}
          />
        </div>
        <p className="text-muted text-xs text-center">Click &apos;Run&apos; to refresh.</p>
      </div>
    )
  }

  // ── Rule Mode ──
  if (mode === 'assert_rule' || mode === 'teach') {
    return (
      <div className="flex-col gap-4" style={{ padding: 'var(--space-md)' }}>
        <div className="vb-section">
          <div className="vb-section-title">Rule Designer</div>
          <div className="vb-section-desc">Define a logical implication.</div>
        </div>

        <div>
          <label className="input-label text-accent">THEN (Conclusion)</label>
          <AtomInput {...ruleHead} onChange={setRuleHead} isHead placeholder="Head Predicate" />
        </div>

        <div className="vb-arrow">
          <span className="vb-arrow-icon">&uarr;</span>
          <span className="vb-arrow-text">IF All Conditions Match</span>
        </div>

        <div className="vb-conditions-box">
          <label className="input-label">IF (Conditions)</label>
          <div className="flex-col gap-2">
            {ruleBody.map((atom, idx) => (
              <div key={idx} className="flex gap-2 items-center">
                <span className="text-muted text-xs" style={{ width: 20 }}>
                  #{idx + 1}
                </span>
                <div style={{ flex: 1 }}>
                  <AtomInput
                    {...atom}
                    onChange={(newAtom) => {
                      const newBody = [...ruleBody]
                      newBody[idx] = newAtom
                      setRuleBody(newBody)
                    }}
                    onDelete={
                      ruleBody.length > 1
                        ? () => setRuleBody(ruleBody.filter((_, i) => i !== idx))
                        : undefined
                    }
                  />
                </div>
              </div>
            ))}
          </div>
          <button
            className="btn btn-secondary w-full"
            style={{ marginTop: 'var(--space-md)', justifyContent: 'center' }}
            onClick={() =>
              setRuleBody([...ruleBody, { predicate: '', args: [''], negated: false }])
            }
          >
            + Add Condition
          </button>
        </div>

        <div>
          <label className="input-label">Scope (Optional)</label>
          <input
            className="input"
            placeholder="Context ID"
            value={scope}
            onChange={(e) => setScope(e.target.value)}
          />
        </div>
      </div>
    )
  }

  // ── Template Mode ──
  if (mode === 'assert_template' /* legacy */) {
    const showPQ = [
      'SYLLOGISM',
      'MODUS_PONENS',
      'MODUS_TOLLENS',
      'DISJUNCTIVE_SYLLOGISM',
      'HYPOTHETICAL_SYLLOGISM',
    ].includes(templateType)

    return (
      <div className="flex-col gap-4" style={{ padding: 'var(--space-md)' }}>
        <div className="vb-section">
          <div className="vb-section-title">Logic Template</div>
          <div className="vb-section-desc">{DESCRIPTIONS.assert_template}</div>
        </div>

        <div className="card card-body">
          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label className="input-label">Template Type</label>
            <select
              className="input"
              value={templateType}
              onChange={(e) => setTemplateType(e.target.value as TemplateType)}
            >
              <optgroup label="Formal Logic">
                <option value="SYLLOGISM">Syllogism (Classic Inference)</option>
                <option value="MODUS_PONENS">Modus Ponens (If P then Q)</option>
                <option value="MODUS_TOLLENS">Modus Tollens (Contrapositive)</option>
                <option value="HYPOTHETICAL_SYLLOGISM">Hypothetical Syllogism (Chain)</option>
                <option value="DISJUNCTIVE_SYLLOGISM">Disjunctive Syllogism</option>
                <option value="CONSTRUCTIVE_DILEMMA">Constructive Dilemma</option>
                <option value="DESTRUCTIVE_DILEMMA">Destructive Dilemma</option>
              </optgroup>
              <optgroup label="Argumentation Schemes">
                <option value="CAUSAL_ARGUMENT">Causal Argument</option>
                <option value="DEFINITIONAL_ARGUMENT">Definitional Argument</option>
                <option value="EVALUATIVE_ARGUMENT">Evaluative Argument</option>
                <option value="PRACTICAL_ARGUMENT">Practical Argument (Defeasible)</option>
              </optgroup>
            </select>
          </div>

          <div className="flex-col gap-3">
            <label className="input-label text-accent">Predicates</label>
            {showPQ && (
              <>
                <div>
                  <label className="input-label">P (Antecedent)</label>
                  <input
                    className="input"
                    placeholder="e.g. Man"
                    value={templatePredicates.P ?? ''}
                    onChange={(e) => setTemplatePreds({ ...templatePredicates, P: e.target.value })}
                  />
                </div>
                <div>
                  <label className="input-label">Q (Consequent)</label>
                  <input
                    className="input"
                    placeholder="e.g. Mortal"
                    value={templatePredicates.Q ?? ''}
                    onChange={(e) => setTemplatePreds({ ...templatePredicates, Q: e.target.value })}
                  />
                </div>
                {templateType === 'HYPOTHETICAL_SYLLOGISM' && (
                  <div>
                    <label className="input-label">R (Final)</label>
                    <input
                      className="input"
                      placeholder="e.g. Finite"
                      value={templatePredicates.R ?? ''}
                      onChange={(e) =>
                        setTemplatePreds({ ...templatePredicates, R: e.target.value })
                      }
                    />
                  </div>
                )}
              </>
            )}
          </div>

          <div style={{ marginTop: 'var(--space-lg)' }}>
            <label className="input-label">Shared Arguments</label>
            <div className="flex gap-2">
              {templateArgs.map((arg, i) => (
                <input
                  key={i}
                  className="input"
                  value={arg}
                  onChange={(e) => {
                    const n = [...templateArgs]
                    n[i] = e.target.value
                    setTemplateArgs(n)
                  }}
                  style={{ width: 80 }}
                />
              ))}
              <button
                className="btn btn-secondary btn-sm"
                onClick={() => setTemplateArgs([...templateArgs, ''])}
              >
                +
              </button>
            </div>
          </div>

          <div style={{ marginTop: 'var(--space-lg)' }}>
            <label className="input-label">Scope (Optional)</label>
            <input
              className="input"
              value={scope}
              onChange={(e) => setScope(e.target.value)}
              placeholder="Context ID"
            />
          </div>
        </div>
      </div>
    )
  }

  // ── Fact / Query / Retract Mode ──
  return (
    <div className="flex-col gap-4" style={{ padding: 'var(--space-md)' }}>
      <div className="vb-section">
        <div className="vb-section-title">
          {(mode === 'infer' || mode === 'ask') ? 'Ask' : (mode === 'retract' || mode === 'forget') ? 'Forget' : 'Tell'}
        </div>
        <div className="vb-section-desc">{DESCRIPTIONS[mode]}</div>
      </div>

      <AtomInput {...factState} onChange={setFactState} canNegate placeholder="Predicate" />

      {mode === 'assert_fact' && (
        <div>
          <label className="input-label">Metadata (Optional JSON)</label>
          <textarea
            className="input"
            placeholder='{"unit": "celsius", "confidence": 0.95}'
            value={metadataJson}
            onChange={(e) => setMetadataJson(e.target.value)}
            rows={3}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12, resize: 'vertical' }}
          />
          {metadataError && (
            <div className="text-xs" style={{ color: 'var(--danger)', marginTop: 4 }}>
              {metadataError}
            </div>
          )}
        </div>
      )}

      <div>
        <label className="input-label">Scope (Optional)</label>
        <input
          className="input"
          placeholder="Context ID"
          value={scope}
          onChange={(e) => setScope(e.target.value)}
        />
      </div>
    </div>
  )
}
