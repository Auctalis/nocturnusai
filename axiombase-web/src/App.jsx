import React, { useState } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, Link, useNavigate } from 'react-router-dom';
import Login from './Login';
import Dashboard from './Dashboard';
import QueryConsole from './QueryConsole';

function Layout({ children }) {
  const navigate = useNavigate();
  const handleLogout = () => {
    localStorage.removeItem('api_key');
    navigate('/login');
  };

  return (
    <>
      <header className="app-header" style={{ backgroundColor: 'var(--masters-green)', borderBottom: '4px solid var(--masters-yellow)' }}>
        <Link to="/" className="app-logo" style={{ fontFamily: 'var(--font-serif)', letterSpacing: '0.05em' }}>AxiomBase</Link>
        <nav className="app-nav">
          <Link to="/" style={{ color: 'white', fontWeight: 600 }}>Databases</Link>
          <a href="#" onClick={handleLogout} style={{ color: 'rgba(255,255,255,0.8)' }}>Logout</a>
        </nav>
      </header>
      <main className="container">
        {children}
      </main>
    </>
  );
}

function PrivateRoute({ children }) {
  const apiKey = localStorage.getItem('api_key');
  return apiKey ? children : <Navigate to="/login" />;
}

function App() {
  return (
    <Router>
      <Routes>
        <Route path="/login" element={<Login />} />
        
        <Route path="/" element={
          <PrivateRoute>
            <Layout>
              <Dashboard />
            </Layout>
          </PrivateRoute>
        } />
        
        <Route path="/db/:dbName" element={
          <PrivateRoute>
             <QueryConsole />
          </PrivateRoute>
        } />
      </Routes>
    </Router>
  );
}

export default App;
