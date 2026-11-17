import { useEffect, useRef, useState } from 'react'
import {
  CircleNotch,
  Copy,
  DownloadSimple,
  File as FileIcon,
  FileArchive,
  FileAudio,
  FilePdf,
  FileText,
  FileVideo,
  Folder,
  Package,
  PlayCircle,
  X,
} from '@phosphor-icons/react'
import * as api from '../api'
import { entryKindLabel, formatBytes } from '../data'
import type { Entry, FileEntry } from '../types'
import { AppIconTile } from './AppIconTile'

const TEXT_PREVIEW_LIMIT = 1024 * 1024

interface DetailsDialogProps {
  entry: Entry | null
  onClose: () => void
  onDownload: (entry: Entry) => void
  onCopy: (entry: FileEntry) => void
  onOpenMedia: (entry: FileEntry) => void
}

function DetailGlyph({ entry, size = 28 }: { entry: Entry; size?: number }) {
  if (entry.type === 'app') return <AppIconTile iconUrl={entry.iconUrl} name={entry.name} size={76} />
  if (entry.type === 'folder') return <Folder size={size} weight="regular" />
  switch (entry.kind) {
    case 'image':
      return <FileIcon size={size} weight="regular" />
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
    default:
      return <FileIcon size={size} weight="regular" />
  }
}

export function DetailsDialog({ entry, onClose, onDownload, onCopy, onOpenMedia }: DetailsDialogProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [textContent, setTextContent] = useState<string | null>(null)
  const [textLoading, setTextLoading] = useState(false)
  const [textError, setTextError] = useState('')

  useEffect(() => {
    if (!entry || entry.type !== 'file' || entry.kind !== 'text') {
      setTextContent(null)
      setTextError('')
      setTextLoading(false)
      return
    }
    if (entry.size > TEXT_PREVIEW_LIMIT) {
      setTextContent(null)
      setTextLoading(false)
      setTextError('文件超过 1 MiB，请下载后查看')
      return
    }
    let cancelled = false
    const controller = new AbortController()
    setTextContent(null)
    setTextError('')
    setTextLoading(true)
    void api.readTextFile(entry.path, controller.signal).then((text) => {
      if (cancelled) return
      setTextContent(text)
      setTextLoading(false)
    }).catch((caught: unknown) => {
      if (cancelled) return
      setTextError(caught instanceof api.ApiError && caught.code === 'not-text'
        ? '文件不像纯文本，请下载后查看'
        : caught instanceof api.ApiError && caught.code === 'text-too-large'
          ? '文件超过 1 MiB，请下载后查看'
          : '无法读取文本内容')
      setTextLoading(false)
    })
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [entry])

  useEffect(() => {
    if (!entry) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    const timer = window.setTimeout(() => panelRef.current?.focus(), 30)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.clearTimeout(timer)
    }
  }, [entry, onClose])

  if (!entry) return null

  const isText = entry.type === 'file' && entry.kind === 'text'
  const isMedia = entry.type === 'file' && (entry.kind === 'image' || entry.kind === 'video' || entry.kind === 'audio')
  const previewImage = entry.type === 'file' ? entry.thumbUrl : undefined

  const rows: Array<{ label: string; value: string; mono?: boolean }> = []
  if (entry.type === 'app') {
    rows.push({ label: '类型', value: entry.scope === 'system' ? '系统应用' : '用户应用' })
    rows.push({ label: '版本', value: entry.version, mono: true })
    rows.push({ label: '包名', value: entry.packageName, mono: true })
    rows.push({ label: '大小', value: formatBytes(entry.size), mono: true })
    rows.push({ label: '安装时间', value: entry.installedAt })
    rows.push({ label: '位置', value: '手机内部存储' })
  } else if (entry.type === 'file') {
    rows.push({ label: '类型', value: entryKindLabel(entry.kind) })
    rows.push({ label: '大小', value: formatBytes(entry.size), mono: true })
    rows.push({ label: '修改时间', value: entry.modified })
    rows.push({ label: '路径', value: entry.path, mono: true })
    if (isText && textContent !== null) rows.push({ label: '字符数', value: `${textContent.length}`, mono: true })
  } else {
    rows.push({ label: '类型', value: '文件夹' })
    rows.push({ label: '内容', value: `${entry.itemCount.toLocaleString('zh-CN')} 项` })
    if (entry.size > 0) rows.push({ label: '大小', value: formatBytes(entry.size), mono: true })
    rows.push({ label: '修改时间', value: entry.modified })
  }

  return (
    <div className="modal-overlay" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <div
        className="detail-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="detail-title"
        tabIndex={-1}
        ref={panelRef}
      >
        <button type="button" className="icon-button modal-close" onClick={onClose} aria-label="关闭详情">
          <X size={18} weight="regular" />
        </button>

        <div
          className={`detail-preview${previewImage ? ' has-image' : ''}${isText ? ' is-text' : ''}${entry.type === 'app' ? ' is-app' : ''}${isMedia ? ' is-media' : ''}`}
        >
          {isText ? (
            textLoading ? (
              <span className="detail-text-state">
                <CircleNotch className="spin" size={18} weight="regular" />
                正在读取文本
              </span>
            ) : textError ? (
              <span className="detail-text-state is-error">{textError}</span>
            ) : (
              <p>{textContent ?? ''}</p>
            )
          ) : previewImage ? (
            <img src={previewImage} alt="" draggable={false} onDragStart={(event) => event.preventDefault()} />
          ) : (
            <span className="detail-glyph" aria-hidden="true">
              <DetailGlyph entry={entry} />
            </span>
          )}
          {isMedia && (
            <button
              type="button"
              className="detail-media-open"
              onClick={() => onOpenMedia(entry as FileEntry)}
            >
              <PlayCircle size={19} weight="fill" />
              {entry.type === 'file' && entry.kind === 'image'
                ? '查看图片'
                : entry.type === 'file' && entry.kind === 'video'
                  ? '播放视频'
                  : '播放音频'}
            </button>
          )}
        </div>

        <div className="detail-heading">
          <p className="eyebrow">{entry.type === 'app' ? '应用详情' : isText ? '剪贴板内容' : '文件详情'}</p>
          <h2 id="detail-title">{entry.name}</h2>
          {entry.type === 'app' && <p className="detail-sub">{entry.packageName}</p>}
          {isText && entry.type === 'file' && <p className="detail-sub">{entry.modified} · {formatBytes(entry.size)}</p>}
        </div>

        <dl className="detail-grid">
          {rows.map((row) => (
            <div className="detail-row" key={row.label}>
              <dt>{row.label}</dt>
              <dd className={row.mono ? 'is-mono' : undefined}>{row.value}</dd>
            </div>
          ))}
        </dl>

        <div className="detail-actions">
          <button type="button" className="btn btn-primary" onClick={() => {
            if (isText && textContent !== null) onCopy({ ...(entry as FileEntry), text: textContent })
            else onDownload(entry)
          }}>
            {isText && textContent !== null
              ? <Copy size={16} weight="regular" />
              : <DownloadSimple size={16} weight="regular" />}
            {isText && textContent !== null ? '复制内容' : entry.type === 'app' ? '下载 APK' : '下载到电脑'}
          </button>
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            关闭
          </button>
        </div>
      </div>
    </div>
  )
}
