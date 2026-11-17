import { useEffect, useRef } from 'react'
import { File as FileIcon, UploadSimple, X } from '@phosphor-icons/react'
import { formatBytes } from '../data'

interface UploadQueueDialogProps {
  files: File[]
  onAdd: (files: FileList | File[]) => void
  onRemove: (index: number) => void
  onClear: () => void
  onClose: () => void
  onConfirm: () => void
}

export function UploadQueueDialog({
  files,
  onAdd,
  onRemove,
  onClear,
  onClose,
  onConfirm,
}: UploadQueueDialogProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const total = files.reduce((sum, file) => sum + file.size, 0)

  useEffect(() => {
    const timer = window.setTimeout(() => panelRef.current?.focus(), 30)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [onClose])

  return (
    <div
      className="modal-overlay"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        className="upload-queue-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="upload-queue-title"
        tabIndex={-1}
        ref={panelRef}
      >
        <div className="upload-queue-head">
          <div>
            <p className="eyebrow">隔空闪传</p>
            <h2 id="upload-queue-title">传输队列</h2>
            <p>确认后按顺序批量上传到手机。</p>
          </div>
          <button type="button" className="icon-button modal-close" onClick={onClose} aria-label="关闭">
            <X size={18} weight="regular" />
          </button>
        </div>

        <button
          type="button"
          className="upload-dropzone"
          onClick={() => inputRef.current?.click()}
          onDragEnter={(event) => {
            event.preventDefault()
            event.stopPropagation()
          }}
          onDragOver={(event) => {
            event.preventDefault()
            event.stopPropagation()
          }}
          onDragLeave={(event) => event.stopPropagation()}
          onDrop={(event) => {
            event.preventDefault()
            event.stopPropagation()
            if (event.dataTransfer.files.length > 0) onAdd(event.dataTransfer.files)
          }}
        >
          <span className="upload-dropzone-icon" aria-hidden="true">
            <UploadSimple size={22} weight="regular" />
          </span>
          <span>
            <strong>{files.length > 0 ? '继续添加文件' : '选择要上传的文件'}</strong>
            <small>支持多选，也可以把文件拖到这里</small>
          </span>
        </button>

        <input
          ref={inputRef}
          className="visually-hidden"
          type="file"
          multiple
          tabIndex={-1}
          aria-hidden="true"
          onChange={(event) => {
            if (event.target.files?.length) onAdd(event.target.files)
            event.target.value = ''
          }}
        />

        <div className="upload-queue-list-head">
          <span>{files.length > 0 ? `${files.length} 个文件 · ${formatBytes(total)}` : '队列为空'}</span>
          {files.length > 0 && (
            <button type="button" className="upload-queue-clear" onClick={onClear}>
              清空
            </button>
          )}
        </div>

        {files.length > 0 ? (
          <ul className="upload-queue-list">
            {files.map((file, index) => (
              <li className="upload-queue-item" key={`${file.name}-${file.size}-${file.lastModified}-${index}`}>
                <span className="upload-queue-file" aria-hidden="true">
                  <FileIcon size={17} weight="regular" />
                </span>
                <span className="upload-queue-copy">
                  <strong>{file.name}</strong>
                  <small>{formatBytes(file.size)}</small>
                </span>
                <button
                  type="button"
                  className="icon-button tiny"
                  onClick={() => onRemove(index)}
                  aria-label={`从队列移除 ${file.name}`}
                >
                  <X size={15} weight="regular" />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="upload-queue-empty">还没有选择文件。加入队列后可以继续浏览或调整顺序。</p>
        )}

        <div className="upload-queue-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button type="button" className="btn btn-primary" onClick={onConfirm} disabled={files.length === 0}>
            <UploadSimple size={16} weight="regular" />
            开始上传
          </button>
        </div>
      </div>
    </div>
  )
}
