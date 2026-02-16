interface SpinnerProps {
  size?: number
  label?: string
}

export default function Spinner({ size = 16, label }: SpinnerProps) {
  return (
    <span className="spinner-container" style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--space-xs)' }}>
      <svg
        className="spinner-arc"
        width={size}
        height={size}
        viewBox="0 0 16 16"
        fill="none"
      >
        <circle
          cx="8"
          cy="8"
          r="6.5"
          stroke="var(--gray-30)"
          strokeWidth="2"
        />
        <path
          d="M14.5 8a6.5 6.5 0 0 0-6.5-6.5"
          stroke="var(--blue-60)"
          strokeWidth="2"
          strokeLinecap="round"
        />
      </svg>
      {label && <span className="spinner-label">{label}</span>}
    </span>
  )
}
