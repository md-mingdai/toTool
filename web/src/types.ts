export type Theme = 'light' | 'dark'

export type CategoryId =
  | 'storage'
  | 'photos'
  | 'videos'
  | 'audio'
  | 'documents'
  | 'archives'
  | 'apks'
  | 'apps'
  | 'downloads'
  | 'clipboard'
  | 'transfers'

export type CategoryGlyph =
  | 'storage'
  | 'photos'
  | 'videos'
  | 'audio'
  | 'documents'
  | 'archives'
  | 'apks'
  | 'apps'
  | 'downloads'
  | 'clipboard'
  | 'transfers'

export interface Category {
  id: CategoryId
  label: string
  group: '文件' | '应用' | '传输'
  icon: CategoryGlyph
}

export type AppScope = 'system' | 'user'

export type EntryKind =
  | 'folder'
  | 'image'
  | 'video'
  | 'audio'
  | 'document'
  | 'archive'
  | 'apk'
  | 'text'
  | 'file'

export interface AppEntry {
  type: 'app'
  id: string
  name: string
  version: string
  packageName: string
  size: number
  scope: AppScope
  installedAt: string
  modifiedAt?: number
  iconUrl?: string
}

export interface FileEntry {
  type: 'file'
  id: string
  name: string
  kind: EntryKind
  size: number
  modified: string
  modifiedAt?: number
  path: string
  thumbUrl?: string
  text?: string
}

export interface FolderEntry {
  type: 'folder'
  id: string
  name: string
  size: number
  itemCount: number
  modified: string
  modifiedAt?: number
  path: string
}

export type Entry = AppEntry | FileEntry | FolderEntry

export interface DeviceInfo {
  name: string
  model: string
  system: string
  storageUsed: number
  storageTotal: number
}

export interface AppCounts {
  all: number
  system: number
  user: number
}

export interface FilePage {
  items: Entry[]
  total: number
  page: number
  pageSize: number
}

export type TransferDirection = 'upload' | 'download'

export type TransferPhase = 'upload' | 'encrypt' | 'download' | 'decrypt'
export type UploadPhase = 'upload' | 'encrypt'
export type DownloadPhase = 'download' | 'decrypt'

export interface ClipboardMessage {
  id: string
  text: string
  source: 'phone' | 'web'
  createdAt: string
}

export interface TransferRecord {
  id: string
  name: string
  size: number
  direction: TransferDirection
  progress: number
  status: 'active' | 'done' | 'cancelled' | 'failed'
  createdAt: string
  phase?: TransferPhase
  speed?: number
  path?: string
  local?: boolean
}

export interface ServerInfo {
  port: number
  device: DeviceInfo
  encryptionEnabled: boolean
}

export interface EncryptionInfo {
  enabled: boolean
  salt?: string
  iterations?: number
}

export interface PairResult {
  token: string
  port: number
  device: DeviceInfo
  encryption: EncryptionInfo
}
