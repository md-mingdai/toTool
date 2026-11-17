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

export default function App() {
  const [theme, setTheme] = useState<Theme>(initialTheme)
  const [paired, setPaired] = useState(api.hasSession())
  const [info, setInfo] = useState<ServerInfo | null>(null)
  const [encryptionEnabled, setEncryptionEnabled] = useState(api.isEncryptionActive())
  const [category, setCategory] = useState<CategoryId>('apps')
  const [folder, setFolder] = useState<FolderEntry | null>(null)
  const [appsScope, setAppsScope] = useState<AppScope | 'all'>('all')
  const [query, setQuery] = useState('')
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
          const result = await api.listFiles(category, folder?.path ?? '', query)
          if (!cancelled) {
            setEntries(result)
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
    closeSidebar()
    scrollTop()
  }, [closeSidebar, scrollTop])

  const handlePair = useCallback(async (code: string) => {
    const result = await api.pair(code)
    setInfo({
      port: result.port,
      device: result.device,
      encryptionEnabled: result.encryption.enabled,
    })
    setEncryptionEnabled(result.encryption.enabled)
    setPaired(true)
    setCategory('apps')
    setFolder(null)
    setAppsScope('all')
    setQuery('')
    setEntries([])
    setTransfers([])
    setToastRecords([])
    setConnectionLost(false)
    setNotice(`已连接 ${result.device.name}`)
  }, [])
  const handleOpenFolder = useCallback((next: FolderEntry) => {
    setFolder(next)
    setQuery('')
    setSelected(null)
    scrollTop()
  }, [scrollTop])

  const handleGoRoot = useCallback(() => {
    setFolder(null)
    setQuery('')
    scrollTop()
  }, [scrollTop])

  const handleViewTransfers = useCallback(() => {
    setCategory('transfers')
    setFolder(null)
    setQuery('')
    setToastOpen(false)
    closeSidebar()
    scrollTop()
  }, [closeSidebar, scrollTop])

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
    try {
      const name = await api.downloadEntry(entry)
      setNotice(`已开始下载 ${name}`)
      setSelected(null)
      const records = await api.listTransfers()
      setTransfers(records)
      setToastRecords(records.slice(0, 3))
      setToastOpen(true)
    } catch (caught) {
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
            onQueryChange={setQuery}
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
              onScopeChange={setAppsScope}
              onOpenFolder={handleOpenFolder}
              onGoRoot={handleGoRoot}
              onOpenEntry={setSelected}
              onDownload={(entry) => void handleDownload(entry)}
              onCopy={handleCopy}
              onUpload={() => inputRef.current?.click()}
              onClearQuery={() => setQuery('')}
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
                <span>文件会保存到手机的 Download 文件夹</span>
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
