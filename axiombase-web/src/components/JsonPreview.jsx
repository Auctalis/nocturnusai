import React from 'react';

const JsonPreview = ({ data }) => {
  const jsonString = typeof data === 'string' ? data : JSON.stringify(data, null, 2);

  const highlight = (json) => {
    if (!json) return '';
    
    // Regex to match keys, strings, numbers, booleans, nulls
    return json.replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, function (match) {
      let cls = 'number';
      let color = '#f08d49'; // Default Number format
      
      if (/^"/.test(match)) {
         if (/:$/.test(match)) {
            cls = 'key';
            color = '#88c999'; // Key Color (Greenish)
         } else {
            cls = 'string';
            color = '#ce9178'; // String Color (Orange/Redish)
         }
      } else if (/true|false/.test(match)) {
         cls = 'boolean';
         color = '#569cd6'; // Boolean (Blue)
      } else if (/null/.test(match)) {
         cls = 'null';
         color = '#569cd6';
      }
      
      // Return span with color
      return `<span style="color: ${color}">${match}</span>`;
    });
  };

  try {
      // Ensure we are working with valid JSON for pretty printing if it's a string
      const obj = typeof data === 'string' ? JSON.parse(data || '{}') : data;
      const formatted = JSON.stringify(obj, null, 2);
       
      return (
        <pre 
            style={{ 
                margin: 0,
                fontFamily: 'var(--font-mono)', 
                fontSize: '13px', 
                overflow: 'auto',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
                color: '#d4d4d4',
                lineHeight: '1.5'
            }}
            dangerouslySetInnerHTML={{ __html: highlight(formatted) }}
        />
      );
  } catch (e) {
      // Fallback for invalid JSON being typed
      return <pre style={{ color: '#d4d4d4' }}>{data}</pre>;
  }
};

export default JsonPreview;
