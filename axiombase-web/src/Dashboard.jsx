import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

function Dashboard() {
  const [databases, setDatabases] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const navigate = useNavigate();

  const createDatabase = async (name, isMultiTenant) => {
      if (!name) return;
      try {
        const apiKey = localStorage.getItem('api_key');
        const apiUrl = import.meta.env.VITE_API_URL || 'http://localhost:9300';
        
        const response = await fetch(`${apiUrl}/admin/databases`, {
            method: 'POST',
            headers: {
                'X-API-Key': apiKey,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ name, isMultiTenant })
        });
        
        if (response.ok) {
            alert(`Database ${name} created!`);
            window.location.reload(); 
        } else {
            const err = await response.text();
            alert(`Error: ${err}`);
        }
      } catch (e) {
          alert(`Error: ${e.message}`);
      }
  };

  useEffect(() => {
    const fetchDatabases = async () => {
      try {
        const apiKey = localStorage.getItem('api_key');
        const apiUrl = import.meta.env.VITE_API_URL || 'http://localhost:9300';
        
        const response = await fetch(`${apiUrl}/admin/databases`, {
            headers: { 'X-API-Key': apiKey, 'Content-Type': 'application/json' }
        });

        if (!response.ok) {
            if (response.status === 401) { navigate('/login'); return; }
            throw new Error('Failed to fetch databases');
        }

        const data = await response.json();
        // data is now List<DbInfo> { name, isMultiTenant }
        setDatabases(Array.isArray(data) ? data : []);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    };

    fetchDatabases();
  }, [navigate]);

  return (
    <div>
      <h1>Databases</h1>
      <p style={{ marginBottom: '2rem', color: '#666' }}>Select a database to manage.</p>
      
      {loading && <p>Loading...</p>}
      {error && <div className="card" style={{ color: 'red' }}>Error: {error}</div>}
      
      {!loading && !error && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '24px' }}>
          {/* Create DB Card */}
          <div 
               className="glass-panel db-card-create" 
               onClick={() => {
                   const name = prompt("Enter new database name:");
                   if (name) {
                       const isMT = confirm("Is this a Multi-tenant database?");
                       createDatabase(name, isMT);
                   }
               }}
               style={{ 
                   display: 'flex', 
                   flexDirection: 'column', 
                   alignItems: 'center', 
                   justifyContent: 'center', 
                   minHeight: '180px',
                   cursor: 'pointer',
                   border: '2px dashed var(--glass-border)',
                   backgroundColor: 'rgba(255,255,255,0.02)',
                   transition: 'all 0.2s'
               }}
          >
              <div style={{ fontSize: '32px', marginBottom: '16px', color: 'var(--c-accent)' }}>+</div>
              <h3 style={{ color: 'var(--c-accent)', fontSize: '16px', margin: 0 }}>Create Database</h3>
              <p style={{ fontSize: '13px', color: 'var(--c-text-muted)',  marginTop: '8px' }}>Start a new knowledge base</p>
          </div>

          {databases.map(db => (
            <div key={db.name} 
                 className="glass-panel db-card" 
                 onClick={() => navigate(`/db/${db.name}`)}
                 style={{ 
                     padding: '24px', 
                     cursor: 'pointer',
                     minHeight: '180px',
                     display: 'flex',
                     flexDirection: 'column',
                     justifyContent: 'space-between',
                     transition: 'transform 0.2s',
                     border: '1px solid var(--glass-border)'
                 }}
            >
              <div>
                  <div className="flex-between">
                      <div style={{ width: '40px', height: '40px', borderRadius: '8px', background: 'rgba(57, 255, 20, 0.1)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '20px' }}>
                        📚
                      </div>
                      {db.isMultiTenant && (
                          <span style={{
                              fontSize: '11px', 
                              fontWeight: 700,
                              background: 'var(--c-accent)', 
                              color: 'black', 
                              padding: '4px 8px', 
                              borderRadius: '12px',
                              letterSpacing: '0.05em'
                          }}>MULTI-TENANT</span>
                      )}
                  </div>
                  
                  <h3 style={{ fontSize: '20px', marginTop: '16px', marginBottom: '8px', fontWeight: 600 }}>{db.name}</h3>
                  <p style={{ fontSize: '14px', color: 'var(--c-text-muted)', lineHeight: '1.5' }}>
                      Operational knowledge base for facts and rules.
                  </p>
              </div>
              
              <div style={{ marginTop: '16px', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: 'var(--c-accent)' }}>
                  <span>Manage</span>
                  <span>→</span>
              </div>
            </div>
          ))}

          {databases.length === 0 && (
            <div style={{ gridColumn: '1 / -1', padding: '40px', textAlign: 'center', color: 'var(--c-text-muted)' }}>
               No databases found.
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default Dashboard;
