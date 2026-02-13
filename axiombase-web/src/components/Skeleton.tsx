interface SkeletonProps {
  width?: string | number
  height?: string | number
  lines?: number
  className?: string
}

export default function Skeleton({ width, height = 14, lines = 1, className = '' }: SkeletonProps) {
  if (lines > 1) {
    return (
      <div className={className}>
        {Array.from({ length: lines }, (_, i) => (
          <div
            key={i}
            className="skeleton skeleton-text"
            style={{
              width: i === lines - 1 ? '60%' : '100%',
              height: typeof height === 'number' ? `${height}px` : height,
            }}
          />
        ))}
      </div>
    )
  }

  return (
    <div
      className={`skeleton ${className}`}
      style={{
        width: typeof width === 'number' ? `${width}px` : width,
        height: typeof height === 'number' ? `${height}px` : height,
      }}
    />
  )
}
