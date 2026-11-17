import { useEffect, useRef, useState } from 'react'
import { CircleNotch, DownloadSimple, X } from '@phosphor-icons/react'
import * as api from '../api'
import { formatBytes } from '../data'
import type { FileEntry } from '../types'

interface MediaViewerDialogProps {
  entry: FileEntry | null
  onClose: () => void
  onDownload: (entry: FileEntry) => void
}

function isMedia(entry: FileEntry): boolean {
  return entry.kind === 'image' || entry.kind === 'video' || entry.kind === 'audio'
}

function mediaLabel(entry: FileEntry): string {
  if (entry.kind === 'image') return '查看图片'
  if (entry.kind === 'video') return '播放视频'
  return '播放音频'
}

export function MediaViewerDialog({ entry, onClose, onDownload }: MediaViewerDialogProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [src, setSrc] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!entry || !isMedia(entry)) return
    const direct = api.mediaUrl(entry)
    if (direct) {
      setSrc(direct)
      setError('')
      setLoading(entry.kind !== 'audio')
      return
    }
    let cancelled = false
    let objectUrl: string | null = null
    const controller = new AbortController()
    setSrc(null)
    setError('')
    setLoading(true)
    void api.fetchEntryBlob(entry, controller.signal).then((blob) => {
      if (cancelled) return
      objectUrl = URL.createObjectURL(blob)
      setSrc(objectUrl)
      setLoading(entry.kind !== 'audio')
    }).catch((caught: unknown) => {
      if (cancelled) return
      setError(caught instanceof Error ? caught.message : '无法加载这个文件')
      setLoading(false)
    })
    return () => {
      cancelled = true
      controller.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [entry])

  useEffect(() => {
    if (!entry) return
    const timer = window.setTimeout(() => panelRef.current?.focus(), 30)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [entry, onClose])

  if (!entry || !isMedia(entry)) return null

  return (
    <div
      className="modal-overlay media-overlay"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        className="media-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="media-title"
        tabIndex={-1}
        ref={panelRef}
      >
        <div className="media-head">
          <div>
            <p className="eyebrow">{mediaLabel(entry)}</p>
            <h2 id="media-title">{entry.name}</h2>
            <p>{formatBytes(entry.size)} · {entry.modified}</p>
          </div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="关闭">
            <X size={18} weight="regular" />
          </button>
        </div>

        <div className="media-stage">
          {loading && (
            <span className="media-loading">
              <CircleNotch className="spin" size={22} weight="regular" />
              正在准备文件
            </span>
          )}
          {error && <p className="media-error">{error}</p>}
          {src && entry.kind === 'image' && (
            <img
              src={src}
              alt={entry.name}
              draggable={false}
              onLoad={() => setLoading(false)}
              onError={() => {
                setLoading(false)
                setError('图片加载失败')
              }}
            />
          )}
          {src && entry.kind === 'video' && (
            <video
              src={src}
              controls
              autoPlay
              playsInline
              onLoadedData={() => setLoading(false)}
              onError={() => {
                setLoading(false)
                setError('视频无法播放')
              }}
            />
          )}
          {src && entry.kind === 'audio' && (
            <audio
              src={src}
              controls
              autoPlay
              onLoadedData={() => setLoading(false)}
              onError={() => {
                setLoading(false)
                setError('音频无法播放')
              }}
            />
          )}
        </div>

        <div className="media-foot">
          <span>{entry.path}</span>
          <button type="button" className="btn btn-ghost" onClick={() => onDownload(entry)}>
            <DownloadSimple size={15} weight="regular" />
            下载到电脑
          </button>
        </div>
      </div>
    </div>
  )
}
