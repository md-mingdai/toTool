import { useEffect, useRef } from 'react'
import { MagnifyingGlass, X } from '@phosphor-icons/react'

interface SearchOverlayProps {
  query: string
  onQueryChange: (value: string) => void
  onClose: () => void
}

export function SearchOverlay({ query, onQueryChange, onClose }: SearchOverlayProps) {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => inputRef.current?.focus(), 30)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [onClose])

  return (
    <div
      className="search-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="搜索"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div className="search-panel">
        <MagnifyingGlass size={20} weight="regular" aria-hidden="true" />
        <input
          ref={inputRef}
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') onClose()
          }}
          type="search"
          placeholder="搜索文件、应用或包名"
          aria-label="搜索文件、应用或包名"
          autoComplete="off"
          spellCheck={false}
        />
        {query ? (
          <button type="button" className="search-clear" onClick={() => onQueryChange('')} aria-label="清除搜索">
            <X size={16} weight="regular" />
          </button>
        ) : (
          <kbd>Esc</kbd>
        )}
        <button type="button" className="search-overlay-close" onClick={onClose} aria-label="退出搜索">
          <X size={18} weight="regular" />
        </button>
      </div>
      <p className="search-overlay-hint">输入关键词后按 Esc 退出搜索</p>
    </div>
  )
}
