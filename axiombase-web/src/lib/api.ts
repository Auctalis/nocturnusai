import type { AtomResponse, AskResponse, TellResponse, RecallResponse, ContextWindow, Database, FactRequest, RuleRequest, TemplateRequest } from './types'

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:9300'

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function buildHeaders(apiKey: string, database?: string, tenantId?: string): HeadersInit {
  const headers: Record<string, string> = {
    'X-API-Key': apiKey,
    'Content-Type': 'application/json',
  }
  if (database) headers['X-Database'] = database
  if (tenantId) headers['X-Tenant-ID'] = tenantId
  return headers
}

async function request<T>(
  path: string,
  options: RequestInit & { apiKey: string; database?: string; tenantId?: string; parseJson?: boolean },
): Promise<T> {
  const { apiKey, database, tenantId, parseJson = true, ...init } = options
  const headers = buildHeaders(apiKey, database, tenantId)

  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: { ...headers, ...(init.headers as Record<string, string> | undefined) },
  })

  if (!response.ok) {
    const text = await response.text()
    throw new ApiError(text || response.statusText, response.status)
  }

  if (parseJson) {
    const text = await response.text()
    try {
      return JSON.parse(text) as T
    } catch {
      return text as T
    }
  }

  return (await response.text()) as T
}

// Database operations
export async function listDatabases(apiKey: string): Promise<Database[]> {
  return request<Database[]>('/admin/databases', { apiKey })
}

export async function createDatabase(apiKey: string, name: string, isMultiTenant: boolean): Promise<string> {
  return request<string>('/admin/databases', {
    apiKey,
    method: 'POST',
    body: JSON.stringify({ name, isMultiTenant }),
    parseJson: false,
  })
}

export async function deleteDatabase(apiKey: string, name: string): Promise<string> {
  return request<string>(`/admin/databases/${encodeURIComponent(name)}`, {
    apiKey,
    method: 'DELETE',
    parseJson: false,
  })
}

export async function nukeDatabase(apiKey: string, name: string): Promise<string> {
  return request<string>(`/admin/databases/${encodeURIComponent(name)}/nuke`, {
    apiKey,
    method: 'POST',
    parseJson: false,
  })
}

// Tenant operations
export async function listTenants(apiKey: string, dbName: string): Promise<string[]> {
  const result = await request<string[]>(`/admin/databases/${encodeURIComponent(dbName)}/tenants`, { apiKey })
  return Array.isArray(result) ? result : []
}

export async function createTenant(apiKey: string, dbName: string, tenantId: string): Promise<string> {
  return request<string>(`/admin/databases/${encodeURIComponent(dbName)}/tenants`, {
    apiKey,
    method: 'POST',
    body: JSON.stringify({ tenantId }),
    parseJson: false,
  })
}

export async function nukeTenant(apiKey: string, dbName: string, tenantId: string): Promise<string> {
  return request<string>(
    `/admin/databases/${encodeURIComponent(dbName)}/tenants/${encodeURIComponent(tenantId)}/nuke`,
    {
      apiKey,
      method: 'POST',
      parseJson: false,
    },
  )
}

// Knowledge base operations
export async function listFacts(
  apiKey: string,
  dbName: string,
  tenantId?: string,
  scope?: string,
): Promise<AtomResponse[]> {
  const params = scope ? `?scope=${encodeURIComponent(scope)}` : ''
  return request<AtomResponse[]>(`/admin/databases/${encodeURIComponent(dbName)}/facts${params}`, {
    apiKey,
    tenantId,
  })
}

export async function listRules(
  apiKey: string,
  dbName: string,
  tenantId?: string,
  scope?: string,
): Promise<string[]> {
  const params = scope ? `?scope=${encodeURIComponent(scope)}` : ''
  return request<string[]>(`/admin/databases/${encodeURIComponent(dbName)}/rules${params}`, {
    apiKey,
    tenantId,
  })
}

// Logic operations
export async function infer(
  apiKey: string,
  database: string,
  body: FactRequest,
  tenantId?: string,
): Promise<AtomResponse[]> {
  return request<AtomResponse[]>('/infer', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function assertFact(
  apiKey: string,
  database: string,
  body: FactRequest,
  tenantId?: string,
): Promise<string> {
  return request<string>('/assert/fact', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
    parseJson: false,
  })
}

export async function assertRule(
  apiKey: string,
  database: string,
  body: RuleRequest,
  tenantId?: string,
): Promise<string> {
  return request<string>('/assert/rule', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
    parseJson: false,
  })
}

export async function assertTemplate(
  apiKey: string,
  database: string,
  body: TemplateRequest,
  tenantId?: string,
): Promise<string> {
  return request<string>('/assert/template', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
    parseJson: false,
  })
}

export async function retractFact(
  apiKey: string,
  database: string,
  body: FactRequest,
  tenantId?: string,
): Promise<string> {
  return request<string>('/retract', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
    parseJson: false,
  })
}

export async function execute(
  apiKey: string,
  database: string,
  command: string,
  tenantId?: string,
): Promise<string> {
  return request<string>('/execute', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify({ command }),
    parseJson: false,
  })
}

// Context operations
export async function contextAsk(
  apiKey: string,
  database: string,
  question: string,
  tenantId?: string,
  scope?: string,
): Promise<AskResponse> {
  const body: Record<string, string> = { question }
  if (scope) body.scope = scope
  return request<AskResponse>('/memory/query/salient', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function contextTell(
  apiKey: string,
  database: string,
  text: string,
  tenantId?: string,
  scope?: string,
): Promise<TellResponse> {
  const body: Record<string, string> = { text }
  if (scope) body.scope = scope
  return request<TellResponse>('/assert/fact', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
    parseJson: false,
  }).then((raw) => {
    // Wrap the string response into TellResponse shape
    const msg = typeof raw === 'string' ? raw : String(raw)
    return { count: 1, understood: [msg], rules: [], rulesCount: 0 }
  })
}

export async function contextRecall(
  apiKey: string,
  database: string,
  params: { topic?: string; predicate?: string },
  tenantId?: string,
  pagination?: { limit: number; offset: number },
  scope?: string,
): Promise<RecallResponse> {
  const body: Record<string, unknown> = { ...params }
  if (scope) body.scope = scope
  if (pagination) {
    body.limit = pagination.limit
    body.offset = pagination.offset
  }
  return request<RecallResponse>('/memory/query/temporal', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
  }).then((raw) => {
    // Normalize: if the server returns an array of atoms, wrap it
    if (Array.isArray(raw)) {
      const atoms = raw as AtomResponse[]
      return {
        facts: atoms.map((a) => `${a.negated ? 'NOT ' : ''}${a.predicate}(${a.args.join(', ')})`),
        count: atoms.length,
      }
    }
    return raw
  })
}

export async function getContextWindow(
  apiKey: string,
  database: string,
  tenantId?: string,
  maxFacts?: number,
): Promise<ContextWindow> {
  const body: Record<string, unknown> = {}
  if (maxFacts) body.maxFacts = maxFacts
  return request<ContextWindow>('/memory/context', {
    apiKey,
    database,
    tenantId,
    method: 'POST',
    body: JSON.stringify(body),
  })
}

// Health
export async function getHealth(apiKey: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>('/health', { apiKey })
}
