import { useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { FileText, Copy, ExternalLink } from 'lucide-react'
import { toast } from 'sonner'
import { useDatabases, useTenants, useDatabaseActions } from '@/lib/hooks'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:9300'

interface CodeBlockProps {
  code: string
  label?: string
}

function CodeBlock({ code, label }: CodeBlockProps) {
  const handleCopy = useCallback(() => {
    void navigator.clipboard.writeText(code)
    toast.success('Copied')
  }, [code])

  return (
    <div style={{ position: 'relative', marginBottom: 'var(--space-sm)' }}>
      {label && (
        <div style={{
          fontSize: 'var(--text-xs)',
          fontWeight: 600,
          color: 'var(--text-muted)',
          marginBottom: 'var(--space-xs)',
          textTransform: 'uppercase',
          letterSpacing: '0.05em',
        }}>
          {label}
        </div>
      )}
      <div className="logic-json-block" style={{ position: 'relative', paddingRight: '40px' }}>
        {code}
        <button
          className="btn btn-ghost btn-icon"
          onClick={handleCopy}
          title="Copy to clipboard"
          style={{
            position: 'absolute',
            top: 'var(--space-xs)',
            right: 'var(--space-xs)',
            opacity: 0.6,
          }}
        >
          <Copy size={14} />
        </button>
      </div>
    </div>
  )
}

interface EndpointSectionProps {
  title: string
  method: string
  path: string
  curl: string
  python: string
  typescript: string
}

function EndpointSection({ title, method, path, curl, python, typescript }: EndpointSectionProps) {
  return (
    <details className="card" style={{ marginBottom: 'var(--space-md)' }}>
      <summary className="card-header" style={{ cursor: 'pointer', userSelect: 'none' }}>
        <span style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
          <FileText size={14} />
          {title}
        </span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
          <span className="badge badge-info">{method}</span>
          <code style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)' }}>{path}</code>
        </span>
      </summary>
      <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
        <CodeBlock label="cURL" code={curl} />
        <CodeBlock label="Python SDK" code={python} />
        <CodeBlock label="TypeScript SDK" code={typescript} />
      </div>
    </details>
  )
}

export default function ApiReference() {
  const { dbName } = useParams<{ dbName: string }>()
  const navigate = useNavigate()

  const { databases, refresh: refreshDbs } = useDatabases()
  const currentDb = databases.find((d) => d.name === dbName)
  const isMultiTenant = currentDb?.isMultiTenant ?? false

  const { tenants, currentTenant, setCurrentTenant, refresh: refreshTenants } = useTenants(
    dbName,
    isMultiTenant,
  )

  const { sidebarActions, modalState } = useDatabaseActions(
    dbName,
    refreshDbs,
    refreshTenants,
    setCurrentTenant,
    { onDeletedCurrentDb: () => navigate('/') },
  )

  const db = dbName ?? 'default'

  return (
    <>
      <Layout
        sidebar={
          <Sidebar
            databases={databases}
            currentDb={dbName}
            onSelectDb={(name) => navigate(`/db/${name}/api`)}
            {...sidebarActions}
            tenants={tenants}
            currentTenant={currentTenant}
            onSelectTenant={setCurrentTenant}
            isMultiTenant={isMultiTenant}
          />
        }
        toolbar={
          <div className="toolbar-row">
            <div className="toolbar-left">
              <h1 style={{ fontSize: 'var(--text-lg)', fontWeight: 600, margin: 0 }}>
                API Reference
              </h1>
              {dbName && (
                <span className="badge badge-info">{dbName}</span>
              )}
              {isMultiTenant && currentTenant && (
                <span className="badge badge-success">{currentTenant}</span>
              )}
            </div>
            <div className="toolbar-right">
              <a
                href={`${BASE_URL}/llm.txt`}
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-ghost"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--space-xs)', textDecoration: 'none' }}
              >
                <ExternalLink size={14} />
                <span style={{ fontSize: 'var(--text-xs)' }}>llm.txt</span>
              </a>
            </div>
          </div>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-md)', maxWidth: 900, overflow: 'auto', height: '100%', paddingBottom: 'var(--space-lg)' }}>
          {/* Introduction */}
          <div className="card">
            <div className="card-body">
              <p style={{ margin: 0, fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                AxiomBase provides HTTP REST, Python SDK, TypeScript SDK, and MCP protocol access.
                All endpoints accept JSON and require the <code>X-API-Key</code> header for authentication.
                Use the <code>X-Database</code> header to select a database and <code>X-Tenant-ID</code> for multi-tenant databases.
              </p>
              <div style={{ marginTop: 'var(--space-sm)', display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                <span className="text-muted text-xs">Base URL:</span>
                <code style={{ fontSize: 'var(--text-xs)', color: 'var(--text-primary)' }}>{BASE_URL}</code>
              </div>
            </div>
          </div>

          {/* Assert Facts */}
          <EndpointSection
            title="Assert Facts"
            method="POST"
            path="/assert/fact"
            curl={`curl -X POST ${BASE_URL}/assert/fact \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: YOUR_KEY" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "Parent",
    "args": ["Zeus", "Ares"],
    "truthVal": true
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(
    base_url="${BASE_URL}",
    api_key="YOUR_KEY",
    database="${db}"
)

result = client.assert_fact(
    predicate="Parent",
    args=["Zeus", "Ares"],
    truth_val=True
)
print(result)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({
  baseUrl: '${BASE_URL}',
  apiKey: 'YOUR_KEY',
  database: '${db}'
})

const result = await client.assertFact({
  predicate: 'Parent',
  args: ['Zeus', 'Ares'],
  truthVal: true
})
console.log(result)`}
          />

          {/* Assert Rules */}
          <EndpointSection
            title="Assert Rules"
            method="POST"
            path="/assert/rule"
            curl={`curl -X POST ${BASE_URL}/assert/rule \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: YOUR_KEY" \\
  -H "X-Database: ${db}" \\
  -d '{
    "head": {
      "predicate": "GrandParent",
      "args": ["?x", "?z"]
    },
    "body": [
      { "predicate": "Parent", "args": ["?x", "?y"] },
      { "predicate": "Parent", "args": ["?y", "?z"] }
    ]
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(
    base_url="${BASE_URL}",
    api_key="YOUR_KEY",
    database="${db}"
)

result = client.assert_rule(
    head={"predicate": "GrandParent", "args": ["?x", "?z"]},
    body=[
        {"predicate": "Parent", "args": ["?x", "?y"]},
        {"predicate": "Parent", "args": ["?y", "?z"]}
    ]
)
print(result)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({
  baseUrl: '${BASE_URL}',
  apiKey: 'YOUR_KEY',
  database: '${db}'
})

const result = await client.assertRule({
  head: { predicate: 'GrandParent', args: ['?x', '?z'] },
  body: [
    { predicate: 'Parent', args: ['?x', '?y'] },
    { predicate: 'Parent', args: ['?y', '?z'] }
  ]
})
console.log(result)`}
          />

          {/* Query / Infer */}
          <EndpointSection
            title="Query / Infer"
            method="POST"
            path="/infer"
            curl={`curl -X POST ${BASE_URL}/infer \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: YOUR_KEY" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "GrandParent",
    "args": ["?x", "?y"]
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(
    base_url="${BASE_URL}",
    api_key="YOUR_KEY",
    database="${db}"
)

results = client.infer(
    predicate="GrandParent",
    args=["?x", "?y"]
)
for atom in results:
    print(f"{atom.predicate}({', '.join(atom.args)})")`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({
  baseUrl: '${BASE_URL}',
  apiKey: 'YOUR_KEY',
  database: '${db}'
})

const results = await client.infer({
  predicate: 'GrandParent',
  args: ['?x', '?y']
})
results.forEach(atom =>
  console.log(\`\${atom.predicate}(\${atom.args.join(', ')})\`)
)`}
          />

          {/* Retract */}
          <EndpointSection
            title="Retract"
            method="POST"
            path="/retract"
            curl={`curl -X POST ${BASE_URL}/retract \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: YOUR_KEY" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "Parent",
    "args": ["Zeus", "Ares"]
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(
    base_url="${BASE_URL}",
    api_key="YOUR_KEY",
    database="${db}"
)

result = client.retract(
    predicate="Parent",
    args=["Zeus", "Ares"]
)
print(result)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({
  baseUrl: '${BASE_URL}',
  apiKey: 'YOUR_KEY',
  database: '${db}'
})

const result = await client.retract({
  predicate: 'Parent',
  args: ['Zeus', 'Ares']
})
console.log(result)`}
          />

          {/* Memory: Context Window */}
          <EndpointSection
            title="Memory: Context Window"
            method="POST"
            path="/memory/context"
            curl={`curl -X POST ${BASE_URL}/memory/context \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: YOUR_KEY" \\
  -H "X-Database: ${db}" \\
  -d '{
    "maxFacts": 50
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(
    base_url="${BASE_URL}",
    api_key="YOUR_KEY",
    database="${db}"
)

context = client.get_context_window(max_facts=50)
for scored in context.facts:
    atom = scored.atom
    print(f"[{scored.salience:.2f}] {atom.predicate}({', '.join(atom.args)})")`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({
  baseUrl: '${BASE_URL}',
  apiKey: 'YOUR_KEY',
  database: '${db}'
})

const context = await client.getContextWindow({ maxFacts: 50 })
context.facts.forEach(({ atom, salience }) =>
  console.log(\`[\${salience.toFixed(2)}] \${atom.predicate}(\${atom.args.join(', ')})\`)
)`}
          />

          {/* Memory: Temporal Query */}
          <EndpointSection
            title="Memory: Temporal Query"
            method="POST"
            path="/memory/query/temporal"
            curl={`curl -X POST ${BASE_URL}/memory/query/temporal \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: YOUR_KEY" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "Parent",
    "from": "2024-01-01T00:00:00Z",
    "to": "2025-01-01T00:00:00Z"
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(
    base_url="${BASE_URL}",
    api_key="YOUR_KEY",
    database="${db}"
)

results = client.query_temporal(
    predicate="Parent",
    from_time="2024-01-01T00:00:00Z",
    to_time="2025-01-01T00:00:00Z"
)
for fact in results:
    print(fact)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({
  baseUrl: '${BASE_URL}',
  apiKey: 'YOUR_KEY',
  database: '${db}'
})

const results = await client.queryTemporal({
  predicate: 'Parent',
  from: '2024-01-01T00:00:00Z',
  to: '2025-01-01T00:00:00Z'
})
console.log(results)`}
          />

          {/* Execute DSL */}
          <EndpointSection
            title="Execute DSL"
            method="POST"
            path="/execute"
            curl={`curl -X POST ${BASE_URL}/execute \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: YOUR_KEY" \\
  -H "X-Database: ${db}" \\
  -d '{
    "command": "assert Parent(Zeus, Ares)."
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(
    base_url="${BASE_URL}",
    api_key="YOUR_KEY",
    database="${db}"
)

result = client.execute("assert Parent(Zeus, Ares).")
print(result)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({
  baseUrl: '${BASE_URL}',
  apiKey: 'YOUR_KEY',
  database: '${db}'
})

const result = await client.execute('assert Parent(Zeus, Ares).')
console.log(result)`}
          />
        </div>
      </Layout>

      <DatabaseModals {...modalState} />
    </>
  )
}
