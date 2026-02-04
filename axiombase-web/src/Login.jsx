import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';

function Login() {
  const [apiKey, setApiKey] = useState('');
  const navigate = useNavigate();

  const handleSubmit = (e) => {
    e.preventDefault();
    if (apiKey.trim()) {
      localStorage.setItem('api_key', apiKey.trim());
      navigate('/');
    }
  };

  return (
    <div className="auth-container">
      <div className="card auth-box">
        <h2>AxiomBase Login</h2>
        <form onSubmit={handleSubmit}>
          <div style={{ textAlign: 'left', marginBottom: '1rem' }}>
            <label style={{ display: 'block', marginBottom: '5px' }}>API Key</label>
            <input 
              type="password" 
              value={apiKey} 
              onChange={(e) => setApiKey(e.target.value)} 
              placeholder="Enter your API Key"
              required
            />
          </div>
          <button type="submit" className="btn" style={{ width: '100%' }}>Login</button>
        </form>
      </div>
    </div>
  );
}

export default Login;
