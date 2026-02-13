import { useMemo, useState, useRef, useEffect } from 'react'
import type { AtomResponse, InspectItem } from '@/lib/types'

// ── Types ──────────────────────────────────────────

interface GNode {
  id: string
  x: number
  y: number
  vx: number
  vy: number
  kind: 'entity' | 'variable'
  r: number
  unary: string[]
}

interface GEdge {
  from: string
  to: string
  label: string
  edgeKind: 'fact' | 'rule'
  negated: boolean
}

interface ParsedAtom {
  pred: string
  args: string[]
  neg: boolean
}

// ── Parsing ────────────────────────────────────────

function parseAtom(s: string): ParsedAtom | null {
  const t = s.trim()
  const neg = t.startsWith('¬') || t.startsWith('NOT ')
  const c = neg ? t.replace(/^(¬|NOT\s+)/, '') : t
  const m = c.match(/^(\w+)\(([^)]*)\)$/)
  if (!m || !m[1] || m[2] === undefined) return null
  return {
    pred: m[1],
    args: m[2].split(',').map((a) => a.trim()).filter(Boolean),
    neg,
  }
}

function parseRule(s: string) {
  const idx = s.indexOf(':-')
  if (idx < 0) return null
  const headStr = s.slice(0, idx).trim()
  const bodyStr = s.slice(idx + 2).trim()

  const head = parseAtom(headStr)
  if (!head) return null

  const body: ParsedAtom[] = []
  let depth = 0
  let cur = ''
  for (const ch of bodyStr) {
    if (ch === '(') depth++
    else if (ch === ')') depth--
    else if (ch === ',' && depth === 0) {
      const p = parseAtom(cur.trim())
      if (p) body.push(p)
      cur = ''
      continue
    }
    cur += ch
  }
  const last = parseAtom(cur.trim())
  if (last) body.push(last)
  return { head, body }
}

// ── Graph Construction ─────────────────────────────

function nodeRadius(id: string, kind: 'entity' | 'variable') {
  if (kind === 'variable') return Math.min(28, Math.max(16, id.length * 3 + 6))
  return Math.min(36, Math.max(22, id.length * 3.3 + 6))
}

function buildGraph(items: InspectItem[]) {
  const nodeMap = new Map<string, GNode>()
  const edges: GEdge[] = []

  function ensure(id: string): GNode {
    let node = nodeMap.get(id)
    if (!node) {
      const kind = id.startsWith('?') ? 'variable' as const : 'entity' as const
      node = {
        id, x: 0, y: 0, vx: 0, vy: 0,
        kind, r: nodeRadius(id, kind), unary: [],
      }
      nodeMap.set(id, node)
    }
    return node
  }

  function addAtom(a: ParsedAtom, ek: 'fact' | 'rule') {
    const arg0 = a.args[0]
    const arg1 = a.args[1]
    if (a.args.length === 1 && arg0) {
      ensure(arg0).unary.push((a.neg ? '¬' : '') + a.pred)
    } else if (a.args.length >= 2 && arg0 && arg1) {
      if (arg0 === arg1) {
        ensure(arg0).unary.push(a.pred + '(self)')
      } else {
        ensure(arg0)
        ensure(arg1)
        edges.push({ from: arg0, to: arg1, label: a.pred, edgeKind: ek, negated: a.neg })
      }
    }
  }

  for (const item of items) {
    if (item.Type === 'Fact') {
      if (typeof item.Content === 'object' && 'predicate' in item.Content) {
        // Structured AtomResponse
        const atom = item.Content as AtomResponse
        const parsed: ParsedAtom = {
          pred: atom.predicate,
          args: atom.args,
          neg: atom.negated,
        }
        addAtom(parsed, 'fact')
      } else {
        // Legacy string format
        const a = parseAtom(String(item.Content))
        if (a) addAtom(a, 'fact')
      }
    } else {
      const r = parseRule(String(item.Content))
      if (r) {
        addAtom(r.head, 'rule')
        r.body.forEach((b) => addAtom(b, 'rule'))
      }
    }
  }

  return { nodes: [...nodeMap.values()], edges }
}

// ── Force-Directed Layout ──────────────────────────

function layoutGraph(nodes: GNode[], edges: GEdge[], w: number, h: number) {
  if (nodes.length === 0) return
  const cx = w / 2
  const cy = h / 2

  if (nodes.length === 1) {
    const n = nodes[0]
    if (n) { n.x = cx; n.y = cy }
    return
  }

  // Initialize in circle
  const initR = Math.min(w, h) * 0.3
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i]
    if (!n) continue
    const a = (2 * Math.PI * i) / nodes.length - Math.PI / 2
    n.x = cx + initR * Math.cos(a)
    n.y = cy + initR * Math.sin(a)
    n.vx = 0
    n.vy = 0
  }

  const byId = new Map(nodes.map((n) => [n.id, n]))
  const iters = 250

  for (let iter = 0; iter < iters; iter++) {
    const alpha = 1 - iter / iters

    // Node repulsion
    for (let i = 0; i < nodes.length; i++) {
      const ni = nodes[i]
      if (!ni) continue
      for (let j = i + 1; j < nodes.length; j++) {
        const nj = nodes[j]
        if (!nj) continue
        let dx = ni.x - nj.x
        let dy = ni.y - nj.y
        let d2 = dx * dx + dy * dy
        if (d2 < 1) {
          dx = Math.random() - 0.5
          dy = Math.random() - 0.5
          d2 = 0.5
        }
        const d = Math.sqrt(d2)
        const f = (4500 * alpha) / d2
        const fx = (dx / d) * f
        const fy = (dy / d) * f
        ni.vx += fx
        ni.vy += fy
        nj.vx -= fx
        nj.vy -= fy
      }
    }

    // Edge attraction
    for (const e of edges) {
      const a = byId.get(e.from)
      const b = byId.get(e.to)
      if (!a || !b) continue
      const dx = b.x - a.x
      const dy = b.y - a.y
      const f = 0.004 * alpha
      a.vx += dx * f
      a.vy += dy * f
      b.vx -= dx * f
      b.vy -= dy * f
    }

    // Center gravity
    for (const n of nodes) {
      n.vx += (cx - n.x) * 0.008 * alpha
      n.vy += (cy - n.y) * 0.008 * alpha
    }

    // Apply velocity with damping
    for (const n of nodes) {
      n.vx *= 0.85
      n.vy *= 0.85
      n.x += n.vx
      n.y += n.vy
    }

    // Prevent overlap
    for (let i = 0; i < nodes.length; i++) {
      const ni = nodes[i]
      if (!ni) continue
      for (let j = i + 1; j < nodes.length; j++) {
        const nj = nodes[j]
        if (!nj) continue
        const dx = ni.x - nj.x
        const dy = ni.y - nj.y
        const d = Math.sqrt(dx * dx + dy * dy)
        const minD = ni.r + nj.r + 12
        if (d < minD && d > 0.1) {
          const push = (minD - d) / 2
          const ux = dx / d
          const uy = dy / d
          ni.x += ux * push
          ni.y += uy * push
          nj.x -= ux * push
          nj.y -= uy * push
        }
      }
    }

    // Keep in bounds
    const pad = 50
    for (const n of nodes) {
      n.x = Math.max(pad, Math.min(w - pad, n.x))
      n.y = Math.max(pad, Math.min(h - pad, n.y))
    }
  }
}

// ── Component ──────────────────────────────────────

interface KnowledgeGraphProps {
  items: InspectItem[]
}

export default function KnowledgeGraph({ items }: KnowledgeGraphProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(600)
  const height = 360
  const [hovered, setHovered] = useState<string | null>(null)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const ro = new ResizeObserver((entries) => {
      for (const entry of entries) setWidth(Math.max(entry.contentRect.width, 300))
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const { nodes, edges } = useMemo(() => {
    const g = buildGraph(items)
    layoutGraph(g.nodes, g.edges, width, height)
    return g
  }, [items, width])

  const byId = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes])

  // Build adjacency for hover highlighting
  const connectedTo = useMemo(() => {
    const m = new Map<string, Set<string>>()
    for (const e of edges) {
      if (!m.has(e.from)) m.set(e.from, new Set())
      if (!m.has(e.to)) m.set(e.to, new Set())
      m.get(e.from)!.add(e.to)
      m.get(e.to)!.add(e.from)
    }
    return m
  }, [edges])

  // Compute highlight set from hovered node
  const hlSet = useMemo(() => {
    if (!hovered) return null
    const s = new Set([hovered])
    connectedTo.get(hovered)?.forEach((id) => s.add(id))
    return s
  }, [hovered, connectedTo])

  // Group edges by sorted node-pair for curve offsets
  const processedEdges = useMemo(() => {
    const groups = new Map<string, GEdge[]>()
    for (const e of edges) {
      const key = [e.from, e.to].sort().join('||')
      const group = groups.get(key)
      if (group) {
        group.push(e)
      } else {
        groups.set(key, [e])
      }
    }
    const result: { edge: GEdge; offset: number }[] = []
    for (const [, group] of groups) {
      if (group.length === 1 && group[0]) {
        result.push({ edge: group[0], offset: 0 })
      } else {
        const spacing = 35
        const total = (group.length - 1) * spacing
        group.forEach((e, i) => {
          result.push({ edge: e, offset: -total / 2 + i * spacing })
        })
      }
    }
    return result
  }, [edges])

  // ── Edge rendering helpers ──

  function edgePath(e: GEdge, offset: number): string {
    const a = byId.get(e.from)
    const b = byId.get(e.to)
    if (!a || !b) return ''

    const dx = b.x - a.x
    const dy = b.y - a.y
    const d = Math.sqrt(dx * dx + dy * dy)
    if (d < 1) return ''

    const ux = dx / d
    const uy = dy / d
    const x1 = a.x + ux * (a.r + 2)
    const y1 = a.y + uy * (a.r + 2)
    const x2 = b.x - ux * (b.r + 7)
    const y2 = b.y - uy * (b.r + 7)

    if (offset === 0) return `M${x1},${y1} L${x2},${y2}`

    const mx = (x1 + x2) / 2
    const my = (y1 + y2) / 2
    const px = mx + -uy * offset
    const py = my + ux * offset
    return `M${x1},${y1} Q${px},${py} ${x2},${y2}`
  }

  function labelPos(e: GEdge, offset: number) {
    const a = byId.get(e.from)
    const b = byId.get(e.to)
    if (!a || !b) return { x: 0, y: 0 }

    const mx = (a.x + b.x) / 2
    const my = (a.y + b.y) / 2
    if (offset === 0) return { x: mx, y: my - 6 }

    const dx = b.x - a.x
    const dy = b.y - a.y
    const d = Math.sqrt(dx * dx + dy * dy)
    if (d < 1) return { x: mx, y: my - 6 }
    return {
      x: mx + (-dy / d) * offset * 0.55,
      y: my + (dx / d) * offset * 0.55 - 4,
    }
  }

  function edgeColor(e: GEdge): string {
    if (e.negated) return '#FF7EB6'
    return e.edgeKind === 'rule' ? '#BE95FF' : '#82CFFF'
  }

  function arrowId(e: GEdge): string {
    if (e.negated) return 'kg-arr-neg'
    return e.edgeKind === 'rule' ? 'kg-arr-rule' : 'kg-arr-fact'
  }

  if (nodes.length === 0) return null

  return (
    <div ref={containerRef} className="kg-container">
      <svg width={width} height={height} className="kg-svg">
        <defs>
          <marker id="kg-arr-fact" viewBox="0 0 10 8" refX="9" refY="4" markerWidth="8" markerHeight="6" orient="auto-start-reverse">
            <path d="M0,0 L10,4 L0,8 Z" fill="#82CFFF" />
          </marker>
          <marker id="kg-arr-rule" viewBox="0 0 10 8" refX="9" refY="4" markerWidth="8" markerHeight="6" orient="auto-start-reverse">
            <path d="M0,0 L10,4 L0,8 Z" fill="#BE95FF" />
          </marker>
          <marker id="kg-arr-neg" viewBox="0 0 10 8" refX="9" refY="4" markerWidth="8" markerHeight="6" orient="auto-start-reverse">
            <path d="M0,0 L10,4 L0,8 Z" fill="#FF7EB6" />
          </marker>
        </defs>

        {/* Edges */}
        <g>
          {processedEdges.map(({ edge: e, offset }, i) => {
            const path = edgePath(e, offset)
            const lp = labelPos(e, offset)
            const col = edgeColor(e)
            const hl = !hlSet || (hlSet.has(e.from) && hlSet.has(e.to))
            return (
              <g key={i} opacity={hl ? 1 : 0.12} className="kg-edge-group">
                <path
                  d={path}
                  fill="none"
                  stroke={col}
                  strokeWidth={hl && hovered ? 2.5 : 1.8}
                  strokeDasharray={e.edgeKind === 'rule' ? '6,4' : undefined}
                  markerEnd={`url(#${arrowId(e)})`}
                />
                <text x={lp.x} y={lp.y} textAnchor="middle" className="kg-edge-label">
                  {e.label}
                </text>
              </g>
            )
          })}
        </g>

        {/* Nodes */}
        <g>
          {nodes.map((n) => {
            const fill = n.kind === 'entity' ? '#0F62FE' : '#3DDBD9'
            const textFill = n.kind === 'entity' ? '#FFFFFF' : '#121619'
            const hl = !hlSet || hlSet.has(n.id)
            const isHovered = hovered === n.id
            const fontSize = n.id.length <= 4 ? 12 : n.id.length <= 7 ? 11 : 10
            return (
              <g
                key={n.id}
                onMouseEnter={() => setHovered(n.id)}
                onMouseLeave={() => setHovered(null)}
                style={{ cursor: 'pointer' }}
                opacity={hl ? 1 : 0.12}
              >
                {/* Hover glow */}
                {isHovered && (
                  <circle cx={n.x} cy={n.y} r={n.r + 8} fill={fill} opacity={0.12} />
                )}

                {/* Node circle */}
                <circle
                  cx={n.x}
                  cy={n.y}
                  r={n.r}
                  fill={fill}
                  stroke={isHovered ? '#82CFFF' : 'none'}
                  strokeWidth={2.5}
                />

                {/* Node label */}
                <text
                  x={n.x}
                  y={n.y + 1}
                  textAnchor="middle"
                  dominantBaseline="central"
                  fill={textFill}
                  className="kg-node-label"
                  style={{ fontSize }}
                >
                  {n.id}
                </text>

                {/* Unary predicate badges */}
                {n.unary.map((label, j) => {
                  const badgeW = Math.max(label.length * 6.5 + 12, 36)
                  return (
                    <g key={j}>
                      <rect
                        x={n.x - badgeW / 2}
                        y={n.y - n.r - 20 - j * 20}
                        width={badgeW}
                        height={18}
                        rx={9}
                        fill="#E5F6FF"
                        stroke="#82CFFF"
                        strokeWidth={1}
                      />
                      <text
                        x={n.x}
                        y={n.y - n.r - 9 - j * 20}
                        textAnchor="middle"
                        className="kg-unary-label"
                      >
                        {label}
                      </text>
                    </g>
                  )
                })}
              </g>
            )
          })}
        </g>
      </svg>

      {/* Legend */}
      <div className="kg-legend">
        <span className="kg-legend-item">
          <span className="kg-legend-dot" style={{ background: '#0F62FE' }} />
          Entity
        </span>
        <span className="kg-legend-item">
          <span className="kg-legend-dot" style={{ background: '#3DDBD9' }} />
          Variable
        </span>
        <span className="kg-legend-item">
          <svg width="20" height="4">
            <line x1="0" y1="2" x2="20" y2="2" stroke="#82CFFF" strokeWidth="2" />
          </svg>
          Fact
        </span>
        <span className="kg-legend-item">
          <svg width="20" height="4">
            <line x1="0" y1="2" x2="20" y2="2" stroke="#BE95FF" strokeWidth="2" strokeDasharray="4,3" />
          </svg>
          Rule
        </span>
      </div>
    </div>
  )
}
