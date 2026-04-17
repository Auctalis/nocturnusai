'use client';

/**
 * app/page.tsx — minimal chat UI powered by useChat.
 *
 * Nothing NocturnusAI-specific here — the compression happens in
 * app/api/chat/route.ts. This page is just a standard Vercel AI SDK
 * chat UI with a persistent sessionId.
 */
import { useChat } from 'ai/react';
import { useMemo } from 'react';

export default function Chat() {
  const sessionId = useMemo(
    () => `session-${Math.random().toString(36).slice(2, 10)}`,
    [],
  );

  const { messages, input, handleInputChange, handleSubmit } = useChat({
    api: '/api/chat',
    body: { sessionId },
  });

  return (
    <main
      style={{
        maxWidth: 720,
        margin: '0 auto',
        padding: '3rem 1.5rem',
        fontFamily: 'system-ui, sans-serif',
      }}
    >
      <h1 style={{ fontSize: '1.4rem', marginBottom: '0.4rem' }}>
        Chat — powered by Nocturnus
      </h1>
      <p
        style={{
          fontSize: '0.82rem',
          color: '#666',
          marginBottom: '1.5rem',
        }}
      >
        Session: <code>{sessionId}</code>
      </p>

      <div style={{ marginBottom: '1.5rem' }}>
        {messages.map((m) => (
          <div
            key={m.id}
            style={{
              padding: '0.8rem 1rem',
              marginBottom: '0.6rem',
              borderRadius: 8,
              background: m.role === 'user' ? '#f0f4ff' : '#f6f6f6',
              whiteSpace: 'pre-wrap',
            }}
          >
            <strong style={{ fontSize: '0.78rem', color: '#666' }}>
              {m.role === 'user' ? 'You' : 'Assistant'}
            </strong>
            <div style={{ marginTop: '0.3rem' }}>{m.content}</div>
          </div>
        ))}
      </div>

      <form onSubmit={handleSubmit} style={{ display: 'flex', gap: '0.5rem' }}>
        <input
          value={input}
          onChange={handleInputChange}
          placeholder="Ask anything..."
          style={{
            flex: 1,
            padding: '0.7rem 1rem',
            border: '1px solid #ddd',
            borderRadius: 6,
            fontSize: '1rem',
          }}
        />
        <button
          type="submit"
          style={{
            padding: '0.7rem 1.4rem',
            background: '#111',
            color: '#fff',
            border: 0,
            borderRadius: 6,
            cursor: 'pointer',
          }}
        >
          Send
        </button>
      </form>
    </main>
  );
}
