interface KbdProps {
  keys: string[]
}

export default function Kbd({ keys }: KbdProps) {
  return (
    <span className="kbd">
      {keys.map((key, i) => (
        <kbd key={i} className="kbd-key">
          {key}
        </kbd>
      ))}
    </span>
  )
}
