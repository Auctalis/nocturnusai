import { useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { FileText, Copy, ExternalLink } from 'lucide-react'
import { toast } from 'sonner'
import { useDatabases, useTenants, useDatabaseActions } from '@/lib/hooks'
import { BASE_URL } from '@/lib/api'
import Layout from '@/components/Layout'
import Sidebar from '@/components/Sidebar'
import DatabaseModals from '@/components/DatabaseModals'

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
  description?: string
  curl: string
  python: string
  typescript: string
}

function EndpointSection({ title, method, path, description, curl, python, typescript }: EndpointSectionProps) {
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
        {description && (
          <p style={{ margin: 0, fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>{description}</p>
        )}
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
                Four verbs: <strong>Tell</strong>, <strong>Ask</strong>, <strong>Teach</strong>, <strong>Forget</strong>.
                Plus memory management for context, recall, compression, and cleanup.
                All endpoints accept JSON. Use <code>X-Database</code> header to select a database and <code>X-Tenant-ID</code> for multi-tenant databases.
              </p>
              <div style={{ marginTop: 'var(--space-sm)', display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                <span className="text-muted text-xs">Base URL:</span>
                <code style={{ fontSize: 'var(--text-xs)', color: 'var(--text-primary)' }}>{BASE_URL}</code>
              </div>
            </div>
          </div>

          <h2 style={{ fontSize: 'var(--text-md)', fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>Core API</h2>

          {/* Tell */}
          <EndpointSection
            title="Tell — Store Knowledge"
            method="POST"
            path="/tell"
            description="Tell AxiomBase something it should know. Supports auto-expiration via ttl or validUntil."
            curl={`curl -X POST ${BASE_URL}/tell \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "parent",
    "args": ["alice", "bob"]
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient("${BASE_URL}", database="${db}")

client.tell("parent", ["alice", "bob"])

# With auto-expiration (1 hour TTL):
client.tell("status", ["task_1", "active"], ttl=3600000)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk';

const ab = new AxiomBaseClient({ baseUrl: '${BASE_URL}', database: '${db}' });

await ab.tell('parent', ['alice', 'bob']);

// With auto-expiration:
await ab.tell('status', ['task_1', 'active'], { ttl: 3600000 });`}
          />

          {/* Teach */}
          <EndpointSection
            title="Teach — Define Rules"
            method="POST"
            path="/teach"
            description="Teach AxiomBase an if-then rule. Use ?prefix for variables. When all conditions (body) are true, the conclusion (head) is automatically derivable."
            curl={`curl -X POST ${BASE_URL}/teach \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{
    "head": {
      "predicate": "grandparent",
      "args": ["?x", "?z"]
    },
    "body": [
      { "predicate": "parent", "args": ["?x", "?y"] },
      { "predicate": "parent", "args": ["?y", "?z"] }
    ]
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient("${BASE_URL}", database="${db}")

client.teach(
    head={"predicate": "grandparent", "args": ["?x", "?z"]},
    body=[
        {"predicate": "parent", "args": ["?x", "?y"]},
        {"predicate": "parent", "args": ["?y", "?z"]}
    ]
)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk';

const ab = new AxiomBaseClient({ baseUrl: '${BASE_URL}', database: '${db}' });

await ab.teach(
  { predicate: 'grandparent', args: ['?x', '?z'] },
  [
    { predicate: 'parent', args: ['?x', '?y'] },
    { predicate: 'parent', args: ['?y', '?z'] },
  ]
);`}
          />

          {/* Ask */}
          <EndpointSection
            title="Ask — Query with Reasoning"
            method="POST"
            path="/ask"
            description="Ask AxiomBase a question. Finds all provable answers by applying rules and matching facts. Use ?prefix for unknowns."
            curl={`curl -X POST ${BASE_URL}/ask \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "grandparent",
    "args": ["?who", "?of"]
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient("${BASE_URL}", database="${db}")

results = client.ask("grandparent", ["?who", "?of"])
for atom in results:
    print(f"{atom.predicate}({', '.join(atom.args)})")

# With proof chain:
proofs = client.ask("grandparent", ["?who", "charlie"], with_proof=True)`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk';

const ab = new AxiomBaseClient({ baseUrl: '${BASE_URL}', database: '${db}' });

const results = await ab.ask('grandparent', ['?who', '?of']);
results.forEach(atom =>
  console.log(\`\${atom.predicate}(\${atom.args.join(', ')})\`)
);`}
          />

          {/* Forget */}
          <EndpointSection
            title="Forget — Remove Knowledge"
            method="POST"
            path="/forget"
            description="Make AxiomBase forget a fact. Any knowledge derived from it is also automatically forgotten (cascading retraction)."
            curl={`curl -X POST ${BASE_URL}/forget \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "parent",
    "args": ["alice", "bob"]
  }'`}
            python={`from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient("${BASE_URL}", database="${db}")

client.forget("parent", ["alice", "bob"])`}
            typescript={`import { AxiomBaseClient } from '@axiombase/sdk';

const ab = new AxiomBaseClient({ baseUrl: '${BASE_URL}', database: '${db}' });

await ab.forget('parent', ['alice', 'bob']);`}
          />

          <h2 style={{ fontSize: 'var(--text-md)', fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>Memory</h2>

          {/* Memory: Context */}
          <EndpointSection
            title="Get Context — Most Relevant Knowledge"
            method="POST"
            path="/memory/context"
            description="Get the most relevant knowledge for the current reasoning step, ranked by recency, frequency, and priority."
            curl={`curl -X POST ${BASE_URL}/memory/context \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{ "maxFacts": 50 }'`}
            python={`context = client.get_context_window(max_facts=50)
for scored in context.facts:
    atom = scored.atom
    print(f"[{scored.salience:.2f}] {atom.predicate}({', '.join(atom.args)})")`}
            typescript={`const context = await ab.getContextWindow({ maxFacts: 50 });
context.facts.forEach(({ atom, salience }) =>
  console.log(\`[\${salience.toFixed(2)}] \${atom.predicate}(\${atom.args.join(', ')})\`)
);`}
          />

          {/* Memory: Recall */}
          <EndpointSection
            title="Recall — Time-Travel Queries"
            method="POST"
            path="/memory/recall"
            description="Recall what was known at a specific point in time. Filters by validFrom/validUntil/ttl."
            curl={`curl -X POST ${BASE_URL}/memory/recall \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{
    "predicate": "location",
    "args": ["alice", "?where"],
    "timestamp": 1700000000000
  }'`}
            python={`import time
one_hour_ago = int((time.time() - 3600) * 1000)
results = client.recall("location", ["alice", "?where"], timestamp=one_hour_ago)`}
            typescript={`const oneHourAgo = Date.now() - 3600000;
const results = await ab.recall('location', ['alice', '?where'], { timestamp: oneHourAgo });`}
          />

          {/* Memory: Compress */}
          <EndpointSection
            title="Compress — Consolidate Patterns"
            method="POST"
            path="/memory/compress"
            description="Compress repeated episodic patterns into semantic summary facts. Helps manage memory growth."
            curl={`curl -X POST ${BASE_URL}/memory/compress \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{}'`}
            python={`result = client.compress()
print(f"Compressed {result.facts_consolidated} facts into {len(result.new_facts)} summaries")`}
            typescript={`const result = await ab.compress();
console.log(\`Compressed \${result.factsConsolidated} facts\`);`}
          />

          {/* Memory: Cleanup */}
          <EndpointSection
            title="Cleanup — Expire Stale Knowledge"
            method="POST"
            path="/memory/cleanup"
            description="Expire facts past their TTL and evict low-relevance facts. Run periodically in long-running sessions."
            curl={`curl -X POST ${BASE_URL}/memory/cleanup \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{ "threshold": 0.05 }'`}
            python={`result = client.cleanup(threshold=0.05)
print(f"Expired: {result.expired_count}, Evicted: {result.evicted_count}")`}
            typescript={`const result = await ab.cleanup({ threshold: 0.05 });
console.log(\`Expired: \${result.expiredCount}, Evicted: \${result.evictedCount}\`);`}
          />

          <h2 style={{ fontSize: 'var(--text-md)', fontWeight: 600, margin: 0, color: 'var(--text-primary)' }}>Advanced</h2>

          {/* Execute DSL */}
          <EndpointSection
            title="Execute — Raw DSL"
            method="POST"
            path="/execute"
            description="Execute raw Logiql DSL commands for advanced users."
            curl={`curl -X POST ${BASE_URL}/execute \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${db}" \\
  -d '{ "command": "ASSERT parent(alice, bob)." }'`}
            python={`result = client.execute("ASSERT parent(alice, bob).")`}
            typescript={`const result = await ab.execute('ASSERT parent(alice, bob).');`}
          />
        </div>
      </Layout>

      <DatabaseModals {...modalState} />
    </>
  )
}
