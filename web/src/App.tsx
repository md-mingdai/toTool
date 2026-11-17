import { useCallback, useEffect, useRef, useState } from 'react'
import { CheckCircle, UploadSimple, WarningCircle } from '@phosphor-icons/react'
import * as api from './api'
import { DetailsDialog } from './components/DetailsDialog'
import { FileBrowser } from './components/FileBrowser'
import { PairingGate } from './components/PairingGate'
import { Sidebar } from './components/Sidebar'
import { Topbar } from './components/Topbar'
import { TransferToast } from './components/TransferToast'
import { categories } from './data'
import type {
  AppCounts,
  AppScope,
  CategoryId,
  Entry,
  FileEntry,
  FolderEntry,
  ServerInfo,
  Theme,
  TransferRecord,
} from './types'

function initialTheme(): Theme {
  try {
    const stored = window.localStorage.getItem('flashdrop-theme')
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // 隐私模式下 localStorage 可能不可用
  }
  return 'light'
}

async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(area)
    return copied
  }
}

function messageForError(error: unknown): string {
  if (error instanceof api.StoragePermissionError) {
    return '手机还没有授予「所有文件访问权限」。请在手机的隔空闪传页面授权后重试。'
  }
  if (error instanceof api.ApiError) {
    if (error.code === 'network' || error.status === 0) return '无法连接手机端，请确认服务仍在运行。'
    if (error.code === 'file-too-large-for-encryption') return '加密模式暂支持 256 MB 以内的文件。'
  }
  if (error instanceof Error && error.message) return error.message
  return '操作失败，请稍后重试。'
}

interface RouteState {
  category: CategoryId
  path: string
  query: string
  scope: AppScope | 'all'
}

const categoryIds = new Set<CategoryId>(categories.map((item) => item.id))

function parseRoute(): RouteState {
  const url = new URL(window.location.href)
  let segments: string[] = []
  try {
    segments = url.pathname.split('/').filter(Boolean).map((part) => decodeURIComponent(part))
  } catch {
    segments = url.pathname.split('/').filter(Boolean)
  }
  const first = segments[0]
  let category: CategoryId = 'storage'
  let path = ''
  if (first === 'storage') {
    path = segments.slice(1).join('/')
  } else if (first && categoryIds.has(first as CategoryId)) {
    category = first as CategoryId
  }
  const scopeValue = url.searchParams.get('scope')
  const scope: AppScope | 'all' = scopeValue === 'system' || scopeValue === 'user' ? scopeValue : 'all'
  return {
    category,
    path,
    query: url.searchParams.get('q') ?? '',
    scope,
  }
}

function folderFromPath(path: string): FolderEntry | null {
  if (!path) return null
  const segments = path.split('/').filter(Boolean)
  return {
    type: 'folder',
    id: path,
    name: segments[segments.length - 1] ?? path,
    path,
    size: 0,
    itemCount: 0,
    modified: '',
  }
}

function routeUrl(category: CategoryId, path: string, query: string, scope: AppScope | 'all'): string {
  const base = category === 'storage'
    ? path
      ? `/storage/${path.split('/').filter(Boolean).map(encodeURIComponent).join('/')}`
      : '/storage'
    : `/${category}`
  const params = new URLSearchParams()
  if (query.trim()) params.set('q', query.trim())
  if (category === 'apps' && scope !== 'all') params.set('scope', scope)
  const search = params.toString()
  return search ? `${base}?${search}` : base
}

export default function App() {
  const initialRoute = useRef<RouteState>(parseRoute()).current
  const [theme, setTheme] = useState<Theme>(initialTheme)
  const [paired, setPaired] = useState(api.hasSession())
  const [info, setInfo] = useState<ServerInfo | null>(null)
  const [encryptionEnabled, setEncryptionEnabled] = useState(api.isEncryptionActive())
  const [category, setCategory] = useState<CategoryId>(initialRoute.category)
  const [folder, setFolder] = useState<FolderEntry | null>(() => folderFromPath(initialRoute.path))
  const [appsScope, setAppsScope] = useState<AppScope | 'all'>(initialRoute.scope)
  const [query, setQuery] = useState(initialRoute.query)
  const [entries, setEntries] = useState<Entry[]>([])
  const [appCounts, setAppCounts] = useState<AppCounts>({ all: 0, system: 0, user: 0 })
  const [transfers, setTransfers] = useState<TransferRecord[]>([])
  const [toastRecords, setToastRecords] = useState<TransferRecord[]>([])
  const [toastOpen, setToastOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [selected, setSelected] = useState<Entry | null>(null)
  const [dragging, setDragging] = useState(false)
  const [connectionLost, setConnectionLost] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const mobileMenuRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = theme
    const meta = document.querySelector('meta[name="theme-color"]')
    meta?.setAttribute('content', theme === 'dark' ? '#0b0e09' : '#f2f3ec')
    try {
      window.localStorage.setItem('flashdrop-theme', theme)
    } catch {
      // 忽略无法写入的情况
    }
  }, [theme])

  // 首次访问或旧的深层链接：把地址规范到 /storage 或已知分类。
  useEffect(() => {
    const first = window.location.pathname.split('/').filter(Boolean)[0]
    if (!first || (first !== 'storage' && !categoryIds.has(first as CategoryId))) {
      window.history.replaceState(null, '', routeUrl(initialRoute.category, initialRoute.path, initialRoute.query, initialRoute.scope))
    }
  }, [initialRoute])

  // PJAX 的返回/前进：只换数据和 URL 对应状态，不刷新页面。
  useEffect(() => {
    const onPopState = () => {
      const route = parseRoute()
      setCategory(route.category)
      setFolder(folderFromPath(route.path))
      setAppsScope(route.scope)
      setQuery(route.query)
      setSelected(null)
      contentRef.current?.scrollTo({ top: 0 })
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  const handleDisconnect = useCallback(() => {
    void api.logout()
    setPaired(false)
    setEntries([])
    setTransfers([])
    setToastRecords([])
    setToastOpen(false)
    setSelected(null)
    setSidebarOpen(false)
    setEncryptionEnabled(false)
    setConnectionLost(false)
  }, [])

  useEffect(() => {
    let cancelled = false
    async function boot() {
      try {
        if (api.hasSession()) {
          const restored = await api.restoreSession()
          if (cancelled) return
          if (restored) {
            setInfo(restored)
            setPaired(true)
            setEncryptionEnabled(api.isEncryptionActive())
            setConnectionLost(false)
          } else {
            setPaired(false)
          }
        }
        if (!api.hasSession()) {
          const publicInfo = await api.getInfo()
          if (!cancelled) {
            setInfo(publicInfo)
            setEncryptionEnabled(false)
          }
        }
      } catch (caught) {
        if (cancelled) return
        setPaired(false)
        if (caught instanceof api.PairingUnavailableError) {
          try {
            const publicInfo = await api.getInfo()
            if (!cancelled) setInfo(publicInfo)
          } catch {
            // 服务确实不可达，配对页会显示连接失败
          }
        } else {
          setNotice(messageForError(caught))
        }
      }
    }
    void boot()
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 3200)
    return () => window.clearTimeout(timer)
  }, [notice])

  useEffect(() => {
    if (!toastOpen) return
    if (toastRecords.some((record) => record.status === 'active')) return
    const timer = window.setTimeout(() => setToastOpen(false), 4800)
    return () => window.clearTimeout(timer)
  }, [toastOpen, toastRecords])

  useEffect(() => {
    if (!sidebarOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSidebarOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [sidebarOpen])

  useEffect(() => {
    if (!paired) {
      setConnectionLost(false)
      return
    }
    let cancelled = false
    const check = async () => {
      const ok = await api.ping()
      if (!cancelled) setConnectionLost(!ok)
    }
    void check()
    const timer = window.setInterval(() => void check(), 5000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [paired])

  useEffect(() => {
    if (!paired) return
    const folderPath = folder?.path ?? ''
    let cancelled = false
    const timer = window.setTimeout(async () => {
      setLoading(true)
      setError(null)
      try {
        if (category === 'apps') {
          const result = await api.listApps(appsScope, query)
          if (!cancelled) {
            setEntries(result.entries)
            setAppCounts(result.counts)
            setConnectionLost(false)
          }
        } else if (category === 'transfers') {
          const result = await api.listTransfers()
          if (!cancelled) {
            setTransfers(result)
            setEntries([])
            setConnectionLost(false)
          }
        } else if (category === 'clipboard') {
          const result = await api.listClipboard()
          const needle = query.trim().toLowerCase()
          if (!cancelled) {
            setEntries(needle
              ? result.filter((entry) => `${entry.name} ${entry.text ?? ''}`.toLowerCase().includes(needle))
              : result)
            setConnectionLost(false)
          }
        } else {
          const result = await api.listFiles(category, folderPath, query)
          if (!cancelled) {
            setEntries(result)
            setFolder((previous) => {
              if (!previous || previous.path !== folderPath) return previous
              const loadedSize = result.reduce((total, entry) => total + entry.size, 0)
              const first = result[0]
              const modified = first && 'modified' in first ? first.modified : previous.modified
              return {
                ...previous,
                itemCount: previous.itemCount || result.length,
                size: previous.size || loadedSize,
                modified: previous.modified || modified,
              }
            })
            setConnectionLost(false)
          }
        }
      } catch (caught) {
        if (cancelled) return
        if (caught instanceof api.UnauthorizedError) {
          handleDisconnect()
          return
        }
        if (caught instanceof api.ApiError && (caught.status === 0 || caught.code === 'network')) {
          setConnectionLost(true)
        }
        setError(messageForError(caught))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }, query.trim() ? 250 : 0)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [appsScope, category, folder?.path, handleDisconnect, paired, query, refreshKey])

  const scrollTop = useCallback(() => {
    contentRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
  }, [])

  const syncUrl = useCallback((
    nextCategory: CategoryId,
    nextPath: string,
    nextQuery: string,
    nextScope: AppScope | 'all',
    replace = false,
  ) => {
    const nextUrl = routeUrl(nextCategory, nextPath, nextQuery, nextScope)
    const current = window.location.pathname + window.location.search
    if (nextUrl === current) return
    if (replace) {
      window.history.replaceState(null, '', nextUrl)
    } else {
      window.history.pushState(null, '', nextUrl)
    }
  }, [])

  const closeSidebar = useCallback(() => {
    setSidebarOpen(false)
    window.setTimeout(() => mobileMenuRef.current?.focus(), 0)
  }, [])

  const closeDetails = useCallback(() => setSelected(null), [])

  const handleSelectCategory = useCallback((next: CategoryId) => {
    setCategory(next)
    setFolder(null)
    setAppsScope('all')
    setQuery('')
    setSelected(null)
    syncUrl(next, '', '', 'all')
    closeSidebar()
    scrollTop()
  }, [closeSidebar, scrollTop, syncUrl])

  const handlePair = useCallback(async (code: string) => {
    const result = await api.pair(code)
    setInfo({
      port: result.port,
      device: result.device,
      encryptionEnabled: result.encryption.enabled,
    })
    setEncryptionEnabled(result.encryption.enabled)
    setPaired(true)
    setCategory('storage')
    setFolder(null)
    setAppsScope('all')
    setQuery('')
    setEntries([])
    setTransfers([])
    setToastRecords([])
    setConnectionLost(false)
    syncUrl('storage', '', '', 'all', true)
    setNotice(`已连接 ${result.device.name}`)
  }, [syncUrl])

  const handleOpenFolder = useCallback((next: FolderEntry) => {
    setFolder(next)
    setQuery('')
    setSelected(null)
    syncUrl('storage', next.path, '', 'all')
    scrollTop()
  }, [scrollTop, syncUrl])

  const handleGoRoot = useCallback(() => {
    setFolder(null)
    setQuery('')
    syncUrl('storage', '', '', 'all')
    scrollTop()
  }, [scrollTop, syncUrl])

  const handleNavigatePath = useCallback((path: string) => {
    setFolder(folderFromPath(path))
    setQuery('')
    setSelected(null)
    syncUrl('storage', path, '', 'all')
    scrollTop()
  }, [scrollTop, syncUrl])

  const handleQueryChange = useCallback((next: string) => {
    setQuery(next)
    syncUrl(category, folder?.path ?? '', next, appsScope, true)
  }, [appsScope, category, folder?.path, syncUrl])

  const handleScopeChange = useCallback((next: AppScope | 'all') => {
    setAppsScope(next)
    syncUrl('apps', '', query, next, true)
  }, [query, syncUrl])

  const clearQuery = useCallback(() => {
    setQuery('')
    syncUrl(category, folder?.path ?? '', '', appsScope, true)
  }, [appsScope, category, folder?.path, syncUrl])

  const handleViewTransfers = useCallback(() => {
    setCategory('transfers')
    setFolder(null)
    setQuery('')
    setToastOpen(false)
    syncUrl('transfers', '', '', 'all')
    closeSidebar()
    scrollTop()
  }, [closeSidebar, scrollTop, syncUrl])

  const handleUpload = useCallback(async (files: FileList | File[]) => {
    if (!paired) return
    const list = Array.from(files)
    if (list.length === 0) return
    const stamp = Date.now()
    let batch: TransferRecord[] = list.map((file, index) => ({
      id: `local-${stamp}-${index}`,
      name: file.name,
      size: file.size,
      direction: 'upload',
      progress: 0,
      status: 'active',
      createdAt: '正在传输',
    }))
    setToastRecords(batch)
    setToastOpen(true)
    for (let index = 0; index < list.length; index += 1) {
      const file = list[index]
      try {
        await api.uploadFile(file, (ratio) => {
          const progress = Math.max(1, Math.min(99, Math.round(ratio * 100)))
          batch = batch.map((record, recordIndex) =>
            recordIndex === index ? { ...record, progress } : record)
          setToastRecords(batch)
        })
        batch = batch.map((record, recordIndex) =>
          recordIndex === index ? { ...record, progress: 100, status: 'done', createdAt: '刚刚' } : record)
        setToastRecords(batch)
      } catch (caught) {
        if (caught instanceof api.UnauthorizedError) {
          handleDisconnect()
          return
        }
        batch = batch.filter((_, recordIndex) => recordIndex !== index)
        setToastRecords(batch)
        if (caught instanceof api.ApiError && (caught.status === 0 || caught.code === 'network')) {
          setConnectionLost(true)
        }
        setNotice(messageForError(caught))
        break
      }
    }
    setRefreshKey((value) => value + 1)
    try {
      setTransfers(await api.listTransfers())
    } catch {
      // 传输列表刷新失败不影响已完成的文件
    }
  }, [handleDisconnect, paired])

  const handleDownload = useCallback(async (entry: Entry) => {
    const record: TransferRecord = {
      id: `download-${Date.now()}`,
      name: entry.name,
      size: entry.size,
      direction: 'download',
      progress: 0,
      status: 'active',
      createdAt: '正在下载',
      phase: 'download',
    }
    setToastRecords([record])
    setToastOpen(true)
    const update = (patch: Partial<TransferRecord>) => {
      setToastRecords((previous) =>
        previous.map((item) => (item.id === record.id ? { ...item, ...patch } : item)))
    }
    try {
      const name = await api.downloadEntry(entry, (phase, ratio) => {
        update({
          phase,
          progress: phase === 'decrypt' ? 90 + Math.round(ratio * 10) : Math.round(ratio * 90),
          createdAt: phase === 'decrypt' ? '正在解密' : '正在下载',
        })
      })
      update({ name, progress: 100, status: 'done', phase: undefined, createdAt: '刚刚' })
      setSelected(null)
      setNotice(`已下载 ${name}`)
      try {
        setTransfers(await api.listTransfers())
      } catch {
        // 下载已经完成，传输列表刷新失败不打断
      }
    } catch (caught) {
      setToastRecords((previous) => previous.filter((item) => item.id !== record.id))
      if (caught instanceof api.UnauthorizedError) {
        handleDisconnect()
        return
      }
      if (caught instanceof api.ApiError && (caught.status === 0 || caught.code === 'network')) {
        setConnectionLost(true)
      }
      setNotice(messageForError(caught))
    }
  }, [handleDisconnect])

  const handleCopy = useCallback((entry: FileEntry) => {
    const text = entry.text ?? entry.name
    void copyToClipboard(text).then((copied) => {
      setNotice(copied ? '已复制到电脑剪贴板' : '浏览器拒绝了剪贴板访问')
    })
  }, [])

  useEffect(() => {
    if (!paired) return
    const hasFiles = (event: DragEvent) => Array.from(event.dataTransfer?.types ?? []).includes('Files')
    let depth = 0
    const onDragEnter = (event: DragEvent) => {
      if (!hasFiles(event)) return
      event.preventDefault()
      depth += 1
      setDragging(true)
    }
    const onDragOver = (event: DragEvent) => {
      if (!hasFiles(event)) return
      event.preventDefault()
    }
    const onDragLeave = (event: DragEvent) => {
      if (!hasFiles(event)) return
      depth = Math.max(0, depth - 1)
      if (depth === 0) setDragging(false)
    }
    const onDrop = (event: DragEvent) => {
      if (!hasFiles(event)) return
      event.preventDefault()
      depth = 0
      setDragging(false)
      if (event.dataTransfer?.files.length) void handleUpload(event.dataTransfer.files)
    }
    window.addEventListener('dragenter', onDragEnter)
    window.addEventListener('dragover', onDragOver)
    window.addEventListener('dragleave', onDragLeave)
    window.addEventListener('drop', onDrop)
    return () => {
      window.removeEventListener('dragenter', onDragEnter)
      window.removeEventListener('dragover', onDragOver)
      window.removeEventListener('dragleave', onDragLeave)
      window.removeEventListener('drop', onDrop)
    }
  }, [handleUpload, paired])

  const categoryLabel = categories.find((item) => item.id === category)?.label ?? '手机文件'
  const port = info?.port ?? (Number(window.location.port) || null)

  return (
    <>
      <div
        className={`app-shell${sidebarOpen ? ' sidebar-open' : ''}${!paired ? ' is-locked' : ''}`}
        aria-hidden={!paired || undefined}
        inert={!paired || Boolean(selected) || undefined}
      >
        <Sidebar
          activeCategory={category}
          device={info?.device ?? null}
          port={port}
          encryptionEnabled={encryptionEnabled}
          onSelect={handleSelectCategory}
          onDisconnect={handleDisconnect}
          onClose={closeSidebar}
          open={sidebarOpen}
        />

        {sidebarOpen && (
          <button type="button" className="drawer-scrim" aria-label="关闭分类菜单" onClick={closeSidebar} />
        )}

        <div className="app-main" inert={sidebarOpen || undefined}>
          {connectionLost && paired && (
            <div className="connection-banner" role="alert">
              <WarningCircle size={18} weight="fill" aria-hidden="true" />
              <span>无法连接至设备。请检查 toTool 是否正在运行，并确认手机和电脑仍在同一 Wi-Fi。</span>
              <button
                type="button"
                onClick={() => {
                  void api.ping().then((ok) => {
                    if (ok) {
                      setConnectionLost(false)
                      setRefreshKey((value) => value + 1)
                    }
                  })
                }}
              >
                重试
              </button>
            </div>
          )}
          <Topbar
            query={query}
            onQueryChange={handleQueryChange}
            onUpload={() => inputRef.current?.click()}
            theme={theme}
            onToggleTheme={() => setTheme((current) => (current === 'dark' ? 'light' : 'dark'))}
            sidebarOpen={sidebarOpen}
            onOpenSidebar={() => setSidebarOpen(true)}
            menuButtonRef={mobileMenuRef}
          />

          <main className="content-scroll" ref={contentRef}>
            <FileBrowser
              category={category}
              categoryLabel={categoryLabel}
              entries={entries}
              folder={folder}
              query={query}
              appsScope={appsScope}
              appCounts={appCounts}
              transfers={transfers}
              loading={loading}
              error={error}
              onScopeChange={handleScopeChange}
              onOpenFolder={handleOpenFolder}
              onGoRoot={handleGoRoot}
              onNavigatePath={handleNavigatePath}
              onOpenEntry={setSelected}
              onDownload={(entry) => void handleDownload(entry)}
              onCopy={handleCopy}
              onUpload={() => inputRef.current?.click()}
              onClearQuery={clearQuery}
              onRetry={() => setRefreshKey((value) => value + 1)}
            />
          </main>

          {dragging && (
            <div className="drop-overlay" aria-hidden="true">
              <div className="drop-card">
                <span className="drop-icon">
                  <UploadSimple size={28} weight="regular" />
                </span>
                <strong>松手，传到手机</strong>
                <span>文件会保存到手机的 Download/toTool/FlashDrop 文件夹</span>
              </div>
            </div>
          )}

          {!loading && !error && (
            <span className="visually-hidden" aria-live="polite">
              {entries.length} 项
            </span>
          )}
        </div>
      </div>

      <PairingGate
        open={!paired}
        device={info?.device ?? null}
        port={port}
        onSubmit={handlePair}
      />

      <DetailsDialog
        entry={selected}
        onClose={closeDetails}
        onDownload={(entry) => void handleDownload(entry)}
        onCopy={handleCopy}
      />

      <TransferToast
        records={toastRecords}
        visible={toastOpen}
        onClose={() => setToastOpen(false)}
        onView={handleViewTransfers}
      />

      {notice && (
        <div className="notice" role="status">
          <CheckCircle size={16} weight="fill" aria-hidden="true" />
          {notice}
        </div>
      )}

      <input
        ref={inputRef}
        className="visually-hidden"
        type="file"
        multiple
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          if (event.target.files) void handleUpload(event.target.files)
          event.target.value = ''
        }}
      />
    </>
  )
}
