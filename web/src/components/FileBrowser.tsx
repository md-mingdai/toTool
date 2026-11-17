import {
  AppWindow,
  ArrowClockwise,
  ArrowLeft,
  CaretRight,
  Check,
  Copy,
  DownloadSimple,
  File as FileIcon,
  FileArchive,
  FileAudio,
  FilePdf,
  FileText,
  FileVideo,
  Folder,
  Image as ImageIcon,
  Package,
  Plus,
  Trash,
  UploadSimple,
  WarningCircle,
  X,
} from '@phosphor-icons/react'
import { Fragment, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { categories, entryKindLabel, formatBytes, formatSpeed, totalBytes, transferPhaseLabel } from '../data'
import type {
  AppCounts,
  AppEntry,
  AppScope,
  CategoryId,
  ClipboardMessage,
  Entry,
  FileEntry,
  FolderEntry,
  TransferRecord,
} from '../types'
import { AppIconTile } from './AppIconTile'
import { CategoryIcon } from './CategoryIcon'
import { ClipboardChat } from './ClipboardChat'

interface FileBrowserProps {
  category: CategoryId
  categoryLabel: string
  entries: Entry[]
  folder: FolderEntry | null
  query: string
  appsScope: AppScope | 'all'
  appCounts: AppCounts
  transfers: TransferRecord[]
  clipboardMessages: ClipboardMessage[]
  loading: boolean
  error: string | null
  onScopeChange: (scope: AppScope | 'all') => void
  onOpenFolder: (folder: FolderEntry) => void
  onGoRoot: () => void
  onGoUp: () => void
  onNavigatePath: (path: string) => void
  onOpenEntry: (entry: Entry) => void
  onDownload: (entry: Entry) => void
  onCopy: (entry: FileEntry) => void
  onUpload: () => void
  onClearQuery: () => void
  onRetry: () => void
  onCancelTask: (id: string) => void
  onDeleteTask: (id: string) => void
  onSendClipboard: (text: string) => Promise<void>
  onCopyClipboard: (text: string) => void
  onBatchDownload: (entries: Entry[]) => void
  onDeleteFile: (entry: FileEntry | FolderEntry) => void
  onDeleteFiles: (entries: Entry[]) => void
}

function FileGlyph({ kind, size = 20 }: { kind: FileEntry['kind']; size?: number }) {
  switch (kind) {
    case 'image':
      return <ImageIcon size={size} weight="regular" />
    case 'video':
      return <FileVideo size={size} weight="regular" />
    case 'audio':
      return <FileAudio size={size} weight="regular" />
    case 'document':
      return <FilePdf size={size} weight="regular" />
    case 'archive':
      return <FileArchive size={size} weight="regular" />
    case 'apk':
      return <Package size={size} weight="regular" />
    case 'text':
      return <FileText size={size} weight="regular" />
    case 'folder':
      return <Folder size={size} weight="regular" />
    default:
      return <FileIcon size={size} weight="regular" />
  }
}

function EntryGlyph({ entry }: { entry: Entry }) {
  if (entry.type === 'app') {
    return <AppIconTile iconUrl={entry.iconUrl} name={entry.name} />
  }
  if (entry.type === 'folder') {
    return (
      <span className="entry-tile file-tile is-folder" aria-hidden="true">
        <Folder size={20} weight="regular" />
      </span>
    )
  }
  if (entry.thumbUrl) {
    return (
      <span
        className="entry-thumb"
        style={{ backgroundImage: `url(${entry.thumbUrl})` }}
        aria-hidden="true"
      />
    )
  }
  return (
    <span className={`entry-tile file-tile is-${entry.kind}`} aria-hidden="true">
      <FileGlyph kind={entry.kind} />
    </span>
  )
}

function Highlight({ text, query }: { text: string; query: string }) {
  const needle = query.trim().toLowerCase()
  if (!needle) return <>{text}</>
  const index = text.toLowerCase().indexOf(needle)
  if (index < 0) return <>{text}</>
  return (
    <>
      {text.slice(0, index)}
      <mark>{text.slice(index, index + needle.length)}</mark>
      {text.slice(index + needle.length)}
    </>
  )
}

function entryMeta(entry: Entry): ReactNode {
  if (entry.type === 'app') {
    return (
      <>
        <span className="meta-primary">{entry.packageName}</span>
        <span className="meta-dot" aria-hidden="true" />
        <span>{formatBytes(entry.size)}</span>
      </>
    )
  }
  if (entry.type === 'folder') {
    return (
      <>
        <span>{entry.itemCount.toLocaleString('zh-CN')} 项</span>
        {entry.size > 0 && (
          <>
            <span className="meta-dot" aria-hidden="true" />
            <span>{formatBytes(entry.size)}</span>
          </>
        )}
        <span className="meta-dot" aria-hidden="true" />
        <span>{entry.modified}</span>
      </>
    )
  }
  if (entry.kind === 'text' && entry.text) {
    return (
      <>
        <span className="meta-primary">{entry.text.replace(/\n/g, ' · ')}</span>
        <span className="meta-dot" aria-hidden="true" />
        <span>{entry.modified}</span>
      </>
    )
  }
  return (
    <>
      <span>{entryKindLabel(entry.kind)}</span>
      <span className="meta-dot" aria-hidden="true" />
      <span>{formatBytes(entry.size)}</span>
      <span className="meta-dot" aria-hidden="true" />
      <span>{entry.modified}</span>
    </>
  )
}

function rowAction(entry: Entry): { label: string; icon: ReactNode } {
  if (entry.type === 'folder') return { label: `打开 ${entry.name}`, icon: <CaretRight size={16} weight="regular" /> }
  if (entry.type === 'app') return { label: `下载 ${entry.name} 安装包`, icon: <DownloadSimple size={17} weight="regular" /> }
  if (entry.kind === 'text') return { label: `复制 ${entry.name}`, icon: <Copy size={16} weight="regular" /> }
  return { label: `下载 ${entry.name}`, icon: <DownloadSimple size={17} weight="regular" /> }
}

function LoadingRows() {
  return (
    <div className="skeleton-list" aria-label="正在读取" role="status">
      {Array.from({ length: 7 }).map((_, index) => (
        <div className="skeleton-row" key={index}>
          <span className="skeleton-icon" />
          <span className="skeleton-copy">
            <span className="skeleton-line skeleton-title" />
            <span className="skeleton-line skeleton-meta" />
          </span>
        </div>
      ))}
    </div>
  )
}

function ErrorState({ error, onRetry }: { error: string; onRetry: () => void }) {
  return (
    <div className="empty-state">
      <span className="empty-icon" aria-hidden="true">
        <WarningCircle size={26} weight="regular" />
      </span>
      <h2>读取失败</h2>
      <p>{error}</p>
      <button type="button" className="btn btn-ghost" onClick={onRetry}>
        <ArrowClockwise size={16} weight="regular" />
        重新读取
      </button>
    </div>
  )
}

function TransferList({
  transfers,
  onUpload,
  hasQuery,
  query,
  onClearQuery,
  onCancel,
  onDelete,
}: {
  transfers: TransferRecord[]
  onUpload: () => void
  hasQuery: boolean
  query: string
  onClearQuery: () => void
  onCancel: (id: string) => void
  onDelete: (id: string) => void
}) {
  if (transfers.length === 0) {
    return (
      <div className="empty-state">
        <span className="empty-icon" aria-hidden="true">
          <UploadSimple size={26} weight="regular" />
        </span>
        <h2>{hasQuery ? '没有找到传输记录' : '还没有传输记录'}</h2>
        <p>{hasQuery ? `没有与 “${query.trim()}” 匹配的传输记录。` : '从电脑上传文件到手机，或下载手机里的文件，记录会出现在这里。'}</p>
        {hasQuery ? (
          <button type="button" className="btn btn-ghost" onClick={onClearQuery}>清除搜索</button>
        ) : (
          <button type="button" className="btn btn-primary" onClick={onUpload}>
            <UploadSimple size={16} weight="regular" />
            传到手机
          </button>
        )}
      </div>
    )
  }

  return (
    <ul className="entry-list transfer-list">
      {transfers.map((record) => {
        const uploading = record.direction === 'upload'
        const active = record.status === 'active'
        const phaseLabel = transferPhaseLabel(record.phase)
        const statusText = active
          ? phaseLabel
            ? `${phaseLabel} ${Math.round(record.progress)}%`
            : `${Math.round(record.progress)}%`
          : record.status === 'done'
            ? '已完成'
            : record.status === 'cancelled'
              ? '已取消'
              : '失败'
        return (
          <li className="entry-row transfer-row" key={record.id}>
            <span className={`entry-tile transfer-tile ${uploading ? 'is-upload' : 'is-download'}`} aria-hidden="true">
              {record.status === 'done'
                ? <Check size={19} weight="bold" />
                : record.status === 'cancelled' || record.status === 'failed'
                  ? <WarningCircle size={19} weight="regular" />
                  : uploading
                    ? <UploadSimple size={19} weight="regular" />
                    : <DownloadSimple size={19} weight="regular" />}
            </span>
            <span className="entry-text">
              <span className="entry-title">
                <span className="entry-name">{record.name}</span>
                <span className={`transfer-status is-${record.status}`}>{statusText}</span>
              </span>
              <span className="entry-meta">
                <span>{uploading ? '传到手机' : '下载到电脑'}</span>
                <span className="meta-dot" aria-hidden="true" />
                <span>{formatBytes(record.size)}</span>
                {formatSpeed(record.speed) && (
                  <>
                    <span className="meta-dot" aria-hidden="true" />
                    <span>{formatSpeed(record.speed)}</span>
                  </>
                )}
                <span className="meta-dot" aria-hidden="true" />
                <span>{record.createdAt}</span>
              </span>
              {active && (
                <span className="inline-progress" role="progressbar" aria-label={`${record.name} 传输进度`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(record.progress)}>
                  <span style={{ width: `${record.progress}%` }} />
                </span>
              )}
            </span>
            <div className="entry-actions transfer-actions">
              <button
                type="button"
                className="row-action"
                onClick={() => (active ? onCancel(record.id) : onDelete(record.id))}
                aria-label={active ? `取消 ${record.name}` : `删除 ${record.name} 的记录`}
                title={active ? '取消任务' : '删除记录'}
              >
                {active ? <X size={16} weight="regular" /> : <Trash size={16} weight="regular" />}
              </button>
            </div>
          </li>
        )
      })}
    </ul>
  )
}

export function FileBrowser({
  category,
  categoryLabel,
  entries,
  folder,
  query,
  appsScope,
  appCounts,
  transfers,
  clipboardMessages,
  loading,
  error,
  onScopeChange,
  onOpenFolder,
  onGoRoot,
  onGoUp,
  onNavigatePath,
  onOpenEntry,
  onDownload,
  onCopy,
  onUpload,
  onClearQuery,
  onRetry,
  onCancelTask,
  onDeleteTask,
  onSendClipboard,
  onCopyClipboard,
  onBatchDownload,
  onDeleteFile,
  onDeleteFiles,
}: FileBrowserProps) {
  const categoryMeta = categories.find((item) => item.id === category)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [sortBy, setSortBy] = useState<'modified' | 'name' | 'size'>('modified')
  const sortedEntries = useMemo(() => {
    const list = [...entries]
    list.sort((a, b) => {
      const aFolder = a.type === 'folder'
      const bFolder = b.type === 'folder'
      if (aFolder !== bFolder) return aFolder ? -1 : 1
      if (sortBy === 'name') return a.name.localeCompare(b.name, 'zh-CN')
      if (sortBy === 'size') return b.size - a.size
      return (b.modifiedAt ?? 0) - (a.modifiedAt ?? 0)
    })
    return list
  }, [entries, sortBy])

  useEffect(() => {
    setSelectedIds(new Set())
  }, [category, folder?.path])

  useEffect(() => {
    setSelectedIds((previous) => {
      const available = new Set(entries.filter((entry) => entry.type !== 'folder').map((entry) => entry.id))
      const next = new Set([...previous].filter((id) => available.has(id)))
      return next.size === previous.size ? previous : next
    })
  }, [entries])

  const isApps = category === 'apps'
  const isTransfers = category === 'transfers'
  const isClipboard = category === 'clipboard'
  const total = totalBytes(entries)
  const hasQuery = query.trim().length > 0
  const visibleTransfers = hasQuery
    ? transfers.filter((record) => record.name.toLowerCase().includes(query.trim().toLowerCase()))
    : transfers
  const visibleClipboard = hasQuery
    ? clipboardMessages.filter((message) =>
      `${message.text} ${message.source === 'phone' ? '手机' : '电脑'}`.toLowerCase().includes(query.trim().toLowerCase()))
    : clipboardMessages
  const resultCount = isTransfers
    ? visibleTransfers.length
    : isClipboard
      ? visibleClipboard.length
      : entries.length

  const selectableCount = sortedEntries.filter((entry) => entry.type !== 'folder').length
  const selectedFiles = sortedEntries.filter(
    (entry): entry is FileEntry => entry.type === 'file' && selectedIds.has(entry.id),
  )

  const lede = folder
    ? `${folder.itemCount.toLocaleString('zh-CN')} 项${folder.size > 0 ? ` · ${formatBytes(folder.size)}` : ''}`
    : isApps
      ? `${appCounts.all} 个应用 · 系统 ${appCounts.system} · 用户 ${appCounts.user}`
      : isTransfers
        ? `${visibleTransfers.length} 条传输记录`
        : isClipboard
          ? `${visibleClipboard.length} 条剪贴板消息`
          : `${entries.length} 项 · ${formatBytes(total)}`

  return (
    <div className="content-inner">
      {folder && (() => {
        const parts = folder.path.split('/').filter(Boolean)
        return (
          <div className="folder-path">
            <button type="button" className="path-back" onClick={onGoRoot}>
              手机存储
            </button>
            {parts.map((part, index) => {
              const path = parts.slice(0, index + 1).join('/')
              const last = index === parts.length - 1
              return (
                <Fragment key={path}>
                  <CaretRight size={13} weight="regular" aria-hidden="true" />
                  {last ? (
                    <strong>{part}</strong>
                  ) : (
                    <button type="button" className="path-back" onClick={() => onNavigatePath(path)}>
                      {part}
                    </button>
                  )}
                </Fragment>
              )
            })}
          </div>
        )
      })()}

      <div className="page-head">
        <div className="page-title">
          <p className="eyebrow">{folder ? '手机存储' : categoryMeta?.group ?? '文件'}</p>
          <h1>{folder ? folder.name : categoryLabel}</h1>
          <p className="page-meta" aria-live="polite">
            {loading ? '正在读取…' : hasQuery ? `找到 ${resultCount} 项` : lede}
          </p>
        </div>
      </div>

      {isApps && (
        <div className="tabs" role="tablist" aria-label="应用范围">
          {([
            ['all', '全部应用', appCounts.all],
            ['system', '系统应用', appCounts.system],
            ['user', '用户应用', appCounts.user],
          ] as const).map(([scope, label, count]) => (
            <button
              key={scope}
              type="button"
              role="tab"
              aria-selected={appsScope === scope}
              className={`tab${appsScope === scope ? ' is-active' : ''}`}
              onClick={() => onScopeChange(scope)}
            >
              {label}
              <span>{count}</span>
            </button>
          ))}
        </div>
      )}

      <div className="file-toolbar">
        <div className="toolbar-nav">
          <button
            type="button"
            className="toolbar-icon"
            onClick={folder ? onGoUp : onGoRoot}
            disabled={!folder}
            aria-label="返回上一级"
            title="返回上一级"
          >
            <ArrowLeft size={17} weight="regular" />
          </button>
          {!isClipboard && !isTransfers && (
            <button type="button" className="toolbar-icon" onClick={onUpload} aria-label="传到手机" title="传到手机">
              <Plus size={17} weight="regular" />
            </button>
          )}
          <button type="button" className="toolbar-icon" onClick={onRetry} disabled={loading} aria-label="刷新" title="刷新">
            <ArrowClockwise className={loading ? 'spin' : ''} size={17} weight="regular" />
          </button>
          {!isClipboard && !isTransfers && (
            <label className="toolbar-check">
              <input
                type="checkbox"
                checked={selectableCount > 0 && selectedIds.size === selectableCount}
                disabled={selectableCount === 0}
                onChange={(event) => {
                  setSelectedIds(event.target.checked
                    ? new Set(sortedEntries.filter((entry) => entry.type !== 'folder').map((entry) => entry.id))
                    : new Set())
                }}
              />
              全选
            </label>
          )}
        </div>
        <div className="toolbar-meta">
          {selectedIds.size > 0 && <span className="toolbar-selected">已选 {selectedIds.size} 项</span>}
          {!isClipboard && !isTransfers && (
            <label className="sort-select">
              <span>排序</span>
              <select value={sortBy} onChange={(event) => setSortBy(event.target.value as typeof sortBy)} aria-label="排序方式">
                <option value="modified">修改时间</option>
                <option value="name">名称</option>
                <option value="size">大小</option>
              </select>
            </label>
          )}
          <span className="toolbar-count">
            {resultCount} 项
          </span>
        </div>
      </div>

      {!isTransfers && !isClipboard && selectedIds.size > 0 && (
        <div className="selection-bar">
          <span>已选择 {selectedIds.size} 项</span>
          <button type="button" className="btn btn-primary" onClick={() => {
            const selected = sortedEntries.filter((entry) => selectedIds.has(entry.id))
            onBatchDownload(selected)
          }}>
            <DownloadSimple size={16} weight="regular" />
            下载所选
          </button>
          {selectedFiles.length > 0 && (
            <button type="button" className="btn btn-danger" onClick={() => onDeleteFiles(selectedFiles)}>
              <Trash size={16} weight="regular" />
              删除所选文件
            </button>
          )}
          <button type="button" className="btn btn-ghost" onClick={() => setSelectedIds(new Set())}>
            取消选择
          </button>
        </div>
      )}

      {loading ? (
        <LoadingRows />
      ) : error ? (
        <ErrorState error={error} onRetry={onRetry} />
      ) : isTransfers ? (
        <TransferList
          transfers={visibleTransfers}
          onUpload={onUpload}
          hasQuery={hasQuery}
          query={query}
          onClearQuery={onClearQuery}
          onCancel={onCancelTask}
          onDelete={onDeleteTask}
        />
      ) : isClipboard ? (
        <ClipboardChat
          messages={visibleClipboard}
          onSend={onSendClipboard}
          onCopy={onCopyClipboard}
        />
      ) : entries.length > 0 ? (
        <ul className="entry-list">
          {sortedEntries.map((entry) => {
            const action = rowAction(entry)
            return (
              <li className="entry-row" key={entry.id}>
                {entry.type === 'folder' ? (
                  <span className="row-check-spacer" />
                ) : (
                  <input
                    type="checkbox"
                    className="row-check"
                    checked={selectedIds.has(entry.id)}
                    onChange={(event) => {
                      setSelectedIds((previous) => {
                        const next = new Set(previous)
                        if (event.target.checked) next.add(entry.id)
                        else next.delete(entry.id)
                        return next
                      })
                    }}
                    aria-label={`选择 ${entry.name}`}
                  />
                )}
                <button
                  type="button"
                  className="entry-main"
                  onClick={() => (entry.type === 'folder' ? onOpenFolder(entry) : onOpenEntry(entry))}
                >
                  <EntryGlyph entry={entry} />
                  <span className="entry-text">
                    <span className="entry-title">
                      <span className="entry-name">
                        <Highlight text={entry.name} query={query} />
                      </span>
                      {entry.type === 'app' && <span className="entry-version">{entry.version}</span>}
                      {entry.type === 'folder' && <span className="entry-badge">{entry.itemCount.toLocaleString('zh-CN')} 项</span>}
                    </span>
                    <span className="entry-meta">
                      {entry.type === 'app' ? (
                        <>
                          <span className="meta-primary">
                            <Highlight text={entry.packageName} query={query} />
                          </span>
                          <span className="meta-dot" aria-hidden="true" />
                          <span>{formatBytes(entry.size)}</span>
                        </>
                      ) : (
                        entryMeta(entry)
                      )}
                    </span>
                  </span>
                </button>
                <div className="entry-actions">
                  <button
                    type="button"
                    className="row-action"
                    aria-label={action.label}
                    title={action.label}
                    onClick={() => {
                      if (entry.type === 'folder') onOpenFolder(entry as FolderEntry)
                      else if (entry.type === 'file' && entry.kind === 'text') onCopy(entry as FileEntry)
                      else onDownload(entry as AppEntry | FileEntry)
                    }}
                  >
                    {action.icon}
                  </button>
                  {entry.type === 'folder' && (
                    <button
                      type="button"
                      className="row-action"
                      aria-label={`下载 ${entry.name} 的压缩包`}
                      title="打包为 zip 后下载"
                      onClick={() => onDownload(entry)}
                    >
                      <DownloadSimple size={16} weight="regular" />
                    </button>
                  )}
                  {(entry.type === 'file' || entry.type === 'folder') && (
                    <button
                      type="button"
                      className="row-action row-action-danger"
                      aria-label={`删除手机上的 ${entry.name}`}
                      title={entry.type === 'folder' ? '从手机删除文件夹及其中内容' : '从手机删除'}
                      onClick={() => onDeleteFile(entry)}
                    >
                      <Trash size={16} weight="regular" />
                    </button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      ) : (
        <div className="empty-state">
          <span className="empty-icon" aria-hidden="true">
            {hasQuery ? <AppWindow size={26} weight="regular" /> : <CategoryIcon name={categoryMeta?.icon ?? 'documents'} size={26} />}
          </span>
          <h2>{hasQuery ? '没有找到匹配内容' : '这个分类还是空的'}</h2>
          <p>{hasQuery ? `没有与 “${query.trim()}” 匹配的文件或应用。` : '从电脑上传文件，或者到其他分类看看。'}</p>
          {hasQuery ? (
            <button type="button" className="btn btn-ghost" onClick={onClearQuery}>清除搜索</button>
          ) : (
            <button type="button" className="btn btn-primary" onClick={onUpload}>
              <UploadSimple size={16} weight="regular" />
              传到手机
            </button>
          )}
        </div>
      )}
    </div>
  )
}
