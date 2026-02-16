import { useState } from 'react'
import { Clipboard, Check } from 'lucide-react'
import { toast } from 'sonner'
import type { OperationMode } from '@/lib/types'

interface CopyAsCodeProps {
  mode: OperationMode
  inputData: string
  database: string
  tenantId?: string
}

interface EndpointMapping {
  endpoint: string
  pythonMethod: string
  tsMethod: string
}

function getEndpointMapping(mode: OperationMode): EndpointMapping {
  switch (mode) {
    case 'infer':
      return { endpoint: '/infer', pythonMethod: 'infer', tsMethod: 'infer' }
    case 'assert_fact':
      return { endpoint: '/assert/fact', pythonMethod: 'assert_fact', tsMethod: 'assertFact' }
    case 'assert_rule':
      return { endpoint: '/assert/rule', pythonMethod: 'assert_rule', tsMethod: 'assertRule' }
    case 'retract':
      return { endpoint: '/retract', pythonMethod: 'retract', tsMethod: 'retract' }
    case 'assert_template':
      return { endpoint: '/assert/template', pythonMethod: 'assert_template', tsMethod: 'assertTemplate' }
    case 'execute':
      return { endpoint: '/execute', pythonMethod: 'execute', tsMethod: 'execute' }
    case 'inspect':
      return { endpoint: '', pythonMethod: '', tsMethod: '' }
    case 'synthesize':
      return { endpoint: '', pythonMethod: '', tsMethod: '' }
    default:
      return { endpoint: '', pythonMethod: '', tsMethod: '' }
  }
}

function generateCurl(mode: OperationMode, inputData: string, database: string, tenantId?: string): string {
  const { endpoint } = getEndpointMapping(mode)
  if (!endpoint) {
    return '# This operation is client-side only and has no corresponding API call.'
  }

  const tenantHeader = tenantId ? `\n  -H "X-Tenant-ID: ${tenantId}" \\` : ''
  const escaped = inputData.replace(/'/g, "'\\''")

  return `curl -X POST http://localhost:9300${endpoint} \\
  -H "Content-Type: application/json" \\
  -H "X-Database: ${database}" \\${tenantHeader}
  -d '${escaped}'`
}

function generatePython(mode: OperationMode, inputData: string, database: string): string {
  const { pythonMethod } = getEndpointMapping(mode)
  if (!pythonMethod) {
    return '# This operation is client-side only and has no corresponding SDK method.'
  }

  let args: string
  try {
    const parsed = JSON.parse(inputData)
    args = JSON.stringify(parsed, null, 2)
      .split('\n')
      .map((line, i) => (i === 0 ? line : '    ' + line))
      .join('\n')
  } catch {
    args = `"${inputData}"`
  }

  return `from axiombase import SyncAxiomBaseClient

client = SyncAxiomBaseClient(base_url="http://localhost:9300", database="${database}")
result = client.${pythonMethod}(${args})
print(result)`
}

function generateTypeScript(mode: OperationMode, inputData: string, database: string): string {
  const { tsMethod } = getEndpointMapping(mode)
  if (!tsMethod) {
    return '// This operation is client-side only and has no corresponding SDK method.'
  }

  let args: string
  try {
    const parsed = JSON.parse(inputData)
    args = JSON.stringify(parsed, null, 2)
  } catch {
    args = `"${inputData}"`
  }

  return `import { AxiomBaseClient } from '@axiombase/sdk'

const client = new AxiomBaseClient({ baseUrl: 'http://localhost:9300', database: '${database}' })
const result = await client.${tsMethod}(${args})
console.log(result)`
}

type CodeFormat = 'curl' | 'python' | 'typescript'

export default function CopyAsCode({ mode, inputData, database, tenantId }: CopyAsCodeProps) {
  const [copiedFormat, setCopiedFormat] = useState<CodeFormat | null>(null)
  const [openPreview, setOpenPreview] = useState<CodeFormat | null>(null)

  const generators: Record<CodeFormat, () => string> = {
    curl: () => generateCurl(mode, inputData, database, tenantId),
    python: () => generatePython(mode, inputData, database),
    typescript: () => generateTypeScript(mode, inputData, database),
  }

  const labels: Record<CodeFormat, string> = {
    curl: 'cURL',
    python: 'Python',
    typescript: 'TypeScript',
  }

  const handleCopy = async (format: CodeFormat) => {
    const code = generators[format]()
    try {
      await navigator.clipboard.writeText(code)
      setCopiedFormat(format)
      toast.success('Copied!')
      setTimeout(() => setCopiedFormat(null), 2000)
    } catch {
      toast.error('Failed to copy to clipboard')
    }
  }

  const handleTogglePreview = (format: CodeFormat) => {
    setOpenPreview(openPreview === format ? null : format)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
        {(Object.keys(labels) as CodeFormat[]).map((format) => (
          <button
            key={format}
            className="btn btn-ghost btn-sm"
            onClick={() => void handleCopy(format)}
            title={`Copy as ${labels[format]}`}
          >
            {copiedFormat === format ? <Check size={12} /> : <Clipboard size={12} />}
            {labels[format]}
          </button>
        ))}
      </div>
      {(Object.keys(labels) as CodeFormat[]).map((format) => (
        <details
          key={format}
          open={openPreview === format}
          onToggle={() => handleTogglePreview(format)}
          style={{ fontSize: 'var(--text-xs)' }}
        >
          <summary
            style={{
              cursor: 'pointer',
              color: 'var(--text-muted)',
              fontSize: 'var(--text-xs)',
              userSelect: 'none',
            }}
          >
            Preview {labels[format]}
          </summary>
          <pre className="logic-json-block" style={{ marginTop: 'var(--space-xs)' }}>
            {generators[format]()}
          </pre>
        </details>
      ))}
    </div>
  )
}
