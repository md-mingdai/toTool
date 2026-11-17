import { Lightning } from '@phosphor-icons/react'

export function BrandMark({ size = 34 }: { size?: number }) {
  return (
    <span
      className="brand-mark"
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.32) }}
      aria-hidden="true"
    >
      <Lightning size={Math.round(size * 0.5)} weight="fill" />
    </span>
  )
}

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <span className="brand-lockup">
      <BrandMark />
      <span className="brand-copy">
        <strong>隔空闪传</strong>
        {!compact && <small>FlashDrop</small>}
      </span>
    </span>
  )
}
