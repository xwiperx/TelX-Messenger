'use client'

import { useState, useEffect } from 'react'
import { api, POST, fullName, msgPreview } from './helpers'
import type { TUser, TChat } from './helpers'
import Avatar from './avatar'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Send, Bookmark, Loader2, Lock } from 'lucide-react'

type Props = {
  me: TUser
  messageIds?: string[]
  excludeChatId?: string | null
  onClose: () => void
  onDone: () => void
  presetIds?: string[]
  ids?: string[]
}

export default function ForwardDialog({ me, ids, onClose, onDone }: Props) {
  const [chats, setChats] = useState<TChat[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    api<{ chats: TChat[] }>('/api/chats').then((r) => { if (r.ok) setChats(r.chats) })
  }, [])

  async function forwardTo(chatId: string) {
    if (!ids?.length) return
    setBusy(true)
    const r = await api<{ count: number }>('/api/messages/forward', POST({ messageIds: ids, chatId }))
    setBusy(false)
    if (!r.ok) return setError(r.error || 'خطا در فوروارد')
    onDone()
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent className="rounded-2xl max-w-sm" dir="rtl">
        <DialogHeader>
          <DialogTitle className="text-right">فوروارد به…</DialogTitle>
        </DialogHeader>
        {error && <p className="text-xs text-red-500 flex items-center gap-1"><Lock className="w-3.5 h-3.5" /> {error}</p>}
        <div className="max-h-[55vh] overflow-y-auto tg-scroll -mx-2">
          {chats.map((c) => (
            <button
              key={c.id}
              disabled={busy}
              onClick={() => forwardTo(c.id)}
              className="w-full flex items-center gap-3 px-2 py-2.5 text-right hover:bg-[var(--tg-hover)] rounded-xl disabled:opacity-50"
            >
              {c.type === 'saved' ? (
                <div className="w-11 h-11 rounded-full flex items-center justify-center shrink-0" style={{ background: 'linear-gradient(135deg,#3390EC,#6ec531)' }}>
                  <Bookmark className="w-5 h-5 text-white fill-white" />
                </div>
              ) : (
                <Avatar user={c.other} size={44} showOnline />
              )}
              <div className="flex-1 min-w-0">
                <p className="text-[15px] font-medium truncate">{c.type === 'saved' ? 'پیام‌های ذخیره شده' : fullName(c.other)}</p>
                <p className="text-xs text-muted-foreground truncate">{msgPreview(c.lastMessage)}</p>
              </div>
              {busy ? <Loader2 className="w-4 h-4 animate-spin text-muted-foreground" /> : <Send className="w-4 h-4 text-muted-foreground -rotate-12" />}
            </button>
          ))}
          {chats.length === 0 && <p className="text-sm text-muted-foreground text-center py-6">چتی موجود نیست</p>}
        </div>
        <Button variant="outline" className="rounded-xl" onClick={onClose}>انصراف</Button>
      </DialogContent>
    </Dialog>
  )
}
