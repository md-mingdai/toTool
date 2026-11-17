import { useEffect, useRef, useState } from 'react'
import { Trash, WarningCircle, X } from '@phosphor-icons/react'
import type { FileEntry, FolderEntry } from '../types'

interface ConfirmDeleteDialogProps {
  entries: Array<FileEntry | FolderEntry>
  onClose: () => void
  onConfirm: () => Promise<void>
}

export function ConfirmDeleteDialog({ entries, onClose, onConfirm }: ConfirmDeleteDialogProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    const timer = window.setTimeout(() => panelRef.current?.focus(), 30)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [busy, onClose])

  if (entries.length === 0) return null
  const hasFolder = entries.some((entry) => entry.type === 'folder')

  return (
    <div
      className="modal-overlay"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) onClose()
      }}
    >
      <div
        className="confirm-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-title"
        tabIndex={-1}
        ref={panelRef}
      >
        <div className="confirm-icon" aria-hidden="true">
          <WarningCircle size={26} weight="regular" />
        </div>
        <button type="button" className="icon-button modal-close" onClick={onClose} disabled={busy} aria-label="关闭">
          <X size={18} weight="regular" />
        </button>
        <h2 id="delete-title">删除手机文件？</h2>
        <p>
          将从手机中永久删除 {entries.length} 项{hasFolder ? '（文件夹会连同其中所有内容递归删除）' : '文件'}，此操作无法撤销。
        </p>
        <ul className="delete-list">
          {entries.slice(0, 6).map((entry) => (
            <li key={entry.id}>{entry.name}</li>
          ))}
          {entries.length > 6 && <li>以及另外 {entries.length - 6} 项</li>}
        </ul>
        {error && <p className="confirm-error" role="alert">{error}</p>}
        <div className="confirm-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>
            取消
          </button>
          <button
            type="button"
            className="btn btn-danger"
            disabled={busy}
            onClick={() => {
              setBusy(true)
              setError('')
              void onConfirm().catch((caught: unknown) => {
                setError(caught instanceof Error ? caught.message : '删除失败')
                setBusy(false)
              })
            }}
          >
            <Trash size={16} weight="regular" />
            {busy ? '正在删除' : '删除'}
          </button>
        </div>
      </div>
    </div>
  )
}
