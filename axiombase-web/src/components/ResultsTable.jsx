import React from 'react';

const ResultsTable = ({ data, error, isLoading }) => {
  if (isLoading) {
    return (
        <div style={{ 
            display: 'flex', 
            justifyContent: 'center', 
            alignItems: 'center', 
            height: '100%', 
            color: 'var(--text-secondary)' 
        }}>
            Processing...
        </div>
    );
  }

  if (error) {
     return (
        <div style={{ padding: 'var(--space-md)', color: 'var(--brand-error)' }}>
            <strong>Error:</strong> {error}
        </div>
    );
  }

  if (!data) {
     return (
        <div style={{ 
            display: 'flex', 
            justifyContent: 'center', 
            alignItems: 'center', 
            height: '100%', 
            color: 'var(--text-secondary)',
            fontStyle: 'italic'
        }}>
            No results to display. Run a command to see output.
        </div>
    );
  }

  // Handle String results (raw text) or existing HTML blobs
  if (typeof data === 'string') {
      // Check if it's HTML (legacy support for inspect)
      if (data.trim().startsWith('<')) {
           return <div style={{ padding: 'var(--space-md)', fontFamily: 'var(--font-mono)' }} dangerouslySetInnerHTML={{ __html: data }} />;
      }
      return <pre style={{ padding: 'var(--space-md)', margin: 0, fontFamily: 'var(--font-mono)' }}>{data}</pre>;
  }

  // If data is JSON object/array
  let rows = [];
  if (Array.isArray(data)) {
      rows = data;
  } else if (typeof data === 'object') {
     // If it's a single object, wrap in array
     rows = [data];
  }

  if (rows.length === 0) {
       return <div style={{ padding: 'var(--space-md)' }}>Empty Result Set</div>;
  }

  // Extract columns
  // Assuming simple object structure. If primitive, map to 'Value'
  const firstRow = rows[0];
  let columns = [];
  if (typeof firstRow === 'object' && firstRow !== null) {
      columns = Object.keys(firstRow);
  } else {
      columns = ['Value'];
      rows = rows.map(v => ({ Value: v }));
  }

  return (
    <div style={{ minWidth: '100%', display: 'inline-block' }}> {/* inline-block allows table to expand */}
        <table style={{ 
            width: '100%', 
            borderCollapse: 'collapse', 
            fontSize: 'var(--text-sm)',
            fontFamily: 'var(--font-mono)'
        }}>
            <thead style={{ 
                position: 'sticky', 
                top: 0, 
                backgroundColor: 'var(--bg-panel-header)', 
                zIndex: 1,
                boxShadow: '0 1px 0 var(--border-color)'
            }}>
                <tr>
                    {columns.map(c => (
                        <th key={c} style={{ 
                            textAlign: 'left', 
                            padding: '8px 12px', 
                            borderRight: '1px solid var(--border-color)',
                            fontWeight: 600,
                            color: 'var(--text-secondary)',
                            whiteSpace: 'nowrap'
                        }}>
                            {c}
                        </th>
                    ))}
                </tr>
            </thead>
            <tbody>
                {rows.map((row, i) => (
                    <tr key={i} style={{ 
                        backgroundColor: i % 2 === 0 ? 'rgba(255,255,255,0.02)' : 'transparent',
                        transition: 'background-color 0.1s' 
                    }}>
                       {columns.map(c => (
                           <td key={c} style={{ 
                               padding: '12px', 
                               borderBottom: '1px solid rgba(255,255,255,0.05)',
                               whiteSpace: 'pre-wrap', 
                               color: 'var(--c-text-main)',
                               fontSize: '13px'
                           }}>
                               {typeof row[c] === 'object' ? JSON.stringify(row[c]) : String(row[c])}
                           </td>
                       ))}
                    </tr>
                ))}
            </tbody>
        </table>
    </div>
  );
};

export default ResultsTable;
