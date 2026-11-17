import { useEffect, useRef } from 'react'
import type { RefObject } from 'react'
import { List, MagnifyingGlass, Moon, Sun, UploadSimple, X } from '@phosphor-icons/react'
import type { Theme } from '../types'
import { Brand } from './Brand'

interface TopbarProps {
  query: string
  onQueryChange: (value: string) => void
  onUpload: () => void
  theme: Theme
  onToggleTheme: () => void
  sidebarOpen: boolean
  onOpenSidebar: () => void
  menuButtonRef: RefObject<HTMLButtonElement | null>
  searchOpen: boolean
  onOpenSearch: () => void
}

export function Topbar({
  query,
  onQueryChange,
  onUpload,
  theme,
  onToggleTheme,
  sidebarOpen,
  onOpenSidebar,
  menuButtonRef,
  searchOpen,
  onOpenSearch,
}: TopbarProps) {
  const searchRef = useRef<HTMLInputElement>(null)
  const shortcut = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘ K' : 'Ctrl K'

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        onOpenSearch()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onOpenSearch])

  return (
    <header className="topbar">
      <div className="topbar-inner">
      <button
        ref={menuButtonRef}
        type="button"
        className="icon-button mobile-menu-button"
        onClick={onOpenSidebar}
        aria-label="打开分类菜单"
        aria-controls="app-sidebar"
        aria-expanded={sidebarOpen}
      >
        <List size={19} weight="regular" />
      </button>

      <span className="mobile-brand">
        <Brand compact />
      </span>

      <div className={`search-box${searchOpen ? ' is-search-open' : ''}`}>
        <MagnifyingGlass className="search-icon" size={18} weight="regular" aria-hidden="true" />
        <input
          ref={searchRef}
          value={query}
          onFocus={onOpenSearch}
          onClick={onOpenSearch}
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              onQueryChange('')
              event.currentTarget.blur()
            }
          }}
          type="search"
          placeholder="搜索文件、应用或包名"
          aria-label="搜索当前分类"
          aria-keyshortcuts="Control+K Meta+K"
          spellCheck={false}
          autoComplete="off"
        />
        {query ? (
          <button type="button" className="search-clear" onClick={() => onQueryChange('')} aria-label="清除搜索">
            <X size={15} weight="regular" />
          </button>
        ) : (
          <kbd className="search-kbd">{shortcut}</kbd>
        )}
      </div>

      <div className="topbar-actions">
        <button type="button" className="btn btn-primary upload-button" onClick={onUpload} aria-label="传到手机" title="传到手机">
          <UploadSimple size={17} weight="regular" aria-hidden="true" />
          <span className="upload-label">传到手机</span>
        </button>
        <button
          type="button"
          className="icon-button theme-toggle"
          onClick={onToggleTheme}
          aria-label={theme === 'dark' ? '切换到亮色界面' : '切换到暗色界面'}
          title={theme === 'dark' ? '切换到亮色界面' : '切换到暗色界面'}
        >
          {theme === 'dark' ? <Sun size={18} weight="regular" /> : <Moon size={18} weight="regular" />}
        </button>
      </div>
      </div>
    </header>
  )
}
