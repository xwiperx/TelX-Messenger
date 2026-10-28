'use client'

import { useState, useEffect, useCallback } from 'react'
import { api, POST, fullName } from './helpers'
import type { TUser } from './helpers'
import Avatar from './avatar'
import ProfileSheet from './profile-sheet'
import { Input } from '@/components/ui/input'
import { Search, UserPlus, Loader2, MessageCircle, Check, Users, SearchX } from 'lucide-react'

export default function ContactsView({ onOpenChat }: { onOpenChat: (chatId: string) => void }) {
  const [contacts, setContacts] = useState<TUser[]>([])
  const [q, setQ] = useState('')
  const [users, setUsers] = useState<TUser[]>([])
  const [addBusy, setAddBusy] = useState<string | null>(null)
  const [profile, setProfile] = useState<TUser | null>(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    const r = await api<{ contacts: TUser[] }>('/api/contacts')
    if (r.ok) setContacts(r.contacts)
    setLoading(false)
  }, [])

  useEffect(() => {
    const t = setTimeout(load, 0)
    return () => clearTimeout(t)
  }, [load])

  useEffect(() => {
    const query = q.trim()
    const t = setTimeout(async () => {
      if (!query) { setUsers([]); return }
      const r = await api<{ users: TUser[] }>(`/api/search?q=${encodeURIComponent(query)}`)
      if (r.ok) setUsers(r.users)
    }, query ? 300 : 0)
    return () => clearTimeout(t)
  }, [q])

  async function addContact(u: TUser) {
    setAddBusy(u.id)
    const r = await api<{ contact: TUser }>('/api/contacts', POST({ userId: u.id }))
    setAddBusy(null)
    if (r.ok) {
      setContacts((prev) => (prev.some((c) => c.id === u.id) ? prev : [r.contact, ...prev]))
      setQ(''); setUsers([])
    }
  }

  async function openChat(userId: string) {
    const r = await api<{ chatId: string }>('/api/chats', POST({ userId }))
    if (r.ok) onOpenChat(r.chatId)
  }

  async function removeContact(u: TUser) {
    await api(`/api/contacts?contactId=${u.id}`, { method: 'DELETE' })
    setContacts((prev) => prev.filter((c) => c.id !== u.id))
  }

  const searching = q.trim().length > 0
  const contactIds = new Set(contacts.map((c) => c.id))

  return (
    <div className="h-full flex flex-col bg-[var(--tg-panel)] text-[var(--tg-panel-text)]">
      <div className="px-4 pt-4 pb-3 shrink-0" style={{ background: 'var(--tg-blue)' }}>
        <h1 className="text-white text-xl font-bold mb-3">مخاطبین</h1>
        <div className="relative">
          <Search className="w-4 h-4 absolute right-3 top-1/2 -translate-y-1/2 text-white/70" />
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="جستجوی کاربر با نام یا @username…"
            className="h-10 rounded-full bg-white/15 border-none text-white placeholder:text-white/60 focus-visible:ring-white/30 pr-9"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto tg-scroll pb-24">
        {loading ? (
          <div className="flex justify-center py-10"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
        ) : searching ? (
          users.length === 0 ? (
            <div className="flex flex-col items-center py-12 gap-2 text-muted-foreground">
              <SearchX className="w-10 h-10 opacity-40" />
              <p className="text-sm">کاربری یافت نشد</p>
            </div>
          ) : (
            users.map((u) => (
              <div key={u.id} className="flex items-center gap-3 px-4 py-2.5">
                <button onClick={() => setProfile(u)}><Avatar user={u} size={46} showOnline /></button>
                <div className="flex-1 min-w-0">
                  <p className="text-[15px] font-medium truncate">{fullName(u)}</p>
                  <p dir="ltr" className="text-xs text-muted-foreground text-left">@{u.username}</p>
                </div>
                {contactIds.has(u.id) ? (
                  <button onClick={() => openChat(u.id)} className="px-3 py-1.5 rounded-full text-white text-xs font-medium flex items-center gap-1" style={{ background: 'var(--tg-blue)' }}>
                    <MessageCircle className="w-3.5 h-3.5" /> چت
                  </button>
                ) : (
                  <button onClick={() => addContact(u)} className="px-3 py-1.5 rounded-full text-white text-xs font-medium flex items-center gap-1" style={{ background: 'var(--tg-green)' }}>
                    {addBusy === u.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <><UserPlus className="w-3.5 h-3.5" /> افزودن</>}
                  </button>
                )}
              </div>
            ))
          )
        ) : contacts.length === 0 ? (
          <div className="flex flex-col items-center py-12 gap-2 text-muted-foreground">
            <Users className="w-12 h-12 opacity-30" />
            <p className="text-sm">لیست مخاطبین خالی است</p>
            <p className="text-xs">کاربران را با جستجوی بالا پیدا و اضافه کنید</p>
          </div>
        ) : (
          contacts.map((u) => (
            <div key={u.id} className="flex items-center gap-3 px-4 py-2.5 hover:bg-[var(--tg-hover)]">
              <button onClick={() => setProfile(u)}><Avatar user={u} size={48} showOnline /></button>
              <button onClick={() => openChat(u.id)} className="flex-1 min-w-0 text-right">
                <p className="text-[15.5px] font-semibold truncate">{fullName(u)}</p>
                <p dir="ltr" className="text-xs text-muted-foreground text-left">{u.online ? 'آنلاین' : `@${u.username}`}</p>
              </button>
              <button onClick={() => openChat(u.id)} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="شروع چت">
                <MessageCircle className="w-5 h-5 text-muted-foreground" />
              </button>
              <button onClick={() => removeContact(u)} className="p-2 rounded-full hover:bg-[var(--tg-hover)]" aria-label="حذف مخاطب">
                <Check className="w-4 h-4 text-muted-foreground opacity-0" />
                <span className="text-red-400 text-lg leading-none">×</span>
              </button>
            </div>
          ))
        )}
      </div>

      {profile && <ProfileSheet user={profile} onClose={() => { setProfile(null); load() }} onStartChat={() => { const p = profile; setProfile(null); openChat(p.id) }} />}
    </div>
  )
}
