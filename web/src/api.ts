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
  PairResult,
  ServerInfo,
  TransferRecord,
} from './types'
const TOKEN_KEY = 'flashdrop-token'
const KEY_KEY = 'flashdrop-key'
const MAX_ENCRYPTED_BYTES = 256 * 1024 * 1024
const PBKDF2_ITERATIONS_FALLBACK = 120_000

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
): Promise<Entry[]> {
  const response = await apiFetch(
    `/api/files?category=${encodeURIComponent(category)}&path=${encodeURIComponent(path)}&q=${encodeURIComponent(query)}`,
  )
  const data = (await response.json()) as { items: Entry[] }
  return data.items.map((entry) => decorateEntry(entry))
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
  onProgress: (phase: DownloadPhase, ratio: number) => void = () => {},
  signal?: AbortSignal,
): Promise<string> {
  if (entry.type === 'folder') throw new Error('不能下载文件夹')
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
        onProgress('download', Math.min(1, event.loaded / event.total))
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
            if (raw.length < 13) throw new Error('加密文件格式错误')
            onProgress('decrypt', 0)
            // 让浏览器先画出“正在解密”的进度状态，再执行同步 AES-GCM 解密。
            await new Promise<void>((paint) => {
              window.requestAnimationFrame(() => window.requestAnimationFrame(() => paint()))
            })
            const plain = gcm(encryptionKey, raw.slice(0, 12)).decrypt(raw.slice(12))
            blob = new Blob([toArrayBuffer(plain)])
            onProgress('decrypt', 1)
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

export async function uploadFile(
  file: File,
  taskId: string,
  onProgress: (ratio: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  const encrypted = Boolean(encryptionKey)
  if (encrypted && file.size > MAX_ENCRYPTED_BYTES) {
    throw new Error('加密模式暂支持 256 MB 以内的文件')
  }

  let body: Blob | File = file
  if (encrypted && encryptionKey) {
    const plain = new Uint8Array(await file.arrayBuffer())
    const iv = randomBytes(12)
    const ciphertext = gcm(encryptionKey, iv).encrypt(plain)
    body = new Blob([toArrayBuffer(iv), toArrayBuffer(ciphertext)], { type: 'application/octet-stream' })
  }

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
      if (event.lengthComputable && event.total > 0) onProgress(event.loaded / event.total)
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
