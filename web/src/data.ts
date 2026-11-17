import type { Category, FileEntry, TransferPhase } from './types'

export const categories: Category[] = [
  { id: 'storage', label: '手机存储', group: '文件', icon: 'storage' },
  { id: 'photos', label: '照片', group: '文件', icon: 'photos' },
  { id: 'videos', label: '视频', group: '文件', icon: 'videos' },
  { id: 'audio', label: '音频', group: '文件', icon: 'audio' },
  { id: 'documents', label: '文档', group: '文件', icon: 'documents' },
  { id: 'archives', label: '压缩包', group: '文件', icon: 'archives' },
  { id: 'apks', label: '安装包', group: '文件', icon: 'apks' },
  { id: 'apps', label: '已安装应用', group: '应用', icon: 'apps' },
  { id: 'downloads', label: '下载', group: '传输', icon: 'downloads' },
  { id: 'clipboard', label: '剪贴板', group: '传输', icon: 'clipboard' },
  { id: 'transfers', label: '最近传输', group: '传输', icon: 'transfers' },
]

export function transferPhaseLabel(phase: TransferPhase | undefined): string {
  switch (phase) {
    case 'encrypt':
      return '加密中'
    case 'decrypt':
      return '解密中'
    case 'upload':
      return '上传中'
    case 'download':
      return '下载中'
    default:
      return ''
  }
}

export function formatSpeed(speed: number | undefined): string {
  if (!speed || speed <= 0) return ''
  return `${formatBytes(speed)}/s`
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unit = units[0]
  for (let i = 1; i < units.length && value >= 1024; i += 1) {
    value /= 1024
    unit = units[i]
  }
  const digits = value >= 100 ? 0 : value >= 10 ? 1 : 2
  return `${value.toFixed(digits)} ${unit}`
}

export function totalBytes(entries: Array<{ size: number }>): number {
  return entries.reduce((total, entry) => total + entry.size, 0)
}

export function entryKindLabel(kind: FileEntry['kind']): string {
  const labels: Record<FileEntry['kind'], string> = {
    folder: '文件夹',
    image: '图片',
    video: '视频',
    audio: '音频',
    document: '文档',
    archive: '压缩包',
    apk: '安装包',
    text: '文本',
    file: '文件',
  }
  return labels[kind]
}
