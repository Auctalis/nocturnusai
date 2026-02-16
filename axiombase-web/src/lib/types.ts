// Domain types matching server DTOs

export interface Database {
  name: string
  isMultiTenant: boolean
}

export interface FactRequest {
  predicate: string
  args: string[]
  truthVal?: boolean
  negated?: boolean
  scope?: string | null
  metadata?: Record<string, unknown>
}

export interface AtomDto {
  predicate: string
  args: string[]
  negated?: boolean
  scope?: string | null
  metadata?: Record<string, unknown>
}

export interface AtomResponse {
  predicate: string
  args: string[]
  negated: boolean
  scope: string | null
  metadata: Record<string, unknown>
}

export interface RuleRequest {
  head: AtomDto
  body: AtomDto[]
  scope?: string | null
}

export type TemplateType =
  | 'SYLLOGISM'
  | 'MODUS_PONENS'
  | 'MODUS_TOLLENS'
  | 'FACT_CHAIN'
  | 'HYPOTHETICAL_SYLLOGISM'
  | 'DISJUNCTIVE_SYLLOGISM'
  | 'CONSTRUCTIVE_DILEMMA'
  | 'DESTRUCTIVE_DILEMMA'
  | 'CAUSAL_ARGUMENT'
  | 'DEFINITIONAL_ARGUMENT'
  | 'PRACTICAL_ARGUMENT'
  | 'EVALUATIVE_ARGUMENT'

export interface TemplateRequest {
  type: TemplateType
  predicates: Record<string, string>
  args: string[]
  scope?: string | null
}

// Operation modes for the query console
export type OperationMode =
  | 'infer'
  | 'assert_fact'
  | 'assert_rule'
  | 'retract'
  | 'assert_template'
  | 'inspect'
  | 'execute'
  | 'synthesize'

// User roles
export type UserRole = 'admin' | 'developer' | 'agent'

// UI state types
export interface QueryHistoryEntry {
  id: string
  mode: OperationMode
  input: string
  timestamp: number
}

export interface AtomState {
  predicate: string
  args: string[]
  negated: boolean
}

export interface InspectFilters {
  type: 'ALL' | 'FACT' | 'RULE'
  filter: string
  scope: string
}

// Inspect result item
export interface InspectItem {
  Type: 'Fact' | 'Rule'
  Content: string | AtomResponse
}

// Context Playground types
export interface AskResponse {
  answer: string
  confidence: number
  derivation: DerivationStep[]
  queriesExecuted: string[]
}

export interface DerivationStep {
  type: 'fact' | 'rule'
  content: string
  description: string
}

export interface TellResponse {
  count: number
  understood: string[]
  rules: string[]
  rulesCount: number
}

export interface RecallResponse {
  facts: string[]
  count: number
}

// Memory types
export interface ScoredAtom {
  atom: AtomResponse
  salience: number
}

export interface ContextWindow {
  facts: ScoredAtom[]
  totalAvailable: number
  windowSize: number
  predicateDistribution: Record<string, number>
  generatedAt: string
}
