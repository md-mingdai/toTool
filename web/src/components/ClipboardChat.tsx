import { useState } from 'react'
import { ClipboardText, Copy, PaperPlaneRight } from '@phosphor-icons/react'
import type { ClipboardMessage } from '../types'

interface ClipboardChatProps {
  messages: ClipboardMessage[]
  onSend: (text: string) => Promise<void>
  onCopy: (text: string) => void
}

export function ClipboardChat({ messages, onSend, onCopy }: ClipboardChatProps) {
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)

  async function submit() {
    const value = text.trim()
    if (!value || sending) return
    setSending(true)
    try {
      await onSend(value)
      setText('')
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="clipboard-chat">
      <div className={`clipboard-messages${messages.length === 0 ? ' is-empty' : ''}`}>
        {messages.length === 0 ? (
          <div className="clipboard-empty">
            <ClipboardText size={26} weight="regular" aria-hidden="true" />
            <strong>还没有剪贴板消息</strong>
            <span>在手机复制文字，或从电脑发送一段内容，都会出现在这里。</span>
          </div>
        ) : (
          messages.map((message) => (
            <div className={`clipboard-row is-${message.source}`} key={message.id}>
              <div className="clipboard-bubble">
                <p>{message.text}</p>
                <div className="clipboard-bubble-foot">
                  <span>{message.source === 'phone' ? '手机' : '电脑'} · {message.createdAt}</span>
                  <button
                    type="button"
                    className="clipboard-copy"
                    onClick={() => onCopy(message.text)}
                    aria-label="复制这条剪贴板内容"
                    title="复制"
                  >
                    <Copy size={14} weight="regular" />
                  </button>
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      <form
        className="clipboard-composer"
        onSubmit={(event) => {
          event.preventDefault()
          void submit()
        }}
      >
        <textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              void submit()
            }
          }}
          placeholder="输入要发送到手机剪贴板的内容，Enter 发送，Shift+Enter 换行"
          rows={2}
          maxLength={100_000}
        />
        <button type="submit" className="btn btn-primary" disabled={sending || !text.trim()}>
          <PaperPlaneRight size={16} weight="regular" />
          {sending ? '发送中' : '发送'}
        </button>
      </form>
    </div>
  )
}
