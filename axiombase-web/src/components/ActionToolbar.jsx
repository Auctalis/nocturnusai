import React from 'react';

const ActionToolbar = ({ mode, onModeChange, onRun, isRunning }) => {
  const modes = [
    { id: 'infer', label: 'Query (Infer)', icon: '🔎' },
    { id: 'assert_fact', label: 'Assert Fact', icon: '➕' },
    { id: 'assert_rule', label: 'Assert Rule', icon: '📜' },
    { id: 'retract', label: 'Retract', icon: '❌' },
    { id: 'inspect', label: 'Inspect Storage', icon: '📦' },
    { id: 'assert_template', label: 'Logic Template', icon: '🧩' },
  ];

  return (
    <div className="glass-panel" style={{ 
        display: 'flex', 
        alignItems: 'center', 
        width: '100%', 
        padding: '8px 16px',
        marginBottom: '0', 
        borderRadius: 'var(--radius-md)',
        border: 'none', /* Remove border since it's inside another container usually or needs to blend */
        background: 'rgba(255, 255, 255, 0.03)'
    }}>
      
      {/* Mode Selector */}
      <div style={{ display: 'flex', marginRight: 'var(--space-md)' }}>
          <select 
            value={mode} 
            onChange={(e) => onModeChange(e.target.value)}
            className="glass-input"
            style={{ 
                minWidth: '160px', 
                fontWeight: 600, 
                color: 'var(--c-text-main)',
                backgroundColor: 'rgba(0,0,0,0.3)',
                cursor: 'pointer'
            }}
          >
              {modes.map(m => <option key={m.id} value={m.id}>{m.icon} {m.label}</option>)}
          </select>
      </div>

      {/* Main Action Button */}
      <button 
        className="glass-button primary" 
        onClick={onRun} 
        disabled={isRunning}
        style={{ 
            marginRight: 'var(--space-md)',
            minWidth: '100px',
            height: '40px'
        }}
      >
        {isRunning ? '⏳ ...' : '▶ Run'}
      </button>
      
      <div style={{ flex: 1 }}></div>

      {/* Right aligned tools */}
      <button 
        className="glass-button" 
        onClick={() => window.open('https://github.com/google-deepmind', '_blank')}
        title="Documentation"
      >
        Docs
      </button>
    </div>
  );
};

export default ActionToolbar;
