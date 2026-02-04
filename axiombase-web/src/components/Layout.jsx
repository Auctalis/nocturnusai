import React from 'react';

const Layout = ({ sidebar, toolbar, content, results }) => {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'var(--sidebar-width) 1fr', height: '100vh', width: '100vw' }}>
      {/* Sidebar Area - Masters Green */}
      <div style={{ 
        backgroundColor: 'var(--bg-sidebar)',
        color: 'var(--text-sidebar)',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        boxShadow: '2px 0 10px rgba(0,0,0,0.1)',
        zIndex: 10
      }}>
        {/* Brand Header */}
        <div style={{ 
            height: 'var(--header-height)', 
            display: 'flex',
            alignItems: 'center',
            padding: '0 var(--space-lg)',
            borderBottom: '1px solid rgba(255,255,255,0.1)',
            backgroundColor: 'var(--masters-green-dark)'
        }}>
           <span style={{ 
               fontFamily: 'var(--font-serif)', 
               fontSize: '22px', 
               fontWeight: 'bold', 
               letterSpacing: '0.05em',
               color: 'white' 
            }}>
                AXIOMBASE
           </span>
        </div>
        
        {/* Navigation */}
        <div style={{ flex: 1, overflowY: 'auto', padding: 'var(--space-md) 0' }}>
            {sidebar}
        </div>
        
        {/* Footer */}
        <div style={{ 
            padding: 'var(--space-md)', 
            borderTop: '1px solid rgba(255,255,255,0.1)', 
            fontSize: 'var(--text-xs)', 
            color: 'rgba(255,255,255,0.5)',
            textAlign: 'center'
        }}>
            v0.1.0 • Masters Edition
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateRows: results ? 'auto 1fr 1fr' : 'auto 1fr', height: '100vh', overflow: 'hidden', backgroundColor: 'var(--bg-app)' }}>
        
        {/* Toolbar - White Card style */}
        <div style={{ 
            height: 'var(--header-height)', 
            backgroundColor: 'var(--bg-panel)',
            display: 'flex',
            alignItems: 'center',
            padding: '0 var(--space-lg)',
            boxShadow: 'var(--shadow-sm)',
            zIndex: 5
        }}>
            {toolbar}
        </div>

        {/* Editor / Main View */}
        <div style={{ 
            overflow: 'auto', 
            padding: 'var(--space-lg)',
            position: 'relative'
        }}>
            {content}
        </div>

        {/* Results Pane (Bottom Half) */}
        {/* Results Pane (Bottom Half) - Only if provided */}
        {results && (
            <div style={{ 
                borderTop: '1px solid var(--border-color)', 
                backgroundColor: 'var(--bg-panel)',
                display: 'flex', 
                flexDirection: 'column',
                overflow: 'hidden',
                boxShadow: '0 -4px 10px rgba(0,0,0,0.05)'
            }}>
                <div style={{ 
                    padding: 'var(--space-sm) var(--space-lg)', 
                    backgroundColor: 'var(--bg-panel-header)', 
                    borderBottom: '1px solid var(--border-color)',
                    fontSize: 'var(--text-sm)',
                    fontWeight: 600,
                    color: 'var(--text-secondary)',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    fontFamily: 'var(--font-serif)',
                    letterSpacing: '0.05em',
                    textTransform: 'uppercase'
                }}>
                    <span><span style={{ color: 'var(--masters-green)' }}>●</span> Results</span>
                    <span style={{ fontSize: 'var(--text-xs)' }}> </span>
                </div>
                <div style={{ flex: 1, overflow: 'auto' }}>
                    {results}
                </div>
            </div>
        )}
      </div>
    </div>
  );
};

export default Layout;
