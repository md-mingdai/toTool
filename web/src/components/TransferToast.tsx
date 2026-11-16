import {
  ArrowRight,
  Check,
  CircleNotch,
  DownloadSimple,
  UploadSimple,
  X,
} from '@phosphor-icons/react'
import { formatBytes } from '../data'
import type { TransferRecord } from '../types'

interface TransferToastProps {
  records: TransferRecord[]
  visible: boolean
  onClose: () => void
  onView: () => void
}

export function TransferToast({ records, visible, onClose, onView }: TransferToastProps) {
  if (!visible || records.length === 0) return null

  const active = records.filter((record) => record.status === 'active')
  const recent = (active.length > 0 ? active : records).slice(0, 3)
  const done = active.length === 0
  const allUploads = records.every((record) => record.direction === 'upload')
  const allDownloads = records.every((record) => record.direction === 'download')
  const title = done
    ? allDownloads
      ? '下载完成'
      : '已传到手机'
    : allUploads
      ? '正在传到手机'
      : allDownloads
        ? '正在下载到电脑'
        : '正在传输'
  const overall = records.length
    ? Math.round(records.reduce((total, record) => total + record.progress, 0) / records.length)
    : 0

  return (
    <section className="transfer-toast" role="status" aria-live="polite" aria-label="传输进度">
      <div className="toast-head">
        <span className={`toast-mark${done ? ' is-done' : ''}`} aria-hidden="true">
          {done ? <Check size={16} weight="bold" /> : <CircleNotch className="spin" size={16} weight="regular" />}
        </span>
        <span className="toast-title">
          <strong>{title}</strong>
          <small>{done ? `${records.length} 个文件已完成` : `${active.length} 个文件传输中 · ${overall}%`}</small>
        </span>
        <button type="button" className="icon-button tiny toast-close" onClick={onClose} aria-label="关闭传输面板">
          <X size={15} weight="regular" />
        </button>
      </div>

      <ul className="toast-list">
        {recent.map((record) => (
          <li className="toast-item" key={record.id}>
            <span className={`toast-direction ${record.direction}`} aria-hidden="true">
              {record.direction === 'upload' ? <UploadSimple size={14} weight="regular" /> : <DownloadSimple size={14} weight="regular" />}
            </span>
            <span className="toast-copy">
              <span className="toast-name">{record.name}</span>
              <span className="toast-meta">
                {record.status === 'done' ? '已完成' : `${Math.round(record.progress)}%`}
                <span aria-hidden="true"> · </span>
                {formatBytes(record.size)}
              </span>
              {record.status === 'active' && (
                <span className="inline-progress" role="progressbar" aria-label={`${record.name} 传输进度`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(record.progress)}>
                  <span style={{ width: `${record.progress}%` }} />
                </span>
              )}
            </span>
          </li>
        ))}
      </ul>

      <button type="button" className="toast-view" onClick={onView}>
        查看传输记录
        <ArrowRight size={14} weight="regular" />
      </button>
    </section>
  )
}
