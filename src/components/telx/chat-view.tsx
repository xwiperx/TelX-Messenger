'use client'

import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { api, POST, fullName, timeStr, dayDividerStr, lastSeenFa, sizeStr, waveHeights, REACTION_EMOJIS, wallpaperClass, avatarColor } from './helpers'
import type { TUser, TChat, TMessage } from './helpers'
import Avatar from './avatar'
import ForwardDialog from './forward-dialog'
import MediaViewer from './media-viewer'
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  ArrowRight, MoreVertical, Pin, Search, X, Send, Mic, Paperclip, Smile,
  Check, CheckCheck, Copy, Forward, Pencil, Trash2, Reply, Play, Pause,
  Loader2, Image as ImageIcon, File as FileIcon, Lock, CircleAlert,
} from 'lucide-react'

type Props = {
  me: TUser
  chatId: string
  chatInfo: TChat | null
  onBack: () => void
  onChatsChanged: () => void
  onOpenProfile: (u: TUser) => void
}

type PendingReply = { id: string; text: string; senderName: string; mine: boolean } | null

export default function ChatView({ me, chatId, chatInfo, onBack, onChatsChanged, onOpenProfile }: Props) {
  const [messages, setMessages] = useState<TMessage[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [text, setText] = useState('')
  const [replyTo, setReplyTo] = useState<PendingReply>(null)
  const [editing, setEditing] = useState<TMessage | null>(null)
  const [other, setOther] = useState<TUser | null>(null)
  const [chatType, setChatType] = useState<'private' | 'saved'>('private')
  const [typingNames, setTypingNames] = useState<string[]>([])
  const [menuOpen, setMenuOpen] = useState(false)
  const [actionMsg, setActionMsg] = useState<TMessage | null>(null)
  const [forwardIds, setForwardIds] = useState<string[] | null>(null)
  const [selectMode, setSelectMode] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [mediaView, setMediaView] = useState<string | null>(null)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQ, setSearchQ] = useState('')
  const [searchResults, setSearchResults] = useState<TMessage[]>([])
  const [pinned, setPinned] = useState<TMessage | null>(null)
  const [highlightId, setHighlightId] = useState<string | null>(null)
  const [attachOpen, setAttachOpen] = useState(false)
  const [recording, setRecording] = useState(false)
  const [recSeconds, setRecSeconds] = useState(0)
  const [sending, setSending] = useState(false)

  const listRef = useRef<HTMLDivElement>(null)
  const endRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const imgInputRef = useRef<HTMLInputElement>(null)
  const pollAbort = useRef<AbortController | null>(null)
  const typingClearTimer = useRef<any>(null)
  const recordingRef = useRef<MediaRecorder | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const recTimerRef = useRef<any>(null)
  const atBottomRef = useRef(true)

  const otherLocked = chatType === 'private' && other?.privacyLock

  // ─── Load chat info + history ───────────────────────────────
  const loadHistory = useCallback(async (scroll = true) => {
    const [infoR, msgR] = await Promise.all([
      api<{ chats: TChat[] }>('/api/chats'),
      api<{ messages: TMessage[]; hasMore: boolean }>(`/api/chats/${chatId}/messages`),
    ])
    if (infoR.ok) {
      const c = infoR.chats.find((x) => x.id === chatId)
      if (c) { setOther(c.other); setChatType(c.type) }
    }
    if (msgR.ok) {
      setMessages(msgR.messages)
      setHasMore(msgR.hasMore)
      const p = msgR.messages.find((m) => m.pinned)
      setPinned(p || null)
    }
    setLoading(false)
    if (scroll) setTimeout(() => endRef.current?.scrollIntoView({ behavior: 'auto' }), 60)
  }, [chatId])

  useEffect(() => {
    const t = setTimeout(() => {
      setLoading(true)
      loadHistory(true)
    }, 0)
    return () => { clearTimeout(t); pollAbort.current?.abort() }
  }, [chatId, loadHistory])

  // ─── Mark read when opening / new messages ─────────────────
  const markRead = useCallback(async () => {
    const last = messages.filter((m) => !m.mine).at(-1)
    if (!last) return
    await api(`/api/chats/${chatId}/read`, POST({ lastMessageId: last.id }))
    onChatsChanged()
  }, [messages, chatId, onChatsChanged])

  useEffect(() => {
    if (!loading && messages.length > 0) markRead()
     
  }, [chatId, loading])

  // ─── Long polling loop ──────────────────────────────────────
  useEffect(() => {
    if (loading) return
    let stopped = false
    const ctrl = new AbortController()
    pollAbort.current = ctrl

    async function poll() {
      while (!stopped) {
        try {
          const res = await fetch(`/api/chats/${chatId}/longpoll`, { signal: ctrl.signal, cache: 'no-store' })
          const data = await res.json()
          if (stopped) break
          if (data.ok && data.changed) {
            if (data.changed === 'message' || data.changed === 'typing') {
              const r = await api<{ messages: TMessage[]; typing: string[] }>(`/api/chats/${chatId}/longpoll`, POST({ afterId: null }))
              if (r.ok) {
                if (r.typing) {
                  setTypingNames(r.typing)
                  clearTimeout(typingClearTimer.current)
                  typingClearTimer.current = setTimeout(() => setTypingNames([]), 4200)
                } else {
                  setTypingNames([])
                }
                if (r.messages.length > 0) {
                  setMessages((prev) => {
                    const map = new Map(prev.map((m) => [m.id, m]))
                    r.messages.forEach((m) => map.set(m.id, m))
                    const merged = [...map.values()].sort((a, b) => +new Date(a.createdAt) - +new Date(b.createdAt))
                    return merged
                  })
                  markRead()
                }
              }
            } else {
              // read / edit / delete / reaction / pin → refresh history view
              const r = await api<{ messages: TMessage[]; hasMore: boolean }>(`/api/chats/${chatId}/messages`)
              if (r.ok) {
                setMessages((prev) => {
                  const map = new Map(prev.map((m) => [m.id, m]))
                  r.messages.forEach((m) => map.set(m.id, m))
                  return [...map.values()].sort((a, b) => +new Date(a.createdAt) - +new Date(b.createdAt))
                })
                const p = r.messages.find((m) => m.pinned)
                setPinned(p || null)
              }
            }
          }
        } catch (e: any) {
          if (stopped || e?.name === 'AbortError') break
          await new Promise((r) => setTimeout(r, 1500))
        }
      }
    }
    poll()
    return () => { stopped = true; ctrl.abort() }
     
  }, [chatId, loading])

  // ─── Typing indicator ───────────────────────────────────────
  const typingSent = useRef(0)
  function onType(v: string) {
    setText(v)
    const now = Date.now()
    if (now - typingSent.current > 2500 && v.length > 0) {
      typingSent.current = now
      api(`/api/chats/${chatId}/typing`, POST({})).catch(() => {})
    }
  }

  // ─── Send ───────────────────────────────────────────────────
  async function sendText() {
    const t = text.trim()
    if (!t || sending) return
    setSending(true)
    if (editing) {
      const r = await api<{ message: TMessage }>(`/api/messages/${editing.id}`, POST({ action: 'edit', text: t }))
      if (r.ok && r.message) setMessages((prev) => prev.map((m) => (m.id === r.message.id ? r.message : m)))
      setEditing(null); setText('')
    } else {
      const r = await api<{ message: TMessage }>(`/api/chats/${chatId}/messages`, POST({ type: 'text', text: t, replyToId: replyTo?.id }))
      if (r.ok && r.message) {
        setMessages((prev) => (prev.some((m) => m.id === r.message.id) ? prev : [...prev, r.message]))
        setReplyTo(null)
        setTimeout(() => endRef.current?.scrollIntoView({ behavior: 'smooth' }), 50)
        onChatsChanged()
      }
    }
    setSending(false)
  }

  async function uploadAndSend(file: File, type: 'image' | 'file') {
    setSending(true)
    const fd = new FormData()
    fd.append('file', file)
    const up = await api<{ url: string }>('/api/upload', { method: 'POST', body: fd })
    setSending(false)
    if (!up.ok) return
    const r = await api<{ message: TMessage }>(`/api/chats/${chatId}/messages`, POST({
      type, text: '', mediaUrl: up.url, fileName: file.name, fileSize: file.size, replyToId: replyTo?.id,
    }))
    if (r.ok && r.message) {
      setMessages((prev) => [...prev, r.message])
      setReplyTo(null)
      setTimeout(() => endRef.current?.scrollIntoView({ behavior: 'smooth' }), 50)
      onChatsChanged()
    }
  }

  // ─── Voice recording ────────────────────────────────────────
  async function startRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const rec = new MediaRecorder(stream)
      chunksRef.current = []
      rec.ondataavailable = (e) => { if (e.data.size > 0) chunksRef.current.push(e.data) }
      rec.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop())
        const blob = new Blob(chunksRef.current, { type: 'audio/webm' })
        const seconds = recSeconds
        if (blob.size > 800 && seconds >= 1) {
          setSending(true)
          const fd = new FormData()
          fd.append('file', new File([blob], 'voice.webm', { type: 'audio/webm' }))
          const up = await api<{ url: string }>('/api/upload', { method: 'POST', body: fd })
          if (up.ok) {
            const r = await api<{ message: TMessage }>(`/api/chats/${chatId}/messages`, POST({
              type: 'voice', text: '', mediaUrl: up.url, duration: seconds, replyToId: replyTo?.id,
            }))
            if (r.ok && r.message) {
              setMessages((prev) => [...prev, r.message])
              setReplyTo(null)
              setTimeout(() => endRef.current?.scrollIntoView({ behavior: 'smooth' }), 50)
              onChatsChanged()
            }
          }
          setSending(false)
        }
      }
      recordingRef.current = rec
      rec.start()
      setRecording(true)
      setRecSeconds(0)
      recTimerRef.current = setInterval(() => setRecSeconds((s) => s + 1), 1000)
    } catch {
      // microphone denied
    }
  }

  function stopRecording(send: boolean) {
    clearInterval(recTimerRef.current)
    const rec = recordingRef.current
    if (!rec) return
    if (!send) { if (recSeconds < 1) setRecSeconds(0); chunksRef.current = []; rec.onstop = null as any; rec.stop(); setRecording(false); recordingRef.current = null; return }
    rec.stop()
    setRecording(false)
    recordingRef.current = null
  }

  // ─── Message actions ────────────────────────────────────────
  async function react(msg: TMessage, emoji: string) {
    const r = await api<{ message: TMessage }>(`/api/messages/${msg.id}`, POST({ action: 'react', emoji }))
    if (r.ok && r.message) setMessages((prev) => prev.map((m) => (m.id === r.message.id ? r.message : m)))
    setActionMsg(null)
  }

  async function deleteMsg(id: string) {
    const r = await api(`/api/messages/${id}`, { method: 'DELETE' })
    if (r.ok) {
      setMessages((prev) => prev.filter((m) => m.id !== id))
      setActionMsg(null)
      setPinned((p) => (p?.id === id ? null : p))
      onChatsChanged()
    }
  }

  async function deleteSelected() {
    for (const id of selected) await api(`/api/messages/${id}`, { method: 'DELETE' })
    setMessages((prev) => prev.filter((m) => !selected.has(m.id)))
    setSelected(new Set()); setSelectMode(false)
    onChatsChanged()
  }

  async function togglePin(msg: TMessage) {
    const r = await api<{ pinned: boolean }>(`/api/messages/${msg.id}`, POST({ action: 'pin', pinned: !msg.pinned }))
    if (r.ok) {
      setPinned(r.pinned ? msg : null)
      setMessages((prev) => prev.map((m) => (m.id === msg.id ? { ...m, pinned: r.pinned } : m.id === pinned?.id ? { ...m, pinned: false } : m)))
      setActionMsg(null)
    }
  }

  function copyMsg(msg: TMessage) {
    navigator.clipboard?.writeText(msg.text || '').catch(() => {})
    setActionMsg(null)
  }

  function canForward(msg: TMessage): boolean {
    if (msg.mine) return true
    return !otherLocked
  }

  // ─── Search in chat ─────────────────────────────────────────
  useEffect(() => {
    const q = searchQ.trim()
    const t = setTimeout(async () => {
      if (!searchOpen || !q) { setSearchResults([]); return }
      const r = await api<{ messages: TMessage[] }>(`/api/chats/${chatId}/search?q=${encodeURIComponent(q)}`)
      if (r.ok) setSearchResults(r.messages)
    }, searchOpen && q ? 300 : 0)
    return () => clearTimeout(t)
  }, [searchQ, searchOpen, chatId])

  function jumpTo(id: string) {
    const el = document.getElementById(`msg-${id}`)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      setHighlightId(id)
      setTimeout(() => setHighlightId(null), 1600)
    }
    setSearchOpen(false); setSearchQ('')
  }

  // ─── Scroll helpers ─────────────────────────────────────────
  function onScroll() {
    const el = listRef.current
    if (!el) return
    atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120
  }

  const title = chatType === 'saved' ? 'پیام‌های ذخیره شده' : fullName(other)
  const statusText = typingNames.length > 0
    ? 'در حال تایپ…'
    : chatType === 'saved'
      ? 'ذخیره‌سازی ابری'
      : other ? lastSeenFa(other) : ''

  const grouped = useMemo(() => {
    const out: { day: string; items: TMessage[] }[] = []
    for (const m of messages) {
      const day = dayDividerStr(m.createdAt)
      const last = out.at(-1)
      if (last && last.day === day) last.items.push(m)
      else out.push({ day, items: [m] })
    }
    return out
  }, [messages])

  return (
    <div className={`h-full flex flex-col relative ${wallpaperClass(me.wallpaper)}`} dir="rtl">
      {/* Header */}
      <div className="flex items-center gap-2 px-2 py-2 shrink-0 z-20" style={{ background: 'var(--tg-panel)', color: 'var(--tg-panel-text)', borderBottom: '1px solid var(--tg-divider)' }}>
        {selectMode ? (
          <>
            <button onClick={() => { setSelectMode(false); setSelected(new Set()) }} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="لغو انتخاب">
              <X className="w-5 h-5" />
            </button>
            <span className="font-semibold flex-1">{selected.size} انتخاب شده</span>
            <button onClick={() => { if (selected.size) setForwardIds([...selected]) }} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="فوروارد">
              <Forward className="w-5 h-5" />
            </button>
            <button onClick={deleteSelected} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="حذف">
              <Trash2 className="w-5 h-5 text-red-500" />
            </button>
          </>
        ) : searchOpen ? (
          <>
            <button onClick={() => setSearchOpen(false)} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="بازگشت">
              <ArrowRight className="w-5 h-5" />
            </button>
            <Input autoFocus value={searchQ} onChange={(e) => setSearchQ(e.target.value)} placeholder="جستجو در این چت…" className="h-9 rounded-full bg-muted border-none" />
          </>
        ) : (
          <>
            <button onClick={onBack} className="p-2 rounded-full hover:bg-[var(--tg-hover)] md:hidden" aria-label="بازگشت">
              <ArrowRight className="w-5 h-5" />
            </button>
            <button className="flex items-center gap-2.5 flex-1 min-w-0 text-right" onClick={() => other && onOpenProfile(other)}>
              {chatType === 'saved' ? (
                <div className="w-10 h-10 rounded-full flex items-center justify-center shrink-0" style={{ background: 'linear-gradient(135deg,#3390EC,#6ec531)' }}>
                  <Send className="w-5 h-5 text-white -rotate-12" />
                </div>
              ) : (
                <Avatar user={other} size={40} showOnline />
              )}
              <div className="min-w-0">
                <p className="text-[15px] font-semibold truncate">{title}</p>
                <p className={`text-[11.5px] truncate ${typingNames.length > 0 ? '' : 'text-muted-foreground'}`} style={typingNames.length > 0 ? { color: 'var(--tg-blue)' } : {}}>
                  {statusText}
                </p>
              </div>
            </button>
            <button onClick={() => setSearchOpen(true)} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="جستجو">
              <Search className="w-5 h-5" />
            </button>
            <button onClick={() => setMenuOpen(true)} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="منو">
              <MoreVertical className="w-5 h-5" />
            </button>
          </>
        )}
      </div>

      {/* Pinned bar */}
      {pinned && !selectMode && (
        <button onClick={() => jumpTo(pinned.id)} className="flex items-center gap-2 px-3 py-1.5 shrink-0 text-right" style={{ background: 'var(--tg-panel)', borderBottom: '1px solid var(--tg-divider)' }}>
          <span className="w-0.5 h-8 rounded-full shrink-0" style={{ background: 'var(--tg-blue)' }} />
          <div className="min-w-0 flex-1">
            <p className="text-[11.5px] font-semibold" style={{ color: 'var(--tg-blue)' }}>پیام سنجاق‌شده</p>
            <p className="text-[12px] text-muted-foreground truncate">{pinned.text || (pinned.type === 'image' ? '🖼 عکس' : pinned.type === 'voice' ? '🎤 پیام صوتی' : '📎 فایل')}</p>
          </div>
          <X onClick={(e) => { e.stopPropagation(); togglePin(pinned) }} className="w-4 h-4 text-muted-foreground shrink-0" />
        </button>
      )}

      {/* Messages */}
      <div ref={listRef} onScroll={onScroll} className="flex-1 overflow-y-auto tg-scroll px-3 py-3">
        {loading ? (
          <div className="flex justify-center py-10"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
        ) : messages.length === 0 ? (
          <div className="h-full flex items-center justify-center">
            <p className="bg-black/25 text-white/90 text-xs px-4 py-2 rounded-full backdrop-blur">
              {chatType === 'saved' ? 'پیام‌ها و فایل‌های خود را اینجا ذخیره کنید' : 'پیامی نیست — اولین پیام را بفرستید'}
            </p>
          </div>
        ) : (
          <>
            {hasMore && (
              <div className="flex justify-center mb-3">
                <button onClick={() => loadHistory(false)} className="text-xs px-3 py-1.5 rounded-full bg-black/25 text-white/85 backdrop-blur">پیام‌های قدیمی‌تر</button>
              </div>
            )}
            {grouped.map((g) => (
              <div key={g.day}>
                <div className="flex justify-center my-3">
                  <span className="text-[11.5px] text-white/90 bg-black/25 px-3 py-1 rounded-full backdrop-blur">{g.day}</span>
                </div>
                {g.items.map((m) => (
                  <Bubble
                    key={m.id}
                    m={m}
                    me={me}
                    locked={otherLocked}
                    highlighted={highlightId === m.id}
                    selectMode={selectMode}
                    selected={selected.has(m.id)}
                    onSelectToggle={() => {
                      if (!selectMode) return
                      setSelected((s) => { const n = new Set(s); if (n.has(m.id)) { n.delete(m.id) } else { n.add(m.id) }; return n })
                    }}
                    onLongPress={() => { if (!selectMode) setActionMsg(m) }}
                    onReact={(emoji) => react(m, emoji)}
                    onJump={jumpTo}
                    onImage={(url) => setMediaView(url)}
                  />
                ))}
              </div>
            ))}
            {typingNames.length > 0 && (
              <div className="flex items-center gap-1.5 my-1 px-2">
                <div className="tg-bubble tg-bubble-in px-3 py-2 flex gap-1 items-center h-7">
                  <span className="tg-typing-dot w-1.5 h-1.5 rounded-full bg-muted-foreground inline-block" />
                  <span className="tg-typing-dot w-1.5 h-1.5 rounded-full bg-muted-foreground inline-block" />
                  <span className="tg-typing-dot w-1.5 h-1.5 rounded-full bg-muted-foreground inline-block" />
                </div>
              </div>
            )}
            <div ref={endRef} />
          </>
        )}
      </div>

      {/* Input bar */}
      <div className="shrink-0 z-20 px-2 pb-2 pt-1" style={{ background: 'transparent' }}>
        {replyTo && !editing && (
          <div className="flex items-center gap-2 bg-[var(--tg-panel)] rounded-t-xl px-3 py-2 border-r-4" style={{ borderColor: 'var(--tg-blue)' }}>
            <div className="flex-1 min-w-0">
              <p className="text-[12px] font-semibold" style={{ color: 'var(--tg-blue)' }}>پاسخ به {replyTo.mine ? 'خودتان' : replyTo.senderName}</p>
              <p className="text-[12px] text-muted-foreground truncate">{replyTo.text || 'رسانه'}</p>
            </div>
            <button onClick={() => setReplyTo(null)} aria-label="لغو پاسخ"><X className="w-4 h-4 text-muted-foreground" /></button>
          </div>
        )}
        {editing && (
          <div className="flex items-center gap-2 bg-[var(--tg-panel)] rounded-t-xl px-3 py-2 border-r-4" style={{ borderColor: '#f5a623' }}>
            <div className="flex-1 min-w-0">
              <p className="text-[12px] font-semibold" style={{ color: '#f5a623' }}>ویرایش پیام</p>
              <p className="text-[12px] text-muted-foreground truncate">{editing.text}</p>
            </div>
            <button onClick={() => { setEditing(null); setText('') }} aria-label="لغو ویرایش"><X className="w-4 h-4 text-muted-foreground" /></button>
          </div>
        )}

        {recording ? (
          <div className="flex items-center gap-3 bg-[var(--tg-panel)] rounded-2xl px-4 py-3">
            <span className="w-3 h-3 rounded-full bg-red-500 tg-recording" />
            <span className="text-red-500 font-mono text-sm min-w-[42px]">{Math.floor(recSeconds / 60)}:{String(recSeconds % 60).padStart(2, '0')}</span>
            <div className="flex-1 flex items-center gap-0.5 overflow-hidden">
              {waveHeights('live', 26).map((h, i) => (
                <span key={i} className="tg-wave" ><span style={{ height: h, animation: `tg-typing ${0.6 + (i % 5) * 0.2}s infinite` }} /></span>
              ))}
            </div>
            <button onClick={() => stopRecording(false)} className="text-xs text-muted-foreground px-2">لغو</button>
            <button onClick={() => stopRecording(true)} className="w-10 h-10 rounded-full text-white flex items-center justify-center" style={{ background: 'var(--tg-blue)' }} aria-label="ارسال">
              <Send className="w-4.5 h-4.5 -rotate-12" />
            </button>
          </div>
        ) : (
          <div className="flex items-end gap-1.5">
            <div className="flex-1 flex items-end bg-[var(--tg-panel)] rounded-2xl px-2 py-1.5 shadow-sm">
              <button onClick={() => setAttachOpen(true)} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="پیوست">
                <Paperclip className="w-5 h-5 text-muted-foreground" />
              </button>
              <input
                ref={inputRef}
                value={text}
                onChange={(e) => onType(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendText() } }}
                placeholder="پیام…"
                className="flex-1 bg-transparent outline-none text-[15px] py-1.5 px-1 min-w-0 placeholder:text-muted-foreground"
                style={{ color: 'var(--tg-panel-text)' }}
                dir="auto"
              />
              <button onClick={() => setText((t) => t + '🙂')} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="ایموجی">
                <Smile className="w-5 h-5 text-muted-foreground" />
              </button>
            </div>
            {text.trim() ? (
              <button onClick={sendText} disabled={sending} className="w-12 h-12 rounded-full text-white flex items-center justify-center shrink-0 shadow-lg transition-transform active:scale-90" style={{ background: 'var(--tg-blue)' }} aria-label="ارسال">
                {sending ? <Loader2 className="w-5 h-5 animate-spin" /> : <Send className="w-5 h-5 -rotate-12 -ml-0.5" />}
              </button>
            ) : (
              <button onClick={startRecording} className="w-12 h-12 rounded-full text-white flex items-center justify-center shrink-0 shadow-lg transition-transform active:scale-90" style={{ background: 'var(--tg-blue)' }} aria-label="ضبط صدا">
                <Mic className="w-5 h-5" />
              </button>
            )}
          </div>
        )}
      </div>

      {/* hidden inputs */}
      <input ref={imgInputRef} type="file" accept="image/*" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadAndSend(f, 'image'); e.target.value = '' }} />
      <input ref={fileInputRef} type="file" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadAndSend(f, 'file'); e.target.value = '' }} />

      {/* Attach menu */}
      <Dialog open={attachOpen} onOpenChange={setAttachOpen}>
        <DialogContent className="rounded-2xl max-w-[240px] bottom-16 top-auto translate-y-0" dir="rtl">
          <DialogTitle className="sr-only">پیوست فایل</DialogTitle>
          <button onClick={() => { setAttachOpen(false); imgInputRef.current?.click() }} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
            <span className="w-9 h-9 rounded-full flex items-center justify-center text-white" style={{ background: '#4dcd5e' }}><ImageIcon className="w-4.5 h-4.5" /></span>
            عکس یا ویدیو
          </button>
          <button onClick={() => { setAttachOpen(false); fileInputRef.current?.click() }} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
            <span className="w-9 h-9 rounded-full flex items-center justify-center text-white" style={{ background: 'var(--tg-blue)' }}><FileIcon className="w-4.5 h-4.5" /></span>
            فایل
          </button>
        </DialogContent>
      </Dialog>

      {/* Header menu */}
      <Dialog open={menuOpen} onOpenChange={setMenuOpen}>
        <DialogContent className="rounded-2xl max-w-[260px] top-14 right-3 left-auto translate-x-0 translate-y-0" dir="rtl">
          <DialogTitle className="sr-only">منوی چت</DialogTitle>
          <button onClick={() => { setMenuOpen(false); setSearchOpen(true) }} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
            <Search className="w-5 h-5 text-muted-foreground" /> جستجو در چت
          </button>
          <button onClick={() => { setMenuOpen(false); setSelectMode(true) }} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
            <Check className="w-5 h-5 text-muted-foreground" /> انتخاب چندگانه
          </button>
          {otherLocked && (
            <p className="flex items-center gap-2 text-xs text-amber-600 pt-2 px-2">
              <Lock className="w-4 h-4" /> قفل پیوی فعال است: کپی/فوروارد محدود
            </p>
          )}
        </DialogContent>
      </Dialog>

      {/* Message action sheet */}
      <Dialog open={!!actionMsg} onOpenChange={(o) => { if (!o) setActionMsg(null) }}>
        <DialogContent className="rounded-2xl max-w-xs" dir="rtl">
          <DialogTitle className="sr-only">عملیات پیام</DialogTitle>
          {actionMsg && (
            <>
              <div className="flex justify-center gap-1 flex-wrap pb-2 border-b" style={{ borderColor: 'var(--tg-divider)' }}>
                {REACTION_EMOJIS.map((e) => (
                  <button key={e} onClick={() => react(actionMsg, e)} className="text-xl p-1.5 rounded-full hover:bg-muted transition-transform active:scale-125">
                    {e}
                  </button>
                ))}
              </div>
              <div className="pt-2 space-y-0.5">
                <button onClick={() => { setReplyTo({ id: actionMsg.id, text: actionMsg.text, senderName: actionMsg.sender.firstName, mine: actionMsg.mine }); setActionMsg(null); inputRef.current?.focus() }} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
                  <Reply className="w-5 h-5 text-muted-foreground" /> پاسخ
                </button>
                {canForward(actionMsg) && (
                  <button onClick={() => { setForwardIds([actionMsg.id]); setActionMsg(null) }} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
                    <Forward className="w-5 h-5 text-muted-foreground" /> فوروارد
                  </button>
                )}
                {actionMsg.mine && actionMsg.type === 'text' && (
                  <button onClick={() => { setEditing(actionMsg); setText(actionMsg.text); setActionMsg(null); inputRef.current?.focus() }} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
                    <Pencil className="w-5 h-5 text-muted-foreground" /> ویرایش
                  </button>
                )}
                {actionMsg.type === 'text' && !otherLocked && (
                  <button onClick={() => copyMsg(actionMsg)} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
                    <Copy className="w-5 h-5 text-muted-foreground" /> کپی متن
                  </button>
                )}
                <button onClick={() => togglePin(actionMsg)} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2">
                  <Pin className="w-5 h-5 text-muted-foreground" /> {actionMsg.pinned ? 'برداشتن سنجاق' : 'سنجاق کردن'}
                </button>
                {actionMsg.mine && (
                  <button onClick={() => deleteMsg(actionMsg.id)} className="flex items-center gap-3 w-full py-2.5 text-right hover:bg-muted rounded-lg px-2 text-red-500">
                    <Trash2 className="w-5 h-5" /> حذف
                  </button>
                )}
              </div>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* Forward */}
      {forwardIds && (
        <ForwardDialog
          me={me}
          excludeChatId={null}
          onClose={() => setForwardIds(null)}
          onDone={() => { setForwardIds(null); setSelectMode(false); setSelected(new Set()); onChatsChanged() }}
        />
      )}

      {/* Media viewer */}
      {mediaView && <MediaViewer url={mediaView} onClose={() => setMediaView(null)} />}
    </div>
  )
}

// ═══════════════ Message Bubble ═══════════════

function Bubble({
  m, me, locked, highlighted, selectMode, selected, onSelectToggle, onLongPress, onJump, onImage,
}: {
  m: TMessage
  me: TUser
  locked: boolean
  highlighted: boolean
  selectMode: boolean
  selected: boolean
  onSelectToggle: () => void
  onLongPress: () => void
  onReact: (emoji: string) => void
  onJump: (id: string) => void
  onImage: (url: string) => void
}) {
  const [playing, setPlaying] = useState(false)
  const [progress, setProgress] = useState(0)
  const audioRef = useRef<HTMLAudioElement>(null)
  const longPressRef = useRef<any>(null)
  const longFired = useRef(false)

  const mine = m.mine
  const heights = useMemo(() => waveHeights(m.id), [m.id])

  function handleTouchStart() {
    longFired.current = false
    longPressRef.current = setTimeout(() => { longFired.current = true; onLongPress() }, 480)
  }
  function clearLongPress() {
    clearTimeout(longPressRef.current)
  }
  function handleClick(e: React.MouseEvent) {
    if (longFired.current) { e.preventDefault(); return }
    if (selectMode) { onSelectToggle(); return }
    if (m.replyTo) onJump(m.replyTo.id)
  }

  const showMedia = !m.isDeleted && m.mediaUrl
  const textOnly = m.type === 'text'

  return (
    <div
      id={`msg-${m.id}`}
      className={`flex mb-1.5 px-1 ${mine ? 'justify-start' : 'justify-end'} transition-colors duration-700 rounded-lg`}
      style={highlighted ? { background: 'rgba(51,144,236,0.18)' } : undefined}
    >
      {selectMode && (
        <button onClick={onSelectToggle} className="self-center ml-2 shrink-0" aria-label="انتخاب">
          <span className={`w-5.5 h-5.5 w-[22px] h-[22px] rounded-full border-2 flex items-center justify-center ${selected ? 'text-white' : ''}`} style={{ borderColor: selected ? 'var(--tg-blue)' : 'rgba(128,128,128,.5)', background: selected ? 'var(--tg-blue)' : 'transparent' }}>
            {selected && <Check className="w-3.5 h-3.5" strokeWidth={3} />}
          </span>
        </button>
      )}

      <div
        dir="auto"
        className={`tg-bubble ${mine ? 'tg-bubble-out tg-tail-out' : 'tg-bubble-in tg-tail-in'} px-2.5 py-1.5 ${selectMode ? 'cursor-pointer' : ''} ${m.pinned ? 'ring-1' : ''}`}
        style={m.pinned ? { '--tw-ring-color': 'var(--tg-blue)' } as any : undefined}
        onTouchStart={handleTouchStart}
        onTouchEnd={clearLongPress}
        onTouchMove={clearLongPress}
        onContextMenu={(e) => { e.preventDefault(); onLongPress() }}
        onClick={handleClick}
      >
        {/* forwarded label */}
        {m.forwardedByName && !m.isDeleted && (
          <p className="text-[12px] font-semibold mb-0.5" style={{ color: 'var(--tg-blue)' }}>
            فورواردشده از {m.forwardedByName}
          </p>
        )}

        {/* reply preview */}
        {m.replyTo && !m.isDeleted && (
          <div className="rounded-md px-2 py-1 mb-1 text-right" style={{ background: 'rgba(51,144,236,0.12)', borderRight: '2.5px solid var(--tg-blue)' }}>
            <p className="text-[12px] font-semibold" style={{ color: 'var(--tg-blue)' }}>{m.replyTo.mine ? 'خودتان' : m.replyTo.senderName}</p>
            <p className="text-[12.5px] opacity-80 truncate max-w-[220px]">
              {m.replyTo.type === 'text' ? m.replyTo.text : m.replyTo.type === 'image' ? '🖼 عکس' : m.replyTo.type === 'voice' ? '🎤 پیام صوتی' : '📎 فایل'}
            </p>
          </div>
        )}

        {/* deleted */}
        {m.isDeleted ? (
          <p className="text-[13.5px] italic opacity-60 py-0.5">این پیام حذف شد</p>
        ) : (
          <>
            {/* image */}
            {m.type === 'image' && showMedia && (
              <div className="relative -mx-0.5 -mt-0.5 mb-1">
                {!locked || mine ? (
                  <button onClick={(e) => { e.stopPropagation(); onImage(m.mediaUrl!) }} className="block">
                    { }
                    <img src={m.mediaUrl!} alt="عکس" className="rounded-lg max-w-[260px] max-h-[320px] object-cover" />
                  </button>
                ) : (
                  <div className="w-[220px] h-[140px] rounded-lg bg-black/10 flex flex-col items-center justify-center gap-1 text-muted-foreground">
                    <Lock className="w-6 h-6" />
                    <span className="text-[11px]">قفل پیوی فعال است</span>
                  </div>
                )}
              </div>
            )}

            {/* voice */}
            {m.type === 'voice' && showMedia && (
              <div className="flex items-center gap-2 py-1 min-w-[190px]" onClick={(e) => e.stopPropagation()}>
                <button
                  onClick={() => {
                    const a = audioRef.current
                    if (!a) return
                    if (playing) { a.pause() } else { a.play() }
                  }}
                  className="w-9 h-9 rounded-full text-white flex items-center justify-center shrink-0"
                  style={{ background: 'var(--tg-blue)' }}
                  aria-label={playing ? 'توقف' : 'پخش'}
                >
                  {playing ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4 -ml-0.5" />}
                </button>
                <div className="flex-1">
                  <div className="tg-wave flex items-end h-4">
                    {heights.map((h, i) => (
                      <span key={i} style={{ height: h, background: (i / heights.length) * 100 <= progress ? 'var(--tg-blue)' : undefined }} />
                    ))}
                  </div>
                  <span className="text-[10.5px] opacity-70">{m.duration ? `${Math.floor(m.duration / 60)}:${String(m.duration % 60).padStart(2, '0')}` : 'صدا'}</span>
                </div>
                <audio
                  ref={audioRef}
                  src={m.mediaUrl!}
                  preload="none"
                  onPlay={() => setPlaying(true)}
                  onPause={() => setPlaying(false)}
                  onEnded={() => { setPlaying(false); setProgress(0) }}
                  onTimeUpdate={(e) => {
                    const a = e.currentTarget
                    if (a.duration) setProgress((a.currentTime / a.duration) * 100)
                  }}
                />
              </div>
            )}

            {/* file */}
            {m.type === 'file' && showMedia && (
              <a
                href={m.mediaUrl!}
                download={m.fileName || 'file'}
                onClick={(e) => { e.stopPropagation(); if (locked && !mine) e.preventDefault() }}
                className="flex items-center gap-2.5 py-1 min-w-[180px]"
              >
                <span className="w-10 h-10 rounded-full flex items-center justify-center text-white shrink-0" style={{ background: 'var(--tg-blue)' }}>
                  <FileIcon className="w-5 h-5" />
                </span>
                <span className="min-w-0">
                  <span className="block text-[13.5px] font-medium truncate max-w-[200px]">{m.fileName}</span>
                  <span className="block text-[11px] opacity-70">{sizeStr(m.fileSize)}</span>
                </span>
              </a>
            )}

            {/* text */}
            {textOnly && m.text && <p className="text-[15px] leading-6 whitespace-pre-wrap break-words">{m.text}</p>}

            {/* link preview */}
            {m.preview && (
              <a
                href={m.preview.url}
                target="_blank"
                rel="noopener noreferrer"
                onClick={(e) => e.stopPropagation()}
                className="block mt-1.5 rounded-lg overflow-hidden border"
                style={{ borderColor: 'rgba(51,144,236,0.3)', background: 'rgba(51,144,236,0.07)', maxWidth: 320 }}
              >
                {m.preview.image && (
                   
                  <img src={m.preview.image} alt="پیش‌نمایش" className="w-full max-h-36 object-cover" />
                )}
                <div className="px-2.5 py-1.5">
                  <p className="text-[13px] font-semibold" style={{ color: 'var(--tg-blue)' }} dir="ltr">{m.preview.title || m.preview.url}</p>
                  {m.preview.description && <p className="text-[11.5px] opacity-75 line-clamp-2" dir="ltr">{m.preview.description}</p>}
                </div>
              </a>
            )}
          </>
        )}

        {/* meta row */}
        <div className="flex items-center justify-end gap-1 mt-0.5 h-4">
          {m.isEdited && !m.isDeleted && <span className="text-[10.5px] opacity-60">ویرایش‌شده</span>}
          <span className="text-[10.5px] opacity-60">{timeStr(m.createdAt)}</span>
          {mine && !m.isDeleted && (m.read
            ? <CheckCheck className="w-4 h-4" style={{ color: 'var(--tg-blue)' }} />
            : <Check className="w-3.5 h-3.5 opacity-60" />)}
        </div>

        {/* reactions */}
        {m.reactions.length > 0 && (
          <div className="flex gap-1 flex-wrap mt-1" onClick={(e) => e.stopPropagation()}>
            {m.reactions.map((r) => (
              <button
                key={r.emoji}
                className="tg-react-chip"
                onClick={() => onReact(r.emoji)}
                title={r.users.map((u) => u.name).join('، ')}
              >
                {r.emoji} {r.users.length}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
