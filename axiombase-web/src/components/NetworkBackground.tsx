import { useRef, useEffect } from 'react'

// IBM pastel palette for nodes
const PASTEL_COLORS: string[] = [
  'rgba(166, 200, 255, 0.6)',  // blue-30
  'rgba(208, 226, 255, 0.5)',  // blue-20
  'rgba(158, 240, 240, 0.5)',  // teal-20
  'rgba(61, 219, 217, 0.4)',   // teal-30
  'rgba(212, 187, 255, 0.5)',  // purple-30
  'rgba(232, 218, 255, 0.4)',  // purple-20
  'rgba(255, 175, 210, 0.4)',  // magenta-30
  'rgba(186, 230, 255, 0.5)',  // cyan-20
  'rgba(130, 207, 255, 0.4)',  // cyan-30
  'rgba(167, 240, 186, 0.4)',  // green-20
]

interface Node {
  x: number
  y: number
  vx: number
  vy: number
  radius: number
  color: string
}

const NODE_COUNT = 50
const CONNECTION_DISTANCE = 160
const LINE_OPACITY = 0.12

export default function NetworkBackground() {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const animationRef = useRef<number>(0)
  const nodesRef = useRef<Node[]>([])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const resize = () => {
      canvas.width = window.innerWidth
      canvas.height = window.innerHeight
    }
    resize()
    window.addEventListener('resize', resize)

    // Initialize nodes
    nodesRef.current = Array.from({ length: NODE_COUNT }, (): Node => ({
      x: Math.random() * canvas.width,
      y: Math.random() * canvas.height,
      vx: (Math.random() - 0.5) * 0.4,
      vy: (Math.random() - 0.5) * 0.4,
      radius: Math.random() * 3 + 1.5,
      color: PASTEL_COLORS[Math.floor(Math.random() * PASTEL_COLORS.length)]!,
    }))

    const animate = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      const nodes = nodesRef.current

      // Update positions
      for (const node of nodes) {
        node.x += node.vx
        node.y += node.vy

        if (node.x < 0 || node.x > canvas.width) node.vx *= -1
        if (node.y < 0 || node.y > canvas.height) node.vy *= -1

        node.x = Math.max(0, Math.min(canvas.width, node.x))
        node.y = Math.max(0, Math.min(canvas.height, node.y))
      }

      // Draw connections
      ctx.lineWidth = 1
      for (let i = 0; i < nodes.length; i++) {
        const nodeA = nodes[i]
        for (let j = i + 1; j < nodes.length; j++) {
          const nodeB = nodes[j]
          if (!nodeA || !nodeB) continue
          const dx = nodeA.x - nodeB.x
          const dy = nodeA.y - nodeB.y
          const dist = Math.sqrt(dx * dx + dy * dy)

          if (dist < CONNECTION_DISTANCE) {
            const opacity = LINE_OPACITY * (1 - dist / CONNECTION_DISTANCE)
            ctx.strokeStyle = `rgba(166, 200, 255, ${opacity})`
            ctx.beginPath()
            ctx.moveTo(nodeA.x, nodeA.y)
            ctx.lineTo(nodeB.x, nodeB.y)
            ctx.stroke()
          }
        }
      }

      // Draw nodes
      for (const node of nodes) {
        ctx.fillStyle = node.color
        ctx.beginPath()
        ctx.arc(node.x, node.y, node.radius, 0, Math.PI * 2)
        ctx.fill()
      }

      animationRef.current = requestAnimationFrame(animate)
    }

    animate()

    return () => {
      window.removeEventListener('resize', resize)
      cancelAnimationFrame(animationRef.current)
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      className="network-bg"
    />
  )
}
