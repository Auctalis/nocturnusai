import type { AtomResponse, Database, FactRequest, RuleRequest, TemplateRequest } from './types'

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
