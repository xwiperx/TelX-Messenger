'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import { api, POST, fullName, chatTimeStr, msgPreview, wallpaperClass } from './helpers'
import type { TUser, TChat, TStoryGroup, TStoryItem } from './helpers'
import Avatar from './avatar'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Search, Send, Bookmark, VolumeX, Loader2, PlusCircle, Clock, MessageCircle, ImageIcon, X } from 'lucide-react'

type Props = {
  me: TUser
  onOpenChat: (chatId: string) => void
  onOpenStories: (groupIndex: number) => void
  refreshSignal: number
}

export default function ChatList({ me, onOpenChat, onOpenStories, refreshSignal }: Props) {
  const [chats, setChats] = useState<TChat[]>([])
  const [groups, setGroups] = useState<TStoryGroup[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [foundUsers, setFoundUsers] = useState<TUser[]>([])
  const [foundMsgs, setFoundMsgs] = useState<{ id: string; chatId: string; chatName: string; text: string; createdAt: string; mine: boolean }[]>([])
  const [storyDialog, setStoryDialog] = useState(false)
  const [storyDuration, setStoryDuration] = useState(15)
  const [uploading, setUploading] = useState(false)
  const [pendingStory, setPendingStory] = useState<{ url: string; type: 'image' | 'video' } | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    const [c, s] = await Promise.all([
      api<{ chats: TChat[] }>('/api/chats'),
      api<{ groups: TStoryGroup[] }>('/api/stories'),
    ])
    if (c.ok) setChats(c.chats)
    if (s.ok) setGroups(s.groups)
    setLoading(false)
  }, [])

  useEffect(() => {
    const t0 = setTimeout(load, 0)
    // light polling for list updates (3.5s)
    const t = setInterval(load, 3500)
    return () => { clearTimeout(t0); clearInterval(t) }
  }, [load, refreshSignal])

  // global search
  useEffect(() => {
    const q = query.trim()
    const t = setTimeout(async () => {
      if (!q) { setFoundUsers([]); setFoundMsgs([]); return }
      const r = await api<{ users: TUser[]; messages: any[] }>(`/api/search?q=${encodeURIComponent(q)}`)
      if (r.ok) { setFoundUsers(r.users); setFoundMsgs(r.messages) }
    }, q ? 300 : 0)
    return () => clearTimeout(t)
  }, [query])

  async function openWith(userId: string) {
    const r = await api<{ chatId: string }>('/api/chats', POST({ userId }))
    if (r.ok) { setQuery(''); onOpenChat(r.chatId) }
  }

  async function pickStoryFile(f: File) {
    setUploading(true)
    const fd = new FormData()
    fd.append('file', f)
    const r = await api<{ url: string; mime: string }>('/api/upload', { method: 'POST', body: fd })
    setUploading(false)
    if (!r.ok) return
    setPendingStory({ url: r.url, type: r.mime.startsWith('video') ? 'video' : 'image' })
    setStoryDialog(true)
  }

  async function publishStory() {
    if (!pendingStory) return
    setUploading(true)
    const r = await api<{ message: string }>('/api/stories', POST({ mediaUrl: pendingStory.url, mediaType: pendingStory.type, duration: storyDuration }))
    setUploading(false)
    if (r.ok) {
      setStoryDialog(false); setPendingStory(null)
      load()
    }
  }

  const searching = query.trim().length > 0

  return (
    <div className="h-full flex flex-col bg-[var(--tg-panel)] text-[var(--tg-panel-text)]">
      {/* Header */}
      <div className="px-4 pt-4 pb-2 shrink-0" style={{ background: 'var(--tg-blue)' }}>
        <div className="flex items-center gap-3 mb-3">
          <h1 className="text-white text-xl font-bold flex-1">{searching ? 'جستجو' : 'TelX'}</h1>
          <Send className="w-5 h-5 text-white/80 -rotate-12" />
        </div>
        <div className="relative pb-2">
          <Search className="w-4 h-4 absolute right-3 top-1/2 -translate-y-1/2 text-white/70" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="جستجوی سراسری…"
            className="h-10 rounded-full bg-white/15 border-none text-white placeholder:text-white/60 focus-visible:ring-white/30 pr-9"
          />
        </div>
      </div>

      {/* Stories row */}
      {!searching && (
        <div className="flex gap-3.5 px-4 py-3 overflow-x-auto tg-scroll shrink-0 border-b" style={{ borderColor: 'var(--tg-divider)' }}>
          {/* add story */}
          <button onClick={() => fileRef.current?.click()} className="flex flex-col items-center gap-1.5 shrink-0 w-[64px]">
            <div className="w-14 h-14 rounded-full border-2 border-dashed flex items-center justify-center" style={{ borderColor: 'var(--tg-blue)' }}>
              {uploading ? <Loader2 className="w-5 h-5 animate-spin" style={{ color: 'var(--tg-blue)' }} /> : <PlusCircle className="w-6 h-6" style={{ color: 'var(--tg-blue)' }} />}
            </div>
            <span className="text-[10.5px] text-muted-foreground text-center leading-tight">استوری من</span>
          </button>
          <input ref={fileRef} type="file" accept="image/*,video/*" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) pickStoryFile(f); e.target.value = '' }} />

          {groups.length === 0 && (
            <div className="flex items-center text-xs text-muted-foreground">استوری‌های مخاطبین اینجا نمایش داده می‌شود</div>
          )}
          {groups.map((g, i) => (
            <button key={g.user.id} onClick={() => onOpenStories(i)} className="flex flex-col items-center gap-1.5 shrink-0 w-[64px]">
              <div className={g.hasUnseen ? 'tg-story-ring' : 'tg-story-ring-seen'}>
                <Avatar user={g.user} size={50} />
              </div>
              <span className="text-[10.5px] text-foreground/80 text-center leading-tight truncate w-full">
                {g.isMine ? 'استوری شما' : g.user.firstName}
              </span>
            </button>
          ))}
        </div>
      )}

      {/* Body */}
      <div className="flex-1 overflow-y-auto tg-scroll">
        {loading ? (
          <div className="flex justify-center py-10"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
        ) : searching ? (
          <div className="pb-24">
            {foundUsers.length > 0 && (
              <>
                <p className="text-xs font-semibold text-muted-foreground px-4 pt-3 pb-1">کاربران</p>
                {foundUsers.map((u) => (
                  <button key={u.id} onClick={() => openWith(u.id)} className="w-full flex items-center gap-3 px-4 py-2.5 text-right hover:bg-[var(--tg-hover)]">
                    <Avatar user={u} size={44} showOnline />
                    <div className="flex-1 min-w-0">
                      <p className="text-[15px] font-medium truncate">{fullName(u)}</p>
                      <p dir="ltr" className="text-xs text-muted-foreground text-left">@{u.username}</p>
                    </div>
                  </button>
                ))}
              </>
            )}
            {foundMsgs.length > 0 && (
              <>
                <p className="text-xs font-semibold text-muted-foreground px-4 pt-3 pb-1">پیام‌ها</p>
                {foundMsgs.map((m) => (
                  <button key={m.id} onClick={() => { setQuery(''); onOpenChat(m.chatId) }} className="w-full flex items-center gap-3 px-4 py-2.5 text-right hover:bg-[var(--tg-hover)]">
                    <div className="w-11 h-11 rounded-full flex items-center justify-center shrink-0" style={{ background: 'var(--tg-hover)' }}>
                      <MessageCircle className="w-5 h-5 text-muted-foreground" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex justify-between items-baseline">
                        <p className="text-[15px] font-medium truncate">{m.chatName}</p>
                        <span className="text-[11px] text-muted-foreground shrink-0">{chatTimeStr(m.createdAt)}</span>
                      </div>
                      <p className="text-xs text-muted-foreground truncate">{m.mine ? 'شما: ' : ''}{m.text}</p>
                    </div>
                  </button>
                ))}
              </>
            )}
            {foundUsers.length === 0 && foundMsgs.length === 0 && (
              <p className="text-center text-sm text-muted-foreground py-10">نتیجه‌ای یافت نشد</p>
            )}
          </div>
        ) : (
          <div className="pb-24">
            {chats.length === 0 && (
              <div className="flex flex-col items-center justify-center py-16 text-muted-foreground gap-3">
                <MessageCircle className="w-12 h-12 opacity-30" />
                <p className="text-sm">هنوز چتی ندارید</p>
                <p className="text-xs">از تب مخاطبین شروع به گفتگو کنید</p>
              </div>
            )}
            {chats.map((c) => (
              <button
                key={c.id}
                onClick={() => onOpenChat(c.id)}
                className="w-full flex items-center gap-3 px-4 py-2.5 text-right hover:bg-[var(--tg-hover)] transition-colors"
              >
                {c.type === 'saved' ? (
                  <div className="w-[54px] h-[54px] rounded-full flex items-center justify-center shrink-0" style={{ background: 'linear-gradient(135deg,#3390EC,#6ec531)' }}>
                    <Bookmark className="w-6 h-6 text-white fill-white" />
                  </div>
                ) : (
                  <Avatar user={c.other} size={54} showOnline />
                )}
                <div className="flex-1 min-w-0">
                  <div className="flex justify-between items-baseline gap-2">
                    <p className="text-[15.5px] font-semibold truncate flex items-center gap-1">
                      {c.type === 'saved' ? 'پیام‌های ذخیره شده' : fullName(c.other)}
                      {c.muted && <VolumeX className="w-3.5 h-3.5 text-muted-foreground" />}
                    </p>
                    {c.lastMessage && <span className="text-[11.5px] text-muted-foreground shrink-0">{chatTimeStr(c.lastMessage.createdAt)}</span>}
                  </div>
                  <div className="flex justify-between items-center gap-2">
                    <p className="text-[13.5px] text-muted-foreground truncate">{msgPreview(c.lastMessage)}</p>
                    {c.unreadCount > 0 && (
                      <span className="min-w-[22px] h-[22px] px-1.5 rounded-full text-[11.5px] font-bold text-white flex items-center justify-center shrink-0" style={{ background: c.muted ? '#8e9aa4' : 'var(--tg-blue)' }}>
                        {c.unreadCount > 99 ? '99+' : c.unreadCount}
                      </span>
                    )}
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Story publish dialog */}
      <Dialog open={storyDialog} onOpenChange={(o) => { if (!o) { setStoryDialog(false); setPendingStory(null) } }}>
        <DialogContent className="rounded-2xl max-w-xs" dir="rtl">
          <DialogHeader>
            <DialogTitle className="text-right">انتشار استوری</DialogTitle>
          </DialogHeader>
          {pendingStory && (
            <div className="flex justify-center">
              {pendingStory.type === 'image'
                ? <img src={pendingStory.url} alt="پیش‌نمایش استوری" className="max-h-48 rounded-xl object-contain" />
                : <video src={pendingStory.url} className="max-h-48 rounded-xl" muted playsInline />}
            </div>
          )}
          <div>
            <p className="text-xs text-muted-foreground mb-2 flex items-center gap-1"><Clock className="w-3.5 h-3.5" /> مدت نمایش</p>
            <div className="flex gap-2">
              {[15, 30, 45, 60].map((d) => (
                <button
                  key={d}
                  onClick={() => setStoryDuration(d)}
                  className={`flex-1 py-2 rounded-xl text-sm font-medium border transition-colors ${storyDuration === d ? 'text-white border-transparent' : 'text-foreground border-border hover:bg-muted'}`}
                  style={storyDuration === d ? { background: 'var(--tg-blue)' } : {}}
                >
                  {d} ثانیه
                </button>
              ))}
            </div>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" className="flex-1 rounded-xl" onClick={() => { setStoryDialog(false); setPendingStory(null) }}>
              انصراف
            </Button>
            <Button className="flex-1 rounded-xl text-white" style={{ background: 'var(--tg-blue)' }} onClick={publishStory} disabled={uploading}>
              {uploading ? <Loader2 className="w-4 h-4 animate-spin" /> : 'انتشار'}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
