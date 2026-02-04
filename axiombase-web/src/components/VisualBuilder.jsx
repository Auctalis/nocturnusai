import React, { useState, useEffect } from 'react';

// Helper Component: Argument List Editor
const ArgList = ({ values, onChange, placeholder = "Arg (e.g. ?x or John)" }) => (
  <div className="flex-col gap-4">
    {values.map((val, idx) => (
      <div key={idx} className="flex-between gap-4">
         {/* Visual bullet or index */}
         <span className="text-muted" style={{ minWidth: '24px', textAlign: 'right', fontSize: '13px' }}>
            {idx + 1}.
         </span>
        <input
          className="glass-input"
          value={val}
          onChange={(e) => {
            const newArgs = [...values];
            newArgs[idx] = e.target.value;
            onChange(newArgs);
          }}
          placeholder={placeholder}
          style={{ flex: 1 }}
        />
        {values.length > 1 && (
          <button 
            className="glass-button danger"
            onClick={() => onChange(values.filter((_, i) => i !== idx))}
            style={{ padding: '6px 12px' }}
          >
            ×
          </button>
        )}
      </div>
    ))}
    <div style={{ marginLeft: '32px' }}>
      <button 
          className="glass-button"
          onClick={() => onChange([...values, ''])}
          style={{ width: 'fit-content', fontSize: '12px' }}
      >
          + Add Arg
      </button>
    </div>
  </div>
);

// Mode Descriptions
const descriptions = {
  infer: "Find all relationships in the database that match a specific pattern.",
  assert_fact: "Add a single, definite unit of information (a Fact) to the database.",
  assert_rule: "Define a logical rule: IF specific conditions are met (Body), THEN conclude a new fact (Head).",
  retract: "Remove a specific fact from the database.",
  inspect: "View all currently stored facts and rules.",
  assert_template: "Generate complex rules using predefined logical patterns like Syllogisms."
};

/**
 * VisualBuilder helps users construct Logic Server JSON queries via UI.
 */
export default function VisualBuilder({ mode, onJsonChange }) {
  // Internal state for builder fields
  const [predicate, setPredicate] = useState('');
  const [args, setArgs] = useState(['']); // Array of strings
  const [negated, setNegated] = useState(false); // For Facts
  
  // Rule specific state
  const [headPredicate, setHeadPredicate] = useState('');
  const [headArgs, setHeadArgs] = useState(['']);
  const [headNegated, setHeadNegated] = useState(false);
  
  const [bodyClauses, setBodyClauses] = useState([
    { predicate: '', args: [''], negated: false }
  ]);

  // Template specific state
  const [templateType, setTemplateType] = useState('SYLLOGISM');
  const [templatePredicates, setTemplatePredicates] = useState({ P: '', Q: '' }); // Dynamic based on type?
  const [templateArgs, setTemplateArgs] = useState(['']);

  // Inspect specific state
  const [inspectFilter, setInspectFilter] = useState('');
  const [inspectType, setInspectType] = useState('ALL'); // ALL, FACT, RULE

  // Global Metadata
  const [scope, setScope] = useState('');

  // Sync state to JSON whenever it changes
  useEffect(() => {
    let json = {};

    if (mode === 'assert_template') {
        // ... (existing template logic)
        json = {
            type: templateType,
            predicates: templatePredicates,
            args: templateArgs.filter(a => a.trim() !== '')
        };
    } else if (mode === 'assert_rule') {
       // ... (existing rule logic)
       json = {
        head: {
          predicate: headPredicate,
          args: headArgs.filter(a => a.trim() !== ''),
          negated: headNegated
        },
        body: bodyClauses.map(clause => ({
          predicate: clause.predicate,
          args: clause.args.filter(a => a.trim() !== ''),
          negated: clause.negated
        }))
      };
    } else if (mode === 'inspect') {
      json = {
          filter: inspectFilter,
          type: inspectType,
          scope: scope.trim() || undefined // If inspect has scope, it's a filter
      };
    } else {
      // ... (existing fact logic)
      json = {
        predicate: predicate,
        args: args.filter(a => a.trim() !== '')
      };
      
      if (mode === 'assert_fact' || mode === 'infer') {
        json.negated = negated;
        if (mode === 'assert_fact') json.truthVal = !negated;
      }
    }

    // Add Scope if present (for all modes except purely local ones, but scope is usually global)
    if (scope && scope.trim() !== '') {
        json.scope = scope.trim();
    }

    onJsonChange(JSON.stringify(json, null, 2));
  }, [mode, predicate, args, negated, headPredicate, headArgs, headNegated, bodyClauses, templateType, templatePredicates, templateArgs, inspectFilter, inspectType, scope]);

  // Reset fields when mode changes
  useEffect(() => {
    setPredicate('');
    setArgs(['']);
    setNegated(false);
    
    setHeadPredicate('');
    setHeadArgs(['']);
    setHeadNegated(false);
    
    setBodyClauses([{ predicate: '', args: [''], negated: false }]);
    
    setTemplateType('SYLLOGISM');
    setTemplatePredicates({ P: '', Q: '' });
    setTemplateArgs(['']);
    
    setInspectFilter('');
    setInspectType('ALL');
    
    setScope('');
  }, [mode]);

  // Render Logic
  if (mode === 'inspect') {
    return (
      <div className="flex-col gap-6" style={{ paddingBottom: 'var(--space-xl)' }}>
        <div>
           <h3 style={{ marginBottom: '4px', fontSize: '16px' }}>Inspect Storage</h3>
           <p className="text-muted" style={{ fontSize: '12px' }}>Browsable view of the Knowledge Base.</p>
        </div>

        <div className="glass-panel" style={{ padding: '20px' }}>
            <div style={{ marginBottom: '16px' }}>
                <label className="text-muted" style={{ fontSize: '12px', display: 'block', marginBottom: '8px' }}>Search / Filter</label>
                <input 
                    className="glass-input" 
                    placeholder="Filter by predicate (e.g. Person)" 
                    value={inspectFilter}
                    onChange={(e) => setInspectFilter(e.target.value)}
                    style={{ width: '100%', fontSize: '14px', padding: '10px 12px' }}
                />
            </div>
            
             <div>
                <label className="text-muted" style={{ fontSize: '12px', display: 'block', marginBottom: '8px' }}>Item Type</label>
                <div style={{ display: 'flex', gap: '2px', background: 'rgba(255,255,255,0.05)', borderRadius: '8px', padding: '4px', width: 'fit-content' }}>
                    {['ALL', 'FACT', 'RULE'].map(type => (
                        <button
                            key={type}
                            onClick={() => setInspectType(type)}
                            style={{
                                background: inspectType === type ? 'var(--c-accent)' : 'transparent',
                                color: inspectType === type ? 'black' : 'var(--c-text-muted)',
                                border: 'none',
                                borderRadius: '6px',
                                padding: '6px 16px',
                                fontSize: '12px',
                                fontWeight: 600,
                                cursor: 'pointer',
                                transition: 'all 0.2s'
                            }}
                        >
                            {type === 'ALL' ? 'All Items' : type === 'FACT' ? 'Facts Only' : 'Rules Only'}
                        </button>
                    ))}
                </div>
            </div>
            
            <div style={{ marginBottom: '16px' }}>
                <label className="text-muted" style={{ fontSize: '12px', display: 'block', marginBottom: '8px' }}>Scope Filter (Optional)</label>
                <input 
                    className="glass-input" 
                    placeholder="Filter by context (e.g. Doc1)" 
                    value={scope}
                    onChange={(e) => setScope(e.target.value)}
                    style={{ width: '100%', fontSize: '14px', padding: '10px 12px' }}
                />
            </div>

            <div style={{ marginTop: '24px', padding: '12px', background: 'rgba(255,255,255,0.02)', borderRadius: '8px', fontSize: '13px', color: 'var(--c-text-muted)', display: 'flex', gap: '12px', alignItems: 'center' }}>
                <span style={{ fontSize: '18px' }}>💡</span>
                <span>Click <strong>Run</strong> to fetch the latest state from the server.</span>
            </div>
        </div>
      </div>
    );
  }

  if (mode === 'assert_template') {
      return (
        <div className="flex-col gap-6" style={{ paddingBottom: 'var(--space-xl)' }}>
            <div>
                 <h3 style={{ marginBottom: 'var(--space-xs)', fontSize: '18px' }}>🧩 Logic Template</h3>
                 <p className="text-muted" style={{ fontSize: '14px' }}>{descriptions['assert_template']}</p>
            </div>

            <div className="glass-panel" style={{ padding: 'var(--space-lg)' }}>
                 <div style={{ marginBottom: 'var(--space-lg)' }}>
                     <label className="text-muted" style={{ display: 'block', marginBottom: '4px', fontSize: '14px' }}>Template Type</label>
                     <select 
                        className="glass-input" 
                        value={templateType}
                        onChange={(e) => setTemplateType(e.target.value)}
                        style={{ width: '100%', fontSize: '16px' }}
                     >
                         <optgroup label="Formal Logic">
                             <option value="SYLLOGISM">Syllogism (Classic Inference)</option>
                             <option value="MODUS_PONENS">Modus Ponens (If P then Q)</option>
                             <option value="MODUS_TOLLENS">Modus Tollens (If P then Q + Contrapositive)</option>
                             <option value="HYPOTHETICAL_SYLLOGISM">Hypothetical Syllogism (Chain)</option>
                             <option value="DISJUNCTIVE_SYLLOGISM">Disjunctive Syllogism (P or Q; Not P -&gt; Q)</option>
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

                 <h4 className="text-accent" style={{ marginBottom: 'var(--space-md)', fontSize: '15px' }}>Predicates</h4>
                 <div className="flex-col gap-4">
                    {/* Dynamic Fields based on Type */}
                    {(templateType === 'SYLLOGISM' || templateType === 'MODUS_PONENS' || templateType === 'MODUS_TOLLENS' || templateType === 'DISJUNCTIVE_SYLLOGISM') && (
                        <>
                            <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>P (Antecedent/First Option)</label>
                                <input className="glass-input" placeholder="e.g. Man or Raining" value={templatePredicates.P || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, P: e.target.value})} />
                            </div>
                            <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Q (Consequent/Second Option)</label>
                                <input className="glass-input" placeholder="e.g. Mortal or Wet" value={templatePredicates.Q || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, Q: e.target.value})} />
                            </div>
                        </>
                    )}
                    
                    {(templateType === 'CONSTRUCTIVE_DILEMMA' || templateType === 'DESTRUCTIVE_DILEMMA') && (
                        <>
                            <div className="flex gap-4">
                                <div style={{flex:1}}>
                                    <label className="text-muted" style={{ fontSize: '13px' }}>P (If P...)</label>
                                    <input className="glass-input" value={templatePredicates.P || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, P: e.target.value})} />
                                </div>
                                <div style={{flex:1}}>
                                    <label className="text-muted" style={{ fontSize: '13px' }}>R (...then R)</label>
                                    <input className="glass-input" value={templatePredicates.R || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, R: e.target.value})} />
                                </div>
                            </div>
                            <div className="flex gap-4">
                                <div style={{flex:1}}>
                                    <label className="text-muted" style={{ fontSize: '13px' }}>Q (If Q...)</label>
                                    <input className="glass-input" value={templatePredicates.Q || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, Q: e.target.value})} />
                                </div>
                                <div style={{flex:1}}>
                                    <label className="text-muted" style={{ fontSize: '13px' }}>S (...then S)</label>
                                    <input className="glass-input" value={templatePredicates.S || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, S: e.target.value})} />
                                </div>
                            </div>
                        </>
                    )}

                    {templateType === 'CAUSAL_ARGUMENT' && (
                        <>
                             <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Cause</label>
                                <input className="glass-input" placeholder="e.g. Smoking" value={templatePredicates.CAUSE || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, CAUSE: e.target.value})} />
                            </div>
                            <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Effect</label>
                                <input className="glass-input" placeholder="e.g. Cancer" value={templatePredicates.EFFECT || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, EFFECT: e.target.value})} />
                            </div>
                        </>
                    )}
                    
                    {templateType === 'DEFINITIONAL_ARGUMENT' && (
                        <>
                             <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Feature</label>
                                <input className="glass-input" placeholder="e.g. HasWings" value={templatePredicates.FEATURE || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, FEATURE: e.target.value})} />
                            </div>
                            <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Category</label>
                                <input className="glass-input" placeholder="e.g. Bird" value={templatePredicates.CATEGORY || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, CATEGORY: e.target.value})} />
                            </div>
                        </>
                    )}
                    
                    {templateType === 'EVALUATIVE_ARGUMENT' && (
                        <>
                             <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Criteria</label>
                                <input className="glass-input" placeholder="e.g. Effective" value={templatePredicates.CRITERIA || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, CRITERIA: e.target.value})} />
                            </div>
                            <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Evaluation</label>
                                <input className="glass-input" placeholder="e.g. GoodPolicy" value={templatePredicates.EVALUATION || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, EVALUATION: e.target.value})} />
                            </div>
                        </>
                    )}

                    {templateType === 'PRACTICAL_ARGUMENT' && (
                        <>
                             <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Evidence</label>
                                <input className="glass-input" placeholder="e.g. FingerprintsFound" value={templatePredicates.EVIDENCE || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, EVIDENCE: e.target.value})} />
                            </div>
                             <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Exception (Unless...)</label>
                                <input className="glass-input" placeholder="e.g. ForgedEvidence" value={templatePredicates.EXCEPTION || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, EXCEPTION: e.target.value})} />
                            </div>
                            <div>
                                <label className="text-muted" style={{ fontSize: '13px' }}>Conclusion</label>
                                <input className="glass-input" placeholder="e.g. Guilty" value={templatePredicates.CONCLUSION || ''} onChange={(e) => setTemplatePredicates({...templatePredicates, CONCLUSION: e.target.value})} />
                            </div>
                        </>
                    )}
                 </div>

                 <div style={{ marginTop: 'var(--space-lg)' }}>
                     <label className="text-muted" style={{ display: 'block', marginBottom: '4px', fontSize: '14px' }}>Common Variables</label>
                     <p style={{ fontSize: '12px', color: 'rgba(255,255,255,0.5)', marginBottom: '8px' }}>Variables shared across the pattern. e.g. "x"</p>
                     <ArgList values={templateArgs} onChange={setTemplateArgs} placeholder="e.g. x" />
                 </div>

                 <div style={{ marginTop: 'var(--space-lg)' }}>
                     <label className="text-muted" style={{ display: 'block', marginBottom: '4px', fontSize: '14px' }}>Scope (Optional)</label>
                     <input 
                        className="glass-input" 
                        placeholder="Context ID (e.g. Doc123)" 
                        value={scope} 
                        onChange={(e) => setScope(e.target.value)} 
                        style={{ width: '100%' }}
                     />
                 </div>
            </div>
            
            <div style={{ padding: '0 var(--space-xs)' }}>
                <p style={{ fontSize: '13px', color: 'var(--c-text-muted)' }}>
                    Preview:<br/>
                    {templateType === 'SYLLOGISM' && `Rule: ${templatePredicates.Q || 'Q'}(?x) <- ${templatePredicates.P || 'P'}(?x)`}
                    {templateType === 'MODUS_PONENS' && `Rule: ${templatePredicates.Q || 'Q'}(?x) <- ${templatePredicates.P || 'P'}(?x)`}
                    {templateType === 'MODUS_TOLLENS' && `Rules:\n1. ${templatePredicates.Q || 'Q'}(?x) <- ${templatePredicates.P || 'P'}(?x)\n2. NOT ${templatePredicates.P || 'P'}(?x) <- NOT ${templatePredicates.Q || 'Q'}(?x)`}
                </p>
            </div>
        </div>
      );
  }

  if (mode === 'assert_rule') {
    return (
      <div className="flex-col gap-4" style={{ paddingBottom: 'var(--space-md)' }}>
        <div>
             <h3 style={{ marginBottom: '4px', fontSize: '16px' }}>Assert Rule</h3>
             <p className="text-muted" style={{ fontSize: '12px' }}>{descriptions['assert_rule']}</p>
        </div>

        {/* HEAD SECTION */}
        <div className="glass-panel" style={{ padding: '16px' }}>
          <div className="flex-between" style={{ marginBottom: '12px' }}>
              <h4 className="text-accent" style={{ fontSize: '14px', margin: 0 }}>IF (Conclusion)</h4>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', cursor: 'pointer' }}>
                   <input 
                    type="checkbox" 
                    checked={headNegated} 
                    onChange={(e) => setHeadNegated(e.target.checked)} 
                   />
                   <span className={headNegated ? "text-danger" : "text-muted"}>Negated</span>
              </label>
          </div>
          
          <div style={{ display: 'flex', gap: '16px' }}>
             <div style={{ flex: 1 }}>
                 <label className="text-muted" style={{ fontSize: '11px', display: 'block', marginBottom: '4px' }}>Head Predicate</label>
                 <input 
                 className="glass-input" 
                 placeholder="e.g. GrandParent" 
                 value={headPredicate} 
                 onChange={(e) => setHeadPredicate(e.target.value)} 
                 style={{ padding: '8px 12px' }}
                 />
            </div>
            <div style={{ flex: 2 }}>
                <label className="text-muted" style={{ fontSize: '11px', display: 'block', marginBottom: '4px' }}>Arguments (?x, ?y...)</label>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                     {headArgs.map((val, idx) => (
                        <div key={idx} style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
                            <input
                              className="glass-input"
                              value={val}
                              onChange={(e) => {
                                const newArgs = [...headArgs];
                                newArgs[idx] = e.target.value;
                                setHeadArgs(newArgs);
                              }}
                              placeholder="Arg"
                              style={{ padding: '6px 8px', minWidth: '60px', width: '80px', fontSize: '13px' }}
                            />
                            {idx === headArgs.length - 1 && (
                                <button className="glass-button" onClick={() => setHeadArgs([...headArgs, ''])} style={{ marginLeft: '4px', padding: '2px 8px' }}>+</button>
                            )}
                             {headArgs.length > 1 && (
                                <button className="glass-button danger" onClick={() => setHeadArgs(headArgs.filter((_, i) => i !== idx))} style={{ marginLeft: '4px', padding: '2px 6px' }}>×</button>
                            )}
                        </div>
                     ))}
                </div>
            </div>
          </div>
        </div>

        {/* BODY SECTION */}
        <div className="glass-panel" style={{ padding: '16px' }}>
          <div className="flex-between" style={{ marginBottom: '12px' }}>
               <h4 className="text-primary" style={{ fontSize: '14px', margin: 0 }}>THEN (Conditions)</h4>
               <button 
                  className="glass-button primary" 
                  onClick={() => setBodyClauses([...bodyClauses, { predicate: '', args: [''], negated: false }])}
                  style={{ padding: '4px 12px', fontSize: '12px' }}
                >
                  + Add Condition
                </button>
          </div>
          
          <div className="flex-col gap-3">
            {bodyClauses.map((clause, idx) => (
              <div key={idx} style={{ 
                display: 'flex', gap: '12px', alignItems: 'flex-start',
                padding: '8px',
                background: 'rgba(255,255,255,0.03)',
                borderRadius: '6px'
              }}>
                <div style={{ width: '24px', paddingTop: '8px', fontSize: '12px', color: 'var(--c-text-muted)' }}>#{idx+1}</div>
                
                <div style={{ flex: 1 }}>
                     <label className="text-muted" style={{ fontSize: '10px', display: 'block', marginBottom: '2px' }}>Predicate</label>
                    <input 
                    className="glass-input" 
                    placeholder="Predicate" 
                    value={clause.predicate} 
                    onChange={(e) => {
                        const newClauses = [...bodyClauses];
                        newClauses[idx].predicate = e.target.value;
                        setBodyClauses(newClauses);
                    }} 
                    style={{ padding: '6px 10px', width: '100%', fontSize: '13px' }}
                    />
                </div>
                
                <div style={{ width: '80px', paddingTop: '18px' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', cursor: 'pointer' }}>
                       <input 
                        type="checkbox" 
                        checked={clause.negated} 
                        onChange={(e) => {
                            const newClauses = [...bodyClauses];
                            newClauses[idx].negated = e.target.checked;
                            setBodyClauses(newClauses);
                        }} 
                       />
                       <span className={clause.negated ? "text-danger" : "text-muted"}>NOT</span>
                    </label>
                </div>

                <div style={{ flex: 2 }}>
                    <label className="text-muted" style={{ fontSize: '10px', display: 'block', marginBottom: '2px' }}>Arguments</label>
                    <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                     {clause.args.map((val, aIdx) => (
                        <div key={aIdx} style={{ display: 'flex', alignItems: 'center' }}>
                            <input
                              className="glass-input"
                              value={val}
                              onChange={(e) => {
                                const newClauses = [...bodyClauses];
                                newClauses[idx].args[aIdx] = e.target.value;
                                setBodyClauses([...newClauses]);
                              }}
                              placeholder="?"
                              style={{ padding: '6px 8px', width: '70px', fontSize: '13px' }}
                            />
                             {aIdx === clause.args.length - 1 && (
                                <button className="glass-button" onClick={() => {
                                     const newClauses = [...bodyClauses];
                                     newClauses[idx].args.push('');
                                     setBodyClauses([...newClauses]);
                                }} style={{ marginLeft: '2px', padding: '0 4px', height: '28px' }}>+</button>
                            )}
                        </div>
                     ))}
                    </div>
                </div>

                {bodyClauses.length > 1 && (
                     <button className="glass-button danger" onClick={() => setBodyClauses(bodyClauses.filter((_, i) => i !== idx))} style={{ marginTop: '18px', padding: '4px 8px' }}>×</button>
                )}
              </div>
            ))}
          </div>
        </div>
        {/* SCOPE SECTION */}
        <div className="glass-panel" style={{ padding: '16px', marginTop: '16px' }}>
            <label className="text-muted" style={{ fontSize: '12px', display: 'block', marginBottom: '8px' }}>Scope (Optional)</label>
            <input 
                className="glass-input" 
                placeholder="Context ID (e.g. Doc123)" 
                value={scope} 
                onChange={(e) => setScope(e.target.value)} 
                style={{ width: '100%' }}
            />
        </div>
      </div>
    );
  }

  // FACT / INFER / RETRACT
  return (
    <div className="glass-panel" style={{ padding: '20px' }}>
       <div className="flex-between" style={{ marginBottom: '16px' }}>
            <div>
                <h3 style={{ marginBottom: '4px', fontSize: '16px' }}>
                    {mode === 'infer' ? '🔍 Infer Query' : 
                    mode === 'assert_fact' ? '➕ Assert Fact' : '❌ Retract Fact'}
                </h3>
                <p className="text-muted" style={{ fontSize: '12px', margin: 0 }}>{descriptions[mode]}</p>
            </div>
       </div>
       
       <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
         <div style={{ display: 'flex', gap: '16px', alignItems: 'flex-start' }}>
             <div style={{ flex: 1 }}>
                <div className="flex-between mb-2">
                    <label className="text-muted" style={{ fontSize: '12px' }}>Predicate</label>
                    {(mode === 'assert_fact' || mode === 'infer') && (
                        <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', cursor: 'pointer' }}>
                           <input 
                            type="checkbox" 
                            checked={negated} 
                            onChange={(e) => setNegated(e.target.checked)} 
                           />
                           <span className={negated ? "text-danger font-bold" : "text-muted"}>Negated</span>
                        </label>
                    )}
                </div>
                
                <input 
                  className="glass-input" 
                  placeholder="e.g. Person" 
                  value={predicate}
                  onChange={(e) => setPredicate(e.target.value)}
                  autoFocus
                  style={{ fontSize: '15px', padding: '10px 14px', width: '100%' }}
                />
             </div>
             
             <div style={{ flex: 1.5 }}>
                 <label className="text-muted" style={{ display: 'block', marginBottom: '8px', fontSize: '12px' }}>Arguments (Order Matters)</label>
                 <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {/* Compact Arg List */}
                    {args.map((val, idx) => (
                        <div key={idx} style={{ display: 'flex', gap: '8px' }}>
                            <span className="text-muted" style={{ fontSize: '12px', paddingTop: '10px', width: '16px' }}>{idx+1}.</span>
                            <input
                              className="glass-input"
                              value={val}
                              onChange={(e) => {
                                const newArgs = [...args];
                                newArgs[idx] = e.target.value;
                                setArgs(newArgs);
                              }}
                              placeholder="Arg (e.g. John)"
                              style={{ flex: 1, padding: '8px 12px' }}
                            />
                            {args.length > 1 && (
                                <button className="glass-button danger" onClick={() => setArgs(args.filter((_, i) => i !== idx))} style={{width:'32px'}}>×</button>
                            )}
                        </div>
                    ))}
                    <button className="glass-button" onClick={() => setArgs([...args, ''])} style={{ width: 'fit-content', fontSize: '12px' }}>+ Add Argument</button>
                 </div>
             </div>
         </div>
        </div>
        
       <div style={{ marginTop: '0px' }}>
            <div style={{ display: 'flex', gap: '16px', alignItems: 'center' }}>
                 <div style={{ flex: 1 }}>
                     <label className="text-muted" style={{ fontSize: '12px', display: 'block', marginBottom: '4px' }}>Scope (Context ID)</label>
                     <input 
                        className="glass-input" 
                        placeholder="Optional (e.g. Doc123)" 
                        value={scope} 
                        onChange={(e) => setScope(e.target.value)} 
                        style={{ width: '100%' }}
                     />
                 </div>
                 <div style={{ flex: 1.5 }}>
                     {/* Spacer or extraoptions */}
                 </div>
            </div>
       </div>
    </div>
  );
}
