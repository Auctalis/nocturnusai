interface ConfidenceIndicatorProps {
  confidence: number
}

export default function ConfidenceIndicator({ confidence }: ConfidenceIndicatorProps) {
  const pct = Math.round(confidence * 100)
  const fillClass =
    confidence > 0.7
      ? 'confidence-fill-high'
      : confidence > 0.4
        ? 'confidence-fill-medium'
        : 'confidence-fill-low'

  return (
    <div className="confidence-bar">
      <div className="confidence-track">
        <div
          className={`confidence-fill ${fillClass}`}
          style={{ width: `${pct}%` }}
        />
      </div>
      <span className="confidence-label">{pct}%</span>
    </div>
  )
}
