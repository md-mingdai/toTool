import { gcm } from '@noble/ciphers/aes.js'
import { pbkdf2 } from '@noble/hashes/pbkdf2.js'
import { sha256 } from '@noble/hashes/sha2.js'
import type {
  AppCounts,
  AppEntry,
  AppScope,
  CategoryId,
  ClipboardMessage,
  DownloadPhase,
  Entry,
  FileEntry,
  FilePage,
  PairResult,
  ServerInfo,
  TransferRecord,
  UploadPhase,
} from './types'
const TOKEN_KEY = 'flashdrop-token'
const KEY_KEY = 'flashdrop-key'
const MAX_ENCRYPTED_BYTES = 256 * 1024 * 1024
const PBKDF2_ITERATIONS_FALLBACK = 120_000
const ENC_MAGIC = new Uint8Array([0x46, 0x44, 0x45, 0x43, 0x31])
const ENC_CHUNK_SIZE = 1024 * 1024
const GCM_IV_BYTES = 12
const GCM_TAG_BYTES = 16

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code)
    this.name = 'ApiError'
  }
}

export class UnauthorizedError extends ApiError {
  constructor() {
    super(401, 'unauthorized')
    this.name = 'UnauthorizedError'
  }
}

export class StoragePermissionError extends ApiError {
  constructor() {
    super(403, 'storage-permission')
    this.name = 'StoragePermissionError'
  }
}

export class PairingRejectedError extends Error {
  constructor() {
    super('invalid-code')
    this.name = 'PairingRejectedError'
  }
}

export class PairingBlockedError extends Error {
  constructor() {
    super('pair-request-blocked')
    this.name = 'PairingBlockedError'
  }
}

export class PairingRequestDeniedError extends Error {
  constructor() {
    super('pair-request-denied')
    this.name = 'PairingRequestDeniedError'
  }
}

export class PairingRequestExpiredError extends Error {
  constructor() {
    super('pair-request-expired')
    this.name = 'PairingRequestExpiredError'
  }
}

export class PairingRequestTimeoutError extends Error {
  constructor() {
    super('pair-request-timeout')
    this.name = 'PairingRequestTimeoutError'
  }
}

export class PairingRateLimitedError extends Error {
  constructor() {
    super('rate-limited')
    this.name = 'PairingRateLimitedError'
  }
}

export class PairingUnavailableError extends Error {
  constructor(message = 'pairing-unavailable') {
    super(message)
    this.name = 'PairingUnavailableError'
  }
}

let token = readStorage(TOKEN_KEY)
let encryptionKey: Uint8Array | null = readKey()

export function hasSession(): boolean {
  return Boolean(token)
}

export function isEncryptionActive(): boolean {
  return encryptionKey !== null
}

/** 浏览器的 WebSocket 不支持自定义 Header，token 走查询参数。 */
export function websocketUrl(): string | null {
  if (!token) return null
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${window.location.host}/ws?token=${encodeURIComponent(token)}`
}

export function clearSession(): void {
  token = ''
  encryptionKey = null
  removeStorage(TOKEN_KEY)
  removeStorage(KEY_KEY)
}

/** 主动注销服务器上的 token；失败也继续清本地状态。 */
export async function logout(): Promise<void> {
  if (token) {
    try {
      await fetch('/api/logout', {
        method: 'POST',
        headers: authHeaders(),
      })
    } catch {
      // 服务已经停止时直接清本地
    }
  }
  clearSession()
}

export async function getInfo(): Promise<ServerInfo> {
  let response: Response
  try {
    response = await fetch('/api/info', { cache: 'no-store' })
  } catch {
    throw new PairingUnavailableError('network')
  }
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) throw new PairingUnavailableError('no-backend')
  if (!response.ok) throw new PairingUnavailableError(`http-${response.status}`)
  return (await response.json()) as ServerInfo
}

/** 轻量探活：服务断开时返回 false，不抛异常。 */
export async function ping(): Promise<boolean> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 4000)
  try {
    const response = await fetch('/api/info', { cache: 'no-store', signal: controller.signal })
    return response.ok
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}

export async function pair(code: string): Promise<PairResult> {
  let response: Response
  try {
    response = await fetch('/api/pair', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code }),
    })
  } catch {
    throw new PairingUnavailableError('network')
  }

  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) throw new PairingUnavailableError('no-backend')
  const data = (await response.json().catch(() => {
    throw new PairingUnavailableError('bad-json')
  })) as PairResult & { error?: string }

  if (!response.ok) {
    if (response.status === 400 || response.status === 401 || response.status === 403) {
      throw new PairingRejectedError()
    }
    if (response.status === 429 || (response.status === 503 && data.error === 'rate-limited')) {
      throw new PairingRateLimitedError()
    }
    throw new PairingUnavailableError(`http-${response.status}`)
  }
  if (!data.token) throw new PairingUnavailableError('bad-payload')

  token = data.token
  writeStorage(TOKEN_KEY, token)
  if (data.encryption?.enabled && data.encryption.salt) {
    const key = deriveKey(code, data.encryption.salt, data.encryption.iterations ?? PBKDF2_ITERATIONS_FALLBACK)
    encryptionKey = key
    writeStorage(KEY_KEY, bytesToBase64(key))
  } else {
    clearEncryptionKey()
  }
  return data
}

export async function requestInteractivePairing(): Promise<string> {
  let response: Response
  try {
    response = await fetch('/api/pair/request', { method: 'POST', cache: 'no-store' })
  } catch {
    throw new PairingUnavailableError('network')
  }
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) throw new PairingUnavailableError('no-backend')
  const data = (await response.json().catch(() => ({}))) as { id?: string; error?: string }
  if (response.status === 403 && data.error === 'pair-request-blocked') {
    throw new PairingBlockedError()
  }
  if (response.status === 429) throw new PairingRateLimitedError()
  if (!response.ok || !data.id) {
    throw new PairingUnavailableError(data.error ?? `http-${response.status}`)
  }
  return data.id
}

/** 轮询手机端是否已允许交互式配对；批准后直接把随机密钥写入本地 session。 */
export async function waitForInteractivePairing(id: string): Promise<PairResult> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    let response: Response
    try {
      response = await fetch(`/api/pair/request/status?id=${encodeURIComponent(id)}`, { cache: 'no-store' })
    } catch {
      throw new PairingUnavailableError('network')
    }
    const contentType = response.headers.get('content-type') ?? ''
    if (!contentType.includes('application/json')) throw new PairingUnavailableError('no-backend')
    const data = (await response.json().catch(() => ({}))) as PairResult & {
      status?: string
      error?: string
    }
    if (response.status === 404) throw new PairingRequestExpiredError()
    if (!response.ok) throw new PairingUnavailableError(data.error ?? `http-${response.status}`)
    switch (data.status) {
      case 'approved': {
        if (!data.token) throw new PairingUnavailableError('bad-payload')
        token = data.token
        writeStorage(TOKEN_KEY, token)
        if (data.encryption?.enabled) {
          if (!data.encryption.key) throw new PairingUnavailableError('missing-key')
          encryptionKey = base64ToBytes(data.encryption.key)
          writeStorage(KEY_KEY, data.encryption.key)
        } else {
          clearEncryptionKey()
        }
        return data
      }
      case 'denied':
        throw new PairingRequestDeniedError()
      case 'blocked':
        throw new PairingBlockedError()
      case 'expired':
        throw new PairingRequestExpiredError()
      default:
        await new Promise<void>((resolve) => window.setTimeout(resolve, 1500))
    }
  }
  throw new PairingRequestTimeoutError()
}

/**
 * 页面刷新后验证 sessionStorage 里的 token。
 * 返回 null 表示需要重新配对；网络/服务异常抛出 PairingUnavailableError。
 */
export async function restoreSession(): Promise<ServerInfo | null> {
  const info = await getInfo()
  if (!token) return null
  if (!info.encryptionEnabled && encryptionKey) {
    clearEncryptionKey()
  }
  if (info.encryptionEnabled && !encryptionKey) {
    clearSession()
    return null
  }
  let response: Response
  try {
    response = await fetch('/api/session', { headers: authHeaders(), cache: 'no-store' })
  } catch {
    throw new PairingUnavailableError('network')
  }
  if (!response.ok) {
    clearSession()
    return null
  }
  return info
}

export async function listApps(
  scope: AppScope | 'all',
  query: string,
): Promise<{ entries: AppEntry[]; counts: AppCounts }> {
  const response = await apiFetch(
    `/api/apps?scope=${encodeURIComponent(scope)}&q=${encodeURIComponent(query)}`,
  )
  const data = (await response.json()) as { items: AppEntry[]; counts: AppCounts }
  return {
    entries: data.items.map((entry) => ({
      ...entry,
      iconUrl: withToken(entry.iconUrl),
    })),
    counts: data.counts,
  }
}

export async function listFiles(
  category: CategoryId,
  path: string,
  query: string,
  page = 1,
  force = false,
  sort: 'modified' | 'name' | 'size' = 'modified',
): Promise<FilePage> {
  const params = new URLSearchParams({ category, path, q: query, sort })
  if (page > 1) params.set('page', String(page))
  if (force) params.set('refresh', '1')
  const response = await apiFetch(`/api/files?${params.toString()}`)
  const data = (await response.json()) as FilePage
  return { ...data, items: data.items.map((entry) => decorateEntry(entry)) }
}

export async function listTransfers(): Promise<TransferRecord[]> {
  const response = await apiFetch('/api/transfers')
  const data = (await response.json()) as { items: TransferRecord[] }
  return data.items
}

export async function deleteTransfer(id: string): Promise<void> {
  await apiFetch('/api/transfers/delete', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ id }),
  })
}

export async function deleteFiles(paths: string[]): Promise<{ deleted: string[]; failed: string[] }> {
  const response = await apiFetch('/api/files/delete', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ paths }),
  })
  return (await response.json()) as { deleted: string[]; failed: string[] }
}

export async function listClipboard(): Promise<ClipboardMessage[]> {
  const response = await apiFetch('/api/clipboard')
  const data = (await response.json()) as { items: ClipboardMessage[] }
  return data.items
}

export async function sendClipboard(text: string): Promise<ClipboardMessage> {
  const response = await apiFetch('/api/clipboard', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ text }),
  })
  const data = (await response.json()) as { item: ClipboardMessage }
  return data.item
}

export async function downloadEntry(
  entry: Entry,
  taskId: string,
  onProgress: (phase: DownloadPhase, ratio: number, loaded: number) => void = () => {},
  signal?: AbortSignal,
): Promise<string> {
  const endpoint = entry.type === 'app'
    ? `/api/download?package=${encodeURIComponent(entry.packageName)}`
    : `/api/download?path=${encodeURIComponent(entry.path)}`

  return new Promise<string>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('GET', endpoint)
    xhr.responseType = 'arraybuffer'
    xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.setRequestHeader('X-FlashDrop-Task-Id', taskId)
    const abort = () => xhr.abort()
    if (signal?.aborted) {
      reject(new ApiError(0, 'aborted'))
      return
    }
    signal?.addEventListener('abort', abort, { once: true })
    xhr.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress('download', Math.min(1, event.loaded / event.total), event.loaded)
      }
    }
    xhr.onerror = () => {
      signal?.removeEventListener('abort', abort)
      reject(new ApiError(0, 'network'))
    }
    xhr.ontimeout = () => {
      signal?.removeEventListener('abort', abort)
      reject(new ApiError(0, 'timeout'))
    }
    xhr.onabort = () => reject(new ApiError(0, 'aborted'))
    xhr.onload = () => {
      signal?.removeEventListener('abort', abort)
      void (async () => {
        if (xhr.status === 401) {
          clearSession()
          reject(new UnauthorizedError())
          return
        }
        if (xhr.status < 200 || xhr.status >= 300) {
          let code = `http-${xhr.status}`
          try {
            const text = new TextDecoder().decode(new Uint8Array(xhr.response as ArrayBuffer))
            const data = JSON.parse(text) as { error?: string }
            code = data.error ?? code
          } catch {
            // 非 JSON 错误响应
          }
          if (xhr.status === 403 && code === 'storage-permission') {
            reject(new StoragePermissionError())
          } else {
            reject(new ApiError(xhr.status, code))
          }
          return
        }
        try {
          const raw = new Uint8Array(xhr.response as ArrayBuffer)
          let blob: Blob
          if (xhr.getResponseHeader('x-flashdrop-encrypted') === '1') {
            if (!encryptionKey) throw new Error('缺少加密密钥，请重新配对')
            onProgress('decrypt', 0, 0)
            blob = await decryptToBlob(raw, encryptionKey, (ratio, loaded) => {
              onProgress('decrypt', ratio, loaded)
            }, signal)
            onProgress('decrypt', 1, raw.length)
          } else {
            blob = new Blob([toArrayBuffer(raw)])
          }
          const dispositionName = filenameFromDisposition(xhr.getResponseHeader('content-disposition'))
          const name = dispositionName || (entry.type === 'app' ? `${entry.name}.apk` : entry.name)
          const url = URL.createObjectURL(blob)
          const anchor = document.createElement('a')
          anchor.href = url
          anchor.download = name
          document.body.appendChild(anchor)
          anchor.click()
          anchor.remove()
          window.setTimeout(() => URL.revokeObjectURL(url), 1000)
          resolve(name)
        } catch (error) {
          reject(error)
        }
      })()
    }
    xhr.send()
  })
}

/** 未开启加密时媒体可直接流式加载；开启加密后由 fetchEntryBlob 解密成 Blob。 */
export function mediaUrl(entry: FileEntry): string | null {
  if (encryptionKey || !token) return null
  return withToken(`/api/media?path=${encodeURIComponent(entry.path)}&preview=1`) ?? null
}

export async function readTextFile(path: string, signal?: AbortSignal): Promise<string> {
  const response = await apiFetch(`/api/text?path=${encodeURIComponent(path)}`, { signal })
  const data = (await response.json()) as { text?: string }
  return data.text ?? ''
}

export async function fetchEntryBlob(entry: FileEntry, signal?: AbortSignal): Promise<Blob> {
  const response = await apiFetch(`/api/download?path=${encodeURIComponent(entry.path)}&preview=1`, { signal })
  const raw = new Uint8Array(await response.arrayBuffer())
  if (response.headers.get('x-flashdrop-encrypted') === '1') {
    if (!encryptionKey) throw new Error('缺少加密密钥，请重新配对')
    if (raw.length < GCM_IV_BYTES + GCM_TAG_BYTES) throw new Error('加密文件格式错误')
    const plain = await decryptToBlob(raw, encryptionKey, () => {}, signal)
    return new Blob([plain], { type: mediaMime(entry.name) })
  }
  return new Blob([toArrayBuffer(raw)], { type: mediaMime(entry.name) })
}

function mediaMime(name: string): string {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  switch (ext) {
    case 'png': return 'image/png'
    case 'jpg':
    case 'jpeg': return 'image/jpeg'
    case 'webp': return 'image/webp'
    case 'gif': return 'image/gif'
    case 'avif': return 'image/avif'
    case 'bmp': return 'image/bmp'
    case 'mp4':
    case 'm4v': return 'video/mp4'
    case 'webm': return 'video/webm'
    case 'mkv': return 'video/x-matroska'
    case 'mov': return 'video/quicktime'
    case '3gp': return 'video/3gpp'
    case 'mp3': return 'audio/mpeg'
    case 'm4a': return 'audio/mp4'
    case 'wav': return 'audio/wav'
    case 'ogg':
    case 'oga': return 'audio/ogg'
    case 'flac': return 'audio/flac'
    case 'aac': return 'audio/aac'
    default: return 'application/octet-stream'
  }
}

export async function uploadFile(
  file: File,
  taskId: string,
  onProgress: (phase: UploadPhase, ratio: number, loaded: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  const encrypted = Boolean(encryptionKey)
  if (encrypted && file.size > MAX_ENCRYPTED_BYTES) {
    throw new Error('加密模式暂支持 256 MB 以内的文件')
  }

  onProgress(encrypted ? 'encrypt' : 'upload', 0, 0)
  let body: Blob | File = file
  if (encrypted && encryptionKey) {
    body = await encryptFileBlob(file, encryptionKey, (ratio, loaded) => {
      onProgress('encrypt', ratio, loaded)
    }, signal)
  }
  if (signal?.aborted) throw new ApiError(0, 'aborted')
  onProgress('upload', 0, 0)

  await new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/upload')
    xhr.responseType = 'json'
    xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    xhr.setRequestHeader('X-FlashDrop-Name', encodeURIComponent(file.name))
    xhr.setRequestHeader('X-FlashDrop-Encrypted', encrypted ? '1' : '0')
    xhr.setRequestHeader('X-FlashDrop-Task-Id', taskId)
    const abort = () => xhr.abort()
    if (signal?.aborted) {
      reject(new ApiError(0, 'aborted'))
      return
    }
    signal?.addEventListener('abort', abort, { once: true })
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) onProgress('upload', event.loaded / event.total, event.loaded)
    }
    xhr.onerror = () => {
      signal?.removeEventListener('abort', abort)
      reject(new ApiError(0, 'network'))
    }
    xhr.ontimeout = () => {
      signal?.removeEventListener('abort', abort)
      reject(new ApiError(0, 'timeout'))
    }
    xhr.onabort = () => reject(new ApiError(0, 'aborted'))
    xhr.onload = () => {
      signal?.removeEventListener('abort', abort)
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      const code = (xhr.response as { error?: string } | null)?.error ?? `http-${xhr.status}`
      if (xhr.status === 401) {
        clearSession()
        reject(new UnauthorizedError())
      } else if (xhr.status === 403 && code === 'storage-permission') {
        reject(new StoragePermissionError())
      } else {
        reject(new ApiError(xhr.status, code))
      }
    }
    xhr.send(body)
  })
}

async function encryptFileBlob(
  file: File,
  key: Uint8Array,
  onProgress: (ratio: number, loaded: number) => void,
  signal?: AbortSignal,
): Promise<Blob> {
  const total = file.size
  const baseIv = randomBytes(GCM_IV_BYTES)
  baseIv.fill(0, 8, GCM_IV_BYTES)
  const parts: BlobPart[] = [toArrayBuffer(ENC_MAGIC), toArrayBuffer(baseIv)]
  let offset = 0
  let index = 0
  while (offset < total) {
    if (signal?.aborted) throw new ApiError(0, 'aborted')
    const end = Math.min(total, offset + ENC_CHUNK_SIZE)
    const plain = new Uint8Array(await file.slice(offset, end).arrayBuffer())
    const ciphertext = gcm(key, nonceFor(baseIv, index)).encrypt(plain)
    parts.push(toArrayBuffer(intToBytes(ciphertext.length)), toArrayBuffer(ciphertext))
    offset = end
    index += 1
    onProgress(total > 0 ? offset / total : 1, offset)
    await yieldToUi()
  }
  if (total === 0) {
    const ciphertext = gcm(key, nonceFor(baseIv, 0)).encrypt(new Uint8Array(0))
    parts.push(toArrayBuffer(intToBytes(ciphertext.length)), toArrayBuffer(ciphertext))
    onProgress(1, 0)
  }
  return new Blob(parts, { type: 'application/octet-stream' })
}

async function decryptToBlob(
  raw: Uint8Array,
  key: Uint8Array,
  onProgress: (ratio: number, loaded: number) => void,
  signal?: AbortSignal,
): Promise<Blob> {
  const hasMagic = raw.length >= ENC_MAGIC.length && ENC_MAGIC.every((byte, index) => raw[index] === byte)
  if (!hasMagic) {
    // 兼容早期 IV + 整文件密文格式。
    if (raw.length < GCM_IV_BYTES + GCM_TAG_BYTES) throw new Error('加密文件格式错误')
    const plain = gcm(key, raw.slice(0, GCM_IV_BYTES)).decrypt(raw.slice(GCM_IV_BYTES))
    return new Blob([toArrayBuffer(plain)])
  }
  if (raw.length < ENC_MAGIC.length + GCM_IV_BYTES) throw new Error('加密文件格式错误')
  const baseIv = raw.slice(ENC_MAGIC.length, ENC_MAGIC.length + GCM_IV_BYTES)
  const parts: BlobPart[] = []
  let offset = ENC_MAGIC.length + GCM_IV_BYTES
  let index = 0
  while (offset < raw.length) {
    if (signal?.aborted) throw new ApiError(0, 'aborted')
    if (offset + 4 > raw.length) throw new Error('加密文件格式错误')
    const length = readInt(raw, offset)
    offset += 4
    if (length < GCM_TAG_BYTES || length > ENC_CHUNK_SIZE + GCM_TAG_BYTES || offset + length > raw.length) {
      throw new Error('加密文件格式错误')
    }
    const ciphertext = raw.subarray(offset, offset + length)
    const plain = gcm(key, nonceFor(baseIv, index)).decrypt(ciphertext)
    parts.push(toArrayBuffer(plain))
    offset += length
    index += 1
    onProgress(offset / raw.length, offset)
    await yieldToUi()
  }
  return new Blob(parts)
}

function nonceFor(baseIv: Uint8Array, index: number): Uint8Array {
  const nonce = baseIv.slice()
  nonce[8] = (index >>> 24) & 0xff
  nonce[9] = (index >>> 16) & 0xff
  nonce[10] = (index >>> 8) & 0xff
  nonce[11] = index & 0xff
  return nonce
}

function intToBytes(value: number): Uint8Array {
  return new Uint8Array([
    (value >>> 24) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 8) & 0xff,
    value & 0xff,
  ])
}

function readInt(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0
}

function yieldToUi(): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, 0))
}

export function withToken(url: string | undefined): string | undefined {
  if (!url || !token) return url
  const separator = url.includes('?') ? '&' : '?'
  return `${url}${separator}token=${encodeURIComponent(token)}`
}

async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  let response: Response
  try {
    response = await fetch(path, { ...init, headers, cache: 'no-store' })
  } catch {
    throw new ApiError(0, 'network')
  }
  if (response.status === 401) {
    clearSession()
    throw new UnauthorizedError()
  }
  if (!response.ok) {
    let code = `http-${response.status}`
    try {
      const body = (await response.clone().json()) as { error?: string }
      code = body.error ?? code
    } catch {
      // 非 JSON 错误响应
    }
    if (response.status === 403 && code === 'storage-permission') {
      throw new StoragePermissionError()
    }
    throw new ApiError(response.status, code)
  }
  return response
}

function decorateEntry(entry: Entry): Entry {
  if (entry.type === 'app') {
    return { ...entry, iconUrl: withToken(entry.iconUrl) }
  }
  if (entry.type === 'file') {
    return { ...entry, thumbUrl: withToken(entry.thumbUrl) }
  }
  return entry
}

function authHeaders(): HeadersInit {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function deriveKey(code: string, saltBase64: string, iterations: number): Uint8Array {
  return pbkdf2(sha256, new TextEncoder().encode(code), base64ToBytes(saltBase64), {
    c: iterations,
    dkLen: 32,
  })
}

function clearEncryptionKey() {
  encryptionKey = null
  removeStorage(KEY_KEY)
}

function readKey(): Uint8Array | null {
  const raw = readStorage(KEY_KEY)
  if (!raw) return null
  try {
    const key = base64ToBytes(raw)
    return key.length === 32 ? key : null
  } catch {
    return null
  }
}

function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return bytes
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

function filenameFromDisposition(value: string | null): string | null {
  if (!value) return null
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(value)
  if (utf8?.[1]) {
    try {
      return decodeURIComponent(utf8[1])
    } catch {
      return utf8[1]
    }
  }
  const plain = /filename="([^"]+)"/i.exec(value)
  return plain?.[1] ?? null
}

function readStorage(key: string): string {
  try {
    return window.sessionStorage.getItem(key) ?? ''
  } catch {
    return ''
  }
}

function writeStorage(key: string, value: string) {
  try {
    window.sessionStorage.setItem(key, value)
  } catch {
    // 隐私模式或存储被禁用时只在当前内存里保持
  }
}

function removeStorage(key: string) {
  try {
    window.sessionStorage.removeItem(key)
  } catch {
    // 忽略
  }
}
