import { useEffect, useRef, useState } from 'react'
import { Trash, WarningCircle, X } from '@phosphor-icons/react'
import type { FileEntry } from '../types'

interface ConfirmDeleteDialogProps {
  files: FileEntry[]
  onClose: () => void
  onConfirm: () => Promise<void>
}

export function ConfirmDeleteDialog({ files, onClose, onConfirm }: ConfirmDeleteDialogProps) {
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

  if (files.length === 0) return null

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
          将从手机中永久删除 {files.length} 个文件，此操作无法撤销。
        </p>
        <ul className="delete-list">
          {files.slice(0, 6).map((file) => (
            <li key={file.id}>{file.name}</li>
          ))}
          {files.length > 6 && <li>以及另外 {files.length - 6} 个文件</li>}
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
