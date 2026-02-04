import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import Layout from './components/Layout';
import Sidebar from './components/Sidebar';
import ActionToolbar from './components/ActionToolbar';
import ResultsTable from './components/ResultsTable';
import VisualBuilder from './components/VisualBuilder';
import JsonPreview from './components/JsonPreview';

function QueryConsole() {
  const { dbName } = useParams();
  const navigate = useNavigate();
  
  // State
  const [mode, setMode] = useState('infer');
  const [inputData, setInputData] = useState('{}');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  
  // Visual Builder State
  const [useVisualBuilder, setUseVisualBuilder] = useState(true);

  // Global Metadata
  const [databases, setDatabases] = useState([]); // List of DBs for sidebar
  
  // MT Support
  const [isMultiTenant, setIsMultiTenant] = useState(false);
  const [tenantId, setTenantId] = useState('');
  const [tenants, setTenants] = useState([]);

  // --- Initial Data Fetching ---

  const getApiUrl = () => import.meta.env.VITE_API_URL || 'http://localhost:9300';
  const getApiKey = () => localStorage.getItem('api_key');

  const fetchDatabases = async () => {
    try {
        const res = await fetch(`${getApiUrl()}/admin/databases`, {
            headers: { 'X-API-Key': getApiKey() }
        });
        if (res.ok) {
            const list = await res.json();
            setDatabases(list);
            
            // Check current DB props
            const current = list.find(d => d.name === dbName);
            if (current && current.isMultiTenant) {
                setIsMultiTenant(true);
            } else {
                setIsMultiTenant(false);
                setTenants([]);
            }
        }
    } catch (e) {
        console.error("Failed to fetch databases", e);
    }
  };

  const fetchTenants = async () => {
      if (!isMultiTenant) return;
      try {
        const res = await fetch(`${getApiUrl()}/admin/databases/${dbName}/tenants`, {
            headers: { 'X-API-Key': getApiKey() }
        });
        if (res.ok) {
            const list = await res.json();
            setTenants(Array.isArray(list) ? list : []);
        }
      } catch (e) {
          console.error("Failed to fetch tenants", e);
      }
  };

  useEffect(() => {
      fetchDatabases();
  }, [dbName]);

  useEffect(() => {
      if (isMultiTenant) {
          fetchTenants();
      }
  }, [isMultiTenant, dbName]);


  // --- Actions ---

  const handleCreateTenant = async () => {
      const newId = prompt("Enter new Tenant ID:");
      if (!newId) return;
      
      try {
        const res = await fetch(`${getApiUrl()}/admin/databases/${dbName}/tenants`, {
            method: 'POST',
            headers: { 'X-API-Key': getApiKey(), 'Content-Type': 'application/json' },
            body: JSON.stringify({ tenantId: newId })
        });
        
        if (res.ok) {
            alert(`Tenant ${newId} created`);
            fetchTenants();
            setTenantId(newId);
        } else {
            alert("Error creating tenant: " + await res.text());
        }
      } catch (e) {
          alert("Error: " + e.message);
      }
  };

  // --- Templates ---
  const templates = {
      infer: `{\n  "predicate": "GrandParent",\n  "args": ["?x", "?y"]\n}`,
      assert_fact: `{\n  "predicate": "Parent",\n  "args": ["Zeus", "Ares"],\n  "truthVal": true\n}`,
      retract: `{\n  "predicate": "Parent",\n  "args": ["Zeus", "Ares"]\n}`,
      assert_rule: `{\n  "head": { "predicate": "GrandParent", "args": ["?x", "?z"] },\n  "body": [\n    { "predicate": "Parent", "args": ["?x", "?y"] },\n    { "predicate": "Parent", "args": ["?y", "?z"] }\n  ]\n}`,
      assert_template: `{\n  "type": "SYLLOGISM",\n  "predicates": { "P": "Man", "Q": "Mortal" },\n  "args": ["?x"]\n}`,
      inspect: `// No input required`
  };

  // --- Effects ---

  // Reset State on Context Change (DB/Tenant)
  useEffect(() => {
    setResult(null);
    setError(null);
    setMode('infer');
    setInputData(templates['infer']);
  }, [dbName, tenantId]);

  const switchMode = (newMode) => {
      setMode(newMode);
      if (templates[newMode] && !useVisualBuilder) {
        setInputData(templates[newMode]);
      }
      setResult(null);
      setError(null);
  };

  const handleExecute = async () => {
    setResult(null);
    setError(null);
    setIsLoading(true);
    
    if (isMultiTenant && !tenantId) {
        setError("Tenant ID is required for this database (Multi-tenant). Select one from the sidebar.");
        setIsLoading(false);
        return;
    }
    
    try {
        const headers = {
            'X-API-Key': getApiKey(),
            'X-Database': dbName,
        };
        
        if (isMultiTenant) {
            headers['X-Tenant-ID'] = tenantId;
        }

        if (mode === 'inspect') {
            // Fetch Facts
             let facts = [];
             let rules = [];

            try {
                // Parse filters from inputData (which VisualBuilder updates)
                let filterPred = '';
                let filterType = 'ALL';
                let filterScope = null;
                try {
                     const fJson = JSON.parse(inputData);
                     if (fJson.filter) filterPred = fJson.filter.toLowerCase();
                     if (fJson.type) filterType = fJson.type;
                     if (fJson.scope) filterScope = fJson.scope;
                } catch {}

                const queryParams = filterScope ? `?scope=${encodeURIComponent(filterScope)}` : '';
                
                const r1 = await fetch(`${getApiUrl()}/admin/databases/${dbName}/facts${queryParams}`, { headers });
                if (r1.ok) facts = await r1.json();
                
                const r2 = await fetch(`${getApiUrl()}/admin/databases/${dbName}/rules${queryParams}`, { headers });
                if (r2.ok) rules = await r2.json();
            
                // Combine for table display
                let combined = [];
                
                if (filterType === 'ALL' || filterType === 'FACT') {
                    combined.push(...facts.map(f => ({ Type: 'Fact', Content: f })));
                }
                if (filterType === 'ALL' || filterType === 'RULE') {
                    combined.push(...rules.map(r => ({ Type: 'Rule', Content: r })));
                }
                
                // Content Filter (Text search on stringified content)
                if (filterPred) {
                    combined = combined.filter(item => 
                        JSON.stringify(item.Content).toLowerCase().includes(filterPred)
                    );
                }
                
                setResult(combined);
            } catch (e) {
                setError("Failed to inspect: " + e.message);
            }
            setIsLoading(false);
            return;
        }

        let endpoint = '/infer';
        if (mode === 'assert_fact') endpoint = '/assert/fact';
        if (mode === 'assert_rule') endpoint = '/assert/rule';
        if (mode === 'assert_template') endpoint = '/assert/template';
        if (mode === 'retract') endpoint = '/retract';
        
        // Validate JSON
        let body;
        try {
            // Remove comments (// or /* */) before parsing
            const cleanedInput = inputData.replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
            // Skip parsing for inspect mode empty templates if they were accidentally left
            if (!cleanedInput.trim()) { body = {}; } 
            else { body = JSON.parse(cleanedInput); }
        } catch (e) {
            throw new Error("Invalid JSON: " + e.message);
        }

        headers['Content-Type'] = 'application/json';

        const response = await fetch(`${getApiUrl()}${endpoint}`, {
            method: 'POST',
            headers,
            body: JSON.stringify(body)
        });
        
        const text = await response.text();
        try {
            const json = JSON.parse(text);
            setResult(json); 
        } catch {
            setResult(text);
        }
    } catch (err) {
        setError(err.message);
    } finally {
        setIsLoading(false);
    }
  };

  return (
    <Layout
        sidebar={
            <Sidebar 
                databases={databases}
                currentDb={dbName}
                onSelectDb={(name) => navigate(`/db/${name}`)}
                tenants={tenants}
                currentTenant={tenantId}
                onSelectTenant={(id) => setTenantId(id)}
                onCreateTenant={handleCreateTenant}
            />
        }
        toolbar={
            <div className="flex-between w-full">
              <div style={{ flex: 1 }}>
                <ActionToolbar 
                    mode={mode} 
                    onModeChange={switchMode} 
                    onRun={handleExecute}
                    isRunning={isLoading}
                />
              </div>
              
              <div className="glass-panel flex-center gap-2" style={{ marginLeft: '16px', padding: '8px 16px', height: '40px', background: 'rgba(255,255,255,0.03)' }}>
                <span className={`text-sm ${useVisualBuilder ? 'text-accent' : 'text-muted'}`} style={{ fontSize: '13px' }}>Visual</span>
                <div 
                  className="switch-track"
                  style={{ 
                    width: '36px', height: '20px', 
                    background: 'rgba(255,255,255,0.1)', 
                    borderRadius: '10px', 
                    position: 'relative',
                    cursor: 'pointer'
                  }}
                  onClick={() => setUseVisualBuilder(!useVisualBuilder)}
                >
                   <div 
                    style={{
                      position: 'absolute',
                      left: useVisualBuilder ? '2px' : '18px', 
                      top: '2px',
                      width: '16px', height: '16px',
                      borderRadius: '50%',
                      background: 'var(--c-text-main)',
                      transition: 'all 0.2s'
                    }}
                  />
                </div>
                <span className={`text-sm ${!useVisualBuilder ? 'text-accent' : 'text-muted'}`} style={{ fontSize: '13px' }}>Code</span>
              </div>
            </div>
        }
        content={
            <div style={{ height: 'calc(100vh - 120px)', display: 'flex', flexDirection: 'column', gap: '16px', padding: '0 8px' }}>
                
                {/* Editor Area: Now takes more space (flex: 3) and only shrinks Results if needed */}
                <div style={{ flex: 3, display: 'flex', gap: '16px', minHeight: '300px', overflow: 'hidden' }}>
                  
                  {/* Left: Input (Visual or Code) */}
                  <div style={{ flex: 2, display: 'flex', flexDirection: 'column', overflowY: 'auto' }}>
                    {useVisualBuilder ? (
                      <VisualBuilder mode={mode} onJsonChange={setInputData} />
                    ) : (
                       <textarea 
                          value={inputData}
                          onChange={(e) => setInputData(e.target.value)}
                          className="glass-panel"
                          style={{ 
                              flex: 1, 
                              width: '100%', 
                              resize: 'none', 
                              backgroundColor: 'rgba(0,0,0,0.3)',
                              fontFamily: 'var(--font-mono)', 
                              border: '1px solid var(--glass-border)',
                              padding: 'var(--space-md)',
                              fontSize: '14px',
                              lineHeight: '1.5',
                              color: 'var(--c-text-main)',
                              outline: 'none'
                          }}
                          spellCheck="false"
                      />
                    )}
                  </div>

                  {/* Right: Live Preview */}
                  {useVisualBuilder && mode !== 'inspect' && (
                    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: '300px' }}>
                       <div className="glass-panel" style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                          <div style={{ padding: '12px', borderBottom: '1px solid var(--glass-border)', background: 'rgba(0,0,0,0.2)' }}>
                            <span className="text-muted text-sm font-mono">LIVE SYNTAX PREVIEW</span>
                          </div>
                          <div style={{ padding: '16px', flex: 1, overflow: 'auto' }}>
                             <JsonPreview data={inputData} />
                          </div>
                       </div>
                    </div>
                  )}


 
                 </div>
             </div>
         }
         results={
           <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
               {!result && !isLoading && (
                   <div style={{ padding: '24px', color: 'var(--c-text-muted)', fontStyle: 'italic', textAlign: 'center' }}>
                       Run a command to see results here.
                   </div>
               )}
               {result && (
                  <ResultsTable 
                        data={result} 
                        error={error} 
                        isLoading={isLoading} 
                    />
               )}
               {isLoading && !result && (
                   <div style={{ padding: '24px', textAlign: 'center' }}>Processing...</div>
               )}
           </div>
         }
     />
  );
}

export default QueryConsole;
