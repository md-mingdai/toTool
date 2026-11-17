import { useEffect, useRef } from 'react'
import { DeviceMobile, SignOut, WifiHigh, X } from '@phosphor-icons/react'
import { categories, formatBytes } from '../data'
import type { Category, CategoryId, DeviceInfo } from '../types'
import { Brand } from './Brand'
import { CategoryIcon } from './CategoryIcon'

interface SidebarProps {
  activeCategory: CategoryId
  device: DeviceInfo | null
  port: number | null
  encryptionEnabled: boolean
  onSelect: (category: CategoryId) => void
  onDisconnect: () => void
  onClose: () => void
  open: boolean
}

const groups: Category['group'][] = ['文件', '应用', '传输']

export function Sidebar({
  activeCategory,
  device,
  port,
  encryptionEnabled,
  onSelect,
  onDisconnect,
  onClose,
  open,
}: SidebarProps) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const usedPercent = device && device.storageTotal > 0
    ? Math.round((device.storageUsed / device.storageTotal) * 100)
    : 0
  const host = typeof window !== 'undefined' ? window.location.host : ''

  useEffect(() => {
    if (open) closeRef.current?.focus()
  }, [open])

  return (
    <aside id="app-sidebar" className="sidebar" aria-label="文件分类">
      <div className="side-brand">
        <Brand />
        <button ref={closeRef} type="button" className="icon-button sidebar-close" onClick={onClose} aria-label="关闭分类菜单">
          <X size={18} weight="regular" />
        </button>
      </div>

      <div className="device-card">
        <span className="device-avatar" aria-hidden="true">
          <DeviceMobile size={19} weight="regular" />
        </span>
        <span className="device-copy">
          <strong>{device?.name ?? '手机'}</strong>
          <span>
            <i className="status-dot" aria-hidden="true" />
            已配对 · 端口 {port ?? (host.split(':')[1] || '2233')}
          </span>
        </span>
        <button
          type="button"
          className="icon-button tiny device-disconnect"
          onClick={onDisconnect}
          aria-label="断开配对"
          title="断开配对"
        >
          <SignOut size={15} weight="regular" />
        </button>
      </div>

      <nav className="side-nav">
        {groups.map((group) => {
          const items = categories.filter((category) => category.group === group)
          return (
            <div className="nav-group" key={group}>
              <p className="nav-label">{group}</p>
              {items.map((category) => {
                const active = category.id === activeCategory
                return (
                  <button
                    key={category.id}
                    type="button"
                    className={`nav-item${active ? ' is-active' : ''}`}
                    onClick={() => onSelect(category.id)}
                    aria-current={active ? 'page' : undefined}
                  >
                    <CategoryIcon name={category.icon} />
                    <span>{category.label}</span>
                  </button>
                )
              })}
            </div>
          )
        })}
      </nav>

      <div className="side-foot">
        <div className="storage-meter">
          <div className="storage-copy">
            <span>手机存储</span>
            <span>
              {device
                ? `${formatBytes(device.storageUsed)} / ${formatBytes(device.storageTotal)}`
                : '正在读取'}
            </span>
          </div>
          <div className="meter" role="progressbar" aria-label="手机存储已用空间" aria-valuemin={0} aria-valuemax={100} aria-valuenow={usedPercent}>
            <span style={{ width: `${usedPercent}%` }} />
          </div>
        </div>

        <div className="service-line">
          <WifiHigh size={14} weight="regular" aria-hidden="true" />
          <span>同一 Wi-Fi{encryptionEnabled ? ' · 内容加密' : ''}</span>
          <code>{host}</code>
        </div>
      </div>
    </aside>
  )
}
