import { useState } from 'react'

export function AppIconTile({
  iconUrl,
  name,
  size = 40,
}: {
  iconUrl?: string
  name: string
  size?: number
}) {
  const [failed, setFailed] = useState(false)
  const radius = Math.round(size * 0.27)
  if (iconUrl && !failed) {
    return (
      <span
        className="app-icon has-image"
        style={{ width: size, height: size, borderRadius: radius }}
        aria-hidden="true"
      >
        <img src={iconUrl} alt="" draggable={false} onDragStart={(event) => event.preventDefault()} onError={() => setFailed(true)} />
      </span>
    )
  }
  const initial = name.trim().charAt(0).toUpperCase() || '?'
  return (
    <span
      className="app-icon is-fallback"
      style={{ width: size, height: size, borderRadius: radius, fontSize: Math.round(size * 0.36) }}
      aria-hidden="true"
    >
      {initial}
    </span>
  )
}
