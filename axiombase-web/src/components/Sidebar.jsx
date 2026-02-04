import React from 'react';

const Sidebar = ({ databases, currentDb, onSelectDb, tenants, currentTenant, onSelectTenant, onCreateTenant }) => {
  return (
    <div style={{ padding: '0 var(--space-md)' }}>
      <div style={{ marginBottom: 'var(--space-lg)' }}>
        <h4 style={{ 
            margin: 'var(--space-md) 0 var(--space-sm) 0', 
            fontSize: '11px', 
            textTransform: 'uppercase', 
            letterSpacing: '0.1em',
            color: 'var(--c-text-muted)',
            borderBottom: '1px solid var(--glass-border)',
            paddingBottom: '4px'
        }}>Databases</h4>
        
        <div className="flex-col gap-2">
            {databases.map(db => (
                <div 
                    key={db.name}
                    onClick={() => onSelectDb(db.name)}
                    style={{
                        padding: '8px 12px',
                        cursor: 'pointer',
                        borderRadius: 'var(--radius-sm)',
                        backgroundColor: currentDb === db.name ? 'rgba(255,255,255,0.1)' : 'transparent',
                        color: currentDb === db.name ? 'var(--c-text-main)' : 'var(--c-text-muted)',
                        fontSize: '14px',
                        display: 'flex',
                        alignItems: 'center',
                        transition: 'all 0.2s',
                        borderLeft: currentDb === db.name ? '3px solid var(--accent)' : '3px solid transparent'
                    }}
                >
                    <span style={{ marginRight: '10px', opacity: 0.7 }}>
                        {currentDb === db.name ? '📂' : '📁'}
                    </span>
                    <span style={{ fontWeight: currentDb === db.name ? 600 : 400 }}>{db.name}</span>
                </div>
            ))}
        </div>
      </div>

      {/* Tenants Section (Only if DB selected and MT) */}
      {currentDb && tenants.length > 0 && (
         <div style={{ marginBottom: 'var(--space-lg)' }}>
            <div className="flex-between" style={{ marginBottom: 'var(--space-sm)' }}>
                <h4 style={{ 
                    margin: 0, 
                    fontSize: '11px', 
                    textTransform: 'uppercase', 
                    letterSpacing: '0.1em',
                    color: 'var(--c-text-muted)',
                }}>Tenants</h4>
                <button 
                    className="glass-button" 
                    title="New Tenant"
                    onClick={onCreateTenant}
                    style={{ padding: '2px 8px', fontSize: '12px' }}
                >
                    +
                </button>
            </div>
            
            <div className="flex-col gap-2">
                {tenants.map(t => (
                    <div 
                        key={t}
                        onClick={() => onSelectTenant(t)}
                        style={{
                            padding: '6px 12px',
                            cursor: 'pointer',
                            borderRadius: 'var(--radius-sm)',
                            backgroundColor: currentTenant === t ? 'rgba(255,255,255,0.1)' : 'transparent',
                            color: currentTenant === t ? 'var(--c-text-main)' : 'var(--c-text-muted)',
                            fontSize: '13px',
                            paddingLeft: '16px', // Indent tenants
                            borderLeft: currentTenant === t ? '3px solid var(--accent)' : '3px solid transparent'
                        }}
                    >
                        <span style={{ opacity: 0.7, marginRight: '8px' }}>👤</span>
                        {t}
                    </div>
                ))}
            </div>
          </div>
      )}
    </div>
  );
};

export default Sidebar;
