import { useCallback, useEffect, useRef, useState } from 'react'
import { CheckCircle, UploadSimple, WarningCircle } from '@phosphor-icons/react'
import * as api from './api'
import { ConfirmDeleteDialog } from './components/ConfirmDeleteDialog'
import { DetailsDialog } from './components/DetailsDialog'
import { FileBrowser } from './components/FileBrowser'
import { MediaViewerDialog } from './components/MediaViewerDialog'
import { PairingGate } from './components/PairingGate'
import { SearchOverlay } from './components/SearchOverlay'
import { Sidebar } from './components/Sidebar'
import { Topbar } from './components/Topbar'
import { TransferToast } from './components/TransferToast'
import { UploadQueueDialog } from './components/UploadQueueDialog'
import { categories } from './data'
import type {
  AppCounts,
  AppScope,
  CategoryId,
  ClipboardMessage,
  Entry,
  FileEntry,
  FolderEntry,
  PairResult,
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

const LIBRARY_PAGE_SIZE = 250

function fileKey(file: File): string {
  return `${file.name}\u0000${file.size}\u0000${file.lastModified}`
}

function newTaskId(prefix: string): string {
  const suffix = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}-${suffix}`
}

function createSpeedMeter(): (phase: string, loaded: number) => number {
  let phase = ''
  let loaded = 0
  let time = performance.now()
  let speed = 0
  return (nextPhase, nextLoaded) => {
    const now = performance.now()
    if (nextPhase !== phase) {
      phase = nextPhase
      loaded = nextLoaded
      time = now
      speed = 0
      return 0
    }
    const elapsed = now - time
    if (elapsed < 250) return speed
    const instant = Math.max(0, ((nextLoaded - loaded) * 1000) / elapsed)
    speed = speed > 0 ? speed * 0.65 + instant * 0.35 : instant
    loaded = nextLoaded
    time = now
    return speed
  }
}

function mergeTasks(local: TransferRecord[], server: TransferRecord[]): TransferRecord[] {
  const merged = new Map<string, TransferRecord>()
  server.forEach((task) => merged.set(task.id, task))
  local.forEach((task) => {
    const current = merged.get(task.id)
    if (!current || task.status === 'active') {
      merged.set(task.id, { ...current, ...task, local: true })
    }
  })
  return Array.from(merged.values()).sort((a, b) => {
    if (a.status === 'active' && b.status !== 'active') return -1
    if (b.status === 'active' && a.status !== 'active') return 1
    return 0
  })
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
  const [totalItems, setTotalItems] = useState(0)
  const [page, setPage] = useState(1)
  const [sortBy, setSortBy] = useState<'modified' | 'name' | 'size'>('modified')
  const [appCounts, setAppCounts] = useState<AppCounts>({ all: 0, system: 0, user: 0 })
  const [clipboardMessages, setClipboardMessages] = useState<ClipboardMessage[]>([])
  const [transfers, setTransfers] = useState<TransferRecord[]>([])
  const [toastRecords, setToastRecords] = useState<TransferRecord[]>([])
  const [toastOpen, setToastOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchOrigin, setSearchOrigin] = useState<DOMRect | null>(null)
  const [selected, setSelected] = useState<Entry | null>(null)
  const [pendingDelete, setPendingDelete] = useState<Array<FileEntry | FolderEntry> | null>(null)
  const [pendingTransferId, setPendingTransferId] = useState<string | null>(null)
  const [mediaEntry, setMediaEntry] = useState<FileEntry | null>(null)
  const [dragging, setDragging] = useState(false)
  const [uploadQueue, setUploadQueue] = useState<File[]>([])
  const [uploadQueueOpen, setUploadQueueOpen] = useState(false)
  const [connectionLost, setConnectionLost] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const contentRef = useRef<HTMLDivElement>(null)
  const mobileMenuRef = useRef<HTMLButtonElement>(null)
  const abortRef = useRef(new Map<string, () => void>())
  const lastRefreshRef = useRef(refreshKey)

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
      setPage(1)
      setSelected(null)
      contentRef.current?.scrollTo({ top: 0 })
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  const handleDisconnect = useCallback(() => {
    void api.logout()
    abortRef.current.forEach((abort) => abort())
    abortRef.current.clear()
    setPaired(false)
    setEntries([])
    setClipboardMessages([])
    setTransfers([])
    setToastRecords([])
    setToastOpen(false)
    setSelected(null)
    setPendingDelete(null)
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

    let disposed = false
    let socket: WebSocket | null = null
    let opened = false
    let lastPong = Date.now()
    let pollTimer: number | null = null
    let heartbeatTimer: number | null = null
    let watchdogTimer: number | null = null
    let lastPollOk: boolean | null = null

    const clearPolling = () => {
      if (pollTimer !== null) {
        window.clearInterval(pollTimer)
        pollTimer = null
      }
    }

    // WebSocket 不可用或断开后才回退到 HTTP 探活。
    const startPolling = () => {
      if (disposed || pollTimer !== null) return
      const check = async () => {
        const ok = await api.ping()
        if (disposed) return
        if (ok && lastPollOk === false) setRefreshKey((value) => value + 1)
        lastPollOk = ok
        setConnectionLost(!ok)
      }
      void check()
      pollTimer = window.setInterval(() => void check(), 5000)
    }

    const url = api.websocketUrl()
    if (!url || typeof WebSocket === 'undefined') {
      startPolling()
      return () => {
        disposed = true
        clearPolling()
      }
    }

    try {
      socket = new WebSocket(url)
    } catch {
      startPolling()
      return () => {
        disposed = true
        clearPolling()
      }
    }

    watchdogTimer = window.setTimeout(() => {
      if (disposed || !socket || socket.readyState === WebSocket.OPEN) return
      try {
        socket.close()
      } catch {
        // ignore
      }
      startPolling()
    }, 4000)

    socket.onopen = () => {
      if (disposed) return
      opened = true
      setConnectionLost(false)
      clearPolling()
    }
    socket.onmessage = (event) => {
      if (disposed) return
      lastPong = Date.now()
      if (event.data === 'pong') {
        setConnectionLost(false)
        return
      }
      if (typeof event.data !== 'string' || !event.data.startsWith('{')) return
      try {
        const payload = JSON.parse(event.data) as { type?: string; item?: ClipboardMessage }
        if (payload.type === 'clipboard' && payload.item) {
          const item = payload.item
          setClipboardMessages((previous) => (
            previous.some((message) => message.id === item.id) ? previous : [...previous, item]
          ))
        }
      } catch {
        // 非 JSON 心跳或未来扩展消息
      }
    }
    socket.onerror = () => {
      // 随后的 onclose 会切换到回退轮询
    }
    socket.onclose = () => {
      if (disposed) return
      if (watchdogTimer !== null) {
        window.clearTimeout(watchdogTimer)
        watchdogTimer = null
      }
      if (opened) setConnectionLost(true)
      startPolling()
    }

    heartbeatTimer = window.setInterval(() => {
      if (!socket || socket.readyState !== WebSocket.OPEN) return
      const started = Date.now()
      lastPong = started
      try {
        socket.send('ping')
      } catch {
        return
      }
      window.setTimeout(() => {
        if (!disposed && socket?.readyState === WebSocket.OPEN && lastPong <= started) {
          try {
            socket.close()
          } catch {
            // ignore
          }
        }
      }, 6000)
    }, 20000)

    return () => {
      disposed = true
      clearPolling()
      if (watchdogTimer !== null) window.clearTimeout(watchdogTimer)
      if (heartbeatTimer !== null) window.clearInterval(heartbeatTimer)
      try {
        socket?.close()
      } catch {
        // ignore
      }
    }
  }, [paired])

  useEffect(() => {
    if (!paired) return
    const folderPath = folder?.path ?? ''
    const force = refreshKey !== lastRefreshRef.current
    lastRefreshRef.current = refreshKey
    let cancelled = false
    const timer = window.setTimeout(async () => {
      setLoading(true)
      setError(null)
      try {
        if (category === 'apps') {
          const result = await api.listApps(appsScope, query)
          if (!cancelled) {
            setEntries(result.entries)
            setTotalItems(result.entries.length)
            setAppCounts(result.counts)
            setConnectionLost(false)
          }
        } else if (category === 'transfers') {
          const result = await api.listTransfers()
          if (!cancelled) {
            setTransfers((previous) => mergeTasks(previous, result))
            setEntries([])
            setTotalItems(0)
            setConnectionLost(false)
          }
        } else if (category === 'clipboard') {
          const result = await api.listClipboard()
          if (!cancelled) {
            setClipboardMessages(result)
            setEntries([])
            setTotalItems(0)
            setConnectionLost(false)
          }
        } else {
          const result = await api.listFiles(category, folderPath, query, page, force, sortBy)
          if (!cancelled) {
            setEntries(result.items)
            setTotalItems(result.total)
            if (result.page !== page) setPage(result.page)
            setFolder((previous) => {
              if (!previous || previous.path !== folderPath) return previous
              const loadedSize = result.items.reduce((sum, entry) => sum + entry.size, 0)
              const first = result.items[0]
              const modified = first && 'modified' in first ? first.modified : previous.modified
              return {
                ...previous,
                itemCount: result.total,
                size: loadedSize,
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
  }, [appsScope, category, folder?.path, handleDisconnect, page, paired, query, refreshKey, sortBy])

  useEffect(() => {
    if (!paired || category !== 'clipboard') return
    let cancelled = false
    const load = async () => {
      try {
        const messages = await api.listClipboard()
        if (!cancelled) setClipboardMessages(messages)
      } catch (caught) {
        if (caught instanceof api.UnauthorizedError) handleDisconnect()
      }
    }
    const timer = window.setInterval(() => void load(), 2500)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [category, handleDisconnect, paired])

  const scrollTop = useCallback(() => {
    contentRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
  }, [])

  const handlePageChange = useCallback((next: number) => {
    setPage(Math.max(1, next))
    scrollTop()
  }, [scrollTop])

  const handleSortChange = useCallback((next: 'modified' | 'name' | 'size') => {
    setSortBy(next)
    setPage(1)
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

  const openMedia = useCallback((entry: FileEntry) => {
    setSelected(null)
    setMediaEntry(entry)
  }, [])

  const closeMedia = useCallback(() => setMediaEntry(null), [])

  const openSearch = useCallback((rect: DOMRect | null) => {
    setSearchOrigin(rect)
    setSearchOpen(true)
  }, [])

  const closeSearch = useCallback(() => {
    setSearchOpen(false)
    setSearchOrigin(null)
  }, [])

  const updateTask = useCallback((id: string, patch: Partial<TransferRecord>) => {
    setTransfers((previous) => previous.map((task) => (task.id === id ? { ...task, ...patch } : task)))
    setToastRecords((previous) => previous.map((task) => (task.id === id ? { ...task, ...patch } : task)))
  }, [])

  const refreshTasks = useCallback(async () => {
    try {
      const serverTasks = await api.listTransfers()
      setTransfers((previous) => mergeTasks(previous, serverTasks))
    } catch {
      // 任务列表刷新失败不影响正在进行的传输
    }
  }, [])

  const cancelTask = useCallback((id: string) => {
    abortRef.current.get(id)?.()
    abortRef.current.delete(id)
    updateTask(id, { status: 'cancelled', phase: undefined, speed: undefined, createdAt: '已取消' })
    setToastRecords((previous) => previous.filter((task) => task.id !== id))
    void api.deleteTransfer(id).catch(() => {})
    setNotice('已取消传输')
  }, [updateTask])

  const deleteTask = useCallback((id: string) => {
    abortRef.current.get(id)?.()
    abortRef.current.delete(id)
    setTransfers((previous) => previous.filter((task) => task.id !== id))
    setToastRecords((previous) => previous.filter((task) => task.id !== id))
    void api.deleteTransfer(id).catch(() => {})
  }, [])

  const closeDelete = useCallback(() => {
    setPendingDelete(null)
    setPendingTransferId(null)
  }, [])

  const requestDelete = useCallback((entries: Entry[]) => {
    const deletable = entries.filter(
      (entry): entry is FileEntry | FolderEntry => entry.type === 'file' || entry.type === 'folder',
    )
    if (deletable.length > 0) {
      setPendingTransferId(null)
      setPendingDelete(deletable)
    }
  }, [])

  const requestDeleteTransferFile = useCallback((record: TransferRecord) => {
    if (!record.path) return
    setPendingTransferId(record.id)
    setPendingDelete([{
      type: 'file',
      id: record.path,
      name: record.name,
      kind: 'file',
      size: record.size,
      modified: record.createdAt,
      path: record.path,
    }])
  }, [])

  const confirmDelete = useCallback(async () => {
    if (!pendingDelete || pendingDelete.length === 0) return
    try {
      const result = await api.deleteFiles(pendingDelete.map((file) => file.path))
      if (result.failed.length > 0) {
        throw new Error(`${result.failed.length} 个文件删除失败`)
      }
      if (pendingTransferId) {
        await api.deleteTransfer(pendingTransferId)
        setTransfers((previous) => previous.filter((task) => task.id !== pendingTransferId))
        setToastRecords((previous) => previous.filter((task) => task.id !== pendingTransferId))
      }
      setNotice(`已删除 ${result.deleted.length} 个文件`)
      setPendingDelete(null)
      setPendingTransferId(null)
      setRefreshKey((value) => value + 1)
    } catch (caught) {
      if (caught instanceof api.UnauthorizedError) {
        handleDisconnect()
        throw caught
      }
      throw new Error(messageForError(caught))
    }
  }, [handleDisconnect, pendingDelete, pendingTransferId])

  const handleSelectCategory = useCallback((next: CategoryId) => {
    setCategory(next)
    setFolder(null)
    setAppsScope('all')
    setQuery('')
    setPage(1)
    setSelected(null)
    syncUrl(next, '', '', 'all')
    closeSidebar()
    scrollTop()
  }, [closeSidebar, scrollTop, syncUrl])

  const completePair = useCallback((result: PairResult) => {
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
    setPage(1)
    setTotalItems(0)
    setEntries([])
    setClipboardMessages([])
    setTransfers([])
    setToastRecords([])
    setPendingDelete(null)
    setConnectionLost(false)
    syncUrl('storage', '', '', 'all', true)
    setNotice(`已连接 ${result.device.name}`)
  }, [syncUrl])

  const handlePair = useCallback(async (code: string) => {
    completePair(await api.pair(code))
  }, [completePair])

  const handleInteractivePair = useCallback(async (onChallenge: (number: number) => void) => {
    const request = await api.requestInteractivePairing()
    onChallenge(request.number)
    completePair(await api.waitForInteractivePairing(request.id))
  }, [completePair])

  const handleOpenFolder = useCallback((next: FolderEntry) => {
    setFolder(next)
    setQuery('')
    setPage(1)
    setSelected(null)
    syncUrl('storage', next.path, '', 'all')
    scrollTop()
  }, [scrollTop, syncUrl])

  const handleGoRoot = useCallback(() => {
    setFolder(null)
    setQuery('')
    setPage(1)
    syncUrl('storage', '', '', 'all')
    scrollTop()
  }, [scrollTop, syncUrl])

  const handleGoUp = useCallback(() => {
    if (!folder) return
    const parent = folder.path.split('/').filter(Boolean).slice(0, -1).join('/')
    if (!parent) {
      handleGoRoot()
      return
    }
    setFolder(folderFromPath(parent))
    setQuery('')
    setPage(1)
    setSelected(null)
    syncUrl('storage', parent, '', 'all')
    scrollTop()
  }, [folder, handleGoRoot, scrollTop, syncUrl])

  const handleNavigatePath = useCallback((path: string) => {
    setFolder(folderFromPath(path))
    setQuery('')
    setPage(1)
    setSelected(null)
    syncUrl('storage', path, '', 'all')
    scrollTop()
  }, [scrollTop, syncUrl])

  const handleQueryChange = useCallback((next: string) => {
    setQuery(next)
    setPage(1)
    syncUrl(category, folder?.path ?? '', next, appsScope, true)
  }, [appsScope, category, folder?.path, syncUrl])

  // 页面没有输入焦点时，直接敲字就打开搜索，并把第一个字符带进去。
  useEffect(() => {
    if (
      !paired ||
      searchOpen ||
      sidebarOpen ||
      selected ||
      mediaEntry ||
      pendingDelete ||
      uploadQueueOpen
    ) {
      return
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return
      if (event.isComposing || event.key === 'Process' || event.keyCode === 229) return
      if (event.key.length !== 1 || event.key === ' ') return
      const target = event.target
      if (target instanceof HTMLElement) {
        const tag = target.tagName
        if (target.isContentEditable || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
          return
        }
      }
      event.preventDefault()
      handleQueryChange(event.key)
      openSearch(null)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [
    handleQueryChange,
    mediaEntry,
    openSearch,
    paired,
    pendingDelete,
    searchOpen,
    selected,
    sidebarOpen,
    uploadQueueOpen,
  ])

  const handleScopeChange = useCallback((next: AppScope | 'all') => {
    setAppsScope(next)
    setPage(1)
    syncUrl('apps', '', query, next, true)
  }, [query, syncUrl])

  const clearQuery = useCallback(() => {
    setQuery('')
    setPage(1)
    syncUrl(category, folder?.path ?? '', '', appsScope, true)
  }, [appsScope, category, folder?.path, syncUrl])

  const handleViewTransfers = useCallback(() => {
    setCategory('transfers')
    setFolder(null)
    setQuery('')
    setPage(1)
    // 有任务进行时进度面板保持常驻，切到传输记录也不收起。
    if (!toastRecords.some((record) => record.status === 'active')) {
      setToastOpen(false)
    }
    syncUrl('transfers', '', '', 'all')
    closeSidebar()
    scrollTop()
  }, [closeSidebar, scrollTop, syncUrl, toastRecords])

  const handleUpload = useCallback(async (files: FileList | File[]) => {
    if (!paired) return
    const list = Array.from(files)
    if (list.length === 0) return
    const records: TransferRecord[] = list.map((file) => ({
      id: newTaskId('upload'),
      name: file.name,
      size: file.size,
      direction: 'upload',
      progress: 1,
      status: 'active',
      createdAt: '准备传输',
      phase: api.isEncryptionActive() ? 'encrypt' : 'upload',
      local: true,
    }))
    setToastRecords((previous) => [
      ...records,
      ...previous.filter((task) => !records.some((record) => record.id === task.id)),
    ])
    setToastOpen(true)
    setTransfers((previous) => [
      ...records,
      ...previous.filter((task) => !records.some((record) => record.id === task.id)),
    ])

    for (let index = 0; index < list.length; index += 1) {
      const file = list[index]
      const record = records[index]
      const controller = new AbortController()
      abortRef.current.set(record.id, () => controller.abort())
      const measure = createSpeedMeter()
      try {
        await api.uploadFile(
          file,
          record.id,
          (phase, ratio, loaded) => {
            const progress = phase === 'encrypt'
              ? Math.max(1, Math.min(14, Math.round(ratio * 14)))
              : Math.max(15, Math.min(99, 15 + Math.round(ratio * 84)))
            updateTask(record.id, {
              phase,
              progress,
              speed: measure(phase, loaded),
              createdAt: phase === 'encrypt' ? '正在加密' : '正在上传',
            })
          },
          controller.signal,
        )
        updateTask(record.id, { progress: 100, status: 'done', phase: undefined, speed: undefined, createdAt: '刚刚' })
        abortRef.current.delete(record.id)
      } catch (caught) {
        abortRef.current.delete(record.id)
        if (caught instanceof api.UnauthorizedError) {
          handleDisconnect()
          return
        }
        if (caught instanceof api.ApiError && caught.code === 'aborted') {
          updateTask(record.id, { status: 'cancelled', phase: undefined, speed: undefined, createdAt: '已取消' })
        } else {
          updateTask(record.id, { status: 'failed', phase: undefined, speed: undefined, createdAt: '失败' })
          setToastRecords((previous) => previous.filter((task) => task.id !== record.id))
          if (caught instanceof api.ApiError && (caught.status === 0 || caught.code === 'network')) {
            setConnectionLost(true)
          }
          setNotice(messageForError(caught))
        }
      }
    }
    setPage(1)
    setRefreshKey((value) => value + 1)
    await refreshTasks()
  }, [handleDisconnect, paired, refreshTasks, updateTask])

  const addQueuedFiles = useCallback((files: FileList | File[]) => {
    setUploadQueue((previous) => {
      const next = [...previous]
      const keys = new Set(previous.map(fileKey))
      for (const file of Array.from(files)) {
        const key = fileKey(file)
        if (!keys.has(key)) {
          keys.add(key)
          next.push(file)
        }
      }
      return next
    })
  }, [])

  const openUploadQueue = useCallback((files?: FileList | File[]) => {
    if (!paired) return
    if (files) addQueuedFiles(files)
    setUploadQueueOpen(true)
  }, [addQueuedFiles, paired])

  const closeUploadQueue = useCallback(() => {
    setUploadQueueOpen(false)
    setUploadQueue([])
  }, [])

  const confirmUploadQueue = useCallback(() => {
    const files = uploadQueue
    setUploadQueueOpen(false)
    setUploadQueue([])
    if (files.length > 0) void handleUpload(files)
  }, [handleUpload, uploadQueue])

  const handleDownload = useCallback(async (entry: Entry) => {
    const id = newTaskId('download')
    const record: TransferRecord = {
      id,
      name: entry.name,
      size: entry.size,
      direction: 'download',
      progress: 1,
      status: 'active',
      createdAt: '正在下载',
      phase: 'download',
      local: true,
    }
    setToastRecords((previous) => [
      record,
      ...previous.filter((task) => task.id !== record.id),
    ])
    setToastOpen(true)
    setTransfers((previous) => [record, ...previous])
    const controller = new AbortController()
    abortRef.current.set(id, () => controller.abort())
    try {
      const measure = createSpeedMeter()
      const name = await api.downloadEntry(entry, id, (phase, ratio, loaded) => {
        updateTask(id, {
          phase,
          progress: phase === 'decrypt' ? 90 + Math.round(ratio * 10) : Math.round(ratio * 90),
          speed: measure(phase, loaded),
          createdAt: phase === 'decrypt' ? '正在解密' : '正在下载',
        })
      }, controller.signal)
      updateTask(id, { name, progress: 100, status: 'done', phase: undefined, speed: undefined, createdAt: '刚刚' })
      abortRef.current.delete(id)
      setSelected(null)
      setNotice(`已下载 ${name}`)
      await refreshTasks()
    } catch (caught) {
      abortRef.current.delete(id)
      if (caught instanceof api.UnauthorizedError) {
        handleDisconnect()
        return
      }
      if (caught instanceof api.ApiError && caught.code === 'aborted') {
        updateTask(id, { status: 'cancelled', phase: undefined, speed: undefined, createdAt: '已取消' })
      } else {
        updateTask(id, { status: 'failed', phase: undefined, speed: undefined, createdAt: '失败' })
        setToastRecords((previous) => previous.filter((task) => task.id !== id))
        if (caught instanceof api.ApiError && (caught.status === 0 || caught.code === 'network')) {
          setConnectionLost(true)
        }
        setNotice(messageForError(caught))
      }
    }
  }, [handleDisconnect, refreshTasks, updateTask])

  const handleBatchDownload = useCallback(async (selected: Entry[]) => {
    for (const entry of selected) {
      await handleDownload(entry)
      await new Promise((resolve) => window.setTimeout(resolve, 180))
    }
  }, [handleDownload])

  const handleCopyText = useCallback((text: string, label: string) => {
    void copyToClipboard(text).then((copied) => {
      setNotice(copied ? `已复制${label}` : '浏览器拒绝了剪贴板访问')
    })
  }, [])

  const handleCopy = useCallback((entry: FileEntry) => {
    const text = entry.text ?? entry.name
    void copyToClipboard(text).then((copied) => {
      setNotice(copied ? '已复制到电脑剪贴板' : '浏览器拒绝了剪贴板访问')
    })
  }, [])

  const handleSendClipboard = useCallback(async (text: string) => {
    try {
      const message = await api.sendClipboard(text)
      setClipboardMessages((previous) => [
        ...previous.filter((item) => item.id !== message.id),
        message,
      ])
      setNotice('已发送到手机剪贴板')
    } catch (caught) {
      if (caught instanceof api.UnauthorizedError) {
        handleDisconnect()
        return
      }
      setNotice(messageForError(caught))
    }
  }, [handleDisconnect])

  const handleCopyClipboard = useCallback((text: string) => {
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
      if (event.dataTransfer?.files.length) openUploadQueue(event.dataTransfer.files)
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
  }, [openUploadQueue, paired])

  const categoryLabel = categories.find((item) => item.id === category)?.label ?? '手机文件'
  const pageLabel = folder?.name || categoryLabel
  const port = info?.port ?? (Number(window.location.port) || null)
  const activeTasks = transfers.filter((task) => task.status === 'active')
  const activeProgress = activeTasks.length > 0
    ? Math.round(activeTasks.reduce((total, task) => total + task.progress, 0) / activeTasks.length)
    : 0

  useEffect(() => {
    document.title = paired ? `隔空闪传 · ${pageLabel}` : '隔空闪传 · 配对'
  }, [paired, pageLabel])

  return (
    <>
      {paired && activeTasks.length > 0 && (
        <div
          className="global-progress"
          role="progressbar"
          aria-label="当前传输进度"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={activeProgress}
        >
          <span style={{ width: `${activeProgress}%` }} />
        </div>
      )}
      <div
        className={`app-shell${sidebarOpen ? ' sidebar-open' : ''}${!paired ? ' is-locked' : ''}`}
        aria-hidden={!paired || undefined}
        inert={!paired || Boolean(selected) || Boolean(mediaEntry) || Boolean(pendingDelete) || searchOpen || uploadQueueOpen || undefined}
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
            onUpload={() => openUploadQueue()}
            theme={theme}
            onToggleTheme={() => setTheme((current) => (current === 'dark' ? 'light' : 'dark'))}
            sidebarOpen={sidebarOpen}
            onOpenSidebar={() => setSidebarOpen(true)}
            menuButtonRef={mobileMenuRef}
            searchOpen={searchOpen}
            onOpenSearch={openSearch}
          />

          <main className="content-scroll" ref={contentRef}>
            <FileBrowser
              category={category}
              categoryLabel={categoryLabel}
              entries={entries}
              totalItems={totalItems}
              page={page}
              pageSize={LIBRARY_PAGE_SIZE}
              sortBy={sortBy}
              folder={folder}
              query={query}
              appsScope={appsScope}
              appCounts={appCounts}
              transfers={transfers}
              clipboardMessages={clipboardMessages}
              loading={loading}
              error={error}
              onScopeChange={handleScopeChange}
              onOpenFolder={handleOpenFolder}
              onGoRoot={handleGoRoot}
              onGoUp={handleGoUp}
              onNavigatePath={handleNavigatePath}
              onOpenEntry={setSelected}
              onOpenMedia={openMedia}
              onDownload={(entry) => void handleDownload(entry)}
              onCopyText={handleCopyText}
              onUpload={() => openUploadQueue()}
              onClearQuery={clearQuery}
              onRetry={() => setRefreshKey((value) => value + 1)}
              onPageChange={handlePageChange}
              onSortChange={handleSortChange}
              onCancelTask={cancelTask}
              onDeleteTask={deleteTask}
              onDeleteFile={(entry) => requestDelete([entry])}
              onDeleteFiles={requestDelete}
              onDeleteTransferFile={requestDeleteTransferFile}
              onSendClipboard={handleSendClipboard}
              onCopyClipboard={handleCopyClipboard}
              onBatchDownload={(selected) => void handleBatchDownload(selected)}
            />
          </main>

          {dragging && (
            <div className="drop-overlay" aria-hidden="true">
              <div className="drop-card">
                <span className="drop-icon">
                  <UploadSimple size={28} weight="regular" />
                </span>
                <strong>松手，加入传输队列</strong>
                <span>确认后会批量上传到手机 Download/toTool/FlashDrop</span>
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

      {searchOpen && paired && (
        <SearchOverlay
          query={query}
          onQueryChange={handleQueryChange}
          onClose={closeSearch}
          originRect={searchOrigin}
        />
      )}

      <PairingGate
        open={!paired}
        device={info?.device ?? null}
        port={port}
        onSubmit={handlePair}
        onInteractive={handleInteractivePair}
      />

      <DetailsDialog
        entry={selected}
        onClose={closeDetails}
        onDownload={(entry) => void handleDownload(entry)}
        onCopy={handleCopy}
        onOpenMedia={openMedia}
      />

      <MediaViewerDialog
        entry={mediaEntry}
        onClose={closeMedia}
        onDownload={(entry) => {
          setMediaEntry(null)
          void handleDownload(entry)
        }}
      />

      {pendingDelete && (
        <ConfirmDeleteDialog
          entries={pendingDelete}
          onClose={closeDelete}
          onConfirm={confirmDelete}
        />
      )}

      {uploadQueueOpen && (
        <UploadQueueDialog
          files={uploadQueue}
          onAdd={addQueuedFiles}
          onRemove={(index) => setUploadQueue((previous) => previous.filter((_, i) => i !== index))}
          onClear={() => setUploadQueue([])}
          onClose={closeUploadQueue}
          onConfirm={confirmUploadQueue}
        />
      )}

      <TransferToast
        records={toastRecords}
        visible={toastOpen}
        onClose={() => {
          if (!toastRecords.some((record) => record.status === 'active')) setToastOpen(false)
        }}
        onView={handleViewTransfers}
      />

      {notice && (
        <div className="notice" role="status">
          <CheckCircle size={16} weight="fill" aria-hidden="true" />
          {notice}
        </div>
      )}

    </>
  )
}
