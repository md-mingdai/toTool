import { useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import {
  ArrowRight,
  CircleNotch,
  DeviceMobile,
  LockKey,
  ShieldCheck,
  WifiHigh,
} from '@phosphor-icons/react'
import { PairingRateLimitedError, PairingRejectedError, PairingUnavailableError } from '../api'
import type { DeviceInfo } from '../types'
import { BrandMark } from './Brand'

interface PairingGateProps {
  open: boolean
  device: DeviceInfo | null
  port: number | null
  onSubmit: (code: string) => Promise<void>
}

const CODE_LENGTH = 6

export function PairingGate({ open, device, port, onSubmit }: PairingGateProps) {
  const [digits, setDigits] = useState<string[]>(() => Array(CODE_LENGTH).fill(''))
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [shake, setShake] = useState(0)
  const [lit, setLit] = useState(false)
  const lightRef = useRef<HTMLSpanElement>(null)
  const inputRefs = useRef<Array<HTMLInputElement | null>>([])

  useEffect(() => {
    if (!open) return
    setDigits(Array(CODE_LENGTH).fill(''))
    setError('')
    setSubmitting(false)
    setLit(false)
    const timer = window.setTimeout(() => inputRefs.current[0]?.focus(), 60)
    return () => window.clearTimeout(timer)
  }, [open])

  if (!open) return null

  function focusInput(index: number) {
    const safeIndex = Math.max(0, Math.min(CODE_LENGTH - 1, index))
    inputRefs.current[safeIndex]?.focus()
    inputRefs.current[safeIndex]?.select()
  }

  function applyDigits(raw: string, startIndex: number) {
    const incoming = raw.replace(/\D/g, '')
    if (!incoming) return
    setDigits((previous) => {
      const next = [...previous]
      let cursor = startIndex
      for (const char of incoming) {
        if (cursor >= CODE_LENGTH) break
        next[cursor] = char
        cursor += 1
      }
      window.setTimeout(() => focusInput(cursor), 0)
      return next
    })
    setError('')
  }

  async function submit() {
    if (submitting) return
    const code = digits.join('')
    if (code.length !== CODE_LENGTH) {
      setError('请输入手机端显示的 6 位配对码。')
      setShake((value) => value + 1)
      focusInput(digits.findIndex((digit) => !digit))
      return
    }
    setSubmitting(true)
    setError('')
    try {
      await onSubmit(code)
    } catch (caught) {
      setShake((value) => value + 1)
      if (caught instanceof PairingRejectedError) {
        setError('配对码不正确，或已过期。请查看手机上的最新配对码。')
        setDigits(Array(CODE_LENGTH).fill(''))
        window.setTimeout(() => focusInput(0), 30)
      } else if (caught instanceof PairingRateLimitedError) {
        setError('尝试过于频繁，请稍等一会儿再试。')
        window.setTimeout(() => focusInput(0), 30)
      } else if (caught instanceof PairingUnavailableError) {
        setError('无法连接手机端。请确认两台设备在同一 Wi-Fi，且手机端网页服务仍在运行。')
        window.setTimeout(() => focusInput(0), 30)
      } else {
        setError('配对失败，请稍后重试。')
      }
    } finally {
      setSubmitting(false)
    }
  }

  function handlePointerEnter(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.pointerType === 'touch') return
    setLit(true)
    event.currentTarget.style.transition = 'transform 0.3s ease-out'
  }

  function handlePointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.pointerType === 'touch') return
    const card = event.currentTarget
    const light = lightRef.current
    if (!light) return
    const rect = card.getBoundingClientRect()
    const x = Math.min(Math.max(event.clientX - rect.left, 0), rect.width)
    const y = Math.min(Math.max(event.clientY - rect.top, 0), rect.height)
    const size = 220
    light.style.left = `${x - size / 2}px`
    light.style.top = `${y - size / 2}px`
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const rotateX = ((y - rect.height / 2) / (rect.height / 2)) * 3
    const rotateY = -((x - rect.width / 2) / (rect.width / 2)) * 3
    card.style.transition = 'transform 0.1s ease-out'
    card.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg)`
  }

  function handlePointerLeave(event: ReactPointerEvent<HTMLDivElement>) {
    setLit(false)
    event.currentTarget.style.transition = 'transform 0.5s ease-out'
    event.currentTarget.style.transform = 'perspective(1000px) rotateX(0deg) rotateY(0deg)'
  }

  const host = typeof window !== 'undefined' ? window.location.host : ''
  const service = port ? `${host.split(':')[0]}:${port}` : host

  return (
    <div className="pair-overlay" role="dialog" aria-modal="true" aria-labelledby="pair-title">
      <div
        className={`pair-card${shake ? ' is-shaking' : ''}${lit ? ' is-lit' : ''}`}
        key={shake}
        onPointerEnter={handlePointerEnter}
        onPointerMove={handlePointerMove}
        onPointerLeave={handlePointerLeave}
      >
        <span className={`pair-light${lit ? ' is-active' : ''}`} ref={lightRef} aria-hidden="true" />
        <div className="pair-content">
        <div className="pair-top">
          <span className="pair-brand">
            <BrandMark size={38} />
            <span>
              <strong>隔空闪传</strong>
              <small>FlashDrop Web</small>
            </span>
          </span>
          <span className="pair-secure">
            <LockKey size={13} weight="regular" aria-hidden="true" />
            安全配对
          </span>
        </div>

        <div className="pair-signal" aria-hidden="true">
          <span className="signal-ring ring-1" />
          <span className="signal-ring ring-2" />
          <span className="signal-ring ring-3" />
          <span className="signal-phone">
            <DeviceMobile size={28} weight="regular" />
          </span>
        </div>

        <div className="pair-heading">
          <p className="eyebrow">连接这台手机</p>
          <h1 id="pair-title">输入手机上的配对码</h1>
          <p>
            这串 6 位数字只显示在运行 toTool 的手机上。配对成功后，文件直接在这两台设备之间传输，不经过云端。
          </p>
        </div>

        <div
          className="code-inputs"
          onPaste={(event) => {
            const text = event.clipboardData.getData('text')
            if (!/\d/.test(text)) return
            event.preventDefault()
            applyDigits(text, 0)
          }}
        >
          {digits.map((digit, index) => (
            <input
              key={index}
              ref={(element) => {
                inputRefs.current[index] = element
              }}
              value={digit}
              onChange={(event) => {
                const raw = event.target.value
                if (raw.length > 1) {
                  applyDigits(raw, index)
                  return
                }
                if (raw && !/^\d$/.test(raw)) return
                setDigits((previous) => {
                  const next = [...previous]
                  next[index] = raw
                  return next
                })
                setError('')
                if (raw) focusInput(index + 1)
              }}
              onKeyDown={(event) => {
                if (event.key === 'Backspace') {
                  if (!digits[index] && index > 0) {
                    event.preventDefault()
                    setDigits((previous) => {
                      const next = [...previous]
                      next[index - 1] = ''
                      return next
                    })
                    focusInput(index - 1)
                  }
                }
                if (event.key === 'ArrowLeft') {
                  event.preventDefault()
                  focusInput(index - 1)
                }
                if (event.key === 'ArrowRight') {
                  event.preventDefault()
                  focusInput(index + 1)
                }
                if (event.key === 'Enter') {
                  event.preventDefault()
                  void submit()
                }
              }}
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              maxLength={1}
              autoComplete={index === 0 ? 'one-time-code' : 'off'}
              aria-label={`配对码第 ${index + 1} 位`}
              disabled={submitting}
            />
          ))}
        </div>

        {error && (
          <p className="pair-error" role="alert">
            {error}
          </p>
        )}

        <button type="button" className="btn btn-primary pair-submit" onClick={() => void submit()} disabled={submitting}>
          {submitting ? (
            <>
              <CircleNotch className="spin" size={17} weight="regular" />
              正在连接
            </>
          ) : (
            <>
              连接这台手机
              <ArrowRight size={17} weight="regular" />
            </>
          )}
        </button>

        <div className="pair-foot">
          <span className="pair-device">
            <span className="device-avatar small" aria-hidden="true">
              <DeviceMobile size={16} weight="regular" />
            </span>
            <span>
              <strong>{device?.name ?? '正在读取手机信息'}</strong>
              <small>{device ? `${device.system} · 同一 Wi-Fi` : '请让手机的隔空闪传服务保持运行'}</small>
            </span>
          </span>
          <span className="pair-network">
            <WifiHigh size={14} weight="regular" aria-hidden="true" />
            <code>{service || '局域网'}</code>
          </span>
        </div>

        <p className="pair-scope">
          <ShieldCheck size={13} weight="regular" aria-hidden="true" />
          手机端在 2233-2333 中自动选择可用端口
        </p>
        </div>
      </div>
    </div>
  )
}
