import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { MagnifyingGlass, X } from '@phosphor-icons/react'

interface SearchOverlayProps {
  query: string
  onQueryChange: (value: string) => void
  onClose: () => void
  originRect: DOMRect | null
}

interface Box {
  left: number
  top: number
  width: number
  height: number
}

const CLOSE_MS = 320

function boxOf(rect: DOMRect): Box {
  return { left: rect.left, top: rect.top, width: rect.width, height: rect.height }
}

function targetBox(): Box {
  const width = Math.min(760, Math.max(240, window.innerWidth - 32))
  return {
    left: (window.innerWidth - width) / 2,
    top: 76,
    width,
    height: 58,
  }
}

function place(panel: HTMLElement, box: Box) {
  panel.style.left = `${box.left}px`
  panel.style.top = `${box.top}px`
  panel.style.width = `${box.width}px`
  panel.style.height = `${box.height}px`
}

export function SearchOverlay({ query, onQueryChange, onClose, originRect }: SearchOverlayProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const originRef = useRef<Box | null>(originRect ? boxOf(originRect) : null)
  const closeTimerRef = useRef<number | null>(null)
  const closingRef = useRef(false)
  const [closing, setClosing] = useState(false)

  useLayoutEffect(() => {
    if (!originRef.current) {
      const box = document.querySelector('.search-box')
      if (box) originRef.current = boxOf(box.getBoundingClientRect())
    }
    const panel = panelRef.current
    const origin = originRef.current
    if (!panel || !origin) return
    const target = targetBox()
    panel.style.position = 'fixed'
    panel.style.margin = '0'
    panel.style.minHeight = '0'
    panel.style.zIndex = '1'
    panel.style.transition = 'none'
    place(panel, origin)
    // 强制布局，确保起始位置先被浏览器接受，再开始过渡。
    void panel.offsetWidth
    panel.style.transition = [
      'left 0.34s var(--ease-soft)',
      'top 0.34s var(--ease-soft)',
      'width 0.34s var(--ease-soft)',
      'height 0.34s var(--ease-soft)',
      'box-shadow 0.34s var(--ease-soft)',
    ].join(', ')
    const frame = window.requestAnimationFrame(() => place(panel, target))
    return () => window.cancelAnimationFrame(frame)
  }, [])

  const beginClose = useCallback(() => {
    if (closingRef.current) return
    closingRef.current = true
    setClosing(true)
    const panel = panelRef.current
    const origin = originRef.current
    if (!panel || !origin) {
      closeTimerRef.current = window.setTimeout(onClose, 180)
      return
    }
    const current = panel.getBoundingClientRect()
    panel.style.transition = 'none'
    place(panel, { left: current.left, top: current.top, width: current.width, height: current.height })
    void panel.offsetWidth
    panel.style.transition = [
      'left 0.3s var(--ease-soft)',
      'top 0.3s var(--ease-soft)',
      'width 0.3s var(--ease-soft)',
      'height 0.3s var(--ease-soft)',
      'box-shadow 0.3s var(--ease-soft)',
    ].join(', ')
    window.requestAnimationFrame(() => place(panel, origin))
    closeTimerRef.current = window.setTimeout(onClose, CLOSE_MS)
  }, [onClose])

  useEffect(() => {
    const timer = window.setTimeout(() => inputRef.current?.focus(), 30)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        beginClose()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.clearTimeout(timer)
      if (closeTimerRef.current !== null) window.clearTimeout(closeTimerRef.current)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [beginClose])

  return (
    <div
      className={`search-overlay${closing ? ' is-closing' : ''}`}
      role="dialog"
      aria-modal="true"
      aria-label="搜索"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) beginClose()
      }}
    >
      <div className="search-panel" ref={panelRef}>
        <MagnifyingGlass size={20} weight="regular" aria-hidden="true" />
        <input
          ref={inputRef}
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
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
        <button type="button" className="search-overlay-close" onClick={beginClose} aria-label="退出搜索">
          <X size={18} weight="regular" />
        </button>
      </div>
      <p className="search-overlay-hint">输入关键词后按 Esc 退出搜索</p>
    </div>
  )
}
